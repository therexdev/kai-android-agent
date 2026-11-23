package com.kai.agent.agent

import com.kai.agent.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TaskContextTest {
    private fun task(index: Int, conversation: String = "current", status: TaskStatus = TaskStatus.ACCEPTED) = AgentTask(
        id = "task-$index", title = "Send email $index", status = status, updatedAt = index.toLong(), conversationId = conversation,
        submittedAt = if (status == TaskStatus.ACCEPTED) index.toLong() else null,
        intent = ActionIntent(toolName = "GMAIL_SEND_EMAIL", accountId = "account-$index", argumentsJson = """{"recipient":"sam@example.test","body":"Friday works."}"""),
        receipts = listOf(ActionReceipt("operation-$index", "message-$index", "ACCEPTED", "Email accepted, delivery not verified.", "https://mail.example.test/message/$index")),
    )

    @Test fun includesOnlySixNewestTasksFromCurrentConversation() {
        val tasks = (1..9).map { task(it) } + task(99, "other-private-chat")
        val raw = TaskContext.ledger(tasks, "current")
        val result = JSONObject(raw)
        assertEquals("phone_task_ledger", result.getString("source"))
        assertEquals(3, result.getInt("omittedTasks"))
        val entries = result.getJSONArray("tasks")
        assertEquals(6, entries.length())
        assertEquals("task-9", entries.getJSONObject(0).getString("id"))
        assertEquals("task-4", entries.getJSONObject(5).getString("id"))
        assertFalse(raw.contains("task-99"))
        assertFalse(raw.contains("other-private-chat"))
    }

    @Test fun statusAndReceiptAreRecordedWithoutUpgradingAcceptedToDelivered() {
        val entry = JSONObject(TaskContext.ledger(listOf(task(1)), "current")).getJSONArray("tasks").getJSONObject(0)
        assertEquals("ACCEPTED", entry.getString("status"))
        assertTrue(entry.getBoolean("submitted"))
        assertEquals("GMAIL_SEND_EMAIL", entry.getString("tool"))
        assertEquals("account-1", entry.getString("accountId"))
        val receipt = entry.getJSONObject("lastReceipt")
        assertEquals("ACCEPTED", receipt.getString("status"))
        assertEquals("message-1", receipt.getString("providerId"))
        assertEquals("https://mail.example.test/message/1", receipt.getString("url"))
        assertTrue(receipt.getString("summary").contains("not verified"))
        assertEquals("Friday works.", entry.getJSONObject("arguments").getString("body"))
        assertFalse(entry.getBoolean("argumentsOmitted"))
    }

    @Test fun largeArgumentsAreOmittedAsAWhole() {
        val original = task(1)
        val big = original.copy(intent = original.intent!!.copy(argumentsJson = JSONObject().put("body", "x".repeat(1800)).toString()))
        val entry = JSONObject(TaskContext.ledger(listOf(big), "current")).getJSONArray("tasks").getJSONObject(0)
        assertFalse(entry.has("arguments"))
        assertTrue(entry.getBoolean("argumentsOmitted"))
        assertEquals("too_large", entry.getString("argumentsOmittedReason"))
    }

    @Test fun budgetRetainsStatusesBeforeFullArgumentDetail() {
        val tasks = (1..6).map { index ->
            val task = task(index)
            task.copy(intent = task.intent!!.copy(argumentsJson = JSONObject().put("body", "x".repeat(1600)).toString()))
        }
        val raw = TaskContext.ledger(tasks, "current")
        assertTrue(raw.length <= 5000)
        val entries = JSONObject(raw).getJSONArray("tasks")
        assertEquals(6, entries.length())
        assertTrue((0 until entries.length()).any { entries.getJSONObject(it).getBoolean("argumentsOmitted") })
        for (i in 0 until entries.length()) assertEquals("ACCEPTED", entries.getJSONObject(i).getString("status"))
    }

    @Test fun invalidNestedOrSecretBearingArgumentsNeverReachModel() {
        for (arguments in listOf("{} {}", "[]", "{bad", """{"nested":{"api_key":"never-share"}}""", "[".repeat(100) + "]".repeat(100))) {
            val task = task(1).let { it.copy(intent = it.intent!!.copy(argumentsJson = arguments)) }
            val raw = TaskContext.ledger(listOf(task), "current")
            val entry = JSONObject(raw).getJSONArray("tasks").getJSONObject(0)
            assertTrue(entry.getBoolean("argumentsOmitted"))
            assertFalse(entry.has("arguments"))
            assertFalse(raw.contains("never-share"))
        }
    }

    @Test fun unknownAndRunningRemainUnconfirmedAndAbsentConversationIsEmpty() {
        val tasks = listOf(task(1, status = TaskStatus.UNKNOWN).copy(receipts = emptyList()), task(2, status = TaskStatus.RUNNING).copy(receipts = emptyList()))
        val entries = JSONObject(TaskContext.ledger(tasks, "current")).getJSONArray("tasks")
        assertEquals("RUNNING", entries.getJSONObject(0).getString("status"))
        assertEquals("UNKNOWN", entries.getJSONObject(1).getString("status"))
        assertTrue(entries.getJSONObject(1).isNull("lastReceipt"))
        assertEquals(0, JSONObject(TaskContext.ledger(tasks, "")).getJSONArray("tasks").length())
    }

    @Test fun tinyBudgetProducesCompleteValidJsonAndExplicitOmissions() {
        val raw = TaskContext.ledger((1..9).map { task(it) }, "current", maxChars = 512)
        assertTrue(raw.length <= 512)
        val result = JSONObject(raw)
        assertTrue(result.getInt("omittedTasks") > 0)
        assertTrue(result.has("tasks"))
    }

    @Test fun memoryChangeExcludesOldContentWhilePreservingReplayGuards() {
        val old = task(1, status = TaskStatus.UNKNOWN).copy(createdAt = 10, updatedAt = 50, title = "private-address",
            intent = task(1).intent!!.copy(argumentsJson = """{"body":"private-address"}"""),
            receipts = listOf(ActionReceipt("op", "message-id", "UNKNOWN", "private-address", "https://example.test/private-address")))
        val raw = TaskContext.ledger(listOf(old), "current", redactDetailsBefore = 30)
        assertFalse(raw.contains("private-address"))
        val entry = JSONObject(raw).getJSONArray("tasks").getJSONObject(0)
        assertEquals("task-1", entry.getString("id"))
        assertEquals("UNKNOWN", entry.getString("status"))
        assertEquals("GMAIL_SEND_EMAIL", entry.getString("tool"))
        assertTrue(entry.getBoolean("argumentsOmitted"))
        assertTrue(entry.getBoolean("detailsExcludedAfterMemoryChange"))
        assertEquals("message-id", entry.getJSONObject("lastReceipt").getString("providerId"))
    }
}
