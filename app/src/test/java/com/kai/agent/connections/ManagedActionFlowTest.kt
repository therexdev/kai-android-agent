package com.kai.agent.connections

import com.kai.agent.agent.ToolResultSummary
import com.kai.agent.data.ActionIntent
import com.kai.agent.data.ActionReceipt
import com.kai.agent.data.AgentTask
import com.kai.agent.data.AppSettings
import com.kai.agent.data.TaskRules
import com.kai.agent.data.TaskStatus
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Cross-module contract test: no credentials, network calls or production mutations. */
class ManagedActionFlowTest {
    private class ServiceFixture : ConnectionsTransport {
        var submissions = 0
        var resultJson = """{"id":"msg_1","status":"accepted"}"""
        var failAfterSubmission = false
        val schema = """{"type":"object","properties":{"to":{"type":"string"},"body":{"type":"string"}},"required":["to","body"]}"""
        override suspend fun request(path: String, token: String?, body: JSONObject?): JSONObject {
            if (path == "/connections/status") return JSONObject()
                .put("ok", true).put("available", true).put("protocol", 1).put("generation", "project_1")
            check(token == "fixture-token")
            check(body?.getString("generation") == "project_1")
            val result: Any = when (path) {
                "/connections/api/accounts" -> JSONObject().put("userId", "kai:owner_1").put("generation", "project_1")
                    .put("accounts", JSONArray().put(JSONObject().put("id", "account_1").put("toolkit", "gmail")
                        .put("userId", "kai:owner_1").put("name", "Test mailbox").put("status", "ACTIVE").put("disabled", false)))
                "/connections/api/tool" -> JSONObject().put("id", "GMAIL_SEND_EMAIL").put("toolkit", "gmail")
                    .put("name", "Send email").put("version", "v1").put("readOnly", false).put("schema", JSONObject(schema))
                "/connections/api/execute" -> {
                    submissions++
                    assertEquals("account_1", body!!.getString("id"))
                    assertEquals("GMAIL_SEND_EMAIL", body.getString("tool"))
                    assertEquals("recipient@example.com", body.getJSONObject("arguments").getString("to"))
                    if (failAfterSubmission) throw ConnectionServiceException("Connection lost after submission", outcomeMayBeUnknown = true)
                    resultJson
                }
                else -> error("Unexpected managed API request: $path")
            }
            return JSONObject().put("ok", true).put("result", result)
        }
    }

    @Test fun reviewedWriteProducesAcceptedReceiptAndCannotReplay() = runBlocking {
        val fixture = ServiceFixture()
        val client = ManagedConnectionsClient(fixture)
        client.useSession(ManagedSession("owner_1", "fixture-token"))
        val snapshot = client.refresh()
        val tool = client.tool("GMAIL_SEND_EMAIL", "v1")
        val settings = AppSettings(composioUserId = snapshot.ownerId, connectionGeneration = snapshot.generation, composioApiKey = "fixture-key")
        val intent = ActionIntent(toolName = tool.id, accountId = "account_1", argumentsJson = """{"to":"recipient@example.com","body":"Hello"}""",
            ownerId = snapshot.ownerId, connectionGeneration = snapshot.generation, toolVersion = tool.version, toolSchemaJson = tool.schemaJson)
        var task = AgentTask(title = "Send email", status = TaskStatus.AWAITING_APPROVAL, intent = intent)
        task = TaskRules.approve(task, settings)
        val approval = client.approveAction(intent.accountId, tool, intent.argumentsJson)
        task = TaskRules.transition(task, TaskStatus.RUNNING, settings)
        val receipt = client.execute(intent.accountId, tool, intent.argumentsJson, approval)
        val parsed = ToolResultSummary.parseExecution(receipt.resultJson, tool.readOnly)
        task = task.copy(receipts = listOf(ActionReceipt(intent.id, parsed.evidenceIds.first(), parsed.status.name, parsed.summary)))
        task = TaskRules.transition(task, parsed.status, settings, parsed.summary)
        assertEquals(TaskStatus.ACCEPTED, task.status)
        assertEquals("msg_1", task.receipts.single().providerId)
        assertEquals(1, fixture.submissions)
        try { TaskRules.approve(task, settings); fail("Submitted action was approved again") }
        catch (_: IllegalArgumentException) { }
        assertEquals(TaskStatus.ACCEPTED, TaskRules.recover(task).status)
    }

    @Test fun ambiguousSubmissionSurvivesRecoveryWithoutAutomaticRetry() = runBlocking {
        val fixture = ServiceFixture().apply { failAfterSubmission = true }
        val client = ManagedConnectionsClient(fixture)
        client.useSession(ManagedSession("owner_1", "fixture-token"))
        val snapshot = client.refresh()
        val tool = client.tool("GMAIL_SEND_EMAIL", "v1")
        val settings = AppSettings(composioUserId = snapshot.ownerId, connectionGeneration = snapshot.generation, composioApiKey = "fixture-key")
        val intent = ActionIntent(toolName = tool.id, accountId = "account_1", argumentsJson = """{"to":"recipient@example.com","body":"Hello"}""",
            ownerId = snapshot.ownerId, connectionGeneration = snapshot.generation, toolVersion = tool.version, toolSchemaJson = tool.schemaJson)
        var task = TaskRules.approve(AgentTask(title = "Send email", status = TaskStatus.AWAITING_APPROVAL, intent = intent), settings)
        val approval = client.approveAction(intent.accountId, tool, intent.argumentsJson)
        task = TaskRules.transition(task, TaskStatus.RUNNING, settings)
        try { client.execute(intent.accountId, tool, intent.argumentsJson, approval); fail("Expected ambiguous failure") }
        catch (error: ConnectionServiceException) { assertTrue(error.outcomeMayBeUnknown) }
        task = TaskRules.recover(task)
        assertEquals(TaskStatus.UNKNOWN, task.status)
        try { TaskRules.transition(task, TaskStatus.RUNNING, settings); fail("Unknown operation replayed") }
        catch (_: IllegalArgumentException) { }
        try { client.execute(intent.accountId, tool, intent.argumentsJson, approval); fail("Used approval replayed") }
        catch (_: ConnectionServiceException) { }
        assertEquals(1, fixture.submissions)
    }
}
