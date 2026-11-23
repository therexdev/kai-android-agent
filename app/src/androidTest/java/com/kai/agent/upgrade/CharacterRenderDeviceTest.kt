package com.kai.agent.upgrade

import android.graphics.Bitmap
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONTokener
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.ViewAction
import androidx.test.espresso.UiController
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import org.hamcrest.Matcher
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kai.agent.ui.*
import java.io.File
import java.lang.reflect.Proxy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Controlled states verify rendering/accessibility; they do not claim live audio behavior. */
@RunWith(AndroidJUnit4::class)
class CharacterRenderDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun allFourCompanionsShowVoiceStatesAndRespectReducedMotion() = verifyCharacters(includeVoiceStates = true)

    @Test fun studioSelectionAndReducedMotionKeepOneVisibleInteractiveRenderer() = verifyCharacters(includeVoiceStates = false)

    /** A bounded continuation of the expensive software-emulator Studio sweep. */
    @Test fun sparkStudioReducedMotionAndReturnRemainVisible() {
        UpgradeFixture.requireEmulator()
        val state = mutableStateOf(KaiUiState(selectedCharacterId = "spark", reducedMotion = true))
        var selected = false
        val actions = Proxy.newProxyInstance(KaiUiActions::class.java.classLoader,
            arrayOf(KaiUiActions::class.java)) { proxy, method, args ->
            if (method.name == "selectCharacter") {
                val id = args!![0] as String
                selected = id == "spark"
                state.value = state.value.copy(selectedCharacterId = id)
            }
            when (method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "StudioTailFixtureActions"
                else -> null
            }
        } as KaiUiActions
        val output = File(UpgradeFixture.context.getExternalFilesDir(null), "qa-voice-character-030").apply { mkdirs() }
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity.setContent { KaiApp(state.value, actions) }
            }
            Log.i("CharacterRenderQA", "Tail: opening Spark Studio in reduced motion")
            compose.onNode(hasText("Spark") and hasClickAction()).performClick()
            compose.onNodeWithText("A personality that fits.").assertIsDisplayed()
            compose.onNode(hasText("Spark") and hasText(builtInKaiCharacters.first { it.id == "spark" }.subtitle) and hasClickAction())
                .performScrollTo().performClick()
            compose.onNodeWithContentDescription("Close characters").performScrollTo()
            Log.i("CharacterRenderQA", "Tail: selected Spark and returned to preview")
            compose.waitUntil(120_000) { compose.onAllNodesWithTag("character-rig-live-spark", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            assertTrue(selected)
            assertEquals(1, rendererCount())
            assertTrue(document().getBoolean("motionOff"))
            assertTrue(document().getDouble("svgHeight") > 20.0)
            screenshot(output, "studio-spark.png")
            compose.onNode(hasContentDescription("Spark, ready") and hasClickAction()).performClick()
            screenshot(output, "studio-reduced-motion-interactive.png")
            compose.onNodeWithContentDescription("Close characters").performClick()
            Log.i("CharacterRenderQA", "Tail: returning to Talk")
            compose.waitUntil(120_000) { compose.onAllNodesWithTag("character-rig-live-spark", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, rendererCount())
            screenshot(output, "studio-selection-return-to-talk.png")
        }
        File(output, "character-studio-tail.json").writeText(JSONObject().put("fixture_only", true)
            .put("spark_selected", true).put("reduced_motion_interactive", true)
            .put("single_live_renderer", true).put("return_to_talk_visible", true).toString(2))
    }

    private fun verifyCharacters(includeVoiceStates: Boolean) {
        UpgradeFixture.requireEmulator()
        val state = mutableStateOf(KaiUiState(reducedMotion = false))
        val selected = mutableListOf<String>()
        val actions = Proxy.newProxyInstance(KaiUiActions::class.java.classLoader,
            arrayOf(KaiUiActions::class.java)) { proxy, method, args ->
            if (method.name == "selectCharacter") {
                val id = args!![0] as String
                selected += id
                state.value = state.value.copy(selectedCharacterId = id)
            }
            when (method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "CharacterRenderFixtureActions"
                else -> null
            }
        } as KaiUiActions
        val output = File(UpgradeFixture.context.getExternalFilesDir(null), "qa-voice-character-030").apply { mkdirs() }
        val observed = JSONArray()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); activity.setContent { KaiApp(state.value, actions) } }
            val labels = mapOf(KaiVoiceState.IDLE to "ready", KaiVoiceState.LISTENING to "listening",
                KaiVoiceState.THINKING to "thinking", KaiVoiceState.SPEAKING to "speaking")
            if (includeVoiceStates) for (character in builtInKaiCharacters) for ((voice, label) in labels) {
                compose.runOnUiThread {
                    state.value = state.value.copy(selectedCharacterId = character.id, voiceState = voice,
                        voiceEnabled = voice != KaiVoiceState.IDLE, isBusy = voice == KaiVoiceState.THINKING,
                        speechAudible = voice == KaiVoiceState.SPEAKING,
                        messages = listOf(KaiMessage("fixture-user", KaiRole.USER, "Let's think this through together.")))
                }
                compose.onNodeWithTag("character-${character.id}").assertIsDisplayed()
                    .assertContentDescriptionEquals("${character.name}, $label")
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Animated"))
                compose.waitUntil(120_000) { compose.onAllNodesWithTag("character-rig-live-${character.id}", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
                val dom = document()
                assertEquals(voice.name.lowercase(), dom.getString("state"))
                assertEquals((voice == KaiVoiceState.SPEAKING).toString(), dom.getString("audible"))
                assertFalse(dom.getBoolean("motionOff"))
                assertTrue(dom.getBoolean("svg"))
                assertTrue("SVG must have actual viewport height", dom.getDouble("svgHeight") > 20.0)
                screenshot(output, "${character.id}-${voice.name.lowercase()}.png")
                observed.put(JSONObject().put("character", character.id).put("state", voice.name))
            }
            compose.runOnUiThread { state.value = state.value.copy(selectedCharacterId = "kai", voiceState = KaiVoiceState.IDLE,
                voiceEnabled = false, isBusy = false, speechAudible = false, reducedMotion = false) }
            compose.onNodeWithTag("character-kai").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Animated"))
            // Tap feedback must be available without network or a microphone permission flow.
            compose.waitUntil(120_000) { compose.onAllNodesWithTag("character-rig-live-kai", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            compose.runOnUiThread { state.value = state.value.copy(voiceState = KaiVoiceState.SPEAKING, speechAudible = false) }
            assertEquals("false", document().getString("audible"))
            screenshot(output, "kai-speaking-gap.png")
            compose.runOnUiThread { state.value = state.value.copy(reducedMotion = true) }
            compose.onNodeWithTag("character-kai").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Still"))
            screenshot(output, "kai-reduced-motion.png")
            compose.runOnUiThread { state.value = state.value.copy(reducedMotion = false) }
            compose.onNodeWithTag("character-kai").assertHasClickAction().performClick()
            assertTrue(state.value.error == null)
            compose.runOnUiThread { state.value = state.value.copy(voiceState = KaiVoiceState.IDLE, speechAudible = false) }
            compose.onNode(hasText("KAI") and hasClickAction()).performClick()
            compose.onNodeWithText("A personality that fits.").assertIsDisplayed()
            for (character in builtInKaiCharacters) {
                compose.onNode(hasText(character.name) and hasText(character.subtitle) and hasClickAction())
                    .performScrollTo().performClick()
                compose.onNodeWithContentDescription("Close characters").performScrollTo()
                compose.waitUntil(120_000) { compose.onAllNodesWithTag("character-rig-live-${character.id}", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
                assertEquals(1, builtInKaiCharacters.sumOf { compose.onAllNodesWithTag("character-rig-live-${it.id}", useUnmergedTree = true).fetchSemanticsNodes().size })
                assertEquals(1, rendererCount())
                assertTrue(document().getBoolean("svg"))
                screenshot(output, "studio-${character.id}.png")
            }
            compose.runOnUiThread { state.value = state.value.copy(reducedMotion = true) }
            compose.onNodeWithTag("character-rig-live-spark", useUnmergedTree = true).assertExists()
            assertTrue(document().getBoolean("motionOff"))
            compose.onNode(hasContentDescription("Spark, ready") and hasClickAction()).performClick()
            screenshot(output, "studio-reduced-motion-interactive.png")
            compose.runOnUiThread { state.value = state.value.copy(reducedMotion = false) }
            compose.onNodeWithContentDescription("Close characters").performClick()
            compose.waitUntil(120_000) { compose.onAllNodesWithTag("character-rig-live-spark", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, rendererCount())
            assertEquals(listOf("kai", "nova", "sage", "spark"), selected)
            screenshot(output, "studio-selection-return-to-talk.png")
        }
        File(output, if (includeVoiceStates) "character-render.json" else "character-studio-render.json").writeText(JSONObject().put("fixture_only", true)
            .put("states", observed).put("reduced_motion_semantics_verified", true)
            .put("tap_action_available", true).put("studio_selection_cycled", true).put("single_live_renderer", true).put("live_audio_tested", false).toString(2))
    }

    private fun inFocusedWindow(description: String, block: (View) -> Unit) {
        onView(isRoot()).perform(object : ViewAction {
            override fun getDescription() = description
            override fun getConstraints(): Matcher<View> = isRoot()
            override fun perform(controller: UiController, view: View) { block(view) }
        })
    }

    private fun rendererCount(): Int {
        var count = 0
        inFocusedWindow("Count live character renderers") { root ->
            fun visit(view: View) {
                if (view is WebView) count++
                if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
            visit(root)
        }
        return count
    }

    private fun document(): JSONObject {
        compose.waitForIdle()
        val raw = AtomicReference<String?>()
        inFocusedWindow("Inspect bundled character state") { root ->
            fun web(view: View): WebView? {
                if (view is WebView) return view
                if (view is ViewGroup) for (index in 0 until view.childCount) web(view.getChildAt(index))?.let { return it }
                return null
            }
            val rig = requireNotNull(web(root)) { "No live character renderer" }
            rig.evaluateJavascript("JSON.stringify({state:document.body.dataset.state,audible:document.body.dataset.audible,motionOff:document.body.classList.contains('motion-off'),svg:!!document.querySelector('svg'),svgHeight:document.querySelector('svg').getBoundingClientRect().height})") { raw.set(it) }
        }
        compose.waitUntil(30_000) { raw.get() != null }
        return JSONObject(JSONTokener(raw.get()).nextValue() as String)
    }

    private fun screenshot(directory: File, name: String) {
        var left = 0; var top = 0; var width = 0; var height = 0
        Log.i("CharacterRenderQA", "Capturing $name")
        inFocusedWindow("Measure visible character bounds") { root ->
            fun find(view: View): WebView? {
                if (view is WebView) return view
                if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
                return null
            }
            val web = requireNotNull(find(root))
            val point = IntArray(2); web.getLocationOnScreen(point)
            left = point[0]; top = point[1]; width = web.width; height = web.height
            assertTrue(width > 20 && height > 20)
        }
        var colored = 0
        var bitmap: Bitmap? = null
        val deadline = System.nanoTime() + 10_000_000_000L
        do {
            bitmap?.recycle()
            bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                ?: error("The emulator did not return a screenshot")
            colored = 0
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            for (y in top.coerceAtLeast(0) until (top + height).coerceAtMost(bitmap.height) step 2)
                for (x in left.coerceAtLeast(0) until (left + width).coerceAtMost(bitmap.width) step 2) {
                    val pixel = pixels[y * bitmap.width + x]
                    val r = pixel shr 16 and 255; val g = pixel shr 8 and 255; val b = pixel and 255
                    if (maxOf(r,g,b) - minOf(r,g,b) >= 40 && maxOf(r,g,b) >= 90) colored++
                }
            if (colored <= 32) Thread.sleep(250)
        } while (colored <= 32 && System.nanoTime() < deadline)
        File(directory, name).outputStream().use { bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap!!.recycle()
        File(directory, name.removeSuffix(".png") + "-pixels.json").writeText(JSONObject()
            .put("screen_colored_samples", colored)
            .put("x", left).put("y", top).put("width", width).put("height", height).toString(2))
        Log.i("CharacterRenderQA", "$name: $colored colored screen samples at $left,$top ${width}x$height")
        assertTrue("Actual on-screen avatar pixels must be visible, not merely a present SVG DOM", colored > 32)
    }
}
