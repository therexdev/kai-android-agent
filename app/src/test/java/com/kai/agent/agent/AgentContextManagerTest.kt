package com.kai.agent.agent

import com.kai.agent.data.*
import com.kai.agent.diagnostics.DiagnosticRedactor
import com.kai.agent.inference.*
import com.kai.agent.web.WebResearchSession
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic regression fixtures reproduce the captured sizes without publishing private reports. */
class AgentContextManagerTest {
    private val config=ProviderConfig(ProviderId.KOINOS,"https://credits.example/platform/api","fixture-only","fixture-model",com.kai.agent.inference.RoutingMode.NETWORK_ONLY,
        accountSession=true,contextTokens=131072,modelOutputLimit=8192)
    private fun bytes(messages: List<InferenceMessage>)=JSONArray(messages.map(NativeToolWire::messageJson)).toString().toByteArray().size
    private val noSummary: suspend (ProviderConfig,List<InferenceMessage>)->Completion = { _,_ -> error("Mechanical pruning should suffice") }
    private fun web(text: String,links: Int=0)=WebResearchSession.PREFIX+JSONObject().put("kind","page").put("status","ok")
        .put("url","https://source.example/ecosystem").put("title","Blockchain projects").put("text",text)
        .put("links",JSONArray((1..links).map { JSONObject().put("title","Project $it").put("url","https://project$it.example/") })).toString()

