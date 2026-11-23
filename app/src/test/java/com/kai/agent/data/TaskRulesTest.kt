package com.kai.agent.data

import org.junit.Assert.*
import org.junit.Test

class TaskRulesTest {
    private val settings = AppSettings(composioUserId = "owner-a", composioApiKey = "composio-test-key", connectionGeneration = "g1")
    private fun intent() = ActionIntent(toolName = "send", accountId = "mailbox", ownerId = "owner-a", connectionGeneration = "g1", toolVersion = "2", toolSchemaJson = "{\"type\":\"object\"}", argumentsJson = "{\"to\":\"sam\",\"body\":\"Friday\"}")
    private fun approved() = TaskRules.approve(AgentTask(title = "Mail", status = TaskStatus.AWAITING_APPROVAL, intent = intent()), settings, 10)

    @Test fun `changing recipient schema version or owner invalidates exact approval`() {
        val approved = approved()
        val original = approved.intent!!
        val changed = listOf(
            original.copy(argumentsJson = "{\"to\":\"alex\",\"body\":\"Friday\"}"),
            original.copy(toolVersion = "3"), original.copy(toolSchemaJson = "{\"type\":\"string\"}"),
            original.copy(ownerId = "owner-b"), original.copy(accountId = "another-mailbox"))
        changed.forEach { modified ->
            assertRejected { TaskRules.transition(approved.copy(intent = modified), TaskStatus.RUNNING, settings) }
        }
    }

    @Test fun `JSON key order does not invalidate same exact action`() {
        val approved = approved()
        val same = approved.intent!!.copy(argumentsJson = "{\"body\":\"Friday\",\"to\":\"sam\"}")
        assertEquals(TaskRules.fingerprint(approved.intent), TaskRules.fingerprint(same))
    }

    @Test fun `submission is checkpointed and recovered unknown without retry`() {
        val running = TaskRules.transition(approved(), TaskStatus.RUNNING, settings, at = 20)
        assertEquals(20L, running.submittedAt)
        val recovered = TaskRules.recover(running, 30)
        assertEquals(TaskStatus.UNKNOWN, recovered.status)
        assertRejected { TaskRules.transition(recovered, TaskStatus.RUNNING, settings) }
        assertRejected { TaskRules.transition(recovered, TaskStatus.READY, settings) }
        assertRejected { TaskRules.transition(recovered, TaskStatus.SUCCEEDED, settings) }
        val reconciled = recovered.copy(receipts = listOf(ActionReceipt(recovered.intent!!.idempotencyKey, "provider-id", "sent")))
        assertEquals(TaskStatus.SUCCEEDED, TaskRules.transition(reconciled, TaskStatus.SUCCEEDED, settings).status)
    }

    @Test fun `switching direct user identity or generation blocks approved action`() {
        assertRejected { TaskRules.transition(approved(), TaskStatus.RUNNING, settings.copy(composioUserId = "owner-b")) }
        assertRejected { TaskRules.transition(approved(), TaskStatus.RUNNING, settings.copy(connectionGeneration = "g2")) }
        assertRejected { TaskRules.transition(approved(), TaskStatus.RUNNING, settings.copy(composioApiKey = "")) }
    }

    @Test fun `legacy managed session is never an authorization credential`() {
        val legacy = settings.copy(composioApiKey = "", accountId = "owner-a", connectionsSessionToken = "old-session")
        assertRejected { TaskRules.transition(approved(), TaskStatus.RUNNING, legacy) }
        val stale = TaskRules.invalidatePendingBinding(approved(), legacy, at = 20)
        assertEquals(TaskStatus.DRAFT, stale.status)
        assertEquals("", stale.approvedFingerprint)
        assertNull(stale.approvedAt)
    }

    @Test fun `binding recovery preserves matching approval and completed history`() {
        val ready = approved()
        assertEquals(ready, TaskRules.invalidatePendingBinding(ready, settings))
        val finished = ready.copy(status = TaskStatus.SUCCEEDED, submittedAt = 20,
            receipts = listOf(ActionReceipt(ready.intent!!.idempotencyKey, "provider-id", "sent")))
        assertEquals(finished, TaskRules.invalidatePendingBinding(finished, AppSettings()))
    }

    @Test fun `a submitted action cannot be called cancelled or resubmitted`() {
        val running = TaskRules.transition(approved(), TaskStatus.RUNNING, settings)
        assertRejected { TaskRules.transition(running, TaskStatus.CANCELLED, settings) }
        val failed = TaskRules.transition(running, TaskStatus.FAILED, settings, "Provider rejected")
        assertRejected { TaskRules.transition(failed, TaskStatus.DRAFT, settings) }
    }

    private fun assertRejected(block: () -> Unit) {
        try { block(); fail("Expected the task operation to be rejected") }
        catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
    }
}
