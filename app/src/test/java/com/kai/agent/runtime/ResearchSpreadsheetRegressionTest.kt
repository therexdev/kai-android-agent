package com.kai.agent.runtime

import com.kai.agent.agent.*
import com.kai.agent.connections.ConnectionTool
import com.kai.agent.data.*
import com.kai.agent.inference.*
import com.kai.agent.ui.ApprovalPresentation
import com.kai.agent.web.*
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic collection/table case; no captured names, accounts, document IDs or private transcript. */
class ResearchSpreadsheetRegressionTest {
    private val request="Make a Google sheet called Local Practices with 10 providers. Add columns for name, phone, website, address and reviews."
    private fun task(tool: String,args: JSONObject,id: String,run: String="run",status: TaskStatus=TaskStatus.ACCEPTED,read: Boolean=false)=AgentTask(
        id=id,title="Fixture action",conversationId="chat",createdAt=101,submittedAt=102,status=status,
        intent=ActionIntent(id=id,toolName=tool,accountId="fixture-account",argumentsJson=args.toString(),executionId=run,readOnly=read),
        receipts=listOf(ActionReceipt(id,status="ACCEPTED",summary="accepted",url=if(tool.contains("CREATE")) "https://docs.google.com/spreadsheets/d/fixture-book/edit" else "")))
    private fun run()=ExecutionRun("run","chat","message",RunOrigin.CHAT,GoalContract.infer(request),privacyRevision=1,createdAt=100)
    private fun create()=task("GOOGLESHEETS_CREATE_GOOGLE_SHEET1",JSONObject().put("title","Local Practices"),"create")
    private fun values(count: Int,target: String="fixture-book")=JSONObject().put("spreadsheet_id",target).put("range","Sheet1!A1:E${count+1}")
        .put("values",JSONArray().put(JSONArray(listOf("Name","Phone","Website","Address","Reviews"))).apply {
            repeat(count) { put(JSONArray(listOf("Practice ${it+1}","555-010${it}","https://example.org/practice/${it+1}","${it+1} Example Street","Public review evidence"))) }
        })

