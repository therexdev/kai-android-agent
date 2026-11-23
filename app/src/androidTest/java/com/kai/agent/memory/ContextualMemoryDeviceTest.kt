package com.kai.agent.memory

import android.app.Application
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kai.agent.data.*
import com.kai.agent.inference.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class ContextualMemoryDeviceTest {
    private class App(base: Context) : Application() {
        val directory = File(base.noBackupFilesDir, "contextual-${UUID.randomUUID()}").apply { mkdirs() }
        init { attachBaseContext(base) }
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = directory
    }
    private fun response(text: String) = MockResponse().setHeader("Content-Type", "application/json")
        .setBody(JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", text)).put("finish_reason", "stop"))).toString())
    private suspend fun until(label: String = "condition", details: suspend () -> String = { "" }, test: () -> Boolean) {
        try { withTimeout(120_000) { while (!test()) delay(100) } }
        catch (e: TimeoutCancellationException) { throw AssertionError("Timed out: $label. ${details()}", e) }
    }

    @Test fun automaticBackgroundSavePersistsAndReportIncludesActualModelAndDecision() = runBlocking {
        for (provider in listOf(ProviderId.OPENAI, ProviderId.KOINOS)) {
            val app = App(InstrumentationRegistry.getInstrumentation().targetContext)
            var store = KaiStore(app)
            val server = MockWebServer()
            val worker = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            var runtime: MemoryRuntime? = null
            try {
                server.start(); store.load()
                val endpoint = server.url("/v1").toString()
                store.updateSettings { it.copy(memoryEnabled = true, memoryMode = MemoryMode.AUTOMATIC,
                    openai = ProviderProfile(endpoint, "memory-device-secret", "fixture"),
                    koinos = ProviderProfile(endpoint, "memory-device-secret", "fixture")) }
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.path == "/v1/models") return MockResponse().setBody("""{"data":[{"id":"fixture","context_window":32768,"context_mode":"fixed"}]}""")
                        return response("""{"claims":[{"subjectId":"self","predicate":"occupation","value":{"type":"TEXT","text":"carpenter"},"quote":"I work as a carpenter.","needsReview":false}]}""")
                    }
                }
                val chat = store.createConversation()
                val source = store.appendMessage(chat.id, "user", "I work as a carpenter.")
                var notices = emptyList<String>()
                runtime = MemoryRuntime(app, store, InferenceClient(), worker,
                    { ProviderConfig(provider, endpoint, "memory-device-secret", "fixture") }, { false }, { notices = it })
                runtime.capture(chat.id, source)
                // A later turn must not steal the older extraction's report attribution.
                store.appendMessage(chat.id, "assistant", "Thanks for telling me.")
                store.appendMessage(chat.id, "user", "Hello again")
                runtime.kick()
                until { store.state.value.memoryGraph.jobs.any { it.status == MemoryJobStatus.SUCCEEDED } && notices.isNotEmpty() }
                runtime.shutdown(); runtime = null
                val report = JSONObject(store.exportChatReport(chat.id))
                val events = report.getJSONArray("trace")
                val end = (0 until events.length()).map { events.getJSONObject(it) }.single { it.getString("event") == "memory_extraction_end" }
                assertEquals(source.id, end.getString("userMessageId"))
                assertEquals(1, end.getJSONObject("data").getInt("saved"))
                assertFalse(report.toString().contains("memory-device-secret"))
                assertTrue(report.toString().contains("model_response"))
                assertEquals("SUCCEEDED", report.getJSONObject("memory").getJSONArray("jobs").getJSONObject(0).getString("status"))
                store.close(); store = KaiStore(app); store.load()
                assertEquals("carpenter", store.state.value.memoryGraph.facts.single { it.predicate == "occupation" }.value?.text)
            } finally { runtime?.shutdown(); worker.cancel(); store.close(); server.shutdown(); app.directory.deleteRecursively() }
        }
    }

    @Test fun malformedExtractionIsVisibleAndTemporaryChatNeverQueues() = runBlocking {
        val app = App(InstrumentationRegistry.getInstrumentation().targetContext)
        val store = KaiStore(app); val server = MockWebServer(); val worker = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var runtime: MemoryRuntime? = null
        try {
            store.load(); server.start(); server.enqueue(response("unreadable proposal"))
            val endpoint = server.url("/v1").toString()
            runtime = MemoryRuntime(app, store, InferenceClient(), worker,
                { ProviderConfig(ProviderId.OPENAI, endpoint, "fixture", "fixture") }, { false }, {})
            val temporary = store.createConversation(temporary = true)
            runtime.capture(temporary.id, store.appendMessage(temporary.id, "user", "I work as a baker."))
            assertTrue(store.state.value.memoryGraph.jobs.isEmpty())
            assertNull(store.beginChatTrace(temporary.id, "memory_capture"))
            val chat = store.createConversation()
            runtime.capture(chat.id, store.appendMessage(chat.id, "user", "I work as a baker."))
            assertEquals("Regular utterance must queue", 1, store.state.value.memoryGraph.jobs.size)
            runtime.kick()
            until("malformed result rejected", { store.exportChatReport(chat.id) }) { store.state.value.memoryGraph.jobs.any { it.status == MemoryJobStatus.FAILED } }
            runtime.shutdown(); runtime = null
            assertTrue(store.state.value.memoryGraph.facts.isEmpty())
            assertTrue(store.exportChatReport(chat.id).contains("invalid_response"))
        } finally { runtime?.shutdown(); worker.cancel(); store.close(); server.shutdown(); app.directory.deleteRecursively() }
    }

    @Test fun backgroundWorkYieldsAndRequeuesWhenConversationResumes() = runBlocking {
        val app = App(InstrumentationRegistry.getInstrumentation().targetContext)
        val store = KaiStore(app); val server = MockWebServer(); val worker = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val busy = AtomicBoolean(true); var runtime: MemoryRuntime? = null
        try {
            store.load(); server.start()
            val endpoint = server.url("/v1").toString()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            runtime = MemoryRuntime(app, store, InferenceClient(), worker,
                { ProviderConfig(ProviderId.OPENAI, endpoint, "fixture", "fixture") }, { busy.get() }, {})
            val chat = store.createConversation(); runtime.capture(chat.id, store.appendMessage(chat.id, "user", "I work as a baker."))
            assertEquals("Regular utterance must queue", 1, store.state.value.memoryGraph.jobs.size)
            runtime.kick(); delay(500)
            assertEquals(0, server.requestCount)
            busy.set(false); runtime.kick()
            until("background request sent", { store.exportChatReport(chat.id) }) { server.requestCount > 0 }
            busy.set(true); runtime.pause()
            until("cancelled request requeued", { store.exportChatReport(chat.id) }) { store.state.value.memoryGraph.jobs.single().status == MemoryJobStatus.QUEUED }
            runtime.shutdown(); runtime = null
            assertTrue(store.state.value.memoryGraph.facts.isEmpty())
            assertTrue(store.exportChatReport(chat.id).contains("interrupted_requeued"))
        } finally { runtime?.shutdown(); worker.cancel(); store.close(); server.shutdown(); app.directory.deleteRecursively() }
    }
    @Test fun contextEvidenceSurvivesBackupAndForgetDoesNotEraseUnrelatedFamilyFact() = runBlocking {
        val app = App(InstrumentationRegistry.getInstrumentation().targetContext)
        val other = App(InstrumentationRegistry.getInstrumentation().targetContext)
        val first = KaiStore(app); val second = KaiStore(other)
        try {
            first.load(); second.load()
            val chat = first.createConversation()
            val intro = first.appendMessage(chat.id, "user", "Say hi to my daughter Aurora")
            first.mutateMemoryGraph { MemoryEngine.ingest(it, MemoryUtterance(intro.id, chat.id, intro.text, intro.createdAt), MemoryMode.AUTOMATIC).graph }
            val reply = first.appendMessage(chat.id, "user", "I like driving")
            first.mutateMemoryGraph { graph -> MemoryEngine.ingest(graph,
                MemoryConversationContext.utterance(first.state.value.conversations.first { it.id == chat.id }, reply, graph), MemoryMode.AUTOMATIC).graph }
            second.importJson(first.exportJson())
            val graph = second.state.value.memoryGraph
            val preference = graph.facts.single { it.predicate == "preference" }
            assertTrue(graph.evidence.any { it.factId == preference.id && it.sourceKind == "user_context" })
            second.forgetStructuredFact(preference.id)
            assertTrue(second.state.value.memoryGraph.facts.any { it.predicate == "has_daughter" })
            assertFalse(second.state.value.memoryGraph.facts.any { it.predicate == "preference" })
            second.importJson(first.exportJson())
            assertFalse(second.state.value.memoryGraph.facts.any { it.predicate == "preference" })
        } finally { first.close(); second.close(); app.directory.deleteRecursively(); other.directory.deleteRecursively() }
    }

}
