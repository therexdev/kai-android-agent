package com.kai.agent.connections

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ManagedConnectionsClientTest {
    private class Fake : ConnectionsTransport {
        var generation = "project-one"
        var owner = "alice"
        var accountOwner = "kai:alice"
        var executeCount = 0
        var lastExecute: JSONObject? = null
        var onAccounts: (() -> Unit)? = null
        val toolJson = JSONObject("""{"id":"SEND_EMAIL","name":"Send email","description":"Send a message","toolkit":"gmail","version":"20260901","readOnly":false,"schema":{"type":"object","properties":{"to":{"type":"string"},"body":{"type":"string"}},"required":["to","body"]}}""")
        override suspend fun request(path: String, token: String?, body: JSONObject?): JSONObject = when (path) {
            "/connections/status" -> JSONObject().put("ok", true).put("available", true).put("protocol", 1).put("generation", generation)
            "/connections/api/accounts" -> {
                assertEquals(generation, body?.getString("generation"))
                onAccounts?.invoke()
                JSONObject().put("result", JSONObject().put("userId", "kai:$owner").put("generation", generation)
                    .put("accounts", org.json.JSONArray().put(JSONObject().put("id", "acct1").put("toolkit", "gmail")
                        .put("userId", accountOwner).put("name", "My Gmail").put("status", "ACTIVE").put("disabled", false))))
            }
            "/connections/api/tool" -> JSONObject().put("result", toolJson)
            "/connections/api/execute" -> {
                executeCount++
                lastExecute = body
                JSONObject().put("result", "{\"id\":\"message-123\"}")
            }
            else -> error("Unexpected request $path")
        }
    }

    @Test fun rejectsForeignAccountMetadata() = runBlocking {
        val fake = Fake().apply { accountOwner = "kai:someone-else" }
        val client = ManagedConnectionsClient(fake)
        client.useSession(ManagedSession("alice", "secret"))
        expect<ConnectionServiceException> { client.refresh() }
    }

    @Test fun ignoresResponseAfterAccountSwitch() = runBlocking {
        val fake = Fake()
        val client = ManagedConnectionsClient(fake)
        client.useSession(ManagedSession("alice", "secret"))
        fake.onAccounts = { client.useSession(ManagedSession("bob", "other-secret")) }
        expect<CancellationException> { client.refresh() }
        expect<ConnectionServiceException> { client.catalog() } // No Alice project/data survives.
    }

    @Test fun changedArgumentsCannotReuseApproval() = runBlocking {
        val fake = Fake()
        val client = ready(fake)
        val tool = client.tool("SEND_EMAIL", "20260901")
        val args = """{"to":"one@example.com","body":"Hello"}"""
        val approval = client.approveAction("acct1", tool, args)
        expect<ConnectionServiceException> {
            client.execute("acct1", tool, """{"to":"other@example.com","body":"Hello"}""", approval)
        }
        assertEquals(0, fake.executeCount)
        val receipt = client.execute("acct1", tool, """{"body":"Hello","to":"one@example.com"}""", approval)
        assertEquals(1, fake.executeCount)
        assertEquals("message-123", JSONObject(receipt.resultJson).getString("id"))
        assertEquals("project-one", fake.lastExecute?.getString("generation"))
        assertFalse(fake.lastExecute!!.has("userId")) // Server derives identity, never caller supplied.
        expect<ConnectionServiceException> { client.execute("acct1", tool, args, approval) }
        assertEquals(1, fake.executeCount) // Double-taps cannot reuse an execution approval.
    }

    @Test fun projectRotationInvalidatesApproval() = runBlocking {
        val fake = Fake()
        val client = ready(fake)
        val tool = client.tool("SEND_EMAIL", "20260901")
        val args = """{"to":"one@example.com","body":"Hello"}"""
        val approval = client.approveAction("acct1", tool, args)
        fake.generation = "project-two"
        client.refresh()
        expect<ConnectionServiceException> { client.execute("acct1", tool, args, approval) }
        assertEquals(0, fake.executeCount)
    }

    @Test fun rejectsUnsafeHostedSignInUrls() {
        listOf("http://connect.composio.dev/link", "https://connect.composio.dev.evil.test/link",
            "https://connect.composio.dev@evil.test/link", "https://connect.composio.dev:444/link",
            "https://evil.test/?next=https://connect.composio.dev").forEach { raw ->
            try { ManagedConnectionsClient.validateHostedUrl(raw); fail("Accepted unsafe URL") }
            catch (_: ConnectionServiceException) { }
        }
        assertEquals("https://connect.composio.dev/link?a=1", ManagedConnectionsClient.validateHostedUrl("https://connect.composio.dev/link?a=1"))
    }

    @Test fun validationRejectsMissingUnknownAndWrongTypeArguments() {
        val schema = """{"properties":{"count":{"type":"integer"}},"required":["count"]}"""
        listOf("{}", "{\"count\":1.5}", "{\"count\":1,\"unexpected\":true}").forEach { raw ->
            try { ConnectionArguments.validate(raw, schema); fail("Accepted invalid inputs") }
            catch (_: ConnectionServiceException) { }
        }
        assertFalse(ManagedSession("alice", "private-token").toString().contains("private-token"))
    }

    @Test fun validatesNestedArrayItemsBeforeWrites() {
        val schema = """{"properties":{"recipients":{"type":"array","items":{"type":"object","properties":{"email":{"type":"string"}},"required":["email"],"additionalProperties":false}}},"required":["recipients"]}"""
        val valid = """{"recipients":[{"email":"one@example.com"}]}"""
        assertEquals(1, ConnectionArguments.validate(valid, schema).getJSONArray("recipients").length())
        listOf("""{"recipients":[{"email":42}]}""", """{"recipients":[{}]}""",
            """{"recipients":[{"email":"one@example.com","hiddenBcc":"other@example.com"}]}""").forEach {
            try { ConnectionArguments.validate(it, schema); fail("Accepted invalid nested recipients: $it") }
            catch (_: ConnectionServiceException) { }
        }
    }

    private suspend fun ready(fake: Fake): ManagedConnectionsClient = ManagedConnectionsClient(fake).also {
        it.useSession(ManagedSession("alice", "secret"))
        it.refresh()
    }
    private suspend inline fun <reified T : Throwable> expect(crossinline block: suspend () -> Unit) {
        try { block(); fail("Expected ${T::class.java.simpleName}") }
        catch (error: Throwable) { if (error !is T) throw error }
    }
}
