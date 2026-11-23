package com.kai.agent.inference

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class InferenceTimingTest {
    private lateinit var server: MockWebServer
    private val messages = listOf(InferenceMessage("user", "Hello"))
    private val events = CopyOnWriteArrayList<Pair<String, JSONObject>>()
    @Before fun setup() { server = MockWebServer(); server.start() }
    @After fun cleanup() { server.shutdown() }
    private fun config(provider: ProviderId = ProviderId.KOINOS) = ProviderConfig(provider, server.url("/v1").toString(), "fixture-secret", "local")
    private fun catalog() = MockResponse().setBody("""{"data":[{"id":"local","context_window":131072,"context_mode":"auto"}]}""")
    private fun answer() = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
    private fun client(read: Long = 1000, total: Long = 5000) = InferenceClient(timeoutPolicy = { InferenceTimeoutPolicy(2000, read, total) })
    private suspend fun collect(client: InferenceClient, provider: ProviderId = ProviderId.KOINOS): String {
        val output = StringBuilder()
        client.events(config(provider), messages, diagnostic = { kind, data -> events += kind to data }).collect {
            if (it is StreamEvent.Delta) output.append(it.text)
        }
        return output.toString()
    }
    private fun event(name: String) = events.single { it.first == name }.second

    @Test(timeout = 20000) fun productionPoliciesAreAppliedPerProviderAndSavedTaskDeadlineAllowsLocalWait() = runBlocking {
        for (provider in ProviderId.entries) {
            events.clear()
            if (provider == ProviderId.KOINOS) server.enqueue(catalog())
            server.enqueue(answer())
            assertEquals("ok", collect(InferenceClient(), provider))
            val policy = event("model_transport_start")
            assertEquals(if (provider == ProviderId.KOINOS) 300000 else 90000, policy.getInt("readTimeoutMs"))
            assertEquals(if (provider == ProviderId.KOINOS) 360000 else 180000, policy.getInt("callTimeoutMs"))
            assertTrue(InferenceTimeoutPolicy.taskTurnMillis(provider) > policy.getLong("callTimeoutMs") + 10000)
            assertTrue(events.any { it.first == "model_request_sent" })
            assertTrue(event("model_first_content").getLong("elapsedMs") >= event("model_http").getLong("elapsedMs"))
        }
    }

    @Test(timeout = 20000) fun delayedHeadersCompleteWithinSelectedWaitWithoutRetry() = runBlocking {
        server.enqueue(catalog()); server.enqueue(answer().setHeadersDelay(400, TimeUnit.MILLISECONDS))
        assertEquals("ok", collect(client(read = 1500, total = 3000)))
        assertTrue(event("model_http").getLong("elapsedMs") >= 350)
        assertEquals(2, server.requestCount)
        assertFalse(events.any { it.first == "model_transport_error" })
    }

    @Test(timeout = 20000) fun keepAlivesAllowSlowContentWithoutBecomingChatText() = runBlocking {
        val beat = ": " + ".".repeat(508) + "\n\n"
        server.enqueue(catalog())
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody(beat.repeat(4) + "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
            .throttleBody(512, 200, TimeUnit.MILLISECONDS))
        assertEquals("ok", collect(client(read = 500, total = 5000)))
        assertTrue(event("model_first_content").getLong("elapsedMs") >= 600)
        assertEquals(2, server.requestCount)
    }

    @Test(timeout = 20000) fun timeoutBeforeHeadersRecordsSentRequestWithoutInventingAResponseOrRetry() = runBlocking {
        server.enqueue(catalog()); server.enqueue(answer().setHeadersDelay(2000, TimeUnit.MILLISECONDS))
        val error = runCatching { collect(client()) }.exceptionOrNull()
        assertTrue(error is InferenceException)
        assertTrue(error!!.message!!.contains("waiting for the local model"))
        assertFalse(error.message!!.contains("fixture-secret"))
        val failure = event("model_transport_error")
        assertEquals("awaiting_response", failure.getString("phase"))
        assertTrue(failure.getBoolean("timeout"))
        assertFalse(failure.getBoolean("headersReceived")); assertFalse(failure.getBoolean("contentReceived"))
        assertFalse(events.any { it.first == "model_http" })
        assertEquals(2, server.requestCount)
    }

    @Test(timeout = 20000) fun headersThenBodyTimeoutIsDistinguishedFromConnectionFailure() = runBlocking {
        server.enqueue(catalog()); server.enqueue(answer().setBodyDelay(2000, TimeUnit.MILLISECONDS))
        val error = runCatching { collect(client()) }.exceptionOrNull()
        assertTrue(error is InferenceException)
        assertTrue(error!!.message!!.contains("rest of the model response"))
        val failure = event("model_transport_error")
        assertEquals("awaiting_content", failure.getString("phase"))
        assertTrue(failure.getBoolean("headersReceived")); assertFalse(failure.getBoolean("contentReceived"))
        assertEquals(200, event("model_http").getInt("httpStatus"))
        assertEquals(2, server.requestCount)
    }

    @Test(timeout = 20000) fun partialResponseStallDoesNotReturnACompletionOrReplay() = runBlocking {
        // MockWebServer also throttles request reads. Keep the first chunk larger than the request.
        val first = ": " + " ".repeat(512) + "\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"
        server.enqueue(catalog())
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody(first + "data: [DONE]\n\n").throttleBody(first.toByteArray().size.toLong(), 2000, TimeUnit.MILLISECONDS))
        val output = StringBuilder()
        val error = runCatching { client().events(config(), messages, diagnostic = { kind, data -> events += kind to data })
            .collect { if (it is StreamEvent.Delta) output.append(it.text) } }.exceptionOrNull()
        assertTrue(error is InferenceException); assertEquals(events.toString(), "partial", output.toString())
        val failure = event("model_transport_error")
        assertEquals("receiving_content", failure.getString("phase"))
        assertTrue(failure.getBoolean("contentReceived")); assertTrue(failure.getBoolean("timeout"))
        assertEquals(2, server.requestCount)
    }

    @Test(timeout = 20000) fun totalDeadlineStillStopsAnActiveSlowStream() = runBlocking {
        server.enqueue(catalog())
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody(": " + " ".repeat(100000) + "\n\ndata: [DONE]\n\n").throttleBody(512, 40, TimeUnit.MILLISECONDS))
        val error = runCatching { collect(client(read = 3000, total = 1500)) }.exceptionOrNull()
        assertTrue(error is InferenceException)
        assertTrue(event("model_transport_error").getBoolean("timeout"))
        assertTrue(events.toString(), event("model_transport_error").getBoolean("headersReceived"))
        assertEquals(events.toString(), 2, server.requestCount)
    }

    @Test(timeout = 20000) fun longLocalWaitRemainsCancellable() = runBlocking {
        server.enqueue(catalog()); server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = launch { collect(InferenceClient()) }
        withTimeout(3000) { while (server.requestCount < 2) delay(10) }
        withTimeout(1000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled); assertEquals(2, server.requestCount)
    }
}
