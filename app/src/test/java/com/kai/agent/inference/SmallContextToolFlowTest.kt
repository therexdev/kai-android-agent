package com.kai.agent.inference

import com.kai.agent.agent.*
import com.kai.agent.connections.*
import com.kai.agent.data.*
import com.kai.agent.memory.MemoryExtractor
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real prompt, discovery, schema, context selection and HTTP serialization. No live accounts. */
class SmallContextToolFlowTest {
    private val request = "Make a Google doc called test and put test doc in the page content and send me the link"
    private val graph = MemoryGraph(
        entities = listOf(MemoryEntity(id = "self:personal", displayName = "You")),
        facts = (1..8).map { StructuredFact(id = "fact-$it", subjectId = "self:personal", predicate = "preference",
            value = MemoryValue(text = "A durable preference $it " + "with detailed supporting context ".repeat(6))) },
        candidates = (1..4).map { MemoryCandidate(id = "pending-$it", question = "Remember?",
            summary = "A proposed personal preference $it " + "with detailed context ".repeat(12),
            proposedFacts = listOf(StructuredFact(subjectId = "self:personal", predicate = "preference", value = MemoryValue(text = "pending $it")))) })
    private val accounts = JSONArray((1..3).map { JSONObject().put("id", "account-$it").put("service", "googledocs")
        .put("emailAddress", "fixture$it@example.test").put("name", "Fixture $it").put("active", true) }).toString()
    private val create = ConnectionTool("GOOGLEDOCS_CREATE", "Create document", "Create a Google document", "googledocs", "v1", false,
        JSONObject().put("type", "object").put("description", "Documentation ".repeat(300))
            .put("properties", JSONObject().put("title", JSONObject().put("type", "string").put("description", "Title annotation ".repeat(200)))
                .put("body", JSONObject().put("type", "string").put("description", "Body annotation ".repeat(200))))
            .put("required", JSONArray(listOf("title", "body"))).put("additionalProperties", false).toString())

    private fun memory(compact: Boolean) = JSONObject().put("status", "AVAILABLE").put("savingMode", "AUTOMATIC")
        .put("reviewContext", MemoryExtractor.context(graph, "personal", 8, if (compact) 1000 else 12000))
        .put("linkedFacts", JSONObject().put("facts", JSONArray())).put("committedThisTurn", JSONArray()).put("clarification", "").toString()
    private fun system(compact: Boolean) = AgentProtocol.systemPrompt("Be friendly.", memory(compact), accounts,
        "fixture time", memoryActions = true, compact = compact) + "\n<task_ledger>" + TaskContext.ledger(emptyList(), "chat") + "</task_ledger>"

    @Test(timeout = 20_000) fun boundedReviewContextNeverChangesTheStoredFactsOrCutsRecords() {
        val before = graph.copy()
        val result = MemoryExtractor.context(graph, "personal", 8, 1000)
        assertTrue(result.toString().length <= 1000)
        assertTrue(result.getBoolean("moreAvailable"))
        assertEquals(before, graph)
        val current = result.getJSONArray("current")
        for (i in 0 until current.length()) assertTrue(current.getJSONObject(i).getString("id") in graph.facts.map { it.id })
    }

    @Test(timeout = 20_000) fun documentDiscoveryInspectionAndReceiptFitFourKWithoutSchemaLoop() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val config = ProviderConfig(ProviderId.KOINOS, server.url("/v1").toString(), "fixture-only-key", "llama31-8b")
            val found = ToolDiscovery(listOf(create) + (2..6).map { create.copy(id = "GOOGLEDOCS_TOOL_$it") }, false, false, 6)
            val original = AgentTurnContext(listOf(InferenceMessage("system", system(false)), InferenceMessage("user", request)))
            val oldCatalog = AgentToolContext.discovery("account-1", "create document", found)
            original.append("{\"type\":\"find_tools\"}", oldCatalog.full, oldCatalog.compact)
            // Broad instructions/memory plus a multi-schema catalog reject a small task. The old
            // newest-result fallback could instead remove schemas and begin repeated inspections.
            val oldMessages = original.messages().toMutableList().also { it[it.lastIndex] = it.last().copy(compactContent = oldCatalog.compact) }
            assertTrue(runCatching { InferenceBudget.prepare(config, oldMessages) }.exceptionOrNull() is InferenceException)
            val context = AgentTurnContext(listOf(InferenceMessage("system", system(true)), InferenceMessage("user", request)))
            val client = InferenceClient()
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"llama31-8b","context_window":4096}]}"""))
            client.discoverModels(config)
            assertEquals("/v1/models", server.takeRequest().path)
            suspend fun infer(reply: String): List<InferenceMessage> {
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(JSONObject().put("choices", JSONArray().put(
                    JSONObject().put("message", JSONObject().put("content", reply)).put("finish_reason", "stop"))).toString()))
                assertEquals(reply, client.stream(config, context.messages()) {}.text)
                val wire = JSONObject(server.takeRequest().body.readUtf8())
                val messages = wire.getJSONArray("messages").let { a -> (0 until a.length()).map { i ->
                    InferenceMessage(a.getJSONObject(i).getString("role"), a.getJSONObject(i).getString("content")) } }
                assertTrue(messages.any { it.content == request })
                assertTrue(InferenceBudget.estimate(messages) + wire.getInt("max_tokens") + 512 <= 4096)
                assertTrue(wire.getInt("max_tokens") >= 256)
                println("4K document stage: prompt estimate=${InferenceBudget.estimate(messages)}, output=${wire.getInt("max_tokens")}")
                return messages
            }
            val find = """{"type":"find_tools","accountId":"account-1","search":"create document"}"""
            infer(find)
            val discovery = AgentToolContext.discovery("account-1", "create document", found, smallContext = true)
            context.append(find, discovery.full, discovery.compact)
            val inspect = """{"type":"inspect_tool","accountId":"account-1","tool":"GOOGLEDOCS_CREATE"}"""
            infer(inspect)
            val schema = AgentToolContext.inspection("account-1", create, smallContext = true)
            context.append(inspect, schema.full, schema.compact)
            val action = """{"type":"action","accountId":"account-1","tool":"GOOGLEDOCS_CREATE","arguments":{"title":"test","body":"test doc"},"explanation":"Create requested document"}"""
            val prepared = infer(action)
            assertEquals(schema.full, prepared.last().content)
            val decision = AgentProtocol.parse(action) as AgentDecision.PrepareAction
            ConnectionArguments.validate(decision.arguments.toString(), create.schemaJson)
            // Simulate the phone's post-approval receipt; no external write occurs in this test.
            val receipt = """{"source":"phone_receipt","accountId":"account-1","tool":"GOOGLEDOCS_CREATE","reportedStatus":"ACCEPTED","url":"https://docs.google.com/document/d/fixture/edit","instructions":"Do not repeat. Content not independently verified."}"""
            context.append(action, receipt)
            val after = infer("""{"type":"reply","text":"The document request was accepted; here is its link."}""")
            assertEquals(receipt, after.last().content)
            assertEquals(5, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test(timeout = 20_000) fun tooLargeNewestSchemaFailsOnceInsteadOfDiscardingItAndRequestingItAgain() {
        val context = AgentTurnContext(listOf(InferenceMessage("user", request)))
        context.append("inspect", "required schema ".repeat(1500), "inspect again")
        val result = runCatching { InferenceBudget.prepare(ProviderConfig(ProviderId.KOINOS, "https://fixture.test/v1", "fixture", "local"), context.messages()) }
        assertTrue(result.exceptionOrNull() is InferenceException)
        assertEquals("required schema ".repeat(1500), context.messages().last().content)
    }
}
