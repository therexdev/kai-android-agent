package com.kai.agent.agent

import com.kai.agent.connections.ConnectionTool
import com.kai.agent.data.*
import com.kai.agent.inference.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeHarnessTest {
    private val body="Ecosystem project — https://project.example/ — A blockchain application supported by the retrieved source."
    private val request="Look up Koinos projects and add descriptions and links to a Google doc called Projects"
    private val schema="""{"type":"object","properties":{"title":{"type":"string"},"text":{"type":"string"}},"required":["title"]}"""
    private val create=ConnectionTool("GOOGLEDOCS_CREATE_DOCUMENT","Create document","Create a document with body text","googledocs","v1",false,schema)
    private val config=ProviderConfig(ProviderId.OPENAI,"https://example.org","fixture","fixture",nativeTools=true)
    private fun task(args: String="""{"title":"Projects"}""",status: TaskStatus=TaskStatus.ACCEPTED,owner: String="owner"): AgentTask {
        val intent=ActionIntent(toolName=create.id,accountId="docs",argumentsJson=args,ownerId=owner,connectionGeneration="generation",executionId="run")
        return AgentTask(title="Projects",instructions=request,conversationId="chat",createdAt=101,submittedAt=102,status=status,intent=intent,
            receipts=listOf(ActionReceipt(intent.id,"document_fixture_123",status.name,url="https://docs.google.com/document/d/document_fixture_123/edit",observedAt=103)))
    }
    @Test fun nativeRegistryBindsIdenticalToolsToTheExactAccountAndPairsResults() {
        val harness=AgentHarness(true);val c=harness.config(config,listOf("docs" to create,"other" to create))
        val actions=c.tools.filter { it.name.startsWith("action_") };assertEquals(2,actions.size);assertNotEquals(actions[0].name,actions[1].name)
        val call=ToolCall("call_1",actions.first { it.description.contains("Account: docs.") }.name,JSONObject().put("title","Projects").put("text",body).toString())
        val decision=harness.parse(Completion("","tool_calls",toolCalls=listOf(call)),AgentResponseRecovery()) as AgentDecision.PrepareAction
        assertEquals("docs",decision.accountId);assertEquals(create.id,decision.tool)
        val exchange=harness.exchange("","Accepted; document_fixture_123")
        assertEquals("tool",exchange.last().role);assertEquals("call_1",exchange.last().toolCallId)
        NativeToolWire.validate(listOf(InferenceMessage("user",request))+exchange,true)
    }
    @Test fun rejectsUnknownFunctionsMultipleCallsAndTruncationWithoutProducingActions() {
        val h=AgentHarness(true);h.config(config,emptyList())
        for(c in listOf(Completion("","tool_calls",toolCalls=listOf(ToolCall("id_1","action","{}"))),
            Completion("","tool_calls",toolCalls=listOf(ToolCall("id_1","web_search","{}"),ToolCall("id_2","web_search","{}"))),
            Completion("","length",toolCalls=listOf(ToolCall("id_1","web_search","{\"query\":"))))) {
            assertTrue(runCatching { h.parse(c,AgentResponseRecovery()) }.exceptionOrNull() is AgentResponseException)
        }
    }
    @Test fun generatedDocumentRequiresBodyReadbackAndReusesOriginalLink() {
        val guard=DocumentContentGuard(request,"chat",100,0,"owner","generation")
        assertTrue(guard.active)
        assertTrue(runCatching { guard.validateAction(create,emptyList(),JSONObject().put("title","Projects")) }.isFailure)
        val args=JSONObject().put("title","Projects").put("text",body)
        guard.validateAction(create,emptyList(),args)
        val write=task(args.toString());assertNotNull(guard.checkReply(listOf(write))!!.correction)
        val readIntent=ActionIntent(toolName="GOOGLEDOCS_GET_DOCUMENT_BY_ID",accountId="docs",argumentsJson="""{"documentId":"document_fixture_123"}""",ownerId="owner",connectionGeneration="generation",executionId="run",readOnly=true)
        val data=JSONObject().put("documentId","document_fixture_123").put("body",JSONObject().put("content",org.json.JSONArray().put(
            JSONObject().put("paragraph",JSONObject().put("elements",org.json.JSONArray().put(JSONObject().put("textRun",JSONObject().put("content",body))))))))
        val read=task().copy(intent=readIntent,status=TaskStatus.SUCCEEDED,createdAt=104,submittedAt=105,
            receipts=listOf(ActionReceipt(readIntent.id,"document_fixture_123","SUCCEEDED",observedAt=106)),
            resultContext=JSONObject().put("source","untrusted_connected_app_output").put("reportedStatus","SUCCEEDED").put("truncated",false).put("data",data.toString()).toString())
        assertNull(guard.checkReply(listOf(write,read)))
        val link="Do you have AL\\nInk to the google doc"
        assertTrue(ResourceReuseGuard.linkRequest(link))
        assertTrue(ResourceReuseGuard.linkReply(link,listOf(write),"chat",0,"owner","generation",setOf("docs"))!!.contains("document_fixture_123"))
        assertNull(ResourceReuseGuard.linkReply(link,listOf(write),"chat",105,"owner","generation",setOf("docs")))
        assertNull(ResourceReuseGuard.linkReply(link,listOf(write),"chat",0,"other","generation",setOf("docs")))
        assertNull(ResourceReuseGuard.linkReply(link,listOf(write),"chat",0,"owner","generation",setOf("other")))
        assertTrue(runCatching { ResourceReuseGuard.validate(link,"chat",0,"owner","generation",AgentDecision.PrepareAction("docs",create.id,args,""),create,listOf(write)) }.isFailure)
    }
    @Test fun duplicateAndUnknownCreationCannotBeReplayedOnAnotherTurn() {
        val action=AgentDecision.PrepareAction("docs",create.id,JSONObject().put("title","Projects"),"")
        for(status in listOf(TaskStatus.ACCEPTED,TaskStatus.UNKNOWN,TaskStatus.RUNNING,TaskStatus.AWAITING_APPROVAL))
            assertTrue(runCatching { ResourceReuseGuard.validate(request,"chat",0,"owner","generation",action,create,listOf(task(status=status))) }.isFailure)
        ResourceReuseGuard.validate("Create another copy","chat",0,"owner","generation",action,create,listOf(task()))
        ResourceReuseGuard.validate(request,"chat",0,"owner","generation",action,create,listOf(task(owner="other")))
    }
    @Test fun returnedUrlsSurviveLargeDocumentMetadataAndCheckpointResume() {
        val result=JSONObject().put("data",JSONObject().put("documentId","document_fixture_123").put("documentStyle",JSONObject().put("huge","x".repeat(15000)))
            .put("display_url","https://docs.google.com/document/d/document_fixture_123/edit").put("documentUrl","https://docs.google.com/document/d/document_fixture_123/edit"))
        val summary=ToolResultSummary.parseExecution(result.toString(),false)
        assertEquals(listOf("https://docs.google.com/document/d/document_fixture_123/edit"),summary.evidenceUrls)
        assertTrue(summary.modelData.length<2000)
        assertTrue(JSONObject(summary.modelData).getJSONArray("resourceUrls").getString(0).contains("document_fixture_123"))
        val context=AgentTurnContext(listOf(InferenceMessage("system","private memory excluded"),InferenceMessage("user",request)))
        context.append("search","Phone web result: real research")
        val restored=AgentTurnContext(listOf(InferenceMessage("system","fresh privacy context")),checkpointSeed=context.checkpoint())
        restored.append("read","Actual project source")
        assertTrue(restored.checkpoint().contains("real research"));assertFalse(restored.checkpoint().contains("private memory"))
    }

    @Test fun loopGuardAllowsChangedResultsAndStopsAnUnchangedLoop() {
        val proposal=Completion("""{"type":"web_search","query":"blockchain projects"}""","stop")
        val progress=AgentHarness(false)
        repeat(6) { i -> progress.parse(proposal,AgentResponseRecovery());progress.exchange(proposal.text,"Different evidence $i") }
        val stuck=AgentHarness(false)
        repeat(3) { stuck.parse(proposal,AgentResponseRecovery());stuck.exchange(proposal.text,"Same result") }
        assertTrue(runCatching { stuck.parse(proposal,AgentResponseRecovery()) }.exceptionOrNull() is AgentResponseException)
    }

    @Test fun deferredRegistryBudgetIncludesDescriptionsAndUtf8SchemaBytes() {
        val tools=(1..24).map { i -> "docs" to create.copy(id="CREATE_$i",description="Details ".repeat(100),schemaJson=JSONObject(schema).put("description","項目".repeat(200)).toString()) }
        val config=AgentHarness(true).config(config,tools)
        assertTrue(org.json.JSONArray(config.tools.map(NativeToolWire::toolJson)).toString().toByteArray().size<24_100)
        assertTrue(config.tools.count { it.name.startsWith("action_") }<24)
    }

    @Test fun inspectingAnOlderActionActivatesItEvenWhenOtherAccountsFillTheRegistry() {
        val h=AgentHarness(true)
        val tools=listOf("first" to create)+(1..35).map { i -> "other" to create.copy(id="CREATE_$i") }
        val initial=h.config(config,tools)
        assertFalse(initial.tools.any { it.description.contains("Account: first.") })
        val inspect=ToolCall("call_inspect","inspect_tool","""{"accountId":"first","tool":"GOOGLEDOCS_CREATE_DOCUMENT"}""")
        h.parse(Completion("","tool_calls",toolCalls=listOf(inspect)),AgentResponseRecovery())
        val focused=h.config(config,tools).tools.first { it.name.startsWith("action_") }
        assertTrue(focused.description.contains("Account: first."))
        val decision=h.parse(Completion("","tool_calls",toolCalls=listOf(ToolCall("call_create",focused.name,"{\"title\":\"Projects\"}"))),AgentResponseRecovery()) as AgentDecision.PrepareAction
        assertEquals("first",decision.accountId)
    }
}
