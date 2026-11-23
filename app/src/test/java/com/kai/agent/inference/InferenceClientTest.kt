package com.kai.agent.inference

import java.io.StringReader
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class InferenceClientTest {
    private lateinit var server: MockWebServer
    private val client = InferenceClient()
    @Before fun before() { server = MockWebServer(); server.start() }
    @After fun after() { server.shutdown() }
    private fun config() = ProviderConfig(ProviderId.OPENAI, server.url("/v1").toString(), "test-only-secret", "test-model")
    private val messages = listOf(InferenceMessage("system", "Be concise."), InferenceMessage("user", "Hello"))

    @Test(timeout = 20_000) fun catalogUsesExactBaseAndNeverPrintsSecret() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"data":[{"id":"z"},{"id":"a","name":"Alpha"},{"id":"z"}]}"""))
        val models = client.discoverModels(config())
        assertEquals(listOf("a", "z"), models.map { it.id })
        val request = server.takeRequest()
        assertEquals("/v1/models", request.path)
        assertEquals("Bearer test-only-secret", request.getHeader("Authorization"))
        assertFalse(config().toString().contains("test-only-secret"))
    }

    @Test(timeout = 20_000) fun streamingCollectsDeltasUsageAndMultiLineFrames() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            ": keep alive\r\n\r\n" +
            "data: {\r\ndata: \"choices\":[{\"delta\":{\"content\":\"Hello \"},\"finish_reason\":null}]}\r\n\r\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"there\"},\"finish_reason\":\"stop\"}]}\n\n" +
            "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":2}}\n\n" +
            "data: [DONE]\n\n"
        ))
        val deltas = mutableListOf<String>()
        val result = client.stream(config(), messages) { deltas += it }
        assertEquals(listOf("Hello ", "there"), deltas)
        assertEquals("Hello there", result.text)
        assertEquals("stop", result.finishReason)
        assertEquals(TokenUsage(4, 2), result.usage)
        val json = JSONObject(server.takeRequest().body.readUtf8())
        assertTrue(json.getBoolean("stream"))
        assertFalse(json.has("tools"))
        assertFalse(json.has("routing"))
    }

    @Test(timeout = 20_000) fun earlyDisconnectDoesNotMasqueradeAsCompletedResponse() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"
        ))
        val received = StringBuilder()
        val error = runCatching { client.stream(config(), messages) { received.append(it) } }.exceptionOrNull()
        assertEquals("partial", received.toString())
        assertTrue(error is InferenceException)
        assertTrue(error!!.message!!.contains("ended early"))
    }

    @Test(timeout = 20_000) fun lateVerifiedContextAfterKeepAliveIsMetadataOnly() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            ": kai-keepalive\n\ndata: {\"kai_context\":{\"contextSize\":16384,\"verified\":true}}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
        ))
        val deltas = mutableListOf<String>()
        val result = client.stream(config(), messages) { deltas += it }
        assertEquals(listOf("ok"), deltas)
        assertEquals(16384, result.activeContextTokens)
        assertEquals(1, server.requestCount)
    }

    @Test(timeout = 20_000) fun unverifiedOrInvalidLateContextCannotReplaceVerifiedHeader() = runBlocking {
        val invalid = listOf("{\"contextSize\":16384}", "{\"contextSize\":16384,\"verified\":false}",
            "{\"contextSize\":\"16384\",\"verified\":true}", "{\"contextSize\":16384.5,\"verified\":true}",
            "{\"contextSize\":-1,\"verified\":true}", "{\"contextSize\":9999999,\"verified\":true}")
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setHeader("x-kai-context-size", "8192").setBody(
            invalid.joinToString("") { "data: {\"kai_context\":$it}\n\n" } + "data: [DONE]\n\n"
        ))
        assertEquals(8192, client.stream(config(), messages) {}.activeContextTokens)
    }

    @Test(timeout = 20_000) fun lateTimeoutIsAnErrorWithStatusAndDoesNotRetryOrEchoProviderText() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            ": kai-keepalive\n\ndata: {\"error\":{\"status\":504,\"code\":\"request_timeout\",\"type\":\"remote_access_error\",\"message\":\"test-only-secret\"}}\n\n"
        ))
        val deltas = mutableListOf<String>()
        val error = runCatching { client.stream(config(), messages) { deltas += it } }.exceptionOrNull()
        assertTrue(error is InferenceException)
        assertEquals(504, (error as InferenceException).httpStatus)
        assertTrue(error.message!!.contains("took longer"))
        assertFalse(error.message!!.contains("test-only-secret"))
        assertTrue(deltas.isEmpty()); assertEquals(1, server.requestCount)
    }

    @Test(timeout = 20_000) fun routeIsRejectedBeforeAnyNetworkRequest() = runBlocking {
        val c = config().copy(provider = ProviderId.KOINOS, routing = RoutingMode.NETWORK_ONLY)
        val error = runCatching { client.stream(c, messages) {} }.exceptionOrNull()
        assertTrue(error is InferenceException)
        assertEquals(0, server.requestCount)
    }

    @Test(timeout = 20_000) fun cancellationStopsBlockedStreamPromptly() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val task = launch { client.stream(config(), messages) {} }
        withTimeout(2000) {
            while (server.requestCount == 0) delay(10)
        }
        withTimeout(1000) { task.cancelAndJoin() }
        assertTrue(task.isCancelled)
    }

    @Test(timeout = 20_000) fun redirectsAreNotFollowedAndSecretsAreNotInErrors() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/steal")))
        val error = runCatching { client.discoverModels(config()) }.exceptionOrNull()
        assertTrue(error is InferenceException)
        assertEquals(1, server.requestCount)
        assertFalse(error.toString().contains("test-only-secret"))
    }

    @Test(timeout = 20_000) fun standardNonStreamingJsonIsAccepted() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
            """{"choices":[{"message":{"content":"Hello"},"finish_reason":"stop"}]}"""
        ))
        assertEquals("Hello", client.stream(config(), messages) {}.text)
    }

    @Test(timeout = 20_000) fun unexpectedToolCallsCannotBeReportedAsExecuted() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"function\":{\"name\":\"send_email\"}}]}}]}\n\n"
        ))
        val error = runCatching { client.stream(config(), messages) {} }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("No action was executed"))
    }

    @Test(timeout = 20_000) fun maliciousErrorBodyNeverLeaksCredential() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("test-only-secret echoed from hostile endpoint"))
        val error = runCatching { client.discoverModels(config()) }.exceptionOrNull()
        assertFalse(error.toString().contains("test-only-secret"))
    }

    @Test(timeout = 20_000) fun coreContextErrorsAreClassifiedWithoutEchoingServerText() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"test-model"}]}"""))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"Prompt is ~5000 tokens — larger than the local model's 4096-token context. test-only-secret"}}"""))
        val error=runCatching { client.stream(config().copy(provider=ProviderId.KOINOS),messages) {} }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("context window"));assertFalse(error.toString().contains("test-only-secret"))
    }
    @Test(timeout = 20_000) fun servedContextMetadataIsScopedToTheCatalogModel() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"test-model","context_length":16384}]}"""))
        val local=config().copy(provider=ProviderId.KOINOS)
        assertEquals(16384,client.discoverModels(local).single().contextTokens)
        server.takeRequest()
        server.enqueue(MockResponse().setHeader("Content-Type","application/json").setBody("""{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}"""))
        assertEquals("ok",client.stream(local,listOf(InferenceMessage("user","a".repeat(15000)))) {}.text)
        server.takeRequest()
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"different-model"}]}"""))
        val error=runCatching { client.stream(local.copy(modelId="different-model"),listOf(InferenceMessage("user","a".repeat(15000)))) {} }.exceptionOrNull()
        assertTrue(error is InferenceException);assertEquals(3,server.requestCount)
    }
    @Test(timeout = 20_000) fun endpointPreservesRelayPrefixAndRejectsUnsafeTransport() {
        assertEquals("https://example.com/relay/user/v1/models", EndpointPolicy.resource("https://example.com/relay/user/v1/", "models").toString())
        assertEquals("https://api.openai.com/v1/models", EndpointPolicy.resource("https://api.openai.com", "models").toString())
        assertEquals("https://api.openai.com/v1/models", EndpointPolicy.resource("https://api.openai.com/v1/chat/completions", "models").toString())
        listOf("http://example.com/v1", "https://secret@example.com/v1", "https://example.com/v1?key=secret", "file:///tmp/foo").forEach {
            assertTrue(runCatching { EndpointPolicy.base(it) }.exceptionOrNull() is InferenceException)
        }
    }

    @Test(timeout = 20_000) fun sseBoundsFramesBeforeAllocatingUnboundedInput() {
        val source = "data: " + "x".repeat(SseDecoder.MAX_FRAME_CHARS + 1)
        val error = runCatching { SseDecoder.read(StringReader(source).buffered()) { true } }.exceptionOrNull()
        assertTrue(error is InferenceException)
    }

    @Test(timeout = 20_000) fun textOnlyKoinosRequestHasNoOpenAiSpecificExtensions() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"test-model"}]}"""))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
        client.stream(config().copy(provider = ProviderId.KOINOS), messages) {}
        assertEquals("/v1/models", server.takeRequest().path)
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertTrue(body.has("max_tokens"))
        assertFalse(body.has("max_completion_tokens"))
        assertFalse(body.has("tools"))
        assertFalse(body.has("response_format"))
    }

    @Test(timeout = 20_000) fun koinosBoundsSerializedUtf8BytesBeforeSubmitting() = runBlocking {
        val content = "界".repeat(45000)
        val error = runCatching {
            client.stream(config().copy(provider = ProviderId.KOINOS), listOf(InferenceMessage("user", content))) {}
        }.exceptionOrNull()
        assertTrue(error is InferenceException)
        assertTrue(error!!.message!!.contains("128 KB"))
        assertEquals(0, server.requestCount)
    }
}
