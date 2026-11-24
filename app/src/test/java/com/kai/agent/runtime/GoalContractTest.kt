package com.kai.agent.runtime
import com.kai.agent.agent.*
import com.kai.agent.inference.Completion
import org.junit.Assert.*
import org.junit.Test
class GoalContractTest {
    @Test fun `external goals need receipts while ordinary advice remains conversation`() {
        assertTrue(GoalContract.infer("Research projects and email the results to me").requiresEffect)
        assertTrue(GoalContract.infer("Create a Google doc about projects").requiresEffect)
        assertFalse(GoalContract.infer("How do I send an email?").requiresEffect)
        assertFalse(GoalContract.infer("Write an article about plants").requiresEffect)
        val reply=AgentDecision.Reply("I sent your email.")
        assertFalse(GoalVerifier.present(reply,true,false).contains("I sent"))
        assertEquals(reply.text,GoalVerifier.present(reply,true,true))
    }
    @Test fun `native final questions and partial results use the same outcome contract`() {
        val harness=AgentHarness(true);val recovery=AgentResponseRecovery()
        val q=harness.parse(Completion("""{"type":"needs_input","text":"Which workspace?"}""","stop"),recovery) as AgentDecision.Reply
        assertEquals(ReplyOutcome.QUESTION,q.outcome);assertEquals("Which workspace?",q.text)
        val partial=harness.parse(Completion("""{"type":"partial","text":"Retrieved two sources; the directory was unavailable."}""","stop"),recovery) as AgentDecision.Reply
        assertEquals(ReplyOutcome.PARTIAL,partial.outcome)
    }
    @Test fun `model channel markup is never released as a completed answer`() {
        val raw="<|channel|>analysis\nprivate work\n<|channel|>final\nanswer"
        assertTrue(runCatching { AgentProtocol.parse(raw) }.isFailure)
        assertTrue(runCatching { AgentHarness(true).parse(Completion(raw,"stop"),AgentResponseRecovery()) }.isFailure)
        assertNull(StreamingReply.read(raw))
    }
}
