package com.kai.agent.inference

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ContextDiscoveryTest {
    private lateinit var server: MockWebServer
    @Before fun before() { server = MockWebServer(); server.start() }
    @After fun after() { server.shutdown() }
    private fun config() = ProviderConfig(ProviderId.KOINOS, server.url("/v1").toString(), "fixture-secret", "local")
    private val large = listOf(InferenceMessage("system", "Preserve action evidence."),
        InferenceMessage("user", "a".repeat(18000)), InferenceMessage("user", "Receipt: UNKNOWN; never replay."))
    private val small = listOf(InferenceMessage("user", "hello"))
    private fun catalog(window: String = "16384", mode: String = "auto") = MockResponse().setBody(
        """{"data":[{"id":"local","context_window":$window,"context_mode":"$mode"}]}""")
    private fun reply() = MockResponse().setHeader("Content-Type", "application/json")
        .setBody("""{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}""")

    @Test(timeout = 20_000) fun freshClientDiscoversBeforeBudgetingAndRequestsEnoughContext() = runBlocking {
        server.enqueue(catalog()); server.enqueue(reply())
        assertEquals("ok", InferenceClient().stream(config(), large) {}.text)
        assertEquals("GET", server.takeRequest().method)
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(3, body.getJSONArray("messages").length())
        assertEquals(large.last().content, body.getJSONArray("messages").getJSONObject(2).getString("content"))
        val requested = body.getInt("context_tokens")
        assertTrue(requested > 4096 && requested <= 16384)
        assertEquals(2048, body.getInt("max_tokens"))
        assertEquals(InferenceBudget.estimate(large) + 512 + 2048, requested)
    }

    @Test(timeout = 20_000) fun newTaskClientAndRestartRediscoverInsteadOfDependingOnSettingsVisit() = runBlocking {
        repeat(2) {
            server.enqueue(catalog()); server.enqueue(reply())
            InferenceClient().stream(config(), large) {}
            assertEquals("/v1/models", server.takeRequest().path)
            assertEquals("/v1/chat/completions", server.takeRequest().path)
        }
    }

    @Test(timeout = 20_000) fun currentCapabilityIsReusedButAnExpiredLowerCapWins() = runBlocking {
        var now = 0L
        val client = InferenceClient(clockMillis = { now })
        server.enqueue(catalog()); server.enqueue(reply()); server.enqueue(reply())
        client.stream(config(), large) {}; client.stream(config(), large) {}
        assertEquals(3, server.requestCount)
        now = 60_001
        server.enqueue(catalog("4096"))
        val error = runCatching { client.stream(config(), large) {} }.exceptionOrNull()
        assertTrue(error is InferenceException)
        assertTrue(error!!.message!!.contains("reported 4096-token"))
        assertEquals(4, server.requestCount)
    }

    @Test(timeout = 20_000) fun refreshedMissingMetadataCannotReuseAnOldLargeWindow() = runBlocking {
        var now = 0L
        val client = InferenceClient(clockMillis = { now })
        server.enqueue(catalog()); client.discoverModels(config())
        now = 60_001; server.enqueue(MockResponse().setBody("""{"data":[{"id":"local"}]}"""))
        val error = runCatching { client.stream(config(), large) {} }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("did not report")); assertEquals(2, server.requestCount)
    }

    @Test(timeout = 20_000) fun modelEndpointAndCredentialsDoNotShareContext() = runBlocking {
        val client = InferenceClient()
        server.enqueue(catalog()); client.discoverModels(config())
        for (changed in listOf(config().copy(modelId = "other"), config().copy(apiKey = "other-secret"), config().copy(endpoint = server.url("/different/v1").toString()))) {
            server.enqueue(MockResponse().setBody("""{"data":[]}"""))
            assertTrue(runCatching { client.stream(changed, large) {} }.exceptionOrNull() is InferenceException)
        }
        assertEquals(4, server.requestCount)
    }

    @Test(timeout = 20_000) fun oldApiRemainsUsableWithoutAnUnsupportedContextExtension() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"local"}]}""")); server.enqueue(reply())
        InferenceClient().stream(config(), small) {}
        server.takeRequest()
        assertFalse(JSONObject(server.takeRequest().body.readUtf8()).has("context_tokens"))
    }

    @Test(timeout = 20_000) fun aFixedReportedWindowIsUsedWithoutRequestingAutomaticAllocation() = runBlocking {
        server.enqueue(catalog(mode = "fixed")); server.enqueue(reply())
        InferenceClient().stream(config(), large) {}; server.takeRequest()
        assertFalse(JSONObject(server.takeRequest().body.readUtf8()).has("context_tokens"))
    }

    @Test(timeout = 20_000) fun missingOrMalformedMetadataReportsFallbackInsteadOfClaimingServerCapacity() = runBlocking {
        for (window in listOf("null", "\"16384\"", "16384.5", "-1", "999999999")) {
            server.enqueue(catalog(window))
            val error = runCatching { InferenceClient().stream(config(), large) {} }.exceptionOrNull()
            assertTrue(error!!.message!!.contains("did not report"))
        }
        assertEquals(5, server.requestCount)
    }

    @Test(timeout = 20_000) fun unavailableDiscoveryUsesTheConservativeBudgetWithoutReplayingInference() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404)); server.enqueue(reply())
        val client = InferenceClient(); assertEquals("ok", client.stream(config(), small) {}.text)
        assertTrue(runCatching { client.stream(config(), large) {} }.exceptionOrNull() is InferenceException)
        assertEquals(2, server.requestCount)
    }

    @Test(timeout = 20_000) fun authorizationFailureStopsBeforeInferenceAndDoesNotExposeSecrets() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("fixture-secret"))
        val error = runCatching { InferenceClient().stream(config(), small) {} }.exceptionOrNull()
        assertTrue(error is InferenceException); assertFalse(error.toString().contains("fixture-secret"))
        assertEquals(1, server.requestCount)
    }

    @Test(timeout = 20_000) fun discoveryCancellationNeverSubmitsAnInferenceRequest() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = launch { InferenceClient().stream(config(), small) {} }
        withTimeout(2000) { while (server.requestCount == 0) delay(10) }
        withTimeout(1000) { job.cancelAndJoin() }
        assertEquals(1, server.requestCount)
    }
    @Test(timeout = 20_000) fun testApiShowsAutomaticFixedAndMissingLimits() = runBlocking {
        for ((window, mode, expected) in listOf(Triple("16384", "auto", "Automatic context: up to 16,384"),
            Triple("4096", "fixed", "API context limit: 4,096"), Triple("null", "auto", "Context limit not reported"))) {
            server.enqueue(catalog(window, mode))
            assertTrue(InferenceClient().testConnection(config()).message.contains(expected))
        }
    }

    @Test(timeout = 20_000) fun responseReportsActualAllocationInsteadOfCatalogCeiling() = runBlocking {
        server.enqueue(catalog("131072"))
        server.enqueue(reply().setHeader("x-kai-context-size", "8192"))
        val result = InferenceClient().stream(config(), small) {}
        assertEquals(8192, result.activeContextTokens)
        assertTrue(result.status(config()).contains("8,192"))
        assertFalse(result.status(config()).contains("131,072"))
    }

    @Test(timeout = 20_000) fun streamedAllocationIsRetainedAfterDone() = runBlocking {
        server.enqueue(catalog()); server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setHeader("x-kai-context-size", "16384").setBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
        assertEquals(16384, InferenceClient().stream(config(), small) {}.activeContextTokens)
    }

    @Test(timeout = 20_000) fun invalidOrUnverifiedAllocationIsNotPresentedAsActive() = runBlocking {
        val client = InferenceClient(); server.enqueue(catalog())
        for (header in listOf("bad", "-1", "999999999", "16384.5")) {
            server.enqueue(reply().setHeader("x-kai-context-size", header))
            val result = client.stream(config(), small) {}
            assertNull(result.activeContextTokens); assertTrue(result.status(config()).contains("not reported"))
        }
        for ((verified, size, expected) in listOf(Triple("false", "16384", null), Triple("true", "16384.5", null), Triple("true", "16384", 16384))) {
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}],"kai_context":{"verified":$verified,"contextSize":$size}}"""))
            assertEquals(expected, client.stream(config(), small) {}.activeContextTokens)
        }
    }

}