    @Test fun capturedApprovalResumeFitsWithoutReplayingCreationOrLosingCanonicalData() = runBlocking {
        val request="Research ecosystem projects and add descriptions and source links to one Google document."
        val system="Phone rules. "+"r".repeat(8800)+"\nExact current receipt: document_fixture_123 https://docs.google.com/document/d/document_fixture_123/edit ACCEPTED; verify contents; never create again."
        val seed=JSONArray().apply {
            put(JSONObject().put("proposal","search").put("result","Search results "+"s".repeat(1850)))
            put(JSONObject().put("proposal","read").put("result",web("Exact research fact. "+"p".repeat(9600),20)))
            put(JSONObject().put("proposal","find").put("result",JSONObject().put("source","phone_tool_discovery").put("accountId","docs-fixture")
                .put("status","TOOLS_FOUND").put("tools",JSONArray().put(JSONObject().put("id","GOOGLEDOCS_CREATE_DOCUMENT").put("schema",JSONObject().put("description","schema ".repeat(2600))))).toString()))
            repeat(5) { put(JSONObject().put("proposal","check").put("result","Earlier tool data "+"c".repeat(1400))) }
        }
        val context=AgentTurnContext(listOf(InferenceMessage("system",system),InferenceMessage("user",request)),checkpointSeed=seed.toString(),managed=true)
        context.appendOutcome(JSONObject().put("source","phone_action_result").put("reportedStatus","ACCEPTED").put("documentId","document_fixture_123")
            .put("documentUrl","https://docs.google.com/document/d/document_fixture_123/edit").put("data","metadata ".repeat(1050)).toString())
        assertTrue(context.fullMessages().sumOf { it.content.length }>56000)
        val canonical=context.archive()
        var calls=0
        val messages=AgentContextManager().prepare(context,config,summarize={ _,_ -> calls++;Completion("Research collected; creation accepted; next verify existing document.","stop") })
        assertTrue(bytes(messages)<60000)
        assertTrue(messages.any { it.role=="user" && it.content==request })
        assertTrue(messages.first().content.contains("document_fixture_123"))
        assertTrue(messages.first().content.contains("ACCEPTED"))
        assertTrue(calls<=1)
        assertTrue(context.archive().contains("schema ".repeat(100)))
        assertTrue(context.checkpoint().length<=16000)
        assertEquals(JSONArray(JSONObject(canonical).getJSONArray("entries").toString()).length(),JSONObject(context.archive()).getJSONArray("entries").length())
        val earlier=JSONObject(context.read("t_2",0));assertTrue(earlier.getString("excerpt").contains("Exact research fact"))
        val restored=AgentTurnContext(listOf(InferenceMessage("system",system),InferenceMessage("user",request)),checkpointSeed=context.archive(),managed=true)
        assertEquals(context.read("t_2",0),restored.read("t_2",0))
    }
    @Test fun nativeSchemaAndUnicodeBytesAreIncludedAndToolResultIdsStayPaired() = runBlocking {
        val c=config.copy(nativeTools=true,tools=listOf(InferenceTool("action_fixture","schema",JSONObject().put("type","object").put("description","標準".repeat(3500)).toString())))
        val context=AgentTurnContext(listOf(InferenceMessage("system","Keep exact receipts; no new permission."),InferenceMessage("user","Continue requested work")),managed=true)
        repeat(8) { i ->
            val call=ToolCall("call_$i","action_fixture","""{"documentId":"document_fixture_123"}""")
            context.append("",web("研究".repeat(2200)),wire=listOf(InferenceMessage("assistant","",toolCalls=listOf(call)),InferenceMessage("tool",web("研究".repeat(2200)),toolCallId=call.id)))
        }
        val messages=AgentContextManager().prepare(context,c,summarize={ _,_ -> Completion("Earlier sources retained in the phone archive.","stop") })
        val schemas=JSONArray(c.tools.map(NativeToolWire::toolJson)).toString().toByteArray().size
        assertTrue(bytes(messages)+schemas<60000)
        NativeToolWire.validate(messages,true)
        assertTrue(messages.any { it.toolCallId=="call_7" })
        assertEquals("document_fixture_123",JSONObject(messages.first { it.toolCalls.any { it.id=="call_7" } }.toolCalls.single().arguments).getString("documentId"))
        assertTrue(JSONObject(context.read("t_1",0)).getString("excerpt").contains("研究"))
    }
    @Test fun summarizationUsesOriginalRecordsRetainsRequestAndFailsBackLocally() = runBlocking {
        val original="Current request with exact user wording"
        val old=(0..18).map { InferenceMessage(if(it%2==0) "user" else "assistant","Earlier conversation $it "+"x".repeat(3000),canOmit=true) }
        val context=AgentTurnContext(listOf(InferenceMessage("system","Rules"))+old+InferenceMessage("user",original),managed=true)
        var count=0
        val manager=AgentContextManager()
        val messages=manager.prepare(context,config,summarize={ c,m ->
            count++;assertTrue(c.tools.isEmpty());assertFalse(c.nativeTools);assertTrue(c.freeOnly)
            assertTrue(m.last().content.length<=24000)
            Completion("The earlier conversation covered the user's project. Exact wording is available in the archive.","stop")
        })
        assertEquals(1,count);assertTrue(messages.any { it.content==original });assertTrue(messages.any { it.content.contains("phone_context_compaction") })
        assertEquals(old[0].content.take(3000),JSONObject(context.read("h_1",0)).getString("excerpt"))
        assertTrue(context.fullMessages().size>messages.size)
        val failed=AgentTurnContext(listOf(InferenceMessage("system","Rules"))+old+InferenceMessage("user",original),managed=true)
        val local=AgentContextManager().prepare(failed,config,summarize={ _,_ -> throw InferenceException("Summary unavailable") })
        assertTrue(local.any { it.content==original });assertEquals(old.size+2,failed.fullMessages().size)
    }
    @Test fun truncatedSummariesAreRejectedAndCancellationDoesNotStartRecovery() = runBlocking {
        val old=(1..12).map { InferenceMessage(if(it%2==0) "assistant" else "user","Earlier turn "+"a".repeat(9000),canOmit=true) }
        val context=AgentTurnContext(listOf(InferenceMessage("system","Rules"))+old+InferenceMessage("user","current"),managed=true)
        AgentContextManager().prepare(context,config,summarize={ _,_ -> Completion("Do not retain this partial summary","length") })
        assertEquals("",context.summary)
        val cancelled=runCatching { AgentContextManager().prepare(context,config,summarize={ _,_ -> throw CancellationException("stop") }) }.exceptionOrNull()
        assertTrue(cancelled is CancellationException)
    }
    @Test fun overflowRecoveryIsBoundedAndNeverTriggeredByTransportOrPermissionErrors() {
        val manager=AgentContextManager()
        assertFalse(manager.recover(InferenceException("Network timeout")))
        assertFalse(manager.recover(InferenceException("Forbidden",403)))
        assertTrue(manager.recover(InferenceException("Context rejected",400,true)))
        assertFalse(manager.recover(InferenceException("Context rejected",400,true)))
    }
    @Test fun largeModelWindowStillHonorsTheInferenceClientInputLimit() = runBlocking {
        val older=(1..11).map { InferenceMessage(if(it%2==0) "assistant" else "user","Earlier turn $it "+"a".repeat(9000),canOmit=true) }
        val latest="Current request "+"b".repeat(9000)
        val context=AgentTurnContext(listOf(InferenceMessage("system","Rules"))+older+InferenceMessage("user",latest),managed=true)
        assertTrue(context.fullMessages().sumOf { it.content.length.toLong() }>InferenceClient.MAX_INPUT_CHARS)
        val large=config.copy(provider=ProviderId.OPENAI,accountSession=false,contextTokens=131072)
        val working=AgentContextManager().prepare(context,large,summarize={ _,_ -> Completion("Earlier work remains retrievable in the phone archive.","stop") })
        assertTrue(working.sumOf { it.content.length.toLong() }<=InferenceClient.MAX_INPUT_CHARS)
        assertTrue(working.any { it.content==latest })
    }
    @Test fun multiCallGroupsKeepDistinctResultsAndUnchangedIds() = runBlocking {
        val calls=listOf(ToolCall("call_a","read_a","{}"),ToolCall("call_b","read_b","{}"))
        val wire=listOf(InferenceMessage("assistant","",toolCalls=calls),InferenceMessage("tool","Result A "+"a".repeat(10000),toolCallId="call_a"),InferenceMessage("tool","Result B "+"b".repeat(10000),toolCallId="call_b"))
        val context=AgentTurnContext.fromMessages(listOf(InferenceMessage("system","Rules"),InferenceMessage("user","Read both"))+wire)
        val projected=context.project(2)
        NativeToolWire.validate(projected,true)
        assertTrue(projected.first { it.toolCallId=="call_a" }.content.contains("Result A"))
        assertFalse(projected.first { it.toolCallId=="call_a" }.content.contains("Result B"))
        assertEquals(calls,projected.first { it.toolCalls.isNotEmpty() }.toolCalls)
    }
    @Test fun checkpointArchiveIsPrivateDurableAndDiscardedByForget() {
        val chat=Conversation(id="chat")
        val task=AgentTask(title="Research",conversationId="chat",createdAt=100,status=TaskStatus.ACCEPTED,submittedAt=101,
            contextArchive="Full private earlier tool data",resultContext="compact checkpoint",intent=ActionIntent(toolName="CREATE",accountId="docs"))
        val state=KaiState(conversations=listOf(chat),tasks=listOf(task))
        val decoded=KaiJson.decode(KaiJson.encode(state));assertEquals(task.contextArchive,decoded.tasks.single().contextArchive)
        val report=DiagnosticRedactor(AppSettings()).clean(KaiJson.task(task)).toString();assertFalse(report.contains("Full private"))
        val forgotten=ActionContextPrivacy.apply(state,state.copy(memoryGraph=state.memoryGraph.copy(deletionEpoch=1))).tasks.single()
        assertEquals("",forgotten.contextArchive);assertEquals(task.intent,forgotten.intent);assertEquals(TaskStatus.ACCEPTED,forgotten.status)
    }
    @Test fun legacyLargeStepArchiveRestoresAndProjectsWithinTheGatewayBudget() = runBlocking {
        val messages=listOf(RunMessage("system","Phone rules"),RunMessage("user","Research and create a document"))+
            (1..8).flatMap { i -> listOf(RunMessage("assistant","Read source $i"),RunMessage("user",web("Source $i "+"x".repeat(12000)))) }
        val run=AgentRun(title="Research",goal="Research",conversationId="chat",steps=listOf(RunStep(title="Research",instructions="Research",messages=messages)))
        RunRules.validate(run)
        val restored=KaiJson.decode(KaiJson.encode(KaiState(runs=listOf(run)))).runs.single().steps.single()
        assertEquals(messages,restored.messages)
        val context=AgentTurnContext.fromMessages(restored.messages.map { InferenceMessage(it.role,it.content,toolCalls=it.toolCalls,toolCallId=it.toolCallId) })
        val working=AgentContextManager().prepare(context,config,summarize={ _,_ -> Completion("Sources have been researched.","stop") })
        assertTrue(bytes(working)<60000);assertEquals(messages.sumOf { it.content.length },context.fullMessages().sumOf { it.content.length })
    }
    @Test fun newReadFunctionIsLocalBoundedAndNeverAnApproval() {
        val d=AgentProtocol.parse("""{"type":"read_context","entryId":"t_1","offset":0}""") as AgentDecision.ReadContext
        val context=AgentTurnContext(listOf(InferenceMessage("user","Goal")),managed=true)
        context.append("read","Exact archived text "+"x".repeat(7000))
        val page=JSONObject(context.read(d.entryId,d.offset));assertEquals(3000,page.getString("excerpt").length);assertEquals(3000,page.getInt("nextOffset"))
        assertEquals("NOT_FOUND",JSONObject(context.read("t_999",0)).getString("status"))
        assertTrue(runCatching { AgentProtocol.parse("""{"type":"read_context","entryId":"t_1","offset":-1}""") }.isFailure)
    }

