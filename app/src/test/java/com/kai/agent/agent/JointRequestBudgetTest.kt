package com.kai.agent.agent

import com.kai.agent.connections.ConnectionTool
import com.kai.agent.inference.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class JointRequestBudgetTest {
    private val config=ProviderConfig(ProviderId.KOINOS,"https://credits.koinosai.com/platform/api","fixture","model",
        accountSession=true,nativeTools=true,contextTokens=131072,maxOutputTokens=4096)
    private val initial=listOf(InferenceMessage("system","Phone instructions "+"r".repeat(5800)),InferenceMessage("user","Research projects and create a document with the results."))
    private val catalog=JSONObject().put("source","phone_tool_discovery").put("tools",JSONArray((1..20).map {
        JSONObject().put("id","ACTION_$it").put("description","Catalog detail ".repeat(60))
    })).toString()
    private val progress="Exact saved research and operation state "+"Evidence, not permission. ".repeat(430)
    private val schema="""{"type":"object","properties":{"text":{"type":"string"}},"required":["text"],"additionalProperties":false}"""
    private val tools=(1..24).map { "account" to ConnectionTool("ACTION_$it","Action $it","Service metadata","service","v1",false,
        JSONObject(schema).put("description","研究 details ".repeat(140)).toString()) }
    private fun bytes(messages: List<InferenceMessage>)=JSONArray(messages.map(NativeToolWire::messageJson)).toString().toByteArray().size
    private fun schemaBytes(c: ProviderConfig)=JSONArray(c.tools.map(NativeToolWire::toolJson)).toString().toByteArray().size
    private fun context(native: Boolean=true): AgentTurnContext {
        val c=AgentTurnContext(initial,managed=true)
        repeat(6) { c.append("read","Earlier source "+"x".repeat(6000)) }
        val call=ToolCall("catalog_call","find_tools","""{"accountId":"account","search":""}""")
        c.append("",catalog,wire=if(native) listOf(InferenceMessage("assistant","",toolCalls=listOf(call),providerReplay=ProviderReplay.read(JSONObject().put("kai_replay","kr1."+"a".repeat(2500)),com.kai.agent.runtime.RunReplay.route(config))),InferenceMessage("tool",catalog,toolCallId=call.id)) else null)
        c.progressContext=progress
        return c
    }

    @Test fun completeCatalogAndResearchShareTheBudgetWithNativeSchemasAcrossRestore()=runBlocking {
        for(resume in listOf(false,true)) {
            var context=context();if(resume) context=AgentTurnContext(initial,checkpointSeed=context.archive(),managed=true).also { it.progressContext=progress }
            val archive=context.archive();val h=AgentHarness(true);val old=h.config(config,tools)
            assertTrue(runCatching { AgentContextManager().prepare(context,old,summarize={_,_->error("No summary")}) }.exceptionOrNull() is InferenceException)
            val changes=mutableListOf<AgentContextManager.Change>()
            val request=AgentContextManager().request(context,configure={ h.config(config,tools,maxToolBytes=it) },
                summarize={_,_->error("No model call needed")},onChange={ changes+=it })
            assertTrue(request.config.tools.size<old.tools.size)
            assertEquals(catalog,request.messages.last().content);assertTrue(request.messages.any { it.content==progress })
            assertEquals(archive,context.archive());assertEquals(initial.first(),request.messages.first())
            assertTrue(bytes(request.messages)+schemaBytes(request.config)+64<=60000)
            assertEquals(1,changes.count { it.stage=="tool_registry" });NativeToolWire.validate(request.messages,true)
            val measured=changes.single { it.stage=="request_components" }.json()
            assertEquals(2504,measured.getInt("continuationBytes"));assertFalse(measured.toString().contains("kr1."))
        }
    }

    @Test fun aDeferredActionCanBeInspectedAndKeepsItsExactSchemaAndAccount()=runBlocking {
        val h=AgentHarness(true);val c=context()
        val first=AgentContextManager().request(c,configure={ h.config(config,tools,maxToolBytes=it) },summarize={_,_->error("No summary")})
        val target=tools.first().second
        assertFalse(first.config.tools.any { it.description.startsWith(target.name+":") })
        val inspect=Completion("","tool_calls",toolCalls=listOf(ToolCall("inspect","inspect_tool",JSONObject().put("accountId","account").put("tool",target.id).toString())))
        assertTrue(h.parse(inspect,AgentResponseRecovery()) is AgentDecision.InspectTool)
        val inspected=AgentToolContext.inspection("account",target).full
        c.append("",inspected,wire=h.exchange("",inspected))
        val next=AgentContextManager().request(c,configure={ h.config(config,tools,maxToolBytes=it) },summarize={_,_->error("No summary")})
        val active=next.config.tools.first { it.name.startsWith("action_") }
        assertEquals(target.schemaJson,active.parameters);assertEquals(inspected,next.messages.last().content)
        val parsed=h.parse(Completion("","tool_calls",toolCalls=listOf(ToolCall("action",active.name,"{\"text\":\"Complete body\"}"))),AgentResponseRecovery()) as AgentDecision.PrepareAction
        assertEquals(target.id,parsed.tool);assertEquals("account",parsed.accountId)
    }

    @Test fun smallerTokenWindowsAlsoReserveOutputSpaceBeforeSelectingSchemas()=runBlocking {
        val c=AgentTurnContext(listOf(InferenceMessage("system","Rules"),InferenceMessage("user","Continue")),managed=true)
        c.append("read","Exact source "+"x".repeat(6000));c.progressContext="Exact progress"
        val small=config.copy(contextTokens=8192)
        val h=AgentHarness(true)
        val r=AgentContextManager().request(c,configure={ h.config(small,tools,maxToolBytes=it) },summarize={_,_->error("No summary")})
        val estimatedSchemas=(schemaBytes(r.config)+64+2)/3
        val budget=InferenceBudget.prepare(r.config,r.messages,8192,estimatedSchemas)
        assertTrue(budget.outputTokens>=256)
        assertTrue(InferenceBudget.estimate(budget.messages)+estimatedSchemas+budget.outputTokens+512<=8192)
        assertEquals(c.project(3).last().content,r.messages.last().content)
    }

    @Test fun jsonProtocolKeepsTheSameFullResultWithoutAFunctionRegistry()=runBlocking {
        val c=context(false);val h=AgentHarness(false)
        val r=AgentContextManager().request(c,configure={ h.config(config.copy(nativeTools=false),tools,maxToolBytes=it) },summarize={_,_->error("No summary")})
        assertTrue(r.config.tools.isEmpty());assertEquals(catalog,r.messages.last().content)
        NativeToolWire.validate(r.messages,false)
    }

    @Test fun requiredInstructionsAndSelectedSchemasCannotBeSilentlyDropped()=runBlocking {
        val h=AgentHarness(true);h.config(config,tools)
        h.parse(Completion("","tool_calls",toolCalls=listOf(ToolCall("inspect","inspect_tool","""{"accountId":"account","tool":"ACTION_1"}"""))),AgentResponseRecovery())
        val builtins=AgentHarness(true).config(config,emptyList()).tools
        val cap=JSONArray(builtins.map(NativeToolWire::toolJson)).toString().toByteArray().size+50
        assertTrue(runCatching { h.config(config,tools,maxToolBytes=cap) }.exceptionOrNull() is InferenceException)
        val c=context();c.append("read","x".repeat(65000));val archive=c.archive()
        assertTrue(runCatching { AgentContextManager().request(c,configure={ h.config(config,emptyList(),maxToolBytes=it) },summarize={_,_->error("No summary")}) }.exceptionOrNull() is InferenceException)
        assertEquals(archive,c.archive())
    }

    @Test fun jointlyPreparedRequestReachesTheTransportWithinItsWireLimit()=runBlocking {
        val server=okhttp3.mockwebserver.MockWebServer();server.start()
        try {
            val http=okhttp3.OkHttpClient.Builder().addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(server.url("/platform/api/chat")).build()) }.build()
            server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type","application/json").setBody("""{"choices":[{"message":{"content":"Fixture response"},"finish_reason":"stop"}]}"""))
            val h=AgentHarness(true);val r=AgentContextManager().request(context(),configure={ h.config(config,tools,maxToolBytes=it) },summarize={_,_->error("No summary")})
            assertEquals("Fixture response",InferenceClient(http).stream(r.config,r.messages) {}.text)
            val raw=server.takeRequest().body.readUtf8();assertTrue(raw.toByteArray().size<65536)
            val sent=JSONObject(raw);assertEquals(catalog,sent.getJSONArray("messages").getJSONObject(r.messages.lastIndex).getString("content"))
            assertEquals(r.config.tools.size,sent.getJSONArray("tools").length());assertEquals(1,server.requestCount)
        } finally { server.shutdown() }
    }
}
