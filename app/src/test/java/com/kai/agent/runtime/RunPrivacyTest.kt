package com.kai.agent.runtime

import com.kai.agent.data.*
import org.junit.Assert.*
import org.junit.Test

class RunPrivacyTest {
    private val chat=Conversation(id="chat",title="Research",characterId="kai",createdAt=1,updatedAt=1)
    private fun state(): KaiState {
        val base=KaiState(conversations=listOf(chat))
        return base.copy(executions=listOf(ExecutionRun("run","chat","msg",RunOrigin.CHAT,GoalContract("private request"),privacyRevision=ExecutionRunRepository.privacy(base,"chat"),createdAt=2,archive="private finding")))
    }
    @Test fun `forget invalidates recoverable content and run ownership`() {
        val old=state();val next=old.copy(memoryGraph=old.memoryGraph.copy(deletionEpoch=old.memoryGraph.deletionEpoch+1))
        val r=ExecutionRunRepository.invalidate(old,next).executions.single()
        assertEquals("[]",r.archive);assertEquals(ExecutionState.CANCELLED,r.state)
        assertFalse(r.goal.request.contains("private"));assertTrue(r.tools.isEmpty())
    }
    @Test fun `temporary execution is never serialized`() {
        val s=state();val temp=s.executions.single().copy(temporary=true)
        val json=KaiJson.encode(s.copy(executions=listOf(temp)))
        assertEquals(0,org.json.JSONObject(json).getJSONArray("executions").length())
    }
    @Test fun `old states load without resetting storage and active records recover paused`() {
        val s=state();val roundTrip=KaiJson.decode(KaiJson.encode(s));assertEquals(s.executions,roundTrip.executions)
        val legacy=org.json.JSONObject(KaiJson.encode(s)).apply { remove("executions") }
        assertTrue(KaiJson.decode(legacy.toString()).executions.isEmpty())
        val active=s.executions.single().copy(owner="old",state=ExecutionState.RUNNING)
        assertEquals(ExecutionState.PAUSED,ExecutionRunRepository.recover(active).state)
        assertEquals("",ExecutionRunRepository.recover(active).owner)
    }
}
