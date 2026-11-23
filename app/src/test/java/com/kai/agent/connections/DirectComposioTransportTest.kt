package com.kai.agent.connections

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Local server only. The injected test interceptor rewrites URL after asserting the fixed API origin. */
class DirectComposioTransportTest {
    private fun transport(server: MockWebServer): DirectComposioHttpTransport = DirectComposioHttpTransport(
        OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("backend.composio.dev", chain.request().url.host)
            assertEquals("https", chain.request().url.scheme)
            val original = chain.request()
            val local = server.url(original.url.encodedPath + (original.url.encodedQuery?.let { "?$it" } ?: ""))
            chain.proceed(original.newBuilder().url(local).build())
        }.build())

    @Test fun pagedCatalogLargerThan512KiBIsAcceptedAndQueryIsEncoded() = runBlocking {
        MockWebServer().use { server ->
            val payload = JSONObject().put("items", JSONArray().apply {
                repeat(24) { put(JSONObject().put("slug", "app$it").put("name", "App $it")
                    .put("meta", JSONObject().put("description", "x".repeat(25000)))) }
            }).put("next_cursor", "page2")
            assertTrue(payload.toString().length > 512 * 1024)
            server.enqueue(MockResponse().setBody(payload.toString()))
            val result = transport(server).request("/toolkits", "fixture-api-key", query = mapOf("limit" to "24", "cursor" to "+/&?="))
            assertEquals(24, result.getJSONArray("items").length())
            val request = server.takeRequest()
            assertEquals("fixture-api-key", request.getHeader("x-api-key"))
            assertEquals("24", request.requestUrl!!.queryParameter("limit"))
            assertEquals("+/&?=", request.requestUrl!!.queryParameter("cursor"))
            assertFalse(request.path!!.contains("fixture-api-key"))
        }
    }

    @Test fun oversizedExecutionReceiptIsUnknownAndNeverAutomaticallyReplayed() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(JSONObject().put("successful", true).put("data", "x".repeat(2 * 1024 * 1024)).toString()))
            val error = expect<ConnectionServiceException> { transport(server).request("/tools/execute/GMAIL_SEND_EMAIL", "fixture-key", "POST", body = JSONObject()) }
            assertTrue(error.outcomeMayBeUnknown)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun redirectsAnd503RetryAfterDoNotReplayOrLeakRemoteError() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://attacker.test/stolen"))
            val client = transport(server)
            val redirect = expect<ConnectionServiceException> { client.request("/toolkits", "fixture-key") }
            assertEquals(302, redirect.statusCode)
            assertEquals(1, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("fixture-key raw secret"))
            val unknown = expect<ConnectionServiceException> { client.request("/tools/execute/GMAIL_SEND_EMAIL", "fixture-key", "POST", body = JSONObject()) }
            assertTrue(unknown.outcomeMayBeUnknown)
            assertFalse(unknown.message!!.contains("fixture-key"))
            assertEquals(2, server.requestCount)
            assertNotNull(server.takeRequest(10, TimeUnit.MILLISECONDS))
            assertNotNull(server.takeRequest(10, TimeUnit.MILLISECONDS))
            assertNull(server.takeRequest(10, TimeUnit.MILLISECONDS))
        }
    }

    @Test fun rejectsArbitraryPathsBeforeSendingCredentialsAndExplicit401IsNotUnknown() = runBlocking {
        MockWebServer().use { server ->
            val client = transport(server)
            listOf("https://attacker.test", "//attacker.test", "/toolkits/../../secret", "/tools/x?key=a").forEach { path ->
                expect<IllegalArgumentException> { client.request(path, "fixture-key") }
            }
            assertEquals(0, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(401).setBody("fixture-key"))
            val error = expect<ConnectionServiceException> { client.request("/connected_accounts/link", "fixture-key", "POST", body = JSONObject()) }
            assertEquals(401, error.statusCode)
            assertFalse(error.outcomeMayBeUnknown)
            assertFalse(error.message!!.contains("fixture-key"))
        }
    }

    @Test fun normalizedKeyUsesProjectHeaderAndNeverFallsBackOn401() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("Invalid API key: private-fixture"))
            val error = expect<ConnectionServiceException> { transport(server).request("/connected_accounts", "Bearer \"private-fixture\"") }
            val request = server.takeRequest()
            assertEquals("private-fixture", request.getHeader("x-api-key"))
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("x-user-api-key"))
            assertTrue(request.path!!.startsWith("/api/v3.1/connected_accounts"))
            assertTrue(error.message!!.contains("wrong key type, or missing permissions"))
            assertTrue(error.message!!.contains("Connected accounts: Read"))
            assertFalse(error.message!!.contains("private-fixture"))
            assertFalse(error.message!!.contains("rejected the API key"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun forbiddenErrorNamesPermissionWithoutTreatingKeyAsInvalid() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("private-fixture access denied"))
            val error = expect<ConnectionServiceException> { transport(server).request("/tools", "private-fixture") }
            assertEquals(403, error.statusCode)
            assertTrue(error.message!!.contains("Tools: Read"))
            assertTrue(error.message!!.contains("does not prove the key is invalid"))
            assertFalse(error.message!!.contains("private-fixture"))
            assertEquals("Tool execution (Legacy): Write", DirectComposioHttpTransport.requiredPermission("/tools/execute/GMAIL_SEND_EMAIL", "POST"))
        }
    }

    @Test fun diagnosticsCorrelateFailedActionAndPreserveSafeServiceDetails() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(422).setBody("""{"error":{"code":"INVALID_INPUT","message":"Missing documentId","api_key":"unknown-echo"}}"""))
            val records=mutableListOf<JSONObject>()
            val redactor=com.kai.agent.diagnostics.DiagnosticRedactor(com.kai.agent.data.AppSettings(composioApiKey="fixture-key"))
            val trace=com.kai.agent.diagnostics.ChatTrace(conversationId="chat",userMessageId="message") { records+=JSONObject(redactor.event(it)) }
            val error=expect<ConnectionServiceException> {
                kotlinx.coroutines.withContext(com.kai.agent.diagnostics.TraceContext(trace,"task-123")) {
                    transport(server).request("/tools/execute/GOOGLEDOCS_INSERT_TEXT","fixture-key","POST",body=JSONObject().put("arguments",JSONObject().put("text","test")))
                }
            }
            assertFalse(error.outcomeMayBeUnknown)
            val failed=records.first { it.getString("event")=="connection_error" }.getJSONObject("data")
            assertEquals("task-123",failed.getString("taskId")); assertEquals(422,failed.getInt("httpStatus"))
            assertTrue(failed.getString("errorBody").contains("Missing documentId"))
            assertFalse(records.toString().contains("fixture-key"));assertFalse(records.toString().contains("unknown-echo"))
            assertEquals(1,server.requestCount)
        }
    }

    private suspend inline fun <reified T : Throwable> expect(crossinline block: suspend () -> Unit): T {
        try { block() } catch (error: Throwable) { if (error is T) return error else throw error }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }
}
