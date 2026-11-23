package com.kai.agent.memory

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kai.agent.KaiViewModel
import com.kai.agent.data.*
import com.kai.agent.ui.KaiMemoryMode
import com.kai.agent.ui.KaiUiState
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real phone controller, SQLCipher and SSE in a separate data directory; no Compose idling. */
@RunWith(AndroidJUnit4::class)
class MemoryConversationDeviceTest {
    private class FixtureApplication(base: Context) : Application() {
        val directory = File(base.noBackupFilesDir, "memory-conversation-fixture-${UUID.randomUUID()}").apply { mkdirs() }
        init { attachBaseContext(base) }
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = directory
    }
    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    private suspend fun awaitState(model: KaiViewModel, predicate: (KaiUiState) -> Boolean) = withTimeout(90_000) {
        while (!predicate(model.ui.value)) { check(model.ui.value.error == null) { model.ui.value.error.orEmpty() }; delay(100) }
    }
    private suspend fun send(model: KaiViewModel, text: String) {
        val count = model.ui.value.messages.size
        main { model.sendMessage(text) }
        awaitState(model) { !it.isBusy && it.messages.size >= count + 2 }
        assertNull(model.ui.value.error)
    }
    @Test fun openAiConversationMemory() { runBlocking { exercise(Provider.OPENAI) } }
    @Test fun koinosConversationMemory() { runBlocking { exercise(Provider.KOINOS) } }

