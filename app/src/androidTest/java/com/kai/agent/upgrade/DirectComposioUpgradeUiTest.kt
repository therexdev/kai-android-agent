package com.kai.agent.upgrade

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kai.agent.KaiViewModel
import com.kai.agent.connections.DirectComposioClient
import com.kai.agent.connections.DirectComposioHttpTransport
import com.kai.agent.data.KaiStore
import com.kai.agent.data.ProviderProfile
import com.kai.agent.ui.KaiApp
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real phone UI/VM/store and HTTP decoder; ONLY the destination is rewritten to a local fixture. */
@RunWith(AndroidJUnit4::class)
class DirectComposioUpgradeUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun ownKeyDiscoversLargeCatalogAndDiagnosesPartialPermissions() {
        UpgradeFixture.requireEmulator()
        // Actual old-APK migration is verified separately by VerifyUpgrade020DeviceTest. Keep
        // this read-only HTTP/UI fixture independent so selector retries never require a downgrade.
        UpgradeFixture.backupProfile()
        runBlocking { KaiStore(UpgradeFixture.context).let { store ->
            try {
                store.load()
                store.updateSettings { it.copy(composioApiKey = "", composioUserId = UpgradeFixture.DIRECT_USER,
                    openai = ProviderProfile("http://10.0.2.2:18765/v1", UpgradeFixture.INFERENCE_KEY, "kai-local-test")) }
                store.remember(UpgradeFixture.FACT)
            } finally { store.close() }
        } }
        val server = MockWebServer()
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val largePage = toolkitPage(false, true)
        assertTrue(largePage.toByteArray().size > 512 * 1024)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                if (request.method != "GET" || request.getHeader("x-api-key") != UpgradeFixture.COMPOSIO_KEY)
                    return response(JSONObject().put("error", "Fixture only permits authenticated reads"), 401)
                val url = request.requestUrl!!
                return when (url.encodedPath) {
                    "/api/v3.1/connected_accounts" -> {
                        if (url.queryParameter("user_ids") != UpgradeFixture.DIRECT_USER)
                            response(JSONObject().put("error", "Wrong fixture identity"), 403)
                        else response(JSONObject().put("items", JSONArray().put(JSONObject()
                            .put("id", "ca_fixture").put("toolkit", JSONObject().put("slug", "fixture_mail"))
                            .put("user_id", UpgradeFixture.DIRECT_USER).put("alias", "Fixture inbox")
                            .put("status", "ACTIVE"))).put("next_cursor", JSONObject.NULL))
                    }
                    "/api/v3.1/toolkits" -> if (url.queryParameter("cursor") == "page-2")
                        response(JSONObject(toolkitPage(true, false))) else response(JSONObject(largePage))
                    "/api/v3.1/tools" -> response(JSONObject().put("error", "Fixture tools scope denied"), 401)
                    "/api/v3.1/auth_configs" -> response(JSONObject().put("error", "Fixture auth scope denied"), 403)
                    "/api/v3.1/toolkits/categories" -> response(JSONObject().put("items", JSONArray()
                        .put(JSONObject().put("id", "productivity").put("name", "Productivity"))))
                    else -> response(JSONObject().put("error", "Unexpected fixture route"), 404)
                }
            }
        }
        server.start()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val original = chain.request()
            if (original.url.host != "backend.composio.dev") throw IOException("Unexpected production origin")
            val local = server.url("/").newBuilder().encodedPath(original.url.encodedPath)
                .encodedQuery(original.url.encodedQuery).build()
            chain.proceed(original.newBuilder().url(local).build())
        }.build()
        val client = DirectComposioClient(DirectComposioHttpTransport(http))
        var scenario: ActivityScenario<ComponentActivity>? = null
        lateinit var model: KaiViewModel
        var modelInitialized = false
        try {
            scenario = ActivityScenario.launch(ComponentActivity::class.java)
            scenario.onActivity { activity ->
                activity.enableEdgeToEdge()
                model = ViewModelProvider(activity, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        KaiViewModel(ApplicationProvider.getApplicationContext<Application>(), client) as T
                })[KaiViewModel::class.java]
                modelInitialized = true
                activity.setContent { val state by model.ui.collectAsStateWithLifecycle(); KaiApp(state, model) }
            }
            waitText("Memory")
            compose.onNodeWithText("Memory").performClick()
            waitText("A little more you.")
            scrollToText(UpgradeFixture.FACT)
            waitText(UpgradeFixture.FACT)
            screenshot("01-fixture-memory.png")
            compose.onNodeWithText("Connections").performClick()
            waitText("Composio setup")
            compose.onNodeWithText("Sign in to connections").assertDoesNotExist()
            assertEquals(0, requests.size) // Legacy session did not authenticate anything.
            field("Composio API key").performScrollTo()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            screenshot("02-direct-setup.png")
            field("Composio API key").performTextReplacement("  \"${UpgradeFixture.COMPOSIO_KEY}\"  ")
            compose.onNodeWithText("Save & connect").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(90_000) { model.ui.value.connectionsSignedIn &&
                !model.ui.value.connectionBusy && model.ui.value.connectionHasMore }
            scrollToText("Fixture inbox")
            screenshot("03-large-catalog-connected.png")
            compose.onNodeWithText("Response too large", substring = true).assertDoesNotExist()
            val saved = readStore()
            assertEquals(UpgradeFixture.COMPOSIO_KEY, saved.state.value.settings.composioApiKey)
            assertEquals(UpgradeFixture.DIRECT_USER, saved.state.value.settings.composioUserId)
            assertEquals("", saved.state.value.settings.connectionsSessionToken)
            assertEquals(UpgradeFixture.INFERENCE_KEY, saved.state.value.settings.openai.apiKey)
            assertEquals(UpgradeFixture.FACT, saved.state.value.memories.single().text)
            File(UpgradeFixture.context.noBackupFilesDir, "kai-state").walkTopDown()
                .filter { it.isFile }.forEach { file ->
                    assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains(UpgradeFixture.COMPOSIO_KEY))
                }
            val export = runBlocking { try { saved.exportJson() } finally { saved.close() } }
            assertFalse(export.contains(UpgradeFixture.COMPOSIO_KEY))
            assertFalse(export.contains(UpgradeFixture.DIRECT_USER))

            scrollToText("Load more apps")
            compose.onNodeWithText("Load more apps").performClick()
            compose.waitUntil(90_000) { !model.ui.value.connectionBusy && !model.ui.value.connectionHasMore }
            scrollToText("Fixture Calendar")
            screenshot("04-paginated-catalog.png")
            scrollToText("Check access")
            compose.onNodeWithText("Check access").performClick()
            compose.waitUntil(90_000) { !model.ui.value.connectionBusy && model.ui.value.composioDiagnostics != null }
            val diagnostics = requireNotNull(model.ui.value.composioDiagnostics)
            assertTrue(diagnostics.contains("401"))
            assertTrue(diagnostics.contains("403"))
            assertTrue(diagnostics.contains("Read is available"))
            assertTrue(model.ui.value.composioKeySaved)
            assertEquals(UpgradeFixture.COMPOSIO_KEY, readStore().state.value.settings.composioApiKey)
            scrollToText("PROJECT ACCESS")
            screenshot("05-partial-permission-diagnostics.png")
            scrollToText("Edit setup")
            compose.onNodeWithText("Edit setup").performClick()
            field("Composio API key").performScrollTo()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            scrollToText("Remove key from this phone")
            compose.onNodeWithText("Remove key from this phone").performScrollTo().assertIsDisplayed().performClick()
            waitText("Remove Composio key?")
            compose.onNodeWithText("Remove key").performClick()
            compose.waitUntil(90_000) { !model.ui.value.composioKeySaved }
            assertTrue(readStore().state.value.settings.composioApiKey.isBlank())
            assertEquals(UpgradeFixture.DIRECT_USER, readStore().state.value.settings.composioUserId)
            assertTrue(requests.all { it.method == "GET" && it.getHeader("Authorization") == null })
            assertTrue(requests.any { it.requestUrl?.queryParameter("cursor") == "page-2" })
            screenshot("06-key-removed-locally.png")
            val evidence = JSONObject().put("fixture_only", true).put("http_requests", requests.size)
                .put("large_catalog_bytes", largePage.toByteArray().size)
                .put("explicit_existing_user_id_reused", true).put("actual_upgrade_verified_in_separate_device_test", true).put("legacy_token_used", false)
                .put("all_requests_read_only", true).put("local_key_removal_sent_delete", false)
                .put("quoted_key_normalized", true).put("partial_401_403_diagnostics_without_key_loss", true)
            File(outputDirectory(), "direct-composio-evidence.json").writeText(evidence.toString(2))
        } catch (e: Throwable) { screenshot("failure-direct-composio.png"); throw e }
        finally {
            scenario?.close()
            if (modelInitialized) runBlocking { withTimeout(60_000) { model.shutdownComplete.await() } }
            server.shutdown()
            UpgradeFixture.restoreProfile()
        }
    }

    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label))
    private fun waitText(text: String) = compose.waitUntil(90_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
    private fun scrollToText(text: String) {
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
            .performScrollToNode(hasText(text))
    }
    private fun readStore() = runBlocking { KaiStore(UpgradeFixture.context).also { it.load(); it.close() } }
    private fun outputDirectory() = File(UpgradeFixture.context.getExternalFilesDir(null), "qa-0.2.0-connections").apply { mkdirs() }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        runCatching { InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { image ->
            File(outputDirectory(), name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        } }
    }
    private fun response(json: JSONObject, code: Int = 200) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json").setBody(json.toString())
    private fun toolkitPage(second: Boolean, large: Boolean): String = JSONObject()
        .put("items", JSONArray().put(JSONObject().put("slug", if (second) "fixture_calendar" else "fixture_mail")
            .put("name", if (second) "Fixture Calendar" else "Fixture Mail")
            .put("meta", JSONObject().put("description", "Local fixture; no external actions.")
                .put("categories", JSONArray().put("Productivity")))
            .put("fixture_padding", if (large) "x".repeat(600 * 1024) else "")))
        .put("total_items", 2).put("next_cursor", if (second) JSONObject.NULL else "page-2").toString()
}
