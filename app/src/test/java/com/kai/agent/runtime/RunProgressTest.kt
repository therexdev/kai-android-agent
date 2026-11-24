package com.kai.agent.runtime

import com.kai.agent.agent.*
import com.kai.agent.data.*
import com.kai.agent.inference.*
import com.kai.agent.web.*
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RunProgressTest {
    private fun result(key: String="page:https://source.example/",cached: Boolean=false,budget: Int=0)=WebResearchSession.PREFIX+
        JSONObject().put("kind","page").put("status","ok").put("requestKey",key).put("cached",cached).put("networkAttempt",!cached)
            .put("text",if(cached) "" else "An original source excerpt").put("budget","Remaining $budget").put("evidence",JSONObject().put("movingIndex",budget)).toString()
    private val read=AgentDecision.ReadWebPage("https://source.example/")
    @Test fun repeatIdentityIgnoresChangingBudgetAndIndexTextAcrossResumes() {
        var p=RunProgress().observe(read,result())
        repeat(3) { i -> p=RunProgressJson.read(RunProgressJson.write(p)).observe(read,result(cached=true,budget=i)) }
        assertEquals(3,p.stalled);assertTrue(p.collectionClosed)
    }
    @Test fun alternatingCachedSourcesCannotEvadeProgressControl() {
        var p=RunProgress()
        repeat(6) { i -> val url="https://source.example/${i%2}";p=p.observe(AgentDecision.ReadWebPage(url),result("page:$url",i>1,i)) }
        assertTrue(p.collectionClosed);assertEquals(4,p.stalled)
    }
    @Test fun failuresWithDifferentQueriesStillConsumeNoProgressAllowance() {
        var p=RunProgress()
        repeat(6) { p=p.observe(AgentDecision.WebSearch("different query $it"),"""{"status":"blocked","code":"SEARCH_BUDGET_EXHAUSTED"}""") }
        assertEquals(6,p.stalled);assertTrue(p.collectionClosed)
    }
    @Test fun discoveryArchiveAndFileLoopsShareOneRuleWithoutReplayingEffects() {
        val reads=listOf(AgentDecision.FindTools("fixture","create"),AgentDecision.InspectTool("fixture","tool"),
            AgentDecision.ReadContext("findings",0),AgentDecision.ReadFile("file",0),AgentDecision.SearchFiles("query"))
        for(d in reads) {
            var p=RunProgress().observe(d,"same result")
            repeat(6) { p=p.observe(d,"same result") }
            assertEquals(6,p.stalled)
            assertEquals(0,p.observe(d,"new result revision").stalled)
            assertEquals(p,p.observe(AgentDecision.PrepareAction("fixture","send",JSONObject(),""),"ACCEPTED"))
        }
    }
    @Test fun paginationAndChangedSourceRevisionsRemainAvailable() {
        var p=RunProgress()
        repeat(8) { p=p.observe(AgentDecision.ReadContext("findings",it*4000),"page $it") }
        assertEquals(0,p.stalled)
        p=p.observe(AgentDecision.ReadContext("research",0),"new source revision")
        assertEquals(0,p.stalled);assertFalse(p.collectionClosed)
    }
    @Test fun duplicateFindingResponsesDoNotCountAsMoreEvidence() {
        val d=AgentDecision.RecordFindings(listOf(FindingProposal("A claim","s1_0","An exact quotation.")))
        var p=RunProgress()
        repeat(4) { p=p.observe(d,"""{"status":"RECORDED","evidence":{"findingCount":1}}""") }
        assertEquals(3,p.stalled)
        assertEquals(0,p.observe(d,"""{"status":"RECORDED","evidence":{"findingCount":2}}""").stalled)
    }
    @Test fun reservesFinishingStepsWithoutIncreasingAnyAllowanceOrClaimingCoverage() {
        val b=RunBudget(modelSteps=28,maxModelSteps=36)
        val p=RunProgress().reserve(b,true)
        assertTrue(p.collectionClosed);assertTrue(p.reason.contains("partial"))
        assertFalse(RunProgress().reserve(b,false).collectionClosed)
        assertFalse(RunProgress().reserve(b.copy(modelSteps=27),true).collectionClosed)
        assertEquals(p,p.reserve(b.copy(maxModelSteps=100),true))
        assertEquals(36,b.maxModelSteps)
    }
    @Test fun capabilitiesExpireIndependentlyAndUnregisteredNativeCallsAreRejected()=runTest {
        var calls=0
        val web=WebResearchSession("Research projects",WebResearchClient(WebTransport { _,_->calls++;throw WebResearchException("offline") }))
        repeat(6) { web.execute(AgentDecision.WebSearch("query $it")) }
        assertFalse(web.searchAvailable);assertTrue(web.readAvailable)
        val h=AgentHarness(true);val c=ProviderConfig(ProviderId.KOINOS,"https://example.test","fixture","model")
        val configured=h.config(c,emptyList(),webSearchAvailable=web.searchAvailable,webReadAvailable=web.readAvailable)
        assertFalse(configured.tools.any { it.name=="web_search" });assertTrue(configured.tools.any { it.name=="read_web_page" })
        assertTrue(configured.tools.any { it.name=="find_tools" });assertTrue(configured.tools.any { it.name=="read_context" })
        val invalid=Completion("","tool_calls",toolCalls=listOf(ToolCall("call","web_search","{\"query\":\"again\"}")))
        assertTrue(runCatching { h.parse(invalid,AgentResponseRecovery()) }.isFailure)
        assertEquals(12,calls)
    }
    private class Repo: RunRepository {
        var saved: ExecutionRun?=null
        override suspend fun get(id: String)=saved
        override suspend fun claim(run: ExecutionRun,owner: String)=run.copy(owner=owner,state=ExecutionState.RUNNING).also { saved=it }
        override suspend fun update(id: String,owner: String,transform: (ExecutionRun)->ExecutionRun): ExecutionRun {
            val old=requireNotNull(saved);if(old.owner!=owner || old.state!=ExecutionState.RUNNING) throw StaleRunException()
            return transform(old).also { saved=it }
        }
    }
    @Test fun bothWireProtocolsAndEveryAdapterStopARepeatedReadWithoutAnyEffect()=runTest {
        for(origin in RunOrigin.entries) for(native in listOf(false,true)) {
            val repo=Repo();val h=AgentHarness(native)
            h.config(ProviderConfig(ProviderId.KOINOS,"https://example.test","fixture","model"),emptyList())
            var count=0
            val session=RunController(repo).open(ExecutionRun("run","chat","msg",origin,GoalContract("Read records"),privacyRevision=1,createdAt=1))
            session.drive {
                count++
                val response=if(native) Completion("","tool_calls",toolCalls=listOf(ToolCall("call_$count","read_context","{\"entryId\":\"missing\"}")))
                    else Completion("{\"type\":\"read_context\",\"entryId\":\"missing\"}","stop")
                val d=h.parse(response,AgentResponseRecovery())
                observeProgress(d,"{\"status\":\"NOT_FOUND\"}")
                checkpoint("original records retained")
            }
            assertEquals(6,count);assertEquals(ExecutionState.PARTIAL,repo.saved!!.state)
            assertEquals(FailureCode.NO_PROGRESS,repo.saved!!.failure)
            assertEquals("original records retained",RunJson.read(RunJson.write(repo.saved!!)).archive)
        }
    }
    @Test fun approvedEffectsCanSettleBeforeAnExhaustedProgressGuard()=runTest {
        val repo=Repo();val session=RunController(repo).open(ExecutionRun("run","chat","msg",RunOrigin.TASK,GoalContract("Send"),privacyRevision=1,createdAt=1,progress=RunProgress(stalled=6)))
        var settled=0;var modelSteps=0
        session.drive(settlePending={ settled++;finish(ExecutionState.WAITING_APPROVAL) }) { modelSteps++ }
        assertEquals(1,settled);assertEquals(0,modelSteps)
    }
    @Test fun actualServiceReceiptsAdvanceProgressButDuplicateCallbacksDoNot()=runTest {
        val repo=Repo();val session=RunController(repo).open(ExecutionRun("run","chat","msg",RunOrigin.TASK,GoalContract("Read and write"),privacyRevision=1,createdAt=1,progress=RunProgress(stalled=5)))
        session.serviceReceipt("read-task",true,TaskStatus.SUCCEEDED)
        assertEquals(0,session.record.progress.stalled)
        session.observeProgress(AgentDecision.ReadContext("missing"),"{\"status\":\"NOT_FOUND\"}")
        session.serviceReceipt("read-task",true,TaskStatus.SUCCEEDED)
        assertEquals(1,session.record.progress.stalled)
        session.serviceReceipt("write-task",false,TaskStatus.ACCEPTED)
        assertEquals(0,session.record.progress.stalled)
        assertEquals(2,session.record.events.count { it.kind.endsWith("_recorded") })
    }
    @Test fun failedAndUnknownServiceOutcomesCannotResetTheSharedStreak()=runTest {
        for(origin in RunOrigin.entries) for(readOnly in listOf(false,true)) {
            val repo=Repo();val session=RunController(repo).open(ExecutionRun("run","chat","msg",origin,GoalContract("Connected work"),privacyRevision=1,createdAt=1,progress=RunProgress(stalled=4)))
            session.serviceReceipt("uncertain",readOnly,TaskStatus.UNKNOWN)
            session.serviceReceipt("unsubmitted",readOnly,TaskStatus.READY)
            assertEquals(4,session.record.progress.stalled)
            session.serviceReceipt("failed",readOnly,TaskStatus.FAILED)
            session.serviceReceipt("failed",readOnly,TaskStatus.FAILED)
            assertEquals(5,session.record.progress.stalled)
            session.serviceReceipt("another-failure",readOnly,TaskStatus.FAILED)
            var calls=0;session.drive { calls++ }
            assertEquals(0,calls);assertEquals(FailureCode.NO_PROGRESS,repo.saved!!.failure)
            assertEquals(0,repo.saved!!.events.count { it.kind in setOf("effect_recorded","read_recorded") })
        }
    }
    @Test fun automaticResumeKeepsTheStopAndExplicitContinuationKeepsResearchClosed()=runTest {
        val repo=Repo();val p=RunProgress().observe(read,result()).copy(stalled=6,collectionClosed=true)
        val r=ExecutionRun("run","chat","msg",RunOrigin.ROUTINE,GoalContract("Research"),privacyRevision=1,createdAt=1,progress=p)
        val session=RunController(repo).open(r)
        var calls=0;session.drive { calls++ }
        assertEquals(0,calls);assertEquals(FailureCode.NO_PROGRESS,repo.saved!!.failure)
        val continued=p.continued()
        assertEquals(0,continued.stalled);assertTrue(continued.collectionClosed);assertEquals(p.outcomes,continued.outcomes)
    }
    @Test fun privacyClearsProgressAndMalformedFutureStateDoesNotSilentlyReset() {
        val chat=Conversation(id="chat");val base=KaiState(conversations=listOf(chat))
        val p=RunProgress().observe(read,result()).copy(stalled=3,collectionClosed=true)
        val old=base.copy(executions=listOf(ExecutionRun("run","chat","msg",RunOrigin.CHAT,GoalContract("Research"),privacyRevision=ExecutionRunRepository.privacy(base,"chat"),createdAt=1,progress=p)))
        val changed=old.copy(memoryGraph=old.memoryGraph.copy(deletionEpoch=1))
        assertEquals(RunProgress(),ExecutionRunRepository.invalidate(old,changed).executions.single().progress)
        assertTrue(runCatching { RunProgressJson.read(RunProgressJson.write(p).put("version",99)) }.isFailure)
        val json=RunJson.write(old.executions.single());json.remove("progress")
        assertEquals(RunProgress(),RunJson.read(json).progress)
        assertTrue(runCatching { RunJson.read(json.put("progress",JSONObject.NULL)) }.isFailure)
    }
}
