package com.kai.agent.inference

import com.kai.agent.agent.*
import com.kai.agent.connections.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Catalog fields only from the reported failure; no personal/account data or live service calls. */
class DocumentCatalogRegressionTest {
    private fun catalog(): List<ConnectionTool> {
        val raw=javaClass.getResourceAsStream("/docs-create-catalog.json")!!.bufferedReader().use { it.readText() }
        val items=JSONArray(raw)
        return (0 until items.length()).map { items.getJSONObject(it) }.map {
            ConnectionTool(it.getString("slug"),it.getString("name"),it.getString("description"),"googledocs","fixture",false,it.getJSONObject("input_parameters").toString())
        }
    }
    @Test fun shorthandRanksDocumentCreationAboveEditsForEveryCatalogOrder() {
        for(query in listOf("create doc","create document","create a Google doc")) {
            for(tools in listOf(catalog(),catalog().reversed()))
                assertEquals("GOOGLEDOCS_CREATE_DOCUMENT",ToolDiscovery.rank(tools,query).first().id)
        }
        assertEquals("GOOGLEDOCS_CREATE_AND_POPULATE_TABLE",ToolDiscovery.rank(catalog(),"create populate table").first().id)
    }
    @Test(timeout=20000) fun largeKoinosModelReceivesAllTenToolsAndCreationSchemaOnTheWire() = runBlocking {
        val server=MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"gemma3-27b","context_window":131072,"context_mode":"auto"}]}"""))
            server.enqueue(MockResponse().setHeader("Content-Type","application/json").setBody("""{"choices":[{"message":{"content":"{\"type\":\"reply\",\"text\":\"fixture only\"}"},"finish_reason":"stop"}]}"""))
            val config=ProviderConfig(ProviderId.KOINOS,server.url("/v1").toString(),"fixture-key","gemma3-27b")
            val client=InferenceClient()
            val compact=client.usesCompactAgentContext(config)
            assertFalse(compact)
            val tools=ToolDiscovery.rank(catalog(),"create doc")
            val payload=AgentToolContext.discovery("fixture-account","create doc",ToolDiscovery(tools,false,false,10),compact)
            val entries=JSONObject(payload.full).getJSONArray("tools")
            assertEquals(10,entries.length())
            assertEquals("GOOGLEDOCS_CREATE_DOCUMENT",entries.getJSONObject(0).getString("id"))
            assertTrue(entries.getJSONObject(0).getBoolean("schemaIncluded"))
            val request="make a Google doc called test and put test doc in the page content and send me the link"
            client.stream(config,listOf(InferenceMessage("system","Only propose actions; never claim completion."),InferenceMessage("user",request),InferenceMessage("user",payload.full))) {}
            assertEquals("/v1/models",server.takeRequest().path)
            val wire=JSONObject(server.takeRequest().body.readUtf8())
            assertEquals(payload.full,wire.getJSONArray("messages").getJSONObject(2).getString("content"))
            assertTrue(wire.getInt("context_tokens")<=131072)
            assertEquals(2,server.requestCount)
        } finally {server.shutdown()}
    }
    @Test(timeout=20000) fun compactPolicyFollowsReportedLimitAndRefreshesOnModelChange() = runBlocking {
        val server=MockWebServer();server.start()
        try {
            val client=InferenceClient()
            val config=ProviderConfig(ProviderId.KOINOS,server.url("/v1").toString(),"fixture-key","small")
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"small","context_window":4096},{"id":"large","context_window":32768}]}"""))
            assertTrue(client.usesCompactAgentContext(config))
            assertFalse(client.usesCompactAgentContext(config.copy(modelId="large")))
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"unknown"}]}"""))
            assertTrue(client.usesCompactAgentContext(config.copy(modelId="unknown")))
            assertEquals(2,server.requestCount)
        } finally {server.shutdown()}
    }
    @Test fun selectedLargeContextToolKeepsCompleteDescriptionsAndRequiredInputs() {
        val tool=catalog().first { it.id=="GOOGLEDOCS_CREATE_AND_POPULATE_TABLE" }
        val inspected=JSONObject(AgentToolContext.inspection("fixture",tool,false).full).getJSONObject("tool")
        assertEquals(tool.description,inspected.getString("description"))
        val original=JSONObject(tool.schemaJson)
        val schema=inspected.getJSONObject("schema")
        assertEquals(original.getJSONArray("required").toString(),schema.getJSONArray("required").toString())
        for(key in original.getJSONObject("properties").keys()) {
            val description=original.getJSONObject("properties").getJSONObject(key).optString("description")
            assertEquals(description,schema.getJSONObject("properties").getJSONObject(key).optString("description"))
        }
    }
}