    @Test fun nativeArgumentBudgetCountsUtf8Bytes() {
        val call=ToolCall("call_unicode","write_document",JSONObject().put("text","研究".repeat(1000)).toString())
        assertTrue(InferenceBudget.estimate(listOf(InferenceMessage("assistant","",toolCalls=listOf(call))))>1500)
    }

    @Test fun compactedApprovalResumeReachesTheInferenceApiAndKeepsTheExistingReceipt() = runBlocking {
        val server=okhttp3.mockwebserver.MockWebServer();server.start()
        try {
            val http=okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url("/platform/api/chat")).build())
            }.build()
            val client=InferenceClient(http)
            val c=config.copy(endpoint="https://credits.koinosai.com/platform/api")
            val goal="Research blockchain projects and create one document called Projects"
            val intent=ActionIntent(toolName="GOOGLEDOCS_CREATE_DOCUMENT",accountId="docs",ownerId="owner",connectionGeneration="generation",argumentsJson="{\"title\":\"Projects\",\"text\":\"Existing researched body\"}")
            val task=AgentTask(title="Projects",instructions=goal,conversationId="chat",createdAt=100,status=TaskStatus.ACCEPTED,submittedAt=101,intent=intent,
                receipts=listOf(ActionReceipt(intent.id,"document_fixture_123","ACCEPTED",url="https://docs.google.com/document/d/document_fixture_123/edit")))
            val context=AgentTurnContext(listOf(InferenceMessage("system","Phone action receipts are authoritative: "+TaskContext.ledger(listOf(task),"chat")),InferenceMessage("user",goal)),managed=true)
            repeat(7) { context.append("read",web("Source fact "+"x".repeat(9000))) }
            context.appendOutcome(ToolResultSummary.parseExecution("""{"data":{"documentId":"document_fixture_123","documentUrl":"https://docs.google.com/document/d/document_fixture_123/edit"}}""",false).modelData)
            val messages=AgentContextManager().prepare(context,c,summarize={ _,_ -> Completion("Research collected; document creation accepted; verify the existing document next.","stop") })
            server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type","application/json").setBody("""{"choices":[{"message":{"content":"{\"type\":\"reply\",\"text\":\"The existing document is ready for verification.\"}"},"finish_reason":"stop"}]}"""))
            val result=client.stream(c,messages) {}
            assertTrue(AgentProtocol.parse(result.text) is AgentDecision.Reply)
            val request=JSONObject(server.takeRequest().body.readUtf8())
            assertTrue(request.getJSONArray("messages").toString().contains("document_fixture_123"))
            assertTrue(request.getJSONArray("messages").toString().toByteArray().size<65536)
            val tool=com.kai.agent.connections.ConnectionTool("GOOGLEDOCS_CREATE_DOCUMENT","Create","Create","googledocs","v1",false,"{\"type\":\"object\"}")
            assertTrue(runCatching { ResourceReuseGuard.validate(goal,"chat",0,"owner","generation",AgentDecision.PrepareAction("docs",tool.id,JSONObject().put("title","Projects"),""),tool,listOf(task)) }.isFailure)
            assertEquals(TaskStatus.ACCEPTED,task.status);assertEquals(1,server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun changedPrivacyBoundaryClearsSavedSummariesWithoutChangingActionReceipts() {
        val chat=Conversation(id="chat")
        val run=AgentRun(title="Work",goal="Work",conversationId="chat",steps=listOf(RunStep(title="Read",instructions="Read",contextSummary="Old private summary",messages=listOf(RunMessage("system","Old private context")))))
        val state=KaiState(conversations=listOf(chat),runs=listOf(run))
        val next=state.copy(conversations=listOf(chat.copy(contextAfter=200)))
        val safe=ActionContextPrivacy.apply(state,next).runs.single().steps.single()
        assertEquals("",safe.contextSummary);assertTrue(safe.messages.isEmpty());assertEquals(-1L,safe.contextRevision)
    }

    @Test fun savedArchivesRemainRetrievableAfterRestartOnlyWithinTheirPrivacyScope() {
        val old=AgentTurnContext(listOf(InferenceMessage("user","Earlier request")),managed=true)
        old.append("read","Exact original source https://project.example/")
        val intent=ActionIntent(toolName="CREATE",accountId="docs",ownerId="owner",connectionGeneration="generation")
        val task=AgentTask(id="task-fixture",title="Projects",conversationId="chat",createdAt=100,intent=intent,contextArchive=old.archive())
        val current=AgentTurnContext(listOf(InferenceMessage("user","Continue earlier work")),managed=true)
        fun read(cid: String="chat",boundary: Long=0,owner: String="owner")=TaskContextArchive.read(current,"task:task-fixture:t_1",0,listOf(task),cid,boundary,owner,"generation")
        assertTrue(read().contains("Exact original source"))
        assertEquals("NOT_FOUND",JSONObject(read(cid="other")).getString("status"))
        assertEquals("NOT_FOUND",JSONObject(read(boundary=101)).getString("status"))
        assertEquals("NOT_FOUND",JSONObject(read(owner="other")).getString("status"))
        assertTrue(TaskContextArchive.read(current,"index",0,listOf(task),"chat",0,"owner","generation").contains("task:task-fixture:index"))
    }

    @Test fun actualProviderContextRejectionCanRetryInferenceOnceWithoutGrowingTheTranscript() = runBlocking {
        val server=okhttp3.mockwebserver.MockWebServer();server.start()
        try {
            val client=InferenceClient()
            val c=ProviderConfig(ProviderId.OPENAI,server.url("/v1").toString(),"fixture-only","fixture",contextTokens=8192)
            val context=AgentTurnContext(listOf(InferenceMessage("system","Rules"),InferenceMessage("user","Continue the existing task")),managed=true)
            val manager=AgentContextManager()
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(400).setBody("""{"error":{"code":"context_length_exceeded","message":"Maximum context length exceeded"}}"""))
            val first=manager.prepare(context,c,noSummary)
            val error=runCatching { client.stream(c,first) {} }.exceptionOrNull() as InferenceException
            assertTrue(error.contextOverflow);assertTrue(manager.recover(error))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type","application/json").setBody("""{"choices":[{"message":{"content":"Continuing from the saved receipt."},"finish_reason":"stop"}]}"""))
            val second=manager.prepare(context,c,noSummary)
            assertEquals("Continuing from the saved receipt.",client.stream(c,second) {}.text)
            assertEquals(2,context.fullMessages().size);assertEquals(2,server.requestCount)
            assertFalse(manager.recover(error))
        } finally { server.shutdown() }
    }
}
