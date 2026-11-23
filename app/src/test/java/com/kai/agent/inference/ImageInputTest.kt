package com.kai.agent.inference

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ImageInputTest {
    @Test fun optInImageUsesContentPartsAndTextRemainsAString() = runBlocking {
        val server=MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setHeader("Content-Type","application/json").setBody("""{"choices":[{"message":{"content":"A photo"},"finish_reason":"stop"}]}"""))
            val config=ProviderConfig(ProviderId.OPENAI,server.url("/v1").toString(),"fixture-key","fixture-model")
            InferenceClient().stream(config,listOf(InferenceMessage("system","Answer accurately"),InferenceMessage("user","Describe",listOf("data:image/jpeg;base64,YQ==")))) {}
            val messages=JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
            assertEquals("Answer accurately",messages.getJSONObject(0).getString("content"))
            val parts=messages.getJSONObject(1).getJSONArray("content")
            assertEquals("image_url",parts.getJSONObject(1).getString("type"))
            assertEquals("data:image/jpeg;base64,YQ==",parts.getJSONObject(1).getJSONObject("image_url").getString("url"))
        } finally { server.shutdown() }
    }
    @Test fun rejectsRemoteImagesAndTextOnlyProviderBeforeNetwork() = runBlocking {
        val config=ProviderConfig(ProviderId.KOINOS,"http://127.0.0.1:1/v1","fixture","model")
        assertNotNull(runCatching { InferenceClient().stream(config,listOf(InferenceMessage("user","Photo",listOf("data:image/jpeg;base64,YQ==")))) {} }.exceptionOrNull())
        assertNotNull(runCatching { InferenceClient().stream(config.copy(provider=ProviderId.OPENAI),listOf(InferenceMessage("user","Photo",listOf("https://example.com/private.jpg")))) {} }.exceptionOrNull())
    }
}
