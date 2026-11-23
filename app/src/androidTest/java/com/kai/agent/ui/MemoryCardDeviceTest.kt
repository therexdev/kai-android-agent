package com.kai.agent.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryCardDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val calls = mutableListOf<Pair<String, List<Any?>>>()
    private val actions = Proxy.newProxyInstance(KaiUiActions::class.java.classLoader, arrayOf(KaiUiActions::class.java)) { proxy, method, args ->
        when (method.name) {
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            "toString" -> "Memory card fixture actions"
            else -> { calls += method.name to args.orEmpty().toList(); null }
        }
    } as KaiUiActions
    private fun show(spelling: Boolean = false) {
        compose.setContent { KaiTheme(true) { Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) { Box(Modifier.padding(16.dp)) {
            MemorySuggestionCard(KaiMemorySuggestion("same-card", "Your last name is Johns.",
                source = "Can you update that card because my last name is J o h n s", reason = "Replace the earlier saved detail?\nBefore: Your last name is Jones.",
                updating = true, needsSpelling = spelling), actions)
        } } } }
    }
    @Test fun correctionShowsFactsBeforeValueAndUpdateAction() {
        show()
        compose.onNodeWithText("UPDATE MEMORY?").assertIsDisplayed()
        compose.onNodeWithText("Your last name is Johns.").assertIsDisplayed()
        compose.onNodeWithText("Before: Your last name is Jones.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Yes, update").assertIsEnabled()
        val output = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "qa0413/update-memory.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithText("Yes, update").performClick()
        assertEquals("same-card", calls.single { it.first == "acceptMemorySuggestion" }.second[0])
    }
    @Test fun ambiguousSpellingCannotBeAcceptedFromOldCard() {
        show(spelling = true)
        compose.onNodeWithText("CHECK SPELLING").assertIsDisplayed()
        compose.onNodeWithText("Yes, update").assertIsNotEnabled()
        compose.onNodeWithText("No, dismiss").performClick()
        assertTrue(calls.none { it.first == "acceptMemorySuggestion" })
        assertEquals("same-card", calls.single { it.first == "dismissMemorySuggestion" }.second[0])
    }
}