    @Test fun koinosSmallDocumentRequestReachesProviderWithPopulatedMemory() { runBlocking {
        val app = FixtureApplication(InstrumentationRegistry.getInstrumentation().targetContext)
        val server = MockWebServer()
        val owner = ViewModelStore()
        lateinit var model: KaiViewModel
        var initialized = false
        try {
            server.start()
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"llama31-8b","context_window":4096}]}"""))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"choices":[{"message":{"content":"{\"type\":\"reply\",\"text\":\"Fixture planning response.\"}"},"finish_reason":"stop"}]}"""))
            val seed = KaiStore(app)
            try {
                seed.load()
                seed.updateSettings { it.copy(selectedProvider = Provider.KOINOS,
                    koinos = ProviderProfile(server.url("/v1").toString(), "local-context-fixture", "llama31-8b")) }
                for (i in 1..8) seed.remember("Personal preference $i " + "with detailed context ".repeat(10))
                val sourceChat = seed.createConversation("kai")
                for (i in 1..4) {
                    val text = "Pending preference $i " + "more context ".repeat(20)
                    val source = seed.appendMessage(sourceChat.id, "user", text)
                    val fact = StructuredFact(subjectId = "self:personal", predicate = "preference", value = MemoryValue(text = "pending $i"))
                    val candidate = MemoryCandidate(question = "Remember?", summary = text, proposedFacts = listOf(fact),
                        evidence = listOf(FactEvidence(factId = fact.id, sourceMessageId = source.id, conversationId = sourceChat.id, quote = text)),
                        sourceMessageId = source.id, conversationId = sourceChat.id)
                    seed.mutateMemoryGraph { it.copy(candidates = it.candidates + candidate) }
                }
                seed.createConversation("kai")
            } finally { seed.close() }
            main { model = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(app))[KaiViewModel::class.java] }
            initialized = true
            awaitState(model) { it.currentConversationId != null && it.providerConfig.modelId == "llama31-8b" }
            val text = "Make a Google doc called test and put test doc in the page content and send me the link"
            send(model, text)
            assertEquals("Fixture planning response.", model.ui.value.messages.last().text)
            assertEquals("/v1/models", server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)?.path)
            val wire = JSONObject(requireNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)).body.readUtf8())
            val a = wire.getJSONArray("messages")
            val messages = (0 until a.length()).map { com.kai.agent.inference.InferenceMessage(a.getJSONObject(it).getString("role"), a.getJSONObject(it).getString("content")) }
            assertTrue(messages.any { it.content == text })
            assertTrue(com.kai.agent.inference.InferenceBudget.estimate(messages) + wire.getInt("max_tokens") + 512 <= 4096)
            assertEquals(8, model.ui.value.memories.size)
            assertEquals(4, model.ui.value.pendingMemories.size)
            assertNull(model.ui.value.pendingApproval)
        } finally {
            main { owner.clear() }
            if (initialized) withTimeout(30_000) { model.shutdownComplete.await() }
            server.shutdown(); app.directory.deleteRecursively()
        }
    } }

    @Test fun olderPendingFamilyCardConvertsAndPersists() { runBlocking {
        val app = FixtureApplication(InstrumentationRegistry.getInstrumentation().targetContext)
        var store = KaiStore(app)
        try {
            store.load()
            val chat = store.createConversation("kai")
            val text = "More things my daughter is Clara and she was born October 3rd 2018"
            val source = store.appendMessage(chat.id, "user", text)
            val note = StructuredFact(subjectId = "self:personal", predicate = "note", value = MemoryValue(text = text))
            val old = MemoryCandidate(question = "Save exact words?", summary = text, proposedFacts = listOf(note),
                evidence = listOf(FactEvidence(factId = note.id, sourceMessageId = source.id, conversationId = chat.id, quote = text)),
                sourceMessageId = source.id, conversationId = chat.id)
            store.mutateMemoryGraph { it.copy(candidates = it.candidates + old) }
            store.mutateMemoryGraph { MemoryNotes.propose(it, MemoryUtterance(source.id, chat.id, text), text).graph }
            val corrected = store.state.value.memoryGraph.candidates.single { it.status == CandidateStatus.PENDING }
            assertEquals(old.id, corrected.id)
            assertEquals(setOf("has_daughter", "name", "birthday"), corrected.proposedFacts.map { it.predicate }.toSet())
            store.mutateMemoryGraph { MemoryEngine.confirm(it, corrected.id, true).graph }
            store.close()
            store = KaiStore(app)
            store.load()
            val facts = store.state.value.memoryGraph.facts.filter { it.status == FactStatus.CURRENT }
            assertEquals(3, facts.size)
            assertEquals(facts.single { it.predicate == "has_daughter" }.objectEntityId, facts.single { it.predicate == "birthday" }.subjectId)
        } finally { store.close(); app.directory.deleteRecursively() }
    } }

    private suspend fun exercise(provider: Provider) {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = FixtureApplication(context)
        val server = MockWebServer().apply { dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/v1/models") return MockResponse().setBody("""{"data":[{"id":"memory-fixture","context_window":4096,"context_mode":"fixed"}]}""")
                val messages = JSONObject(request.body.readUtf8()).getJSONArray("messages")
                val system = messages.getJSONObject(0).getString("content")
                val user = (0 until messages.length()).map { messages.getJSONObject(it) }.last { it.getString("role") == "user" }.getString("content")
                val result = when {
                    system.startsWith("Extract at most") -> "{\"claims\":[]}"
                    user == NOTE -> JSONObject().put("type", "propose_memory").put("quote", NOTE).put("claims", JSONArray().put(JSONObject().put("subjectId", "self").put("predicate", "preference").put("value", JSONObject().put("type", "TEXT").put("text", "quiet cafes")).put("quote", NOTE))).toString()
                    user == "Which cafes suit me?" -> "{\"type\":\"search_memory\",\"query\":\"quiet cafes\"}"
                    user.startsWith("Phone memory lookup result") -> JSONObject().put("type", "reply").put("text", if (user.contains("quiet cafes")) "You prefer quiet cafes." else "No preference found.").toString()
                    else -> "{\"type\":\"reply\",\"text\":\"Fixture reply.\"}"
                }
                val event = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", result))))
                return MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: $event\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
            }
        } }
        val owner = ViewModelStore()
        lateinit var model: KaiViewModel
        var initialized = false
        try {
            server.start()
            val seed = KaiStore(app)
            try {
                seed.load()
                val profile = ProviderProfile(server.url("/v1").toString(), "local-memory-fixture", "memory-fixture")
                seed.updateSettings { it.copy(selectedProvider = provider, openai = profile, koinos = profile) }
            } finally { seed.close() }
            main { model = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(app))[KaiViewModel::class.java] }
            initialized = true
            awaitState(model) { it.currentConversationId != null && it.providerConfig.modelId == "memory-fixture" }
            send(model, "Could you please remember that my name is Alex?")
            assertTrue(model.ui.value.memories.any { it.text.contains("Alex") })
            val old = model.ui.value.currentConversationId
            main { model.newConversation() }
            awaitState(model) { it.currentConversationId != old && it.messages.isEmpty() }
            send(model, "What have you saved about me?")
            assertTrue(model.ui.value.messages.last().text.contains("Alex"))
            main { model.setMemoryMode(KaiMemoryMode.OFF) }
            awaitState(model) { it.memoryMode == KaiMemoryMode.OFF }
            send(model, "Can you remember that my birthday is April 12?")
            assertTrue(model.ui.value.memories.any { it.text.contains("April") })
            main { model.setMemoryMode(KaiMemoryMode.AUTOMATIC) }
            awaitState(model) { it.memoryMode == KaiMemoryMode.AUTOMATIC }
            val statement = "More things my daughter is Clara and she was born October 3rd 2018"
            send(model, statement)
            send(model, "Can you remember those details?")
            val reference = model.ui.value.pendingMemories.single { it.text.contains("Clara") }
            assertFalse(model.ui.value.memories.any { it.text.contains("Clara") })
            main { model.acceptMemorySuggestion(reference.id, null) }
            awaitState(model) { it.memories.any { memory -> memory.text.contains("Clara") } }
            assertEquals(3, model.ui.value.memories.count { it.text.contains("Clara") })
            send(model, "My last name is Jones")
            val surname = model.ui.value.pendingMemories.single { it.text.contains("Jones") }
            send(model, "Can you update that card because my last name is J o h n s")
            val corrected = model.ui.value.pendingMemories.single { it.text.contains("Johns") }
            assertEquals(surname.id, corrected.id)
            assertTrue(corrected.updating)
            assertTrue(corrected.reason.contains("Jones"))
            main { model.acceptMemorySuggestion(corrected.id, null) }
            awaitState(model) { it.memories.any { m -> m.text == "Your last name is Johns." } }
            send(model, "Actually, my last name is Johnson")
            val changed = model.ui.value.pendingMemories.single { it.text.contains("Johnson") }
            main { model.acceptMemorySuggestion(changed.id, null) }
            awaitState(model) { it.memories.any { m -> m.text == "Your last name is Johnson." } }
            assertFalse(model.ui.value.memories.any { it.text == "Your last name is Johns." })
            send(model, "My last name is Johnson")
            assertTrue(model.ui.value.pendingMemories.isEmpty())
            send(model, "Can you update that card because my last name is j o n e s johns")
            assertTrue(model.ui.value.pendingMemories.isEmpty())
            assertTrue(model.ui.value.messages.last().text.contains("exact name"))
            send(model, NOTE)
            val proposed = model.ui.value.pendingMemories.single { it.text.contains("quiet cafes") }
            assertNull(model.ui.value.pendingApproval)
            main { model.acceptMemorySuggestion(proposed.id, null) }
            awaitState(model) { it.memories.any { memory -> memory.text.contains("quiet cafes") } }
            val before = model.ui.value.currentConversationId
            main { model.newConversation() }
            awaitState(model) { it.currentConversationId != before && it.messages.isEmpty() }
            send(model, "Which cafes suit me?")
            assertTrue(model.ui.value.messages.last().text.contains("quiet cafes"))
            main { model.newTemporaryConversation() }
            awaitState(model) { it.temporaryConversation }
            val saved = model.ui.value.memories.map { it.id }.toSet()
            send(model, "Please save my favorite color is orange to memory")
            assertEquals(saved, model.ui.value.memories.map { it.id }.toSet())
            assertTrue(model.ui.value.messages.last().text.contains("temporary chat"))
        } finally {
            main { owner.clear() }
            if (initialized) withTimeout(30_000) { model.shutdownComplete.await() }
            server.shutdown()
            app.directory.deleteRecursively()
        }
    }
    private companion object { const val NOTE = "For future chats, keep in mind that I prefer quiet cafes." }
}
