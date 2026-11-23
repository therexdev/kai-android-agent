package com.kai.agent.memory

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kai.agent.KaiViewModel
import com.kai.agent.MainActivity
import com.kai.agent.data.*
import com.kai.agent.ui.KaiMemoryMode
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Activity, VM, extraction, encrypted database and SSE. Only inference is a localhost fixture. */
@RunWith(AndroidJUnit4::class)
class MemoryUiDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var model: KaiViewModel

    @Test fun naturalRememberRequestsSaveAndRecallAcrossConversations() = withFixture { _ ->
        sendAndAwait("Could you please remember that my name is Alex?")
        assertTrue(model.ui.value.memories.any { it.text.contains("Alex") })
        newConversation()
        sendAndAwait("What have you saved about me?")
        assertTrue(model.ui.value.messages.last().text.contains("Alex"))
        compose.runOnIdle { model.setMemoryMode(KaiMemoryMode.OFF) }
        waitUi { it.memoryMode == KaiMemoryMode.OFF }
        sendAndAwait("Can you remember that my birthday is April 12?")
        assertTrue(model.ui.value.memories.any { it.text.contains("April") })
        newConversation(temporary = true)
        sendAndAwait("Please save my favorite color is orange to memory")
        assertFalse(readState().memoryGraph.facts.any { it.value?.text?.contains("orange") == true })
    }

    @Test fun referentialMemoryRequestUsesHumanTextAndNeedsReview() = withFixture { _ ->
        val statement = "My daughter is Clara and she was born October 3rd 2018"
        sendAndAwait(statement)
        sendAndAwait("Can you remember those details?")
        val suggestion = model.ui.value.pendingMemories.single { it.text.contains(if (statement.contains("Clara")) "Clara" else "quiet cafes") }
        assertFalse(readState().memoryGraph.facts.any { it.value?.text == statement })
        compose.runOnIdle { model.acceptMemorySuggestion(suggestion.id, null) }
        waitUi { it.memories.any { memory -> memory.text.contains("Clara") } }
        newConversation()
        sendAndAwait("Show me my saved memories")
        assertTrue(model.ui.value.messages.last().text.contains("Clara"))
        assertFalse(model.ui.value.messages.last().text.contains("Fixture reply"))
    }

    @Test fun modelMemoryActionCreatesReviewWithoutConnectedAccount() = withFixture { _ ->
        val statement = "For future chats, keep in mind that I prefer quiet cafes."
        sendAndAwait(statement)
        val suggestion = model.ui.value.pendingMemories.single { it.text.contains(if (statement.contains("Clara")) "Clara" else "quiet cafes") }
        assertNull(model.ui.value.pendingApproval)
        assertTrue(readState().tasks.isEmpty())
        compose.runOnIdle { model.acceptMemorySuggestion(suggestion.id, null) }
        waitUi { it.memories.any { memory -> memory.text.contains("quiet cafes") } }
        newConversation()
        sendAndAwait("Which cafes suit me?")
        assertTrue(model.ui.value.messages.last().text.contains("quiet cafes"))
    }

    @Test fun automaticLinkedFactsAndUserMemoryControls() = withFixture { requests ->
        assertEquals(KaiMemoryMode.AUTOMATIC, model.ui.value.memoryMode)
        sendAndAwait(DAUGHTER)
        waitUi { it.memories.any { fact -> fact.text.contains("Aurora") && fact.text.contains("daughter", true) } }
        newConversation()
        sendAndAwait(BIRTHDAY)
        waitUi { it.memories.any { fact -> fact.text.contains("October", true) } }
        val state = readState()
        val birthday = state.memoryGraph.facts.single { it.predicate == "birthday" && it.status == FactStatus.CURRENT }
        assertEquals(10, birthday.value?.month)
        assertNull(birthday.value?.day)
        assertNull(birthday.value?.year)
        assertTrue(state.memoryGraph.facts.any { it.predicate == "has_daughter" && it.objectEntityId == birthday.subjectId })
        assertTrue(state.memoryGraph.evidence.any { it.factId == birthday.id && BIRTHDAY.contains(it.quote) })
        screenshot("01-automatic-linked-memory.png")

        // A third conversation proves retrieval is not just the current transcript.
        newConversation()
        sendAndAwait(BIRTHDAY_QUESTION)
        waitText(RECALLED)
        assertTrue(requests.last { it.user == BIRTHDAY_QUESTION }.savedMemory.contains("October", true))
        screenshot("02-cross-chat-memory-answer.png")

        openMemory()
        val birthdayCard = model.ui.value.memories.single { it.id == birthday.id }
        assertFalse(birthdayCard.editable)
        assertTrue(birthdayCard.relatedPeople.contains("Aurora"))
        assertTrue(birthdayCard.sourceDetail.isNotBlank())
        scrollTo(hasText(birthdayCard.text))
        compose.onAllNodesWithText("LINKED FACT").onFirst().assertExists()
        compose.onAllNodesWithText(birthdayCard.sourceDetail).onFirst().assertExists()
        screenshot("03-memory-sources-and-people.png")

        scrollTo(hasContentDescription("Use saved memory"))
        compose.onNodeWithContentDescription("Use saved memory").performClick()
        waitUi { !it.useSavedMemory }
        compose.onNodeWithText("Talk").performClick()
        newConversation()
        sendAndAwait(BIRTHDAY_QUESTION)
        waitText(MEMORY_OFF)
        assertEquals("{}", requests.last { it.user == BIRTHDAY_QUESTION }.savedMemory.trim())

        openMemory()
        scrollTo(hasContentDescription("Use saved memory"))
        compose.onNodeWithContentDescription("Use saved memory").performClick()
        waitUi { it.useSavedMemory }
        scrollTo(hasText("Ask first"))
        compose.onNodeWithText("Ask first").performClick()
        waitUi { it.memoryMode == KaiMemoryMode.ASK_FIRST }
        compose.onNodeWithText("Talk").performClick()
        sendAndAwait("My brother's name is Briar.")
        waitUi { it.pendingMemories.any { suggestion -> suggestion.text.contains("Briar") } }
        assertNull(model.ui.value.pendingApproval)
        assertTrue(readState().tasks.isEmpty())
        scrollTo(hasText("Yes, save"))
        compose.onNodeWithTag("approval-review-button").assertDoesNotExist()
        screenshot("04-ask-first-memory-review.png")
        compose.onNodeWithText("Yes, save").assertIsEnabled().performClick()
        waitUi { it.pendingMemories.isEmpty() && it.memories.any { fact -> fact.text.contains("Briar") } }
        compose.onNodeWithText("Undo").assertIsEnabled().performClick()
        waitUi { it.memoryNotice == null && it.memories.none { fact -> fact.text.contains("Briar") } }
        assertTrue(readState().memoryGraph.suppressions.isNotEmpty())

        sendAndAwait("My sister's name is Cedar.")
        waitUi { it.pendingMemories.any { suggestion -> suggestion.text.contains("Cedar") } }
        scrollTo(hasText("No, dismiss"))
        compose.onNodeWithText("No, dismiss").performClick()
        waitUi { it.pendingMemories.isEmpty() }
        assertFalse(model.ui.value.memories.any { it.text.contains("Cedar") })
        assertTrue(readState().memoryGraph.candidates.any { it.status == CandidateStatus.DISMISSED })
        screenshot("05-dismissed-and-undone-memory.png")

        openMemory()
        scrollTo(hasText("Off"))
        compose.onNodeWithText("Off").performClick()
        waitUi { it.memoryMode == KaiMemoryMode.OFF }
        val beforeOff = model.ui.value.memories.map { it.id }.toSet()
        compose.onNodeWithText("Talk").performClick()
        sendAndAwait("My friend's name is Dorian.")
        assertEquals(beforeOff, model.ui.value.memories.map { it.id }.toSet())
        assertTrue(model.ui.value.pendingMemories.isEmpty())
        openMemory()
        scrollTo(hasText("Automatic"))
        compose.onNodeWithText("Automatic").performClick()
        waitUi { it.memoryMode == KaiMemoryMode.AUTOMATIC }
        compose.onNodeWithText("Talk").performClick()
        newConversation(temporary = true)
        waitUi { it.temporaryConversation }
        waitText("Temporary chat")
        sendAndAwait("My daughter's name is Tempchild.")
        assertEquals("{}", requests.last { it.user.contains("Tempchild") }.savedMemory.trim())
        assertEquals(beforeOff, model.ui.value.memories.map { it.id }.toSet())
        assertTrue(readState().conversations.none { it.temporary || it.messages.any { message -> message.text.contains("Tempchild") } })
        screenshot("06-temporary-chat-memory-boundary.png")
        compose.onNodeWithText("End").performClick()
        waitUi { !it.temporaryConversation && it.messages.isEmpty() }
        assertTrue(readState().conversations.none { it.messages.any { message -> message.text.contains("Tempchild") } })
        File(outputDirectory(), "memory-ui-evidence.json").writeText(JSONObject()
            .put("fixture_only", true).put("http_inference_requests", requests.size)
            .put("automatic_cross_chat_relation_and_month", true).put("missing_day_year_not_invented", true)
            .put("ask_first_accept_dismiss_undo", true).put("use_memory_and_off_controls", true)
            .put("temporary_no_memory_or_disk_chat", true).put("external_actions", 0).toString(2))
    }

    @Test fun ambiguousIdentityNeedsAnExplicitChoice() = withFixture(seed = { store ->
        val conversation = store.createConversation(title = "Identity review fixture")
        val daughterSource = store.appendMessage(conversation.id, "user", DAUGHTER)
        val colleagueText = "My colleague's name is Aurora."
        val colleagueSource = store.appendMessage(conversation.id, "user", colleagueText)
        val birthdaySource = store.appendMessage(conversation.id, "user", BIRTHDAY)
        val self = MemoryEntity(id = "self:personal", displayName = "You")
        val daughter = MemoryEntity(id = "fixture-aurora-daughter", displayName = "Aurora")
        val colleague = MemoryEntity(id = "fixture-aurora-colleague", displayName = "Aurora")
        val unresolved = MemoryEntity(id = "fixture-unresolved-aurora", displayName = "Aurora")
        val daughterFact = StructuredFact(id = "fixture-daughter", subjectId = self.id, predicate = "has_daughter", objectEntityId = daughter.id)
        val colleagueFact = StructuredFact(id = "fixture-colleague", subjectId = self.id, predicate = "has_colleague", objectEntityId = colleague.id)
        val proposed = StructuredFact(id = "fixture-birthday", subjectId = unresolved.id, predicate = "birthday",
            value = MemoryValue(type = "DATE", month = 10, precision = "month"))
        val candidate = MemoryCandidate(id = "fixture-identity-review", kind = CandidateKind.IDENTITY,
            question = "Which Aurora should this describe?", summary = "Aurora's birthday is in October.",
            proposedFacts = listOf(proposed), proposedEntities = listOf(unresolved),
            evidence = listOf(FactEvidence(factId = proposed.id, sourceMessageId = birthdaySource.id, conversationId = conversation.id, quote = BIRTHDAY)),
            entityChoices = listOf(daughter.id, colleague.id), sourceMessageId = birthdaySource.id, conversationId = conversation.id)
        store.mutateMemoryGraph { MemoryGraph(entities = listOf(self, daughter, colleague), facts = listOf(daughterFact, colleagueFact),
            evidence = listOf(FactEvidence(factId = daughterFact.id, sourceMessageId = daughterSource.id, conversationId = conversation.id, quote = DAUGHTER),
                FactEvidence(factId = colleagueFact.id, sourceMessageId = colleagueSource.id, conversationId = conversation.id, quote = colleagueText)),
            candidates = listOf(candidate)) }
    }) { _ ->
        waitUi { it.pendingMemories.size == 1 }
        val suggestion = model.ui.value.pendingMemories.single()
        assertEquals(2, suggestion.entityChoices.map { it.label }.distinct().size)
        openMemory()
        scrollTo(hasText("Confirm & save"))
        compose.onNodeWithText("Confirm & save").assertIsNotEnabled()
        assertNull(model.ui.value.pendingApproval)
        assertTrue(readState().tasks.isEmpty())
        val choice = suggestion.entityChoices.single { it.id == "fixture-aurora-daughter" }
        scrollTo(hasText(choice.label))
        compose.onNodeWithText(choice.label).performClick()
        scrollTo(hasText("Confirm & save"))
        compose.onNodeWithText("Confirm & save").assertIsEnabled()
        screenshot("07-explicit-identity-choice.png")
        compose.onNodeWithText("Confirm & save").performClick()
        waitUi { it.pendingMemories.isEmpty() && it.memories.any { memory -> memory.id == "fixture-birthday" } }
        val fact = readState().memoryGraph.facts.single { it.id == "fixture-birthday" }
        assertEquals("fixture-aurora-daughter", fact.subjectId)
        assertEquals(10, fact.value?.month)
        val daughterDetails = model.ui.value.memories.single { it.id == "fixture-daughter" }.relatedFacts
        assertTrue("The daughter's linked details must stay with her identity", daughterDetails.any { it.contains("birthday", true) && it.contains("October", true) } && daughterDetails.none { it.contains("colleague", true) })
        assertTrue(readState().tasks.isEmpty())
        screenshot("08-identity-confirmed.png")
    }

    private fun withFixture(seed: suspend (KaiStore) -> Unit = {}, body: (List<InferenceRequest>) -> Unit) {
        requireEmulator()
        preserveProfile()
        val requests = CopyOnWriteArrayList<InferenceRequest>()
        val server = MockWebServer().apply { dispatcher = memoryDispatcher(requests) }
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            server.start()
            runBlocking {
                val store = KaiStore(context)
                try {
                    store.load()
                    store.updateSettings { it.copy(selectedProvider = Provider.OPENAI,
                        openai = ProviderProfile(server.url("/v1").toString(), FIXTURE_KEY, "memory-ui-fixture"),
                        memoryMode = MemoryMode.AUTOMATIC, memoryEnabled = true, useMemory = true) }
                    seed(store)
                } finally { store.close() }
            }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { model = ViewModelProvider(it)[KaiViewModel::class.java] }
            waitUi { it.currentConversationId != null && it.providerConfig.modelId == "memory-ui-fixture" }
            body(requests)
        } catch (failure: Throwable) {
            screenshot("failure-memory-ui.png")
            throw failure
        } finally {
            // Cancel native/indexing work before restoring the complete SQLCipher directory.
            val pending = if (::model.isInitialized) model.viewModelScope.coroutineContext[Job]?.children?.toList().orEmpty() else emptyList()
            scenario?.onActivity { model.onBackground(); model.stopGeneration() }
            scenario?.close()
            runBlocking { withTimeout(60_000) { pending.joinAll() } }
            if (::model.isInitialized) runBlocking { withTimeout(15_000) { model.shutdownComplete.await() } }
            server.shutdown()
            restoreProfile()
        }
    }

    private data class InferenceRequest(val user: String, val savedMemory: String)
    private fun memoryDispatcher(requests: MutableList<InferenceRequest>) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.path != "/v1/chat/completions" || request.getHeader("Authorization") != "Bearer $FIXTURE_KEY")
                return MockResponse().setResponseCode(400).setBody("Unexpected local fixture request")
            val messages = JSONObject(request.body.readUtf8()).getJSONArray("messages")
            val system = messages.getJSONObject(0).getString("content")
            val user = (0 until messages.length()).map { messages.getJSONObject(it) }
                .last { it.getString("role") == "user" }.getString("content")
            val memory = system.substringAfter("<saved_memory>", "{}").substringBefore("</saved_memory>").trim()
            requests += InferenceRequest(user, memory)
            val content = if (system.startsWith("Extract at most")) "{\"claims\":[]}"
                else if (user == "For future chats, keep in mind that I prefer quiet cafes.") JSONObject().put("type", "propose_memory").put("quote", user).put("claims", JSONArray().put(JSONObject().put("subjectId", "self").put("predicate", "preference").put("value", JSONObject().put("type", "TEXT").put("text", "quiet cafes")).put("quote", user))).toString()
                else if (user == "Which cafes suit me?") "{\"type\":\"search_memory\",\"query\":\"quiet cafes\"}"
                else if (user.startsWith("Phone memory lookup result")) JSONObject().put("type", "reply").put("text", if (user.contains("quiet cafes")) "You prefer quiet cafes." else "No preference found.").toString()
                else JSONObject().put("type", "reply").put("text", when {
                    user == BIRTHDAY_QUESTION && memory.contains("Aurora") && memory.contains("October", true) -> RECALLED
                    user == BIRTHDAY_QUESTION -> MEMORY_OFF
                    else -> "Fixture reply received."
                }).toString()
            fun frame(delta: String, finish: Any = JSONObject.NULL) = "data: " + JSONObject()
                .put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", JSONObject().put("content", delta)).put("finish_reason", finish))) + "\n\n"
            val middle = content.length / 2
            return MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody(frame(content.take(middle)) + frame(content.drop(middle)) + frame("", "stop") + "data: [DONE]\n\n")
        }
    }

    private fun sendAndAwait(text: String) {
        if (compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithText("Chat").performClick()
        }
        val count = model.ui.value.messages.size
        compose.onNode(hasSetTextAction()).performTextInput(text)
        compose.onNodeWithContentDescription("Send message").assertIsEnabled().performClick()
        waitUi { (!it.isBusy && it.messages.size >= count + 2) || it.error != null }
        assertNull("Fixture send should complete without an app error", model.ui.value.error)
    }
    private fun newConversation(temporary: Boolean = false) {
        compose.onNodeWithContentDescription("Conversation history").performClick()
        waitText("Your conversations")
        compose.onNodeWithText(if (temporary) "New temporary chat" else "New conversation").performClick()
        waitUi { it.messages.isEmpty() && it.temporaryConversation == temporary }
    }
    private fun openMemory() { compose.onNodeWithText("Memory").performClick(); waitText("A little more you.") }
    private fun scrollTo(matcher: SemanticsMatcher) {
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToNode(matcher)
    }
    private fun waitText(text: String) = compose.waitUntil(180_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun waitUi(predicate: (com.kai.agent.ui.KaiUiState) -> Boolean) = compose.waitUntil(180_000) { predicate(model.ui.value) }
    private fun readState(): KaiState = runBlocking { val store = KaiStore(context); try { store.load(); store.state.value } finally { store.close() } }
    private fun outputDirectory() = File(context.getExternalFilesDir(null), "qa-memory-020").apply { mkdirs() }
    private fun screenshot(name: String) {
        runCatching { compose.waitForIdle(); InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            File(outputDirectory(), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        } }
    }
    private fun requireEmulator() {
        assumeTrue("Memory UI fixtures are only for the isolated preview emulator.", context.packageName == "com.kai.agent.preview" &&
            (Build.FINGERPRINT.contains("generic", true) || Build.MODEL.contains("sdk", true) || Build.MODEL.contains("emulator", true) || Build.HARDWARE in setOf("ranchu", "goldfish")))
    }
    private fun profileDirectory() = File(context.noBackupFilesDir, "kai-state")
    private fun backupDirectory() = File(context.noBackupFilesDir, "kai-memory-ui-020-backup")
    private fun preserveProfile() {
        check(!backupDirectory().exists()) { "A prior memory-test backup needs recovery before this fixture can run." }
        check(backupDirectory().mkdirs())
        if (profileDirectory().exists()) profileDirectory().copyRecursively(File(backupDirectory(), "profile"))
        else File(backupDirectory(), "profile.absent").writeText("absent")
        check(!profileDirectory().exists() || profileDirectory().deleteRecursively())
    }
    private fun restoreProfile() {
        if (!backupDirectory().exists()) return
        check(!profileDirectory().exists() || profileDirectory().deleteRecursively())
        File(backupDirectory(), "profile").takeIf { it.exists() }?.copyRecursively(profileDirectory(), overwrite = true)
        check(backupDirectory().deleteRecursively())
    }
    private companion object {
        const val FIXTURE_KEY = "memory-ui-local-fixture-only"
        const val DAUGHTER = "My daughter's name is Aurora."
        const val BIRTHDAY = "Aurora's birthday is in October."
        const val BIRTHDAY_QUESTION = "When is my daughter's birthday?"
        const val RECALLED = "Aurora is your daughter, and her birthday is in October. The day and year were not provided."
        const val MEMORY_OFF = "Fixture: saved memory is unavailable in this chat."
    }
}
