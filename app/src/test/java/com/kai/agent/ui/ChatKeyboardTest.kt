package com.kai.agent.ui

import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33], qualifiers="w360dp-h640dp")
@LooperMode(LooperMode.Mode.PAUSED)
class ChatKeyboardTest {
    @get:Rule val compose = createComposeRule()
    private val sent = mutableListOf<String>()
    private val actions = Proxy.newProxyInstance(KaiUiActions::class.java.classLoader, arrayOf(KaiUiActions::class.java)) { proxy, method, args ->
        when (method.name) {
            "sendMessage" -> { sent += args!![0] as String; null }
            "equals" -> proxy === args?.get(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "Chat keyboard fixture"
            else -> null
        }
    } as KaiUiActions
    private val state = KaiUiState(currentConversationId="fixture", reducedMotion=true,
        messages=listOf(KaiMessage("m", KaiRole.ASSISTANT, "A recent reply stays available while you type.")))

    @Test fun keyboardInsetsMoveComposerAboveKeyboardAndClosingRestoresNavigation() {
        lateinit var view: View
        var density = 1f
        compose.setContent {
            view = LocalView.current; density = LocalDensity.current.density
            KaiApp(state, actions)
        }
        compose.onNodeWithText("Chat").performClick()
        val screenBottom = compose.onRoot().fetchSemanticsNode().boundsInRoot.bottom
        fun keyboard(open: Boolean) {
            compose.runOnIdle {
                ViewCompat.dispatchApplyWindowInsets(view, WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, if(open) (300*density).toInt() else 0))
                    .setVisible(WindowInsetsCompat.Type.ime(), open).build())
            }
            compose.waitForIdle()
        }
        compose.onNodeWithTag("chat-input").performClick()
        keyboard(true)
        compose.onNodeWithTag("chat-input").performTextInput("This is visible above the keyboard")
        keyboard(true)
        compose.onNodeWithTag("chat-input").assertIsDisplayed().assertTextContains("This is visible above the keyboard")
        compose.onNodeWithContentDescription("Send message").assertIsDisplayed()
        assertTrue(compose.onNodeWithTag("chat-composer").fetchSemanticsNode().boundsInRoot.bottom <= screenBottom - 300*density + 1)
        compose.onNodeWithText("Connections").assertDoesNotExist()
        keyboard(false)
        compose.onNodeWithText("Connections").assertIsDisplayed()
        compose.onNodeWithTag("chat-input").assertTextContains("This is visible above the keyboard")
        compose.onNodeWithContentDescription("Send message").performClick()
        assertEquals(listOf("This is visible above the keyboard"), sent)
    }

    @Test fun shortViewportWithLargeTextKeepsEditorAndSendVisibleWhileTranscriptCanScroll() {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.8f)) {
                KaiTheme(true) {
                    Box(Modifier.size(360.dp, 240.dp).testTag("viewport")) {
                        TalkScreen(state.copy(messages=(1..20).map { KaiMessage("$it", KaiRole.ASSISTANT, "Previous reply $it") }), actions,
                            builtInKaiCharacters.first(), false, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithTag("chat-input").performTextInput("First line\nSecond line\nStill typing")
        compose.onNodeWithTag("chat-input").assertIsDisplayed().assertTextContains("Still typing", substring=true)
        val editor = compose.onNodeWithTag("chat-composer").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        assertTrue(editor.bottom <= viewport.bottom + 1)
        compose.onNodeWithContentDescription("Send message").assertIsDisplayed()
        compose.onNodeWithTag("chat-transcript").performTouchInput { swipeDown() }
        compose.onNodeWithTag("chat-input").assertTextContains("First line", substring=true)
    }

    @Test fun resizingAnEmptyChatPreservesUnsentDraft() {
        val height = mutableStateOf(560.dp)
        compose.setContent {
            KaiTheme(true) { Box(Modifier.width(360.dp).height(height.value)) {
                TalkScreen(state.copy(messages=emptyList()), actions, builtInKaiCharacters.first(), false, {}, {})
            } }
        }
        compose.onNodeWithTag("chat-input").performTextInput("Don't lose this draft")
        compose.runOnIdle { height.value = 220.dp }
        compose.onNodeWithTag("chat-input").assertIsDisplayed().assertTextContains("Don't lose this draft")
        compose.runOnIdle { height.value = 560.dp }
        compose.onNodeWithTag("chat-input").assertTextContains("Don't lose this draft")
        assertTrue(sent.isEmpty())
    }
}
