package com.kai.agent.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kai.agent.voice.VoiceAudioRoute
import java.lang.reflect.Proxy
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationAudioControlsDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun routeSelectionAndOptionalTestReportAreReachableWithoutStartingMicrophone() {
        val state = mutableStateOf(KaiUiState(voiceAudioRoutes = listOf(VoiceAudioRoute("auto", "Automatic"), VoiceAudioRoute("device:1", "Bluetooth headset"))))
        val calls = CopyOnWriteArrayList<String>()
        val actions = Proxy.newProxyInstance(KaiUiActions::class.java.classLoader, arrayOf(KaiUiActions::class.java)) { proxy, method, args ->
            when(method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "ConversationAudioFixtureActions-swipe"
                else -> {
                    calls += method.name
                    when(method.name) {
                        "selectAudioRoute" -> state.value = state.value.copy(selectedAudioRoute = args!![0] as String)
                        "startVoiceTest" -> state.value = state.value.copy(voiceTestRunning = true)
                        "clearVoiceTest" -> state.value = state.value.copy(voiceTestRunning = false)
                    }
                    null
                }
            }
        } as KaiUiActions
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { KaiApp(state.value, actions) } }
            compose.onNodeWithText("Voice").performClick()
            compose.onNodeWithText("Bluetooth headset").performScrollTo().performClick()
            assertEquals("device:1", state.value.selectedAudioRoute)
            // Choose the vertical options list explicitly: these buttons also live in
            // a horizontal row, which is not the parent we need to scroll here.
            fun tapReportControl(text: String) {
                val list = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
                // The settings card is taller than a viewport, so scroll its content with
                // the same vertical gestures as a phone user, rather than only its item index.
                for (attempt in 0 until 12) {
                    if (runCatching { compose.onNodeWithText(text).assertIsDisplayed() }.isSuccess) break
                    list.performTouchInput { swipeUp() }
                    compose.waitForIdle()
                }
                compose.onNodeWithText(text).assertIsDisplayed().performClick()
            }
            tapReportControl("Start test")
            compose.waitUntil(10_000) { state.value.voiceTestRunning }
            assertTrue(state.value.voiceTestRunning); assertFalse(state.value.voiceEnabled)
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "qa-natural-048").apply { mkdirs() }
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(directory, "voice-test-controls.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            tapReportControl("Save test report")
            assertTrue(calls.contains("exportVoiceTest")); assertFalse(calls.contains("toggleVoice"))
            tapReportControl("Clear test")
            compose.waitUntil(10_000) { !state.value.voiceTestRunning }
            assertFalse(state.value.voiceTestRunning)
            compose.runOnUiThread { state.value = state.value.copy(voiceEnabled = true) }
            compose.onNodeWithText("Bluetooth headset").performScrollTo().assertIsNotEnabled()
        }
    }
}
