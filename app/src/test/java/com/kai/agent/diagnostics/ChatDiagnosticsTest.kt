package com.kai.agent.diagnostics

import com.kai.agent.data.*
import com.kai.agent.inference.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatDiagnosticsTest {
    private val secret="diagnostic-fixture-secret"
    private val settings=AppSettings(koinos=ProviderProfile("https://example.com/r/private-route/v1",secret,"local"),composioApiKey="project-key-fixture")
    private val redactor=DiagnosticRedactor(settings)
    @Test fun nestedAndEmbeddedJsonSecretsAreRemovedBeforePersistence() {
        val raw=JSONObject().put("api_key","unknown-key").put("error","Bearer $secret password=unknown-password")
            .put("nested",JSONObject().put("refresh_token","unknown-token").put("output", """{"authorization":"other-secret","content":"$secret"}"""))
        val text=redactor.event(raw)
        listOf(secret,"unknown-key","unknown-password","unknown-token","other-secret").forEach { assertFalse("Leaked: $it in $text",text.contains(it)) }
        assertTrue(text.contains("credential-removed")); JSONObject(text)
    }
    @Test fun signedLinksRelayRoutesAndAttachmentBytesAreExcluded() {
        val text=redactor.text("https://user:pass@example.com/r/private-route/v1?key=another-secret#private data:image/jpeg;base64,YWJjZA==")
        listOf("user:pass","another-secret","private-route/v1","YWJjZA==").forEach { assertFalse("Leaked: $it in $text",text.contains(it)) }
        assertTrue(redactor.text("https://docs.google.com/document/d/doc-id/edit").contains("doc-id/edit"))
        val safeLink=redactor.text("https://docs.google.com/document/d/doc-id/edit?usp=sharing")
        assertEquals(safeLink,redactor.text(safeLink))
        val mixed=redactor.text("https://example.com/path?key=$secret&token=unconfigured-url-token")
        assertFalse(mixed.contains(secret)); assertFalse(mixed.contains("unconfigured-url-token"))
    }
    @Test fun oversizedEventsStayValidAndExplicitlyMarked() {
        val input=JSONObject().put("sessionId","session-1").put("event","connection_response").put("a","x ".repeat(70000)).put("b","y ".repeat(70000))
        val result=redactor.event(input)
        assertTrue(result.length<=DiagnosticRedactor.MAX_EVENT_CHARS)
        assertTrue(JSONObject(result).getBoolean("truncated"))
        assertEquals("session-1",JSONObject(result).getString("sessionId"))
    }
    @Test fun binaryFieldsAndEscapingCannotBypassBounds() {
        assertFalse(redactor.event(JSONObject().put("data","YWJj".repeat(1000))).contains("YWJj"))
        val raw=JSONObject().put("a","\\\" ".repeat(90000)).put("b","\\\" ".repeat(90000))
        val safe=redactor.event(raw)
        assertTrue(safe.length<=DiagnosticRedactor.MAX_EVENT_CHARS); JSONObject(safe)
    }
    @Test fun usefulArgumentsAndModelBudgetsArePreserved() {
        val input=JSONObject().put("max_tokens",4096).put("context_tokens",16384)
            .put("tool","GOOGLEDOCS_CREATE_DOCUMENT").put("arguments",JSONObject().put("title","test").put("text","this is a test"))
        assertEquals(input.toString(),redactor.event(input))
    }
    @Test(timeout=20000) fun reportCapturesActualBudgetedPromptResponseAndStatusWithoutChangingWireRequest() = runBlocking {
        val server=MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"local","context_window":4096,"context_mode":"auto"}]}"""))
            server.enqueue(MockResponse().setHeader("Content-Type","application/json").setHeader("x-kai-context-size","4096")
                .setBody("""{"choices":[{"message":{"content":"{\"type\":\"reply\",\"text\":\"OK\"}"},"finish_reason":"stop"}],"usage":{"prompt_tokens":250,"completion_tokens":12}}"""))
            val records=mutableListOf<JSONObject>()
            val trace=ChatTrace(conversationId="chat",userMessageId="message") { records+=JSONObject(redactor.event(it)) }
            val config=ProviderConfig(ProviderId.KOINOS,server.url("/v1").toString(),secret,"local")
            val messages=listOf(InferenceMessage("system","Keep receipts"),InferenceMessage("user","x".repeat(15000),canOmit=true),InferenceMessage("user","make a doc"))
            withContext(TraceContext(trace)) { InferenceClient().stream(config,messages) {} }
            server.takeRequest(); val wire=JSONObject(server.takeRequest().body.readUtf8())
            val request=records.first { it.getString("event")=="model_request" }.getJSONObject("data")
            assertEquals(1,request.getInt("omittedMessages"))
            val sent=request.getJSONArray("messages")
            assertEquals(wire.getJSONArray("messages").length(),sent.length())
            for(i in 0 until sent.length()) assertEquals(wire.getJSONArray("messages").getJSONObject(i).getString("content"),sent.getJSONObject(i).getString("content"))
            assertEquals(wire.getInt("max_tokens"),request.getJSONObject("generation").getInt("max_tokens"))
            val response=records.first { it.getString("event")=="model_response" }.getJSONObject("data")
            assertEquals(request.getString("requestId"),response.getString("requestId"))
            assertEquals(4096,response.getInt("activeContextTokens")); assertEquals(250,response.getInt("inputTokens"))
            assertEquals("stop",response.getString("finishReason")); assertFalse(records.toString().contains(secret))
            assertEquals(2,server.requestCount)
        } finally { server.shutdown() }
    }
    @Test(timeout=20000) fun provider400KeepsSanitizedBodyAndNeverRetries() = runBlocking {
        val server=MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"Unsupported parameter. Bearer $secret","api_key":"unconfigured-echo"}}"""))
            val records=mutableListOf<JSONObject>(); val trace=ChatTrace(conversationId="chat",userMessageId="message") { records+=JSONObject(redactor.event(it)) }
            val failure=runCatching { withContext(TraceContext(trace)) { InferenceClient().stream(ProviderConfig(ProviderId.OPENAI,server.url("/v1").toString(),secret,"model"),listOf(InferenceMessage("user","Hello"))) {} } }.exceptionOrNull()
            assertTrue(failure is InferenceException)
            assertEquals(400,records.first { it.getString("event")=="model_http" }.getJSONObject("data").getInt("httpStatus"))
            assertTrue(records.toString().contains("Unsupported parameter")); assertFalse(records.toString().contains(secret)); assertFalse(records.toString().contains("unconfigured-echo"))
            assertTrue(records.any { it.getString("event")=="model_error" }); assertEquals(1,server.requestCount)
        } finally { server.shutdown() }
    }
    @Test(timeout=20000) fun incompleteStreamRetainsPartialOutputAndMissingFinish() = runBlocking {
        val server=MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"))
            val records=mutableListOf<JSONObject>(); val trace=ChatTrace(conversationId="chat",userMessageId="message") { records+=it }
            assertNotNull(runCatching { withContext(TraceContext(trace)) { InferenceClient().stream(ProviderConfig(ProviderId.OPENAI,server.url("/v1").toString(),secret,"model"),listOf(InferenceMessage("user","Hello"))) {} } }.exceptionOrNull())
            assertEquals("partial",records.first { it.getString("event")=="model_error" }.getJSONObject("data").getString("partialOutput"))
            assertFalse(records.any { it.getString("event")=="model_response" }); assertEquals(1,server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun malformedModelResponseRetainsTrailingTextWhileStillRedactingSecrets() {
        val raw="""{"type":"reply","text":"first object"} trailing Bearer abcdef-secret"""
        val cleaned=redactor.clean(raw).toString()
        assertTrue(cleaned.contains("trailing"))
        assertTrue(cleaned.contains("first object"))
        assertFalse(cleaned.contains("abcdef-secret"))
    }

}
