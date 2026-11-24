package com.kai.agent.runtime

import com.kai.agent.agent.AgentDecision
import com.kai.agent.data.*
import com.kai.agent.web.*
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Fake transport/repository only: no provider, paid search or connected-account writes. */
class ResearchRunTest {
    private val url="https://example.test/source"
    private val quotation="A captured source excerpt about an open toolkit."
    private fun page()=JSONObject().put("kind","page").put("status","ok").put("requestedUrl",url).put("url",url)
        .put("requestKey","page:$url").put("text",quotation).put("truncated",false).put("networkAttempt",true)
    private fun proposal()=FindingProposal("The source describes a toolkit.","s1_0",quotation)
    private fun run(origin: RunOrigin)=ExecutionRun("run","chat","message",origin,GoalContract("Research a toolkit"),privacyRevision=1,createdAt=1)
    private class Repo: RunRepository {
        var saved: ExecutionRun?=null
        var failWrites=false
        override suspend fun get(id: String)=saved
        override suspend fun claim(run: ExecutionRun,owner: String): ExecutionRun {
            check(saved?.owner.isNullOrEmpty())
            return run.copy(owner=owner,state=ExecutionState.RUNNING,revision=run.revision+1).also { saved=it }
        }
        override suspend fun update(id: String,owner: String,transform: (ExecutionRun)->ExecutionRun): ExecutionRun {
            if(failWrites) error("fixture storage failure")
            val old=requireNotNull(saved)
            if(old.owner!=owner || old.state!=ExecutionState.RUNNING) throw StaleRunException()
            return transform(old).copy(revision=old.revision+1).also { saved=it }
        }
    }
    @Test fun chatAndTaskFindingsSurviveProviderFailureAndExplicitResume()=runTest {
        for(origin in RunOrigin.entries) {
            val repo=Repo();val first=RunController(repo).open(run(origin))
            runCatching { first.drive {
                recordResearch(page());recordFindings(listOf(proposal()));draft("Supported draft $url")
                throw com.kai.agent.inference.InferenceException("fixture failure",502)
            } }
            val recovered=RunJson.read(RunJson.write(requireNotNull(repo.saved)))
            assertEquals(ExecutionState.FAILED,recovered.state)
            assertEquals(quotation,recovered.research.findings.single().quote)
            val resumed=RunController(repo).open(recovered)
            assertTrue(resumed.record.research.context().contains(quotation))
            assertEquals("Supported draft $url",resumed.record.artifacts.single().text)
            resumed.finish(ExecutionState.PAUSED)
        }
    }
    @Test fun invalidBatchDoesNotCommitPartialFindingsAndStaleCallbackCannotRestoreThem()=runTest {
        val repo=Repo();val s=RunController(repo).open(run(RunOrigin.CHAT));s.recordResearch(page())
        val before=repo.saved!!.revision
        assertEquals("REJECTED",JSONObject(s.recordFindings(listOf(proposal(),proposal().copy(quote="This was never returned.")))).getString("status"))
        assertEquals(before,repo.saved!!.revision);assertTrue(repo.saved!!.research.findings.isEmpty())
        s.finish(ExecutionState.CANCELLED)
        assertTrue(runCatching { s.recordFindings(listOf(proposal())) }.exceptionOrNull() is StaleRunException)
        assertTrue(runCatching { s.recordResearch(page()) }.exceptionOrNull() is StaleRunException)
    }
    @Test fun failedPersistenceCannotExposeAnUnsavedReceiptToTheModel()=runTest {
        val repo=Repo();val s=RunController(repo).open(run(RunOrigin.CHAT));repo.failWrites=true
        var calls=0;var exposed=false
        val web=WebResearchSession("Research $url",client=WebResearchClient(WebTransport { address,_->calls++;WebDocument(address,"<main>$quotation</main>","text/html") }),
            trustedResults=emptyList(),onResult={ s.recordResearch(it) })
        assertTrue(runCatching { web.execute(AgentDecision.ReadWebPage(url));exposed=true }.isFailure)
        assertEquals(1,calls);assertFalse(exposed);assertTrue(repo.saved!!.research.receipts.isEmpty())
    }
    private fun state(): KaiState {
        val chat=Conversation(id="chat",title="Fixture",characterId="kai",createdAt=1,updatedAt=1)
        val base=KaiState(conversations=listOf(chat))
        return base.copy(executions=listOf(run(RunOrigin.CHAT).copy(privacyRevision=ExecutionRunRepository.privacy(base,chat.id),
            research=ResearchEvidence().append(page(),2).record(listOf(proposal()),3))))
    }
    @Test fun forgetAndChatDeletionRemoveResearchAndTemporaryRunsNeverPersistIt() {
        val old=state();val next=old.copy(memoryGraph=old.memoryGraph.copy(deletionEpoch=1))
        assertEquals(ResearchEvidence(),ExecutionRunRepository.invalidate(old,next).executions.single().research)
        assertTrue(ExecutionRunRepository.invalidate(old,old.copy(conversations=emptyList())).executions.isEmpty())
        val serialized=KaiJson.encode(old.copy(executions=old.executions.map { it.copy(temporary=true) }))
        assertFalse(serialized.contains(quotation));assertEquals(0,JSONObject(serialized).getJSONArray("executions").length())
    }
    @Test fun importedRunEvidenceCannotBecomePhoneRetrievalAuthority() {
        val incoming=state();val local=incoming.copy(executions=emptyList())
        assertTrue(WorkspaceRules.imported(local,incoming).executions.isEmpty())
        assertEquals(incoming.executions,WorkspaceRules.imported(incoming,local).executions)
    }
    @Test fun legacyArchivesArePreservedWithoutPromotingTheirTextToSources() {
        val serialized=RunJson.write(run(RunOrigin.CHAT).copy(archive="legacy research text"));serialized.remove("research")
        val migrated=RunJson.read(serialized)
        assertEquals("legacy research text",migrated.archive);assertTrue(migrated.research.legacy)
        assertTrue(migrated.research.sources.isEmpty());assertTrue(migrated.research.findings.isEmpty())
        assertTrue(runCatching { RunJson.read(serialized.put("research",JSONObject.NULL)) }.isFailure)
    }
}
