package com.kai.agent.ui

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicReference
import org.hamcrest.Matcher
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Controlled UI states exercise the actual animated renderer; no model, account or microphone calls. */
@RunWith(AndroidJUnit4::class)
class PinnedCompanionDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val i get() = InstrumentationRegistry.getInstrumentation()
    private val longReply = "Opening paragraph must stay available.\n\n" + (1..60).joinToString("\n\n") {
        "Paragraph $it: Every night, someone stole one shoe from outside the cobbler’s shop. Nell waited with a lantern and listened for the visitor."
    }
    private fun state() = KaiUiState(selectedCharacterId = "spark", streamingText = longReply,
        providerConfig = KaiProviderConfig(apiKey = "synthetic-ui-fixture", modelId = "fixture-model"))

    @Test fun avatarStaysPinnedWhileTranscriptScrollsAndNewTextArrives() {
        val state = mutableStateOf(state().copy(voiceEnabled = true, voiceState = KaiVoiceState.SPEAKING, speechAudible = true, reducedMotion = true))
        launch { KaiApp(state.value, actions()) }.use {
            waitForLiveSpark()
            val stage = bounds("companion-stage"); val renderer = renderer()
            val text = compose.onNodeWithText("Opening paragraph must stay available.", substring = true)
                .fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }
            assertEquals(longReply, text) // Includes the beginning, well beyond the old 1,800-character window.
            screenshot("01-pinned-speaking.png")
            compose.onNodeWithTag("companion-transcript").performTouchInput { swipeUp() }
            val position = scrollValue(); assertTrue("Text did not scroll", position > 0f)
            assertBounds(stage, bounds("companion-stage")); assertSame(renderer, renderer())
            compose.runOnIdle { state.value = state.value.copy(streamingText = longReply + "\n\n" + "New streamed text. ".repeat(250)) }
            assertEquals("New tokens moved the reader's position", position, scrollValue(), 1f)
            assertBounds(stage, bounds("companion-stage")); assertSame(renderer, renderer())
            compose.onNodeWithTag("character-spark").assertIsDisplayed().assertHasClickAction().performClick()
            compose.runOnIdle { state.value = state.value.copy(voiceState = KaiVoiceState.LISTENING, speechAudible = false) }
            compose.onNodeWithTag("character-spark").assertContentDescriptionEquals("Spark, listening")
            compose.waitUntil(30_000) { document().optString("state") == "listening" }
            assertBounds(stage, bounds("companion-stage")); assertSame(renderer, renderer())
            screenshot("02-pinned-scrolled-listening.png")
        }
    }

    @Test fun crossedOutCopyIsRemovedAndChatAndVoiceControlsRemainUsable() {
        val state = mutableStateOf(state().copy(streamingText = "Ready when you are."))
        var toggles = 0; var interrupts = 0
        val actions = actions { name ->
            if (name == "toggleVoice") {
                toggles++
                val enabled = !state.value.voiceEnabled
                state.value = state.value.copy(voiceEnabled = enabled, voiceState = if (enabled) KaiVoiceState.LISTENING else KaiVoiceState.IDLE, isBusy = false)
            }
            if (name == "interruptVoice") {
                interrupts++; state.value = state.value.copy(voiceState = KaiVoiceState.LISTENING, speechAudible = false, isBusy = false)
            }
        }
        launch { KaiApp(state.value, actions) }.use {
            compose.onNodeWithText("A little company. A lot of possibility.").assertDoesNotExist()
            compose.onNodeWithText("Open chat & transcript").assertDoesNotExist()
            compose.onNodeWithText("Microphone starts only when you tap Start talking.").assertDoesNotExist()
            val stage = bounds("companion-stage")
            compose.onNodeWithText("Start talking").assertIsDisplayed().performClick()
            compose.onNodeWithText("End conversation").assertIsDisplayed()
            compose.onNodeWithText("Listening stays on while KAI is open. End the conversation to stop.").assertDoesNotExist()
            compose.runOnIdle { state.value = state.value.copy(voiceState = KaiVoiceState.SPEAKING, speechAudible = true) }
            compose.onNodeWithContentDescription("Your turn · interrupt").assertIsDisplayed().performClick()
            assertEquals(1, interrupts); assertBounds(stage, bounds("companion-stage"))
            compose.onNodeWithText("End conversation").performClick(); assertEquals(2, toggles)
            compose.onNode(hasText("Chat") and hasClickAction()).performClick()
            compose.onNodeWithTag("companion-stage").assertDoesNotExist()
            compose.onNode(hasText("Character") and hasClickAction()).performClick()
            compose.onNodeWithTag("companion-stage").assertIsDisplayed()
        }
    }

    @Test fun compactWindowAndLargeTextKeepPanelTranscriptAndControlsSeparate() {
        val state = mutableStateOf(state().copy(voiceEnabled = true, voiceState = KaiVoiceState.SPEAKING))
        launch {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.6f)) {
                Box(Modifier.size(width = 320.dp, height = 440.dp)) { KaiApp(state.value, actions()) }
            }
        }.use {
            val stage = bounds("companion-stage"); val transcript = bounds("companion-transcript")
            assertTrue(stage.height > 30f); assertTrue(transcript.height > 15f)
            assertTrue("Transcript overlaps the pinned panel", transcript.top >= stage.bottom)
            compose.onNodeWithText("End conversation").assertIsDisplayed()
            compose.onNodeWithContentDescription("Your turn · interrupt").assertIsDisplayed()
            compose.onNodeWithTag("companion-transcript").performTouchInput { swipeUp() }
            assertTrue(scrollValue() > 0f); assertBounds(stage, bounds("companion-stage"))
            screenshot("03-compact-large-text.png")
        }
    }

    @Test fun approvalsAndTemporaryNoticeStayReachableWithoutMovingCharacter() {
        val state = state().copy(temporaryConversation = true,
            pendingApproval = KaiApproval("fixture", "Reviewed draft", "Synthetic action preview; nothing is submitted."))
        var approvals = 0
        launch { KaiApp(state, actions { if (it == "approveAction") approvals++ }) }.use {
            val stage = bounds("companion-stage")
            compose.onNodeWithText("Approve").performScrollTo().assertIsDisplayed().performClick()
            assertEquals(1, approvals)
            compose.onNodeWithText("Temporary conversation · no saved transcript").performScrollTo().assertIsDisplayed()
            assertBounds(stage, bounds("companion-stage"))
        }
    }

    private fun actions(onAction: (String) -> Unit = {}): KaiUiActions = Proxy.newProxyInstance(
        KaiUiActions::class.java.classLoader, arrayOf(KaiUiActions::class.java)
    ) { proxy, method, args ->
        onAction(method.name)
        when (method.name) {
            "equals" -> proxy === args?.get(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "PinnedCompanionFixtureActions"
            else -> null
        }
    } as KaiUiActions
    private fun launch(content: @androidx.compose.runtime.Composable () -> Unit): ActivityScenario<ComponentActivity> =
        ActivityScenario.launch(ComponentActivity::class.java).also { scenario ->
            scenario.onActivity { activity ->
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity.setContent(content = content)
            }
        }
    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun assertBounds(expected: Rect, actual: Rect) {
        assertEquals(expected.left, actual.left, 1f); assertEquals(expected.top, actual.top, 1f)
        assertEquals(expected.width, actual.width, 1f); assertEquals(expected.height, actual.height, 1f)
    }
    private fun scrollValue() = compose.onNodeWithTag("companion-transcript").fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun waitForLiveSpark() = compose.waitUntil(120_000) {
        compose.onAllNodesWithTag("character-rig-live-spark", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }
    private fun renderer(): NativeCharacterView {
        val found = mutableListOf<NativeCharacterView>()
        onView(isRoot()).perform(object : ViewAction {
            override fun getDescription() = "Inspect the character renderer"
            override fun getConstraints(): Matcher<View> = isRoot()
            override fun perform(controller: UiController, view: View) {
                fun visit(v: View) {
                    if (v is NativeCharacterView) found += v
                    if (v is ViewGroup) for (index in 0 until v.childCount) visit(v.getChildAt(index))
                }
                visit(view)
            }
        })
        assertEquals("The page must retain exactly one live renderer", 1, found.size)
        return found.single()
    }
    private fun document(): JSONObject {
        val view=renderer();var result:JSONObject?=null
        i.runOnMainSync { val s=view.snapshot();result=JSONObject().put("state",s.state.webState).put("audible",s.audible.toString()) }
        return requireNotNull(result)
    }
    private fun screenshot(name: String) {
        val directory = File(i.targetContext.getExternalFilesDir(null), "qa-pinned-character-047").apply { mkdirs() }
        val bitmap = requireNotNull(i.uiAutomation.takeScreenshot())
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
