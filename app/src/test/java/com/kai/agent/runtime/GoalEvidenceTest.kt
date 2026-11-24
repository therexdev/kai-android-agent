package com.kai.agent.runtime
import com.kai.agent.data.*
import org.junit.Assert.*
import org.junit.Test
class GoalEvidenceTest {
    private fun task(id: String, kind: String, execution: String="run"): AgentTask {
        val intent=ActionIntent(id=id,toolName="WRITE",accountId="account",executionId=execution,effectKind=kind)
        return AgentTask(id=id,title="Write",conversationId="chat",createdAt=101,updatedAt=102,submittedAt=102,status=TaskStatus.ACCEPTED,intent=intent,
            receipts=listOf(ActionReceipt(intent.id,status="ACCEPTED",summary="accepted")))
    }
    @Test fun `one accepted action cannot complete a compound goal and other runs are not evidence`() {
        val run=ExecutionRun("run","chat","msg",RunOrigin.CHAT,GoalContract.infer("Create a document and email the link to me"),privacyRevision=1,createdAt=100)
        assertFalse(GoalVerifier.acceptedEffect(listOf(task("doc","DOCUMENT")),"chat",100,run))
        assertFalse(GoalVerifier.acceptedEffect(listOf(task("doc","DOCUMENT"),task("mail","EMAIL","other")),"chat",100,run))
        assertTrue(GoalVerifier.acceptedEffect(listOf(task("doc","DOCUMENT"),task("mail","EMAIL")),"chat",100,run))
    }
    @Test fun `drafting an email is not a submission request`() {
        assertFalse(GoalContract.infer("Write an email to explain the project").requiresEffect)
        assertTrue(GoalContract.infer("Write an email and send it to me").requiresEffect)
    }
    @Test fun `unreturned document links are omitted`() {
        val trusted=listOf("https://docs.google.com/document/d/real/edit")
        val result=ResultLinks.render("Here: https://docs.google.com/document/d/invented/edit",trusted)
        assertFalse(result.contains("/invented/"));assertTrue(result.contains("/real/"))
    }
}
