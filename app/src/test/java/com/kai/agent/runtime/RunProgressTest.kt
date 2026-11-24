package com.kai.agent.runtime

import com.kai.agent.data.*
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Retired 0.5.6 policy data stays readable but cannot control current execution. */
class RunProgressTest {
    private class Repo: RunRepository {
        var saved: ExecutionRun?=null
        override suspend fun get(id: String)=saved
        override suspend fun claim(run: ExecutionRun,owner: String)=run.copy(owner=owner,state=ExecutionState.RUNNING).also { saved=it }
        override suspend fun update(id: String,owner: String,transform: (ExecutionRun)->ExecutionRun): ExecutionRun {
            val old=requireNotNull(saved);if(old.owner!=owner || old.state!=ExecutionState.RUNNING) throw StaleRunException()
            return transform(old).also { saved=it }
        }
    }
    private fun record(origin: RunOrigin)=ExecutionRun("run","chat","msg",origin,GoalContract("Research and create a document"),privacyRevision=1,createdAt=1,
        progress=RunProgress(listOf("a".repeat(64)),6,true,"Retired collection closure"))
    @Test fun oldClosedAndStalledRunsResumeWithoutDiscardingDataOrIncreasingBudget()=runTest {
        for(origin in RunOrigin.entries) {
            val old=record(origin);val repo=Repo();val session=RunController(repo).open(RunJson.read(RunJson.write(old)))
            var calls=0
            session.drive { calls++;finish(ExecutionState.WAITING_APPROVAL) }
            assertEquals(1,calls);assertEquals(old.progress,session.record.progress)
            assertEquals(old.budget.maxModelSteps,session.record.budget.maxModelSteps)
            val context=GoalProgress.render(emptyList(),session.record,emptyList(),true,true)
            assertFalse(context.contains("collectionClosed"));assertFalse(context.contains("Retired collection closure"))
            assertTrue(context.contains("\"searchAvailable\":true"))
        }
    }
    @Test fun removingHeuristicStopsDoesNotRemoveTheSharedResourceCeiling()=runTest {
        val repo=Repo();val session=RunController(repo).open(record(RunOrigin.TASK).copy(budget=RunBudget(maxModelSteps=2)))
        var steps=0;session.drive { steps++ }
        assertEquals(2,steps);assertEquals(FailureCode.BUDGET,session.record.failure)
        assertEquals(ExecutionState.PARTIAL,session.record.state)
    }
    @Test fun committedServiceReceiptsRemainIdempotentWithoutBecomingAnAdmissionPolicy()=runTest {
        val repo=Repo();val session=RunController(repo).open(record(RunOrigin.CHAT))
        session.serviceReceipt("failed",true,TaskStatus.FAILED);session.serviceReceipt("failed",true,TaskStatus.FAILED)
        session.serviceReceipt("uncertain",false,TaskStatus.UNKNOWN);session.serviceReceipt("accepted",false,TaskStatus.ACCEPTED)
        assertEquals(record(RunOrigin.CHAT).progress,session.record.progress)
        assertEquals(1,session.record.events.count { it.kind=="service_failed" })
        assertEquals(1,session.record.events.count { it.kind=="effect_recorded" })
    }
    @Test fun oldProgressRetainsStrictDecodingAndPrivacyInvalidation() {
        val chat=Conversation(id="chat");val base=KaiState(conversations=listOf(chat));val old=base.copy(executions=listOf(record(RunOrigin.CHAT).copy(privacyRevision=ExecutionRunRepository.privacy(base,"chat"))))
        assertEquals(RunProgress(),ExecutionRunRepository.invalidate(old,old.copy(memoryGraph=old.memoryGraph.copy(deletionEpoch=1))).executions.single().progress)
        assertTrue(runCatching { RunProgressJson.read(RunProgressJson.write(old.executions.single().progress).put("version",99)) }.isFailure)
        val json=RunJson.write(old.executions.single());json.remove("progress")
        assertEquals(RunProgress(),RunJson.read(json).progress)
        assertTrue(runCatching { RunJson.read(json.put("progress",JSONObject.NULL)) }.isFailure)
    }
}
