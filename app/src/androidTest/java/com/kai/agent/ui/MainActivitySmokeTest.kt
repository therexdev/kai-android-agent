package com.kai.agent.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.kai.agent.MainActivity
import com.kai.agent.KaiViewModel
import androidx.lifecycle.ViewModelProvider
import com.kai.agent.data.KaiStore
import com.kai.agent.data.Provider
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * End-to-end real Activity/VM test using ONLY qa/mock_inference_server.py.
 * Run phase1 and phase2 in separate instrumentation invocations with force-stop between them
 * to verify process-death persistence. Running the whole class also works, using fresh Activities.
 * Emulator-only: original encrypted profile is backed up in phase1 and restored in phase2.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class MainActivitySmokeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun phase1ConfigureRememberAndChat() {
        requireEmulator()
        preserveAndResetProfile()
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            waitForText("Talk")
            compose.onNodeWithText("Memory").performClick()
            waitForText("A little more you.")
            compose.onNodeWithContentDescription("Add memory").performClick()
            waitForText("Worth remembering")
            compose.onNode(hasSetTextAction()).performTextInput(FACT)
            compose.onNodeWithText("Save memory").assertIsEnabled().performClick()
            waitForText(FACT)
            screenshot("01-memory-saved.png")

            // This response must work without any configured inference API.
            compose.onNodeWithText("Talk").performClick()
            send("What do you remember about me?")
            waitForText(FACT, substring = true)
            screenshot("02-local-memory-recall.png")

            // Exercise actual settings fields, catalog loading, selection and persistence.
            compose.onNodeWithContentDescription("Settings").performClick()
            waitForText("Make KAI yours.")
            compose.onNodeWithText("OpenAI").performClick()
            waitForText("Use your own OpenAI API account.", substring = true)
            field("API address").performScrollTo().performTextReplacement(ENDPOINT)
            field("API key").performScrollTo().performTextReplacement("local-test-only")
            compose.onNodeWithText("Test API").performScrollTo().performClick()
            waitForText("Connected.", substring = true)
            compose.onNodeWithContentDescription("Choose model").performScrollTo().performClick()
            compose.onNodeWithText("kai-local-test").performClick()
            compose.onNodeWithText("Save AI settings").performScrollTo().assertIsEnabled().performClick()
            waitAbsent("Make KAI yours.")

            // A new conversation prevents transcript leakage from masquerading as memory recall.
            newConversation()
            send("What time do I prefer meetings?")
            waitForText(REPLY)
            waitForPersistedReply()
            screenshot("03-streamed-memory-answer.png")
            val persisted = readStore()
            assertEquals(Provider.OPENAI, persisted.state.value.settings.selectedProvider)
            assertEquals(ENDPOINT, persisted.state.value.settings.openai.endpoint)
            assertEquals("kai-local-test", persisted.state.value.settings.openai.model)
            assertTrue(persisted.state.value.memories.any { it.text == FACT })
            assertTrue(persisted.state.value.conversations.any { conversation ->
                conversation.messages.any { it.role == "assistant" && it.text == REPLY }
            })
            File(backupDirectory(), "phase1-passed").writeText("fixture passed")
        } catch (e: Throwable) {
            // Preserve the backup for recovery or a phase2 cleanup after a failed run.
            screenshot("failure-phase1.png")
            throw e
        } finally { closeScenario(scenario) }
    }

    @Test fun phase2ReopenAndVerify() {
        requireEmulator()
        assumeTrue("Run phase1 first; it creates the local-only fixture.", File(backupDirectory(), "phase1-passed").exists())
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            // A new store and Activity load actual disk state, not the first Activity's ViewModel.
            val reopened = readStore()
            assertTrue(reopened.state.value.memories.any { it.text == FACT })
            assertEquals("local-test-only", reopened.state.value.settings.openai.apiKey)
            scenario = ActivityScenario.launch(MainActivity::class.java)
            waitForText(REPLY)
            screenshot("04-reopened-conversation.png")
            compose.onNodeWithText("Memory").performClick()
            waitForText(FACT)
            screenshot("05-reopened-memory.png")

            // Cancel a real in-flight SSE stream, then ensure a fresh turn is uncontaminated.
            compose.onNodeWithText("Talk").performClick()
            newConversation()
            send("Give me a slow response")
            waitForText("This is a deliberately slow", substring = true)
            compose.onNodeWithContentDescription("Stop response").performClick()
            compose.waitUntil(timeoutMillis = 30_000) {
                compose.onAllNodesWithContentDescription("Stop response").fetchSemanticsNodes().isEmpty()
            }
            send("What time do I prefer meetings?")
            waitForText(REPLY)
            waitForPersistedReply()
            val afterCancel = readStore().state.value.conversations.maxByOrNull { it.updatedAt }!!
            assertFalse(afterCancel.messages.any { it.role == "assistant" && it.text.startsWith("This is a deliberately slow") })
            assertEquals(REPLY, afterCancel.messages.last { it.role == "assistant" }.text)
            screenshot("06-cancelled-stream-new-turn.png")

            // Cover character selection, light theme, and empty connection UX without sign-in.
            newConversation()
            compose.onNodeWithText("KAI").performClick()
            waitForText("A personality that fits.")
            compose.onNodeWithText("Nova").performScrollTo().performClick()
            waitForText("That's my Nova")
            compose.onNodeWithText("That's my Nova").performScrollTo().performClick()
            waitAbsent("A personality that fits.")
            compose.onNodeWithContentDescription("Settings").performClick()
            waitForText("Make KAI yours.")
            compose.onNode(isToggleable()).performScrollTo().performClick()
            compose.onNodeWithContentDescription("Close settings").performScrollTo().performClick()
            waitAbsent("Make KAI yours.")
            screenshot("07-nova-light.png")
            compose.onNodeWithText("Connections").performClick()
            waitForText("Make room for more.")
            compose.onNodeWithText("Composio setup").assertExists()
            compose.onNodeWithText("Composio API key").assertExists()
            compose.onNodeWithText("Sign in to connections").assertDoesNotExist()
            screenshot("08-connections-light.png")
            assertFalse(readStore().state.value.settings.isDarkTheme)
            assertEquals("nova", readStore().state.value.settings.selectedCharacterId)
        } finally {
            closeScenario(scenario)
            restoreProfile()
        }
    }

    private fun field(label: String): SemanticsNodeInteraction =
        compose.onNode(hasSetTextAction() and hasText(label))

    private fun send(text: String) {
        compose.onNode(hasSetTextAction()).performTextInput(text)
        compose.onNodeWithContentDescription("Send message").assertIsEnabled().performClick()
    }

    private fun newConversation() {
        compose.onNodeWithContentDescription("Conversation history").performClick()
        waitForText("Your conversations")
        compose.onNodeWithText("New conversation").performClick()
        waitAbsent("Your conversations")
    }

    private fun waitForText(text: String, substring: Boolean = false) {
        compose.waitUntil(timeoutMillis = 90_000) {
            compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitAbsent(text: String) {
        compose.waitUntil(timeoutMillis = 90_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun waitForPersistedReply() {
        compose.waitUntil(timeoutMillis = 90_000) {
            readStore().state.value.conversations.maxByOrNull { it.updatedAt }?.messages
                ?.lastOrNull { it.role == "assistant" }?.text == REPLY
        }
    }

    private fun readStore(): KaiStore = runBlocking { KaiStore(context).also { it.load(); it.close() } }

    private fun screenshot(name: String) {
        runCatching {
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
            val folder = context.getExternalFilesDir("qa-0.2.0-provider") ?: return
            folder.mkdirs()
            File(folder, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun closeScenario(scenario: ActivityScenario<MainActivity>?) {
        if (scenario == null) return
        var model: KaiViewModel? = null
        scenario.onActivity { model = ViewModelProvider(it)[KaiViewModel::class.java] }
        scenario.close()
        runBlocking { withTimeout(60_000) { model?.shutdownComplete?.await() } }
    }

    private fun requireEmulator() {
        assumeTrue("This fixture changes a test profile and must only run on an isolated emulator.",
            Build.FINGERPRINT.contains("generic", true) || Build.MODEL.contains("sdk", true) ||
                Build.MODEL.contains("emulator", true) || Build.HARDWARE in setOf("ranchu", "goldfish"))
    }

    private fun backupDirectory() = File(context.noBackupFilesDir, "kai-ui-smoke-020-backup")
    private fun profileDirectory() = File(context.noBackupFilesDir, "kai-state")

    private fun preserveAndResetProfile() {
        val backup = backupDirectory()
        if (!backup.exists()) {
            check(backup.mkdirs())
            if (profileDirectory().exists()) profileDirectory().copyRecursively(File(backup, "profile"))
            else File(backup, "profile.absent").writeText("absent")
        }
        File(backup, "phase1-passed").delete()
        check(!profileDirectory().exists() || profileDirectory().deleteRecursively())
    }

    private fun restoreProfile() {
        val backup = backupDirectory()
        if (!backup.exists()) return
        check(!profileDirectory().exists() || profileDirectory().deleteRecursively())
        File(backup, "profile").takeIf { it.exists() }?.copyRecursively(profileDirectory(), overwrite = true)
        check(backup.deleteRecursively())
    }

    private companion object {
        const val FACT = "I prefer meetings after 10 AM."
        const val ENDPOINT = "http://10.0.2.2:18765/v1"
        const val REPLY = "Your saved preference is meetings after 10 AM. I found it in the memory stored on this phone."
    }
}
