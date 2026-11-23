package com.kai.agent.workspace

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kai.agent.ui.*
import com.kai.agent.data.*
import java.lang.reflect.Proxy
import java.io.File
import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Controlled state tests visible entry points; microphone acoustics are tested separately on a phone. */
@RunWith(AndroidJUnit4::class)
class WorkspaceUiDeviceTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun conversationVoiceTranscriptAndWorkspaceControlsAreVisible() {
        val project=KaiProject(name="Launch project")
        val run=AgentRun(title="Prepare a briefing",goal="Read and summarize",conversationId="c",status=RunStatus.PAUSED,steps=listOf(RunStep(title="Read",instructions="Read")))
        val state=mutableStateOf(KaiUiState(reducedMotion=true,providerConnected=true,
            providerConfig=KaiProviderConfig(provider=KaiProvider.OPENAI,apiKey="fixture",modelId="fixture"),
            selectedVoiceId=PlayfulVoiceDefaults.CUTE,
            voices=PlayfulVoiceDefaults.styles.map {KaiVoice(it.id,it.name,it.detail)} + listOf(KaiVoice("system","Device default"),KaiVoice("fixture-soft","English · Test base"),KaiVoice(DesktopVoiceDefaults.VOICE_ID,"KAI Desktop · Azelma","Original desktop model · Can be slow on phones")),
            projects=listOf(project),runs=listOf(run),messages=listOf(KaiMessage("u",KaiRole.USER,"Plan my day"),KaiMessage("a",KaiRole.ASSISTANT,"Let’s start with your priorities."))))
        val events=mutableListOf<String>()
        val actions=Proxy.newProxyInstance(KaiUiActions::class.java.classLoader,arrayOf(KaiUiActions::class.java)) { proxy,method,args ->
            events+=method.name
            if(method.name in listOf("selectVoice","selectVoiceBase")) events+="${method.name}:${args?.get(0)}"
            when(method.name) {
                "toggleVoice" -> { val enabled=!state.value.voiceEnabled;state.value=state.value.copy(voiceEnabled=enabled,voiceState=if(enabled)KaiVoiceState.LISTENING else KaiVoiceState.IDLE);null }
                "selectVoice" -> { state.value=state.value.copy(selectedVoiceId=args!![0] as String);null }
                "selectVoiceBase" -> { state.value=state.value.copy(selectedVoiceBaseId=args!![0] as String);null }
                "equals" -> proxy===args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "Workspace fixture"
                else -> null
            }
        } as KaiUiActions
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { KaiApp(state.value,actions) } }
            compose.onNodeWithText("Start talking").assertIsDisplayed()
            compose.onNodeWithText("Voice").assertIsDisplayed()
            screenshot("01-character-start.png")
            compose.onNodeWithText("Start talking").performClick()
            compose.onNodeWithText("I’m listening.").assertIsDisplayed()
            compose.onNodeWithText("End conversation").performClick()
            compose.onNodeWithText("Voice").performClick()
            compose.onNodeWithText("Voice for KAI").assertIsDisplayed()
            compose.onNodeWithText("KAI conversation · Fast").assertDoesNotExist()
            assertEquals(PlayfulVoiceDefaults.CUTE,state.value.selectedVoiceId)
            PlayfulVoiceDefaults.styles.forEach { style ->
                compose.onNodeWithContentDescription("Select voice ${style.name}").performScrollTo().performClick()
                assertEquals(style.id,state.value.selectedVoiceId)
                compose.onNodeWithContentDescription("Preview ${style.name}").performClick()
                assertEquals(style.id,state.value.selectedVoiceId)
            }
            compose.onNodeWithContentDescription("Select voice KAI · Cute").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Choose base voice").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Use base voice English · Test base").performClick()
            assertEquals("fixture-soft",state.value.selectedVoiceBaseId)
            compose.onNodeWithText("Voice for KAI").performScrollTo()
            screenshot("02-playful-voices-043.png")
            val deviceRow=compose.onNodeWithContentDescription("Select voice Device default")
            // Scroll the LazyColumn to the item. Off-screen cached item bounds are not a tap target.
            val voiceListId=compose.onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText("Voice for KAI")))
                .fetchSemanticsNode().id
            val voiceList=compose.onNode(SemanticsMatcher("Voice options list") { it.id==voiceListId })
            voiceList.performScrollToNode(hasContentDescription("Select voice Device default"))
            deviceRow.assertIsDisplayed()
            val beforeBounds=deviceRow.fetchSemanticsNode().boundsInRoot.toString()
            screenshot("06-before-device-select.png")
            deviceRow.performClick()
            val immediateVoice=state.value.selectedVoiceId
            screenshot("07-after-device-select.png")
            compose.runOnIdle {
                val directory=File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"qa-040").apply {mkdirs()}
                File(directory,"selection-diagnostic.txt").writeText("bounds=$beforeBounds\nimmediate=$immediateVoice\nafterIdle=${state.value.selectedVoiceId}\nbase=${state.value.selectedVoiceBaseId}\nevents=${events.joinToString()}\n")
                assertEquals("Selection events: $events","system",state.value.selectedVoiceId)
            }
            screenshot("02-voice-options.png")
            voiceList.performScrollToIndex(0)
            compose.onNodeWithText("Done").assertIsDisplayed().performClick()
            compose.onNodeWithText("Chat").performClick()
            compose.onNodeWithText("Plan my day").assertExists()
            compose.onNodeWithText("+ File").assertIsDisplayed();compose.onNodeWithText("Camera").assertIsDisplayed()
            screenshot("03-shared-chat.png")
            compose.onNodeWithText("Tasks").performClick()
            compose.onNodeWithText("New task").assertIsDisplayed();compose.onNodeWithText("New routine").assertIsDisplayed()
            compose.onNodeWithText("Prepare a briefing").performClick()
            compose.onNodeWithText("Resume").assertExists();compose.onNodeWithText("Answer / change directions").assertExists()
            screenshot("04-task-progress.png")
            compose.onNodeWithText("New routine").performClick()
            compose.onNodeWithText("Routine name").assertExists();compose.onNodeWithText("Time · HH:mm").assertExists()
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithText("Projects").performClick()
            compose.onNodeWithText("Launch project").assertExists();compose.onNodeWithText("New project").assertIsDisplayed()
            screenshot("05-projects.png")
            assertTrue(events.contains("toggleVoice"));assertTrue(events.contains("selectVoice"))
            assertTrue(events.contains("selectVoiceBase"));assertEquals(3,events.count {it=="previewVoice"})
        }
    }
    private fun screenshot(name: String) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val bitmap=instrumentation.uiAutomation.takeScreenshot() ?: return
        val directory=File(instrumentation.targetContext.getExternalFilesDir(null),"qa-040").apply { mkdirs() }
        File(directory,name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
}
