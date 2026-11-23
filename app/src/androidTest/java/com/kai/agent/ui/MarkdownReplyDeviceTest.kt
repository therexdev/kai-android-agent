package com.kai.agent.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kai.agent.text.ReplyMarkdown
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarkdownReplyDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val raw = "Please confirm **reader@example.com** and open [the guide](https://example.com/help)."
    private val expected = "Please confirm reader@example.com and open the guide."
    private val opened = CopyOnWriteArrayList<String>()
    private val handler = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
    private fun actions() = Proxy.newProxyInstance(KaiUiActions::class.java.classLoader, arrayOf(KaiUiActions::class.java)) { proxy, method, args ->
        when(method.name) { "equals" -> proxy === args?.get(0); "hashCode" -> System.identityHashCode(proxy); "toString" -> "MarkdownUiFixture"; else -> null }
    } as KaiUiActions
    @Test fun characterAndChatRenderBoldAndClickableLabelsWithoutRawMarkdown() {
        val state = KaiUiState(selectedCharacterId = "spark", messages = listOf(KaiMessage("formatted", KaiRole.ASSISTANT, raw)),
            providerConfig = KaiProviderConfig(apiKey = "synthetic-ui-fixture", modelId = "fixture"))
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { CompositionLocalProvider(LocalUriHandler provides handler) { KaiApp(state, actions()) } } }
            assertFormatted()
            assertTrue(opened.isEmpty()); clickGuide(); assertEquals(listOf("https://example.com/help"), opened.toList())
            screenshot("character-markdown.png")
            compose.onNodeWithText("Chat").performClick()
            compose.onNodeWithText(expected).performScrollTo()
            assertFormatted(); clickGuide(); assertEquals(2, opened.size)
            screenshot("chat-markdown.png")
        }
    }
    @Test fun unsafeLinkSchemesRemainPlainLabelsWithoutAUrlAction() {
        val source = "[Safe](https://example.com/) [Unsafe](javascript:alert%281%29) [Private](file:///data/private)"
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { CompositionLocalProvider(LocalUriHandler provides handler) { MarkdownReply(source, Modifier.fillMaxWidth()) } } }
            val text = compose.onNodeWithText("Safe Unsafe Private").fetchSemanticsNode().config[SemanticsProperties.Text].single()
            val links = text.getLinkAnnotations(0, text.length)
            assertEquals(1, links.size); assertEquals("Safe", text.text.substring(links.single().start, links.single().end))
            assertTrue(opened.isEmpty())
        }
    }
    private fun assertFormatted() {
        val node = compose.onNodeWithText(expected)
        val text = node.fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertFalse(text.text.contains("**")); assertFalse(text.text.contains("https"))
        assertTrue(text.spanStyles.any { it.item.fontWeight == FontWeight.Bold && text.text.substring(it.start, it.end) == "reader@example.com" })
        assertTrue(text.getLinkAnnotations(0, text.length).isNotEmpty())
    }
    private fun clickGuide() {
        val before = opened.size
        val node = compose.onNodeWithText(expected)
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val box = layouts.single().getBoundingBox(expected.indexOf("the guide") + 2)
        node.performTouchInput { click(Offset(box.center.x, box.center.y)) }
        compose.waitUntil(10_000) { opened.size > before }
    }
    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "qa-markdown-049").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
}