    @Test fun `creating a file or writing only headers cannot complete a populated spreadsheet goal`() {
        assertFalse(GoalVerifier.acceptedEffect(listOf(create()),"chat",100,run()))
        assertFalse(GoalVerifier.acceptedEffect(listOf(create(),task("GOOGLESHEETS_VALUES_UPDATE",values(0),"headers")),"chat",100,run()))
        assertFalse(GoalVerifier.acceptedEffect(listOf(create(),task("GOOGLESHEETS_VALUES_UPDATE",values(9),"partial")),"chat",100,run()))
        assertTrue(GoalVerifier.acceptedEffect(listOf(create(),task("GOOGLESHEETS_VALUES_UPDATE",values(10),"values")),"chat",100,run()))
        val correction=SpreadsheetEvidence.correction(listOf(create()),request)!!
        assertTrue(correction.contains("fixture-book"));assertTrue(correction.contains("read_context"));assertTrue(correction.contains("reviewed write"))
    }
    @Test fun `an explicitly requested header-only sheet does not require invented data rows`() {
        val goal=run().copy(goal=GoalContract.infer("Create a Google sheet with 5 columns: Name, Phone, Website, Address, Reviews"))
        assertTrue(GoalVerifier.acceptedEffect(listOf(create(),task("GOOGLESHEETS_VALUES_UPDATE",values(0),"headers")),"chat",100,goal))
    }
    @Test fun `unsubmitted unknown other-run and wrong-workbook writes are not completion evidence`() {
        for(write in listOf(task("GOOGLESHEETS_VALUES_UPDATE",values(10),"pending").copy(submittedAt=null),
            task("GOOGLESHEETS_VALUES_UPDATE",values(10),"uncertain",status=TaskStatus.UNKNOWN),
            task("GOOGLESHEETS_VALUES_UPDATE",values(10),"other-run",run="other"),
            task("GOOGLESHEETS_VALUES_UPDATE",values(10,"unrelated-book"),"wrong-book"))) {
            assertFalse(GoalVerifier.acceptedEffect(listOf(create(),write),"chat",100,run()))
        }
    }
    @Test fun `separate columns covering the same rows do not add up to more entries`() {
        val names=JSONObject().put("spreadsheet_id","fixture-book").put("range","Sheet1!A2:A6").put("values",JSONArray((1..5).map { JSONArray(listOf("Practice $it")) }))
        val phones=JSONObject().put("spreadsheet_id","fixture-book").put("range","Sheet1!B2:B6").put("values",JSONArray((1..5).map { JSONArray(listOf("555-010$it")) }))
        assertFalse(GoalVerifier.acceptedEffect(listOf(create(),task("GOOGLESHEETS_VALUES_UPDATE",names,"names"),task("GOOGLESHEETS_VALUES_UPDATE",phones,"phones")),"chat",100,run()))
    }
    @Test fun `batch and column-major writes count actual rows rather than file metadata`() {
        val single=values(10)
        val batch=JSONObject().put("spreadsheet_id","fixture-book").put("data",JSONArray().put(JSONObject().put("range","Sheet1!A1:E11").put("values",single.getJSONArray("values"))))
        assertTrue(GoalVerifier.acceptedEffect(listOf(create(),task("GOOGLESHEETS_UPDATE_VALUES_BATCH",batch,"batch")),"chat",100,run()))
        val original=single.getJSONArray("values")
        val columns=JSONArray((0 until 5).map { c -> JSONArray((0 until original.length()).map { r -> original.getJSONArray(r).get(c) }) })
        val col=single.put("major_dimension","COLUMNS").put("values",columns)
        assertTrue(GoalVerifier.acceptedEffect(listOf(create(),task("GOOGLESHEETS_VALUES_UPDATE",col,"columns")),"chat",100,run()))
    }
    @Test fun `an admitted unfinished answer stays resumable even after a setup action was accepted`() {
        val answer=AgentDecision.Reply("The sheet was created, but I can’t populate those columns yet. Here are the retained findings.")
        assertEquals(ExecutionState.PARTIAL,GoalVerifier.outcome(answer,true,true))
        assertEquals(answer.text,GoalVerifier.present(answer,true,false))
    }
    @Test fun `old setup-only completions become resumable without losing receipts or reviving forgotten data`() {
        val base=KaiState(conversations=listOf(Conversation(id="chat",title="Fixture",characterId="kai",createdAt=1,updatedAt=1)),tasks=listOf(create()))
        val old=run().copy(state=ExecutionState.COMPLETED,archive="retained original findings",privacyRevision=ExecutionRunRepository.privacy(base,"chat"))
        val before=base.copy(executions=listOf(old))
        val repaired=ExecutionRunRepository.invalidate(before,before)
        assertEquals(ExecutionState.PARTIAL,repaired.executions.single().state)
        assertEquals(old.archive,repaired.executions.single().archive);assertEquals(before.tasks,repaired.tasks)
        assertEquals(repaired,ExecutionRunRepository.invalidate(repaired,repaired))
        val forgotten=before.copy(memoryGraph=before.memoryGraph.copy(deletionEpoch=1))
        assertEquals(ExecutionState.CANCELLED,ExecutionRunRepository.invalidate(before,forgotten).executions.single().state)
        val complete=before.copy(tasks=before.tasks+task("GOOGLESHEETS_VALUES_UPDATE",values(10),"values"))
        assertEquals(ExecutionState.COMPLETED,ExecutionRunRepository.invalidate(complete,complete).executions.single().state)
    }
    @Test fun `automatic reads never appear as pending user approval but writes do`() {
        val read=task("GOOGLESHEETS_GET_SPREADSHEET_INFO",JSONObject(),"read",status=TaskStatus.AWAITING_APPROVAL,read=true)
        assertFalse(ApprovalPresentation.requiresReview(read))
        assertTrue(ApprovalPresentation.requiresReview(read.copy(intent=read.intent!!.copy(readOnly=false))))
        assertFalse(ApprovalPresentation.requiresReview(read.copy(status=TaskStatus.READY)))
    }
    @Test fun `bounded collection allowances scale without treating word counts and dates as entities`() {
        assertEquals(10,TaskComplexity.collectionSize(request));assertEquals(64,TaskComplexity.modelSteps(request))
        assertEquals(1,TaskComplexity.collectionSize("Write an article with 2000 words about local businesses"))
        assertEquals(1,TaskComplexity.collectionSize("Compare results for 2026"))
        assertTrue(TaskComplexity.modelSteps("Find 999 companies")<=104)
    }
    @Test fun `repeated cached results do not prevent reading another discovered source`()=runTest {
        val visits=mutableListOf<String>()
        val html="""<div class="result"><a class="result__a" href="https://example.org/directory">Directory</a><div class="result__snippet">Local practices.</div></div><div class="result"><a class="result__a" href="https://other.example/reviews">Practice reviews</a><div class="result__snippet">Public review summaries.</div></div>"""
        val client=WebResearchClient(WebTransport { url,_ -> visits+=url;WebDocument(url,if(url.contains("duckduckgo")) html else "<html><title>Source</title><main>Practice details and reviews.</main></html>","text/html") })
        val session=WebResearchSession(request,client)
        session.execute(AgentDecision.WebSearch("local practices"))
        repeat(3) { session.execute(AgentDecision.WebSearch("local practices")) }
        assertTrue(session.needsNewApproach);assertFalse(session.exhausted)
        assertTrue(session.execute(AgentDecision.ReadWebPage("https://other.example/reviews")).contains("\"status\":\"ok\""))
        assertEquals(2,visits.size)
    }
    @Test fun `a search limit does not consume the separate page allowance`()=runTest {
        val html="""<div class="result"><a class="result__a" href="https://example.org/contact">Contact</a><div class="result__snippet">Details.</div></div>"""
        val client=WebResearchClient(WebTransport { url,_ -> WebDocument(url,if(url.contains("duckduckgo")) html else "<main>Contact information.</main>","text/html") })
        val session=WebResearchSession("Research businesses",client)
        repeat(6) { session.execute(AgentDecision.WebSearch("query $it")) }
        assertTrue(session.execute(AgentDecision.WebSearch("new query")).contains("SEARCH_BUDGET_EXHAUSTED"))
        assertFalse(session.exhausted)
        assertTrue(session.execute(AgentDecision.ReadWebPage("https://example.org/contact")).contains("\"status\":\"ok\""))
    }
    @Test fun `native research closure retains connection discovery archive lookup and approved-action proposals`() {
        val tool=ConnectionTool("GOOGLESHEETS_VALUES_UPDATE","Write table","Write reviewed cell values","googlesheets","fixture",false,"{\"type\":\"object\"}")
        val config=ProviderConfig(ProviderId.KOINOS,"https://example.org","fixture-key","fixture-model",nativeTools=true)
        val tools=AgentHarness(true).config(config,listOf("fixture-account" to tool),webAvailable=false).tools
        assertFalse(tools.any { it.name in setOf("web_search","read_web_page") })
        assertTrue(tools.any { it.name=="find_tools" });assertTrue(tools.any { it.name=="read_context" })
        assertTrue(tools.any { it.name.startsWith("action_") })
    }
    @Test fun `old source details survive tool-catalog compaction and archive retrieval remains exact`() {
        val context=AgentTurnContext(listOf(InferenceMessage("system","Runtime rules"),InferenceMessage("user",request)),managed=true)
        val source=WebResearchSession.PREFIX+JSONObject().put("kind","page").put("status","ok").put("url","https://example.org/directory")
            .put("title","Practice directory").put("text","Navigation ".repeat(300)+"Cedar Practice. Phone 555-010-1234. Address 12 Example Street. Reviews describe friendly service. "+"Footer ".repeat(200)).toString()
        context.append("read",source)
        repeat(18) { context.append("discover","{\"source\":\"phone_tool_discovery\",\"status\":\"TOOLS_FOUND\",\"tools\":[]}") }
        val before=context.archive()
        val projection=context.project(3).joinToString("\n") { it.content }
        assertTrue(projection.contains("555-010-1234"));assertTrue(projection.contains("t_1"));assertTrue(projection.contains("phone_research_evidence_index"))
        assertTrue(context.read("t_1",2500).contains("Cedar Practice"));assertEquals(before,context.archive())
    }
}
