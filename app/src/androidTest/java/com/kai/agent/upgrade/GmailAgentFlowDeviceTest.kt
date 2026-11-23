package com.kai.agent.upgrade

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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
import com.kai.agent.data.KaiState
import com.kai.agent.data.KaiStore
import com.kai.agent.data.MemoryMode
import com.kai.agent.data.Provider
import com.kai.agent.data.ProviderProfile
import com.kai.agent.data.TaskStatus
import com.kai.agent.ui.KaiApp
import com.kai.agent.ui.KaiConnectionStatus
import com.kai.agent.ui.KaiUiState
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

/** Real Compose, agent VM, SQLCipher and HTTP codecs. Every service is a local fixture. */
@RunWith(AndroidJUnit4::class)
class GmailAgentFlowDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var model: KaiViewModel

    @Test fun twoNamedGmailAccountsDiscoverAndReadDespiteEmptySearch() = withFixture(denied = false) { fixture ->
        compose.onNodeWithText("Connections").performClick()
        waitText("Composio setup")
        scrollToText(EMAIL_A)
        compose.onAllNodesWithText(EMAIL_A).onFirst().assertIsDisplayed()
        assertEquals(setOf(EMAIL_A, EMAIL_B), connected().map { it.accountLabel }.toSet())
        screenshot("01-email-account-label.png")
        compose.onNodeWithText("Talk").performClick()
        sendAndAwait("Check both connected Gmail inboxes and tell me what looks important.")
        waitText(SUMMARY)

        assertTrue("Local fixture violations: ${fixture.violations}", fixture.violations.isEmpty())
        assertEquals(listOf(ACCOUNT_A, ACCOUNT_B), fixture.executedAccounts.toList())
        assertEquals(7, fixture.modelPrompts.size)
        assertTrue("An empty natural-language catalog search was exercised", fixture.emptySearches > 0)
        assertTrue("The fallback catalog's next page must be fetched", fixture.secondPages > 0)
        assertTrue(fixture.modelPrompts.first().contains(EMAIL_A))
        assertTrue(fixture.modelPrompts.first().contains(EMAIL_B))
        assertTrue(fixture.modelPrompts.last().contains(RESULT_A))
        assertTrue(fixture.modelPrompts.last().contains(RESULT_B))
        assertTrue(fixture.modelPrompts.none { it.contains(COMPOSIO_KEY) || it.contains(INFERENCE_KEY) || it.contains(SECRET_DECOY) })
        assertNull(model.ui.value.pendingApproval)
        val tasks = readState().tasks
        assertEquals(2, tasks.size)
        assertTrue(tasks.all { it.status == TaskStatus.SUCCEEDED && it.intent?.toolName == FETCH })
        assertEquals(setOf(ACCOUNT_A, ACCOUNT_B), tasks.map { it.intent?.accountId }.toSet())
        assertEquals(2, connected().size)
        screenshot("02-both-inboxes-read.png")
        evidence("both-inboxes-evidence.json", JSONObject()
            .put("fixture_only", true).put("model_requests", fixture.modelPrompts.size)
            .put("exact_accounts_read", JSONArray(fixture.executedAccounts.toList()))
            .put("successful_read_receipts", tasks.size).put("email_labels_verified", true)
            .put("empty_search_fallback", true).put("second_catalog_page", true)
            .put("both_results_in_summary_context", true).put("outgoing_writes", 0))
    }

    @Test fun deniedToolCatalogKeepsKeyAndDoesNotPrescribeReconnect() = withFixture(denied = true) { fixture ->
        val before = connected().map { it.id }.toSet()
        assertEquals(2, before.size)
        sendAndAwait("Check my connected Gmail inbox.")
        waitText(DENIED)
        assertTrue("Local fixture violations: ${fixture.violations}", fixture.violations.isEmpty())
        assertEquals(2, fixture.modelPrompts.size)
        assertTrue(fixture.modelPrompts.last().contains("DISCOVERY_FAILED"))
        assertTrue(fixture.modelPrompts.last().contains("CATALOG_ACCESS_DENIED"))
        assertTrue(fixture.modelPrompts.last().contains("403"))
        assertTrue(fixture.executedAccounts.isEmpty())
        assertTrue(model.ui.value.composioKeySaved)
        assertTrue(model.ui.value.connectionsSignedIn)
        assertEquals(EXACT_USER, model.ui.value.composioUserId)
        assertEquals(before, connected().map { it.id }.toSet())
        val state = readState()
        assertEquals(COMPOSIO_KEY, state.settings.composioApiKey)
        assertEquals(EXACT_USER, state.settings.composioUserId)
        assertTrue(state.tasks.isEmpty())
        assertFalse(model.ui.value.messages.last().text.contains("reconnect", true))
        assertTrue(fixture.modelPrompts.none { it.contains(COMPOSIO_KEY) || it.contains(INFERENCE_KEY) || it.contains(SECRET_DECOY) })
        screenshot("03-tool-permission-denied.png")
        evidence("catalog-denied-evidence.json", JSONObject().put("fixture_only", true)
            .put("catalog_http_status", 403).put("saved_key_preserved", true)
            .put("exact_user_id_preserved", true).put("displayed_accounts_preserved", connected().size)
            .put("executions", 0).put("reconnection_prescribed", false))
    }

    @Test fun deniedFirstReadDoesNotDiscardSecondAccountsDiscoveredTool() = withFixture(denied = false, firstReadDenied = true) { fixture ->
        sendAndAwait("Check both connected inboxes, and report each outcome separately.")
        waitText(PARTIAL_SUMMARY)
        assertTrue("Local fixture violations: ${fixture.violations}", fixture.violations.isEmpty())
        assertEquals(5, fixture.modelPrompts.size)
        assertEquals(listOf(ACCOUNT_A, ACCOUNT_B), fixture.executedAccounts.toList())
        val tasks = readState().tasks
        assertEquals(2, tasks.size)
        assertEquals(TaskStatus.FAILED, tasks.single { it.intent?.accountId == ACCOUNT_A }.status)
        assertEquals(TaskStatus.SUCCEEDED, tasks.single { it.intent?.accountId == ACCOUNT_B }.status)
        assertTrue(fixture.modelPrompts.last().contains(RESULT_B))
        assertFalse(fixture.modelPrompts.last().contains(RESULT_A))
        assertEquals(2, connected().size)
        assertTrue(model.ui.value.composioKeySaved)
        assertNull(model.ui.value.pendingApproval)
        screenshot("04-one-read-denied-other-completed.png")
        evidence("partial-read-evidence.json", JSONObject().put("fixture_only", true)
            .put("first_account_status", "FAILED").put("second_account_status", "SUCCEEDED")
            .put("model_requests", fixture.modelPrompts.size).put("read_attempts", JSONArray(fixture.executedAccounts.toList()))
            .put("pre_discovered_second_tool_preserved", true).put("replayed_reads", 0))
    }

    @Test fun unavailableAccountsDoNotBlockOrdinaryConversation() = withFixture(denied = false, accountsDenied = true) { fixture ->
        sendAndAwait("Hello, can you help me think through an idea?")
        waitText(CHAT_WITHOUT_CONNECTIONS)
        assertTrue("Local fixture violations: ${fixture.violations}", fixture.violations.isEmpty())
        assertEquals(1, fixture.modelPrompts.size)
        assertTrue(fixture.accountListAttempts >= 2) // Startup and the bounded turn refresh both failed.
        assertTrue(fixture.modelPrompts.single().contains("ACCOUNTS_UNAVAILABLE"))
        assertTrue(fixture.executedAccounts.isEmpty())
        assertTrue(connected().isEmpty())
        assertTrue(model.ui.value.composioKeySaved)
        val state = readState()
        assertEquals(COMPOSIO_KEY, state.settings.composioApiKey)
        assertEquals(EXACT_USER, state.settings.composioUserId)
        assertTrue(state.tasks.isEmpty())
        assertFalse(model.ui.value.messages.last().text.contains("reconnect", true))
        screenshot("05-chat-with-unavailable-connections.png")
        evidence("chat-without-accounts-evidence.json", JSONObject().put("fixture_only", true)
            .put("account_list_http_status", 403).put("ordinary_reply_received", true)
            .put("model_requests", 1).put("saved_key_preserved", true).put("executions", 0))
    }

    private fun withFixture(denied: Boolean, firstReadDenied: Boolean = false, accountsDenied: Boolean = false,
                            body: (LocalServices) -> Unit) {
        UpgradeFixture.requireEmulator()
        check(context.packageName == "com.kai.agent.preview")
        preserveProfile()
        val fixture = LocalServices(denied, firstReadDenied, accountsDenied)
        val server = MockWebServer().apply { dispatcher = fixture }
        var scenario: ActivityScenario<ComponentActivity>? = null
        var initialized = false
        try {
            server.start()
            runBlocking {
                val store = KaiStore(context)
                try {
                    store.load()
                    store.updateSettings { it.copy(selectedProvider = Provider.OPENAI,
                        openai = ProviderProfile(server.url("/v1").toString(), INFERENCE_KEY, "gmail-ui-fixture"),
                        composioApiKey = COMPOSIO_KEY, composioUserId = EXACT_USER,
                        memoryMode = MemoryMode.OFF, memoryEnabled = false, useMemory = false) }
                } finally { store.close() }
            }
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val original = chain.request()
                if (original.url.host != "backend.composio.dev") throw IOException("Unexpected production origin")
                val local = server.url("/").newBuilder().encodedPath(original.url.encodedPath)
                    .encodedQuery(original.url.encodedQuery).build()
                chain.proceed(original.newBuilder().url(local).build())
            }.build()
            val client = DirectComposioClient(DirectComposioHttpTransport(http))
            scenario = ActivityScenario.launch(ComponentActivity::class.java)
            scenario.onActivity { activity ->
                activity.enableEdgeToEdge()
                model = ViewModelProvider(activity, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        KaiViewModel(ApplicationProvider.getApplicationContext<Application>(), client) as T
                })[KaiViewModel::class.java]
                initialized = true
                activity.setContent { val state by model.ui.collectAsStateWithLifecycle(); KaiApp(state, model) }
            }
            waitUi { it.currentConversationId != null && it.providerConfig.modelId == "gmail-ui-fixture" &&
                it.composioKeySaved && !it.connectionBusy &&
                (if (accountsDenied) fixture.accountListAttempts > 0 && it.connectionStatus.contains("403") else connected().size == 2) }
            if (!accountsDenied) assertNull(model.ui.value.error)
            body(fixture)
        } catch (failure: Throwable) { screenshot("failure-gmail-${if (denied) "denied" else "read"}.png"); throw failure }
        finally {
            scenario?.onActivity { if (initialized) { model.onBackground(); model.stopGeneration() } }
            scenario?.close()
            if (initialized) runBlocking { withTimeout(60_000) { model.shutdownComplete.await() } }
            server.shutdown()
            restoreProfile()
        }
    }

    private class LocalServices(private val denied: Boolean, private val firstReadDenied: Boolean,
                                private val accountsDenied: Boolean) : Dispatcher() {
        val violations = CopyOnWriteArrayList<String>()
        val modelPrompts = CopyOnWriteArrayList<String>()
        val executedAccounts = CopyOnWriteArrayList<String>()
        @Volatile var emptySearches = 0
        @Volatile var secondPages = 0
        @Volatile var accountListAttempts = 0

        override fun dispatch(request: RecordedRequest): MockResponse = try {
            val url = requireNotNull(request.requestUrl)
            if (url.encodedPath == "/v1/chat/completions") inference(request)
            else {
                require(request.getHeader("x-api-key") == COMPOSIO_KEY) { "Missing project-key header" }
                when (url.encodedPath) {
                    "/api/v3.1/connected_accounts" -> {
                        require(request.method == "GET" && url.queryParameter("user_ids") == EXACT_USER) { "Wrong account owner" }
                        accountListAttempts++
                        val selected = url.queryParameter("connected_account_ids")
                        val ids = if (selected == null) listOf(ACCOUNT_A, ACCOUNT_B) else listOf(selected)
                        require(ids.all { it == ACCOUNT_A || it == ACCOUNT_B }) { "Unexpected connected account" }
                        if (accountsDenied) response(JSONObject().put("error", "Fixture Connected Accounts Read denied"), 403)
                        else response(page(ids.map(::account)))
                    }
                    "/api/v3.1/toolkits" -> response(page(listOf(JSONObject().put("slug", "gmail").put("name", "Gmail")
                        .put("meta", JSONObject().put("description", "Fixture mail")))))
                    "/api/v3.1/toolkits/categories" -> response(page(emptyList()))
                    "/api/v3.1/tools" -> {
                        require(request.method == "GET" && url.queryParameter("toolkit_slug") == "gmail") { "Unscoped tool catalog" }
                        when {
                            denied -> response(JSONObject().put("error", "Fixture Tools Read permission denied"), 403)
                            !url.queryParameter("query").isNullOrBlank() -> { emptySearches++; response(page(emptyList())) }
                            url.queryParameter("cursor") == "gmail-page-2" -> { secondPages++; response(page(listOf(fetchTool()))) }
                            else -> response(page((1..12).map { index -> JSONObject().put("slug", "GMAIL_LABEL_$index")
                                .put("name", "Read label $index").put("description", "Read a label definition")
                                .put("toolkit", JSONObject().put("slug", "gmail")).put("version", VERSION)
                                .put("annotations", JSONObject().put("readOnlyHint", true))
                                .put("input_parameters", JSONObject().put("type", "object").put("properties", JSONObject())) }, "gmail-page-2"))
                        }
                    }
                    "/api/v3.1/tools/$FETCH" -> {
                        require(request.method == "GET" && url.queryParameter("version") == VERSION) { "Tool version was not pinned" }
                        response(fetchTool())
                    }
                    "/api/v3.1/tools/execute/$FETCH" -> {
                        require(request.method == "POST") { "Wrong execution method" }
                        val body = JSONObject(request.body.readUtf8())
                        val selected = body.getString("connected_account_id")
                        require(selected in listOf(ACCOUNT_A, ACCOUNT_B)) { "Wrong execution account" }
                        require(body.getString("user_id") == EXACT_USER && body.getString("version") == VERSION) { "Execution ownership or version changed" }
                        val args = body.getJSONObject("arguments")
                        require(args.getString("user_id") == "me" && args.getInt("max_results") == 5 && args.getString("q") == "in:inbox is:unread") { "Execution arguments changed" }
                        require(selected !in executedAccounts) { "A read was unexpectedly replayed" }
                        executedAccounts += selected
                        if (firstReadDenied && selected == ACCOUNT_A)
                            response(JSONObject().put("error", "Fixture first-account read permission denied"), 403)
                        else response(JSONObject().put("successful", true).put("error", JSONObject.NULL)
                            .put("data", JSONObject().put("messages", JSONArray().put(JSONObject()
                                .put("id", "message-$selected").put("subject", if (selected == ACCOUNT_A) RESULT_A else RESULT_B)))))
                    }
                    else -> error("Unexpected service route ${url.encodedPath}")
                }
            }
        } catch (failure: Exception) {
            violations += failure.message ?: failure.javaClass.simpleName
            response(JSONObject().put("error", "Local fixture assertion failed"), 400)
        }

        private fun inference(request: RecordedRequest): MockResponse {
            require(request.method == "POST" && request.getHeader("Authorization") == "Bearer $INFERENCE_KEY") { "Wrong model request" }
            val messages = JSONObject(request.body.readUtf8()).getJSONArray("messages")
            val prompt = messages.toString()
            require(!prompt.contains(COMPOSIO_KEY) && !prompt.contains(INFERENCE_KEY) && !prompt.contains(SECRET_DECOY)) { "A secret reached inference" }
            modelPrompts += prompt
            val step = modelPrompts.size
            val answer = if (accountsDenied) {
                require(step == 1 && prompt.contains("ACCOUNTS_UNAVAILABLE") && prompt.contains("connection_status")) { "Unavailable account status missing from ordinary chat" }
                val system = messages.getJSONObject(0).getString("content")
                require(system.substringAfter("<available_accounts>").substringBefore("</available_accounts>").trim() == "[]") { "Unavailable accounts must not become usable model context" }
                JSONObject().put("type", "reply").put("text", CHAT_WITHOUT_CONNECTIONS)
            } else if (firstReadDenied) when (step) {
                1 -> find(ACCOUNT_A)
                2 -> {
                    require(prompt.contains("TOOLS_FOUND") && prompt.contains(FETCH)) { "First discovery failed" }
                    find(ACCOUNT_B)
                }
                3 -> {
                    require(prompt.contains(ACCOUNT_A) && prompt.contains(ACCOUNT_B) && prompt.contains("max_results")) { "Both account tools must be discovered first" }
                    action(ACCOUNT_A)
                }
                4 -> {
                    require(prompt.contains("phone_tool_execution") && prompt.contains("FAILED") && prompt.contains("403") && executedAccounts == listOf(ACCOUNT_A)) { "First read failure was not reported accurately" }
                    action(ACCOUNT_B) // No rediscovery: a failure on A must not discard B's metadata.
                }
                5 -> {
                    require(prompt.contains(RESULT_B) && !prompt.contains(RESULT_A) && executedAccounts == listOf(ACCOUNT_A, ACCOUNT_B)) { "Partial summary requires the second account's actual result" }
                    JSONObject().put("type", "reply").put("text", PARTIAL_SUMMARY)
                }
                else -> error("Unexpected model step $step")
            } else if (denied) when (step) {
                1 -> find(ACCOUNT_A)
                2 -> {
                    require(prompt.contains("DISCOVERY_FAILED") && prompt.contains("CATALOG_ACCESS_DENIED") && prompt.contains("403")) { "Denied catalog was not represented accurately" }
                    JSONObject().put("type", "reply").put("text", DENIED)
                }
                else -> error("Unexpected model step $step")
            } else when (step) {
                1 -> {
                    require(prompt.contains(EMAIL_A) && prompt.contains(EMAIL_B)) { "Verified account emails missing from model context" }
                    find(ACCOUNT_A)
                }
                2, 5 -> {
                    require(prompt.contains(FETCH) && prompt.contains("TOOLS_FOUND")) { "Fetch tool missing from discovery" }
                    JSONObject().put("type", "inspect_tool").put("accountId", if (step == 2) ACCOUNT_A else ACCOUNT_B).put("tool", FETCH)
                }
                3, 6 -> {
                    require(prompt.contains("phone_tool_schema") && prompt.contains("max_results")) { "Selected tool schema was not inspected" }
                    action(if (step == 3) ACCOUNT_A else ACCOUNT_B)
                }
                4 -> {
                    require(prompt.contains(RESULT_A) && executedAccounts == listOf(ACCOUNT_A)) { "First inbox was not actually read" }
                    find(ACCOUNT_B)
                }
                7 -> {
                    require(prompt.contains(RESULT_A) && prompt.contains(RESULT_B) && executedAccounts == listOf(ACCOUNT_A, ACCOUNT_B)) { "Both actual read results are required" }
                    JSONObject().put("type", "reply").put("text", SUMMARY)
                }
                else -> error("Unexpected model step $step")
            }
            val content = answer.toString()
            fun frame(text: String, finish: Any = JSONObject.NULL) = "data: " + JSONObject().put("choices", JSONArray()
                .put(JSONObject().put("index", 0).put("delta", JSONObject().put("content", text)).put("finish_reason", finish))) + "\n\n"
            val middle = content.length / 2
            return MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody(frame(content.take(middle)) + frame(content.drop(middle)) + frame("", "stop") + "data: [DONE]\n\n")
        }

        private fun find(account: String) = JSONObject().put("type", "find_tools").put("accountId", account)
            .put("search", "check this Gmail inbox for important unread email messages")
        private fun action(account: String) = JSONObject().put("type", "action").put("accountId", account).put("tool", FETCH)
            .put("arguments", JSONObject().put("user_id", "me").put("max_results", 5).put("q", "in:inbox is:unread"))
            .put("explanation", "Read up to five unread inbox messages.")
        private fun account(id: String) = JSONObject().put("id", id).put("user_id", EXACT_USER).put("status", "ACTIVE")
            .put("toolkit", JSONObject().put("slug", "gmail")).put("alias", if (id == ACCOUNT_A) "gmail_balboa-slid" else "gmail_astral-bab")
            .put("state", JSONObject().put("val", JSONObject().put("displayName", if (id == ACCOUNT_A) EMAIL_A else EMAIL_B)
                .put("access_token", SECRET_DECOY).put("refresh_token", SECRET_DECOY)))
        private fun fetchTool() = JSONObject().put("slug", FETCH).put("name", "Fetch emails")
            .put("description", "Read unread email messages from the selected inbox")
            .put("toolkit", JSONObject().put("slug", "gmail")).put("version", VERSION)
            .put("annotations", JSONObject().put("readOnlyHint", true))
            .put("input_parameters", JSONObject().put("type", "object").put("additionalProperties", false)
                .put("properties", JSONObject().put("user_id", JSONObject().put("type", "string"))
                    .put("q", JSONObject().put("type", "string"))
                    .put("max_results", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 5)))
                .put("required", JSONArray().put("user_id").put("q").put("max_results")))
        private fun page(items: List<JSONObject>, cursor: String? = null) = JSONObject().put("items", JSONArray(items))
            .put("next_cursor", cursor ?: JSONObject.NULL)
        private fun response(json: JSONObject, code: Int = 200) = MockResponse().setResponseCode(code)
            .setHeader("Content-Type", "application/json").setBody(json.toString())
    }

    private fun connected() = model.ui.value.connections.filter { it.status == KaiConnectionStatus.CONNECTED }
    private fun sendAndAwait(text: String) {
        val count = model.ui.value.messages.size
        compose.onNode(hasSetTextAction()).performTextInput(text)
        compose.onNodeWithContentDescription("Send message").assertIsEnabled().performClick()
        waitUi { (!it.isBusy && it.messages.size >= count + 2) || it.error != null }
        assertNull("Fixture agent request must finish without an app error", model.ui.value.error)
    }
    private fun waitUi(predicate: (KaiUiState) -> Boolean) = compose.waitUntil(180_000) { predicate(model.ui.value) }
    private fun waitText(text: String) = compose.waitUntil(180_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun scrollToText(text: String) = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
        .performScrollToNode(hasText(text))
    private fun readState(): KaiState = runBlocking { val store = KaiStore(context); try { store.load(); store.state.value } finally { store.close() } }
    private fun outputDirectory() = File(context.getExternalFilesDir(null), "qa-gmail-021").apply { mkdirs() }
    private fun evidence(name: String, data: JSONObject) = File(outputDirectory(), name).writeText(data.toString(2))
    private fun screenshot(name: String) {
        runCatching { compose.waitForIdle(); InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            File(outputDirectory(), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        } }
    }
    private fun profileDirectory() = File(context.noBackupFilesDir, "kai-state")
    private fun backupDirectory() = File(context.noBackupFilesDir, "kai-gmail-ui-021-backup")
    private fun preserveProfile() {
        check(!backupDirectory().exists()) { "A prior Gmail test backup needs recovery before this fixture can run." }
        check(backupDirectory().mkdirs())
        if (profileDirectory().exists()) profileDirectory().copyRecursively(File(backupDirectory(), "profile"))
        else File(backupDirectory(), "profile.absent").writeText("absent")
        check(!profileDirectory().exists() || profileDirectory().deleteRecursively())
    }
    private fun restoreProfile() {
        if (!backupDirectory().exists()) return
        check(!profileDirectory().exists() || profileDirectory().deleteRecursively())
        File(backupDirectory(), "profile").takeIf { it.exists() }?.copyRecursively(profileDirectory(), overwrite = true)
        check(backupDirectory().deleteRecursively())
    }
    private companion object {
        const val COMPOSIO_KEY = "gmail-ui-local-project-fixture-only"
        const val INFERENCE_KEY = "gmail-ui-local-inference-fixture-only"
        const val SECRET_DECOY = "provider-credential-must-not-escape-fixture"
        const val EXACT_USER = "kai:gmail-fixture-existing-user"
        const val ACCOUNT_A = "ca_gmail_fixture_a"
        const val ACCOUNT_B = "ca_gmail_fixture_b"
        const val EMAIL_A = "primary@example.com"
        const val EMAIL_B = "secondary@example.com"
        const val FETCH = "GMAIL_FETCH_EMAILS"
        const val VERSION = "20261116_00"
        const val RESULT_A = "PRIMARY_INBOX_FIXTURE: School notice needs a reply"
        const val RESULT_B = "SECONDARY_INBOX_FIXTURE: Appointment changed to Tuesday"
        const val SUMMARY = "I checked both inboxes. primary@example.com has a school notice needing a reply; secondary@example.com has an appointment changed to Tuesday."
        const val DENIED = "The Composio project key lacks Tools Read access (403), so I could not discover an email action. Both connected accounts are still saved. Check the project key's Tools Read permission."
        const val PARTIAL_SUMMARY = "primary@example.com denied the read (403), so I could not check it. secondary@example.com was checked and has an appointment changed to Tuesday."
        const val CHAT_WITHOUT_CONNECTIONS = "Yes. Tell me about your idea, and we can think it through together."
    }
}
