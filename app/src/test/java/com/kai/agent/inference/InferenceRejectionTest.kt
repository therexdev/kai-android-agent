package com.kai.agent.inference

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InferenceRejectionTest {
    @Test fun explicitModelUnavailabilityHasItsOwnSafeCategory() {
        val body="""{"error":{"code":"managed_model_unavailable","message":"private provider detail"}}"""
        assertTrue(InferenceRejection.isModelUnavailable(body))
        assertFalse(InferenceRejection.isModelUnavailable("""{"error":{"code":"invalid_argument","message":"private"}}"""))
        val description=InferenceRejection.describe(body,400)!!
        assertTrue(description.contains("currently unavailable"));assertFalse(description.contains("private"))
        val current=ProviderConfig(ProviderId.KOINOS,"https://example.test","fixture","a")
        assertEquals("b",com.kai.agent.runtime.ModelPolicy(listOf(current.copy(modelId="b"))).next(current,InferenceException(description,400,failureCode=com.kai.agent.runtime.FailureCode.MODEL_UNAVAILABLE))?.modelId)
    }

    @Test fun knownAndLegacyRejectionsHaveSafeReasons() {
        val cases = listOf(
            """{"error":{"type":"remote_access_error","source":"local_model","code":"memory_exhausted","message":"secret /private/path"}}""" to "RAM or GPU memory",
            """{"error":{"source":"request_validation","message":"context_tokens must fit this model's 4096-token limit. secret"}}""" to "context window",
            """{"error":{"source":"request_validation","message":"Only local text chat is supported; tools and private application features are not exposed."}}""" to "request field",
            """{"error":{"message":"Choose an installed local model. secret"}}""" to "selected model",
            """{"error":{"code":"chat_template","source":"local_model","message":"secret"}}""" to "conversation format",
            """{"error":{"type":"remote_access_error","message":"Local model request failed."}}""" to "gateway hid",
            "<html>secret prompt and key</html>" to "HTML"
        )
        cases.forEach { (body, expected) ->
            val result = InferenceRejection.describe(body, 400)!!
            assertTrue(result, result.contains(expected)); assertFalse(result.contains("secret")); assertFalse(result.contains("/private/path"))
            assertTrue(result.contains("HTTP 400"))
        }
        val malicious = """{"error":{"code":"secret","type":"secret","source":"secret","message":"secret"}}"""
        assertFalse(InferenceRejection.describe(malicious, 400)!!.contains("secret"))
        assertNull(InferenceRejection.describe(malicious, 503))
    }

    @Test(timeout=20_000) fun explicitResponseTestUsesOnlySyntheticMessagesAndReportsAllocation() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val client = InferenceClient()
            val config = ProviderConfig(ProviderId.KOINOS, server.url("/v1").toString(), "secret-test-key", "llama31-8b")
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"llama31-8b","context_window":131072,"context_mode":"auto"}]}"""))
            server.enqueue(MockResponse().setHeader("content-type","text/event-stream").setHeader("x-kai-context-size","4096").setBody("data: {\"choices\":[{\"delta\":{\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
            val result = client.testResponse(config)
            assertTrue(result.contains("Model response received")); assertTrue(result.contains("4,096")); assertFalse(result.contains("131,072"))
            assertEquals("/v1/models",server.takeRequest().path)
            val request = server.takeRequest(); assertEquals("/v1/chat/completions",request.path)
            val body = JSONObject(request.body.readUtf8())
            assertEquals(128,body.getInt("max_tokens")); assertEquals(2,body.getJSONArray("messages").length())
            assertFalse(body.has("tools")); assertFalse(body.toString().contains("secret-test-key")); assertEquals(2,server.requestCount)
        } finally { server.shutdown() }
    }

    @Test(timeout=20_000) fun responseTestDoesNotRetryRejectedGenerationOrRunMissingModels() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val client=InferenceClient(); val config=ProviderConfig(ProviderId.KOINOS,server.url("/v1").toString(),"test-secret","local")
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"local","context_window":131072,"context_mode":"auto"}]}"""))
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"code":"chat_template","source":"local_model","message":"private prompt and test-secret"}}"""))
            val error=runCatching { client.testResponse(config) }.exceptionOrNull()!!
            assertTrue(error.message!!.contains("Local model")); assertTrue(error.message!!.contains("conversation format")); assertFalse(error.message!!.contains("test-secret")); assertEquals(2,server.requestCount)
            server.enqueue(MockResponse().setBody("""{"data":[]}"""))
            assertTrue(runCatching {client.testResponse(config)}.exceptionOrNull()!!.message!!.contains("Choose an installed model")); assertEquals(3,server.requestCount)
        } finally {server.shutdown()}
    }
}
