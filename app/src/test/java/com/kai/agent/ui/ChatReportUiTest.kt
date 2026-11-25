package com.kai.agent.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import java.lang.reflect.Proxy
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],qualifiers="w360dp-h640dp")
@LooperMode(LooperMode.Mode.PAUSED)
class ChatReportUiTest {
    @get:Rule val compose=createComposeRule()
    private val calls=mutableListOf<Pair<String,List<Any?>>>()
    private val actions=Proxy.newProxyInstance(KaiUiActions::class.java.classLoader,arrayOf(KaiUiActions::class.java)) { p,m,a ->
        when(m.name) { "equals" -> p===a?.get(0); "hashCode" -> System.identityHashCode(p); "toString" -> "Actions"
            else -> { calls+=m.name to a.orEmpty().toList(); null } }
    } as KaiUiActions
    @Test fun allChatsIsTheDefaultAndSavingDispatchesOnlyBatchExport() {
        compose.setContent { KaiTheme(true) { ChatReportDialog("current",actions,20,{}) } }
        compose.onNodeWithText("All saved chats (20)").assertExists()
        compose.onNodeWithText("Save report").performClick()
        Assert.assertEquals(listOf("exportAllChatReports" to emptyList<Any?>()),calls)
    }
    @Test fun currentChatCanStillBeExportedSeparately() {
        compose.setContent { KaiTheme(true) { ChatReportDialog("current",actions,20,{}) } }
        compose.onNodeWithText("This chat only").performClick()
        compose.onNodeWithText("Save report").performClick()
        Assert.assertEquals(listOf("exportChatReport" to listOf("current")),calls)
    }
    @Test fun batchExportWorksWithoutACurrentChatAndCancelDoesNotExport() {
        compose.setContent { KaiTheme(true) { ChatReportDialog(null,actions,20,{}) } }
        compose.onNodeWithText("This chat only").assertDoesNotExist()
        compose.onNodeWithText("Save report").assertIsEnabled()
        compose.onNodeWithText("Cancel").performClick()
        Assert.assertTrue(calls.isEmpty())
    }
    @Test fun noSavedChatsCannotStartAnExport() {
        compose.setContent { KaiTheme(true) { ChatReportDialog(null,actions,0,{}) } }
        compose.onNodeWithText("Save report").assertIsNotEnabled()
    }
}
