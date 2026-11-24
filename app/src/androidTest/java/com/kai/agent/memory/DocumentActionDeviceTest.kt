package com.kai.agent.memory

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kai.agent.KaiViewModel
import com.kai.agent.account.CreditAccount
import com.kai.agent.account.CreditAccountClient
import com.kai.agent.connections.*
import com.kai.agent.data.*
import com.kai.agent.inference.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Real controller + SQLCipher + HTTP inference + direct client. Service/model replies are fixtures. */
@RunWith(AndroidJUnit4::class)
class DocumentActionDeviceTest {
    @Test fun freshAndroidClientsDiscoverLargerContextWithoutOpeningSettings() { runBlocking {
        val server = MockWebServer()
        try {
            server.start()
            val config = ProviderConfig(ProviderId.KOINOS, server.url("/v1").toString(), "context-fixture", "local")
            val messages = listOf(InferenceMessage("system", "Preserve receipts."), InferenceMessage("user", "x".repeat(18000)),
                InferenceMessage("user", "Phone receipt: UNKNOWN. Never replay this action."))
            repeat(2) {
                server.enqueue(MockResponse().setBody("""{"data":[{"id":"local","context_window":16384,"context_mode":"auto"}]}"""))
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}"""))
                assertEquals("ok", InferenceClient().stream(config, messages) {}.text)
                assertEquals("/v1/models", server.takeRequest().path)
                val body = JSONObject(server.takeRequest().body.readUtf8())
                assertTrue(body.getInt("context_tokens") in 4097..16384)
                assertEquals(18000, body.getJSONArray("messages").getJSONObject(1).getString("content").length)
                assertEquals(messages.last().content, body.getJSONArray("messages").getJSONObject(2).getString("content"))
            }
        } finally { server.shutdown() }
    } }

    private class App(base: Context) : Application() {
        val directory = File(base.noBackupFilesDir, "document-flow-${UUID.randomUUID()}").apply { mkdirs() }
        init { attachBaseContext(base) }
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = directory
    }
    private val toolId = "GOOGLEDOCS_CREATE_DOCUMENT"
    private val schema = """{"type":"object","properties":{"title":{"type":"string"},"text":{"type":"string"}},"required":["title","text"],"additionalProperties":false}"""
    private fun tool() = JSONObject().put("slug", toolId).put("name", "Create a document")
        .put("description", "Create a Google Docs document with title and initial text.")
        .put("toolkit", JSONObject().put("slug", "googledocs")).put("version", "fixture-v1")
        .put("annotations", JSONObject().put("readOnlyHint", false)).put("input_parameters", JSONObject(schema))
    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    private suspend fun await(model: KaiViewModel, condition: () -> Boolean) = withTimeout(120_000) {
        while (!condition()) { check(model.ui.value.error == null) { model.ui.value.error.orEmpty() }; delay(100) }
    }

    @Test fun suppliedDocumentSurvivesWebDetourAndPopulatedLedgerThroughApprovalAndReceipt() = documentFlow()
    @Test fun malformedProposalAndPostSubmitReplyRecoverWithoutDuplicateWrites() = documentFlow(recover = true)
    @Test fun repeatedMalformedResponsesStopWithoutApprovalOrExecution() = documentFlow(alwaysInvalid = true)
    @Test fun skippedDiscoveryIsRecoveredBeforeReviewAndOneApprovedWrite() = documentFlow(toolRecovery = true)
    @Test fun repeatedUndiscoveredActionsStopWithoutReviewOrExecution() = documentFlow(alwaysUndiscovered = true)
    @Test fun prematureConfirmationIsCorrectedIntoConcreteReviewBeforeExecution() = documentFlow(confirmation = true)

    @Test fun savedTaskRecoversDiscoveryAndCannotReplayUnknownSubmission() = savedTaskFlow()
    @Test fun savedTaskCorrectsPrematureConfirmationBeforeReviewAndPreservesUnknown() = savedTaskFlow(confirmation = true)

    private fun savedTaskFlow(confirmation: Boolean = false) = runBlocking {
        val app = App(InstrumentationRegistry.getInstrumentation().targetContext)
        val store = KaiStore(app)
        var catalogReads = 0
        var turns = 0
        val transport = object : DirectComposioTransport {
            override suspend fun request(path: String, apiKey: String, method: String, query: Map<String,String>, body: JSONObject?): JSONObject = when(path) {
                "/connected_accounts" -> JSONObject("""{"items":[{"id":"ca_fixture_1","user_id":"fixture-owner","toolkit":{"slug":"googledocs"},"status":"ACTIVE","is_disabled":false}]}""")
                "/tools" -> { catalogReads++; JSONObject().put("items", JSONArray().put(tool())) }
                "/tools/$toolId" -> tool()
                else -> error("Unexpected request; no action should execute: $path")
            }
        }
        val engine = com.kai.agent.tasks.TaskAgentEngine(store, DirectComposioClient(transport), compactContext={false}) { _, messages ->
            turns++
            val response = if(messages.first().content.startsWith("Plan the user's task"))
                """{"steps":[{"title":"Create document","instructions":"Create a Google doc titled test containing test doc"}]}"""
            else if(confirmation && turns == 2) "I am ready to create the document. Please confirm before I prepare it."
            else {
                if(confirmation && turns == 3) assertTrue(messages.last().content.contains("PREPARE"))
                if(turns == if(confirmation) 4 else 3) assertTrue(messages.last().content.contains("SCHEMA_READY"))
                """{"type":"action","accountId":"ca_fixture_1","tool":"GOOGLEDOCS_CREATE_DOCUMENT","arguments":{"title":"test","text":"test doc"}}"""
            }
            Completion(response, "stop")
        }
        try {
            store.load()
            store.updateSettings { it.copy(selectedProvider=Provider.KOINOS,
                creditAccount=CreditAccount(token="fixture-key",id="fixture-account",model="fixture-model",contextTokens=16384,maxOutput=4096),
                memoryMode=MemoryMode.OFF,composioApiKey="fixture-composio-key",composioUserId="fixture-owner") }
            val run = store.createRun("Document", "Create a Google doc titled test containing test doc")
            val waiting = requireNotNull(engine.run(run.id, "first-lease"))
            assertEquals(RunStatus.NEEDS_APPROVAL, waiting.status)
            assertEquals(1, catalogReads); assertEquals(if(confirmation) 4 else 3, turns)
            val task = store.state.value.tasks.single { it.runId == run.id }
            val intent = requireNotNull(task.intent)
            assertEquals(TaskStatus.AWAITING_APPROVAL, task.status)
            assertEquals("test doc", JSONObject(intent.argumentsJson).getString("text"))
            // Simulate a submission whose response was lost; resuming may never replay it.
            store.approveTask(task.id); store.transitionTask(task.id, TaskStatus.RUNNING)
            store.recordReceipt(task.id, ActionReceipt(intent.id, status="UNKNOWN", summary="Fixture response lost"), TaskStatus.UNKNOWN)
            store.setRunStatus(run.id, RunStatus.QUEUED)
            assertEquals(RunStatus.BLOCKED, engine.run(run.id, "second-lease")!!.status)
            assertEquals(if(confirmation) 4 else 3, turns); assertEquals(1, catalogReads)
        } finally { store.close(); app.directory.deleteRecursively() }
    }

    private fun documentFlow(recover: Boolean = false, alwaysInvalid: Boolean = false, toolRecovery: Boolean = false,
        alwaysUndiscovered: Boolean = false, confirmation: Boolean = false) = runBlocking {
        val app = App(InstrumentationRegistry.getInstrumentation().targetContext)
        val owner = ViewModelStore()
        val writes = AtomicInteger()
        val providerTurns = AtomicInteger()
        val catalogReads = AtomicInteger()
        val wireBodies = java.util.Collections.synchronizedList(mutableListOf<String>())
        val transport = object : DirectComposioTransport {
            override suspend fun request(path: String, apiKey: String, method: String, query: Map<String,String>, body: JSONObject?): JSONObject = when {
                path == "/toolkits" || path == "/toolkits/categories" -> JSONObject().put("items", JSONArray())
                path == "/connected_accounts" -> JSONObject().put("items", JSONArray((1..8).filter {
                    query["connected_account_ids"] == null || query["connected_account_ids"] == "ca_fixture_$it"
                }.map { i -> JSONObject().put("id", "ca_fixture_$i").put("user_id", "fixture-owner")
                    .put("toolkit", JSONObject().put("slug", if(i == 1) "googledocs" else "fixture$i"))
                    .put("status", "ACTIVE").put("alias", "random_alias_$i").put("is_disabled", false)
                    .put("state", JSONObject().put("val", JSONObject().put("displayName", "Fixture Account $i"))) }))
                path == "/tools" -> { catalogReads.incrementAndGet(); JSONObject().put("items", JSONArray().put(tool())) }
                path == "/tools/$toolId" -> tool()
                path == "/tools/execute/$toolId" -> {
                    assertEquals("ca_fixture_1", body!!.getString("connected_account_id"))
                    val args = body.getJSONObject("arguments")
                    assertEquals("test", args.getString("title")); assertEquals("test doc", args.getString("text"))
                    writes.incrementAndGet()
                    JSONObject("""{"successful":true,"data":{"documentId":"fixture-created","title":"test","url":"https://docs.google.com/document/d/fixture-created/edit"}}""")
                }
                else -> error("Unexpected connection request: $path")
            }
        }
        val server = MockWebServer().apply { dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/platform/api/overview") return MockResponse().setBody("""{"account":{"id":"fixture-account","wallets":[]},"credits":{"free":{"available":"100","state":"AVAILABLE"},"purchased":{"available":"0"}},"models":[{"model":"llama31-8b","available":true,"freeEligible":true,"contextTokens":16384,"maxOutput":4096,"inputCreditsPerMillion":"0","outputCreditsPerMillion":"0"}]}""")
                if (request.path == "/v1/models") return MockResponse().setBody("""{"data":[{"id":"llama31-8b","context_window":16384,"context_mode":"auto"}]}""")
                val body = request.body.readUtf8(); wireBodies += body
                val requestIndex = providerTurns.getAndIncrement()
                // Reject before discovery, after schema inspection, and after the approved write.
                val rejectedIndices = if (recover) setOf(0, 4, 6) else emptySet()
                val malformed = alwaysInvalid || requestIndex in rejectedIndices
                val turn = requestIndex - rejectedIndices.count { it < requestIndex } - if(confirmation && requestIndex > 3) 1 else 0
                val reply = if (alwaysUndiscovered) """{"type":"action","accountId":"ca_fixture_1","tool":"INVENTED_DOCUMENT_ACTION","arguments":{"title":"discard-this"}}"""
                else if (toolRecovery) when (requestIndex) {
                    0 -> """{"type":"action","accountId":"googledocs","tool":"CREATE_GOOGLE_DOC","arguments":{"title":"discard-this"}}"""
                    1 -> """{"type":"action","accountId":"ca_fixture_1","tool":"CREATE_GOOGLE_DOC","arguments":{"title":"discard-this"}}"""
                    2 -> """{"type":"inspect_tool","accountId":"ca_fixture_1","tool":"GOOGLEDOCS_CREATE_DOCUMENT"}"""
                    3 -> """{"type":"action","accountId":"ca_fixture_1","tool":"GOOGLEDOCS_CREATE_DOCUMENT","arguments":{"name":"discard-this"}}"""
                    4 -> """{"type":"action","accountId":"ca_fixture_1","tool":"GOOGLEDOCS_CREATE_DOCUMENT","arguments":{"title":"test","text":"test doc"}}"""
                    else -> """{"type":"reply","text":"The request was accepted. https://docs.google.com/document/d/fixture-created/edit The saved contents have not been independently verified."}"""
                }
                else if (confirmation && requestIndex == 3) "I am ready to create the document. Please confirm before I prepare it."
                else if (malformed) """{"type":"action","accountId":"do-not-reuse-this-fragment","arguments":{"title":"unfinished"}""" else when(turn) {
                    0 -> """{"type":"web_search","query":"Google docs create document API"}"""
                    1 -> """{"type":"find_tools","accountId":"ca_fixture_1","search":"create document"}"""
                    2 -> """{"type":"inspect_tool","accountId":"ca_fixture_1","tool":"GOOGLEDOCS_CREATE_DOCUMENT"}"""
                    3 -> """{"type":"action","accountId":"ca_fixture_1","tool":"GOOGLEDOCS_CREATE_DOCUMENT","arguments":{"title":"test","text":"test doc"},"explanation":"Create the document using the supplied text"}"""
                    else -> """{"type":"reply","text":"The request was accepted. https://docs.google.com/document/d/fixture-created/edit The saved contents have not been independently verified."}"""
                }
                return MockResponse().setHeader("Content-Type", "application/json").setHeader("x-kai-context-size", "8192").setBody(JSONObject().put("choices", JSONArray().put(
                    JSONObject().put("message", JSONObject().put("content", reply)).put("finish_reason", "stop"))).toString())
            }
        } }
        lateinit var model: KaiViewModel
        var initialized = false
        try {
            server.start()
            val seed = KaiStore(app)
            try {
                seed.load()
                seed.updateSettings { it.copy(selectedProvider = Provider.KOINOS, selectedCharacterId = "nova",
                    creditAccount = CreditAccount(token="fixture-provider-key",id="fixture-account",model="llama31-8b",contextTokens=16384,maxOutput=4096),
                    composioApiKey = "fixture-composio-key", composioUserId = "fixture-owner", connectionGeneration = "fixture-generation") }
                for(i in 1..8) seed.remember("Personal preference $i " + "with context ".repeat(15))
                val chat = seed.createConversation("nova")
                for(i in 1..6) {
                    val task = seed.createTask("Earlier document $i", "Earlier task $i", chat.id)
                    val intent = ActionIntent(toolName=toolId, accountId="ca_fixture_1", argumentsJson=JSONObject().put("title","earlier-$i").put("text","Earlier supplied text ".repeat(15)).toString(),
                        connectionGeneration="fixture-generation", ownerId="fixture-owner", toolVersion="fixture-v1", toolSchemaJson=schema)
                    seed.prepareTaskIntent(task.id, intent); seed.approveTask(task.id)
                    seed.transitionTask(task.id, TaskStatus.RUNNING)
                    val status = if(i == 6) TaskStatus.UNKNOWN else TaskStatus.ACCEPTED
                    seed.recordReceipt(task.id, ActionReceipt(intent.id, "earlier-$i", status.name,
                        "Earlier action result detail ".repeat(12), "https://docs.google.com/document/d/earlier-$i/edit"), status)
                }
            } finally { seed.close() }
            // Exercise the current managed-account wire entirely against the local fixture.
            val fixtureHttp = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
                check(chain.request().url.host == "credits.koinosai.com")
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.build()
            main {
                model = ViewModelProvider(owner, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = KaiViewModel(app, DirectComposioClient(transport),
                        inference=InferenceClient(fixtureHttp),creditClient=CreditAccountClient(server.url("/").toString().trimEnd('/'))) as T
                })[KaiViewModel::class.java]
            }
            initialized = true
            await(model) { model.ui.value.currentConversationId != null && model.ui.value.providerConfig.modelId == "llama31-8b" }
            val text = "make a Google doc called test and put test doc in the page content and send me the link"
            main { model.sendMessage(text) }
            if (alwaysInvalid || alwaysUndiscovered) {
                withTimeout(120_000) { while (model.ui.value.error == null) delay(100) }
                assertEquals(if(alwaysUndiscovered) 4 else 3, providerTurns.get()); assertEquals(0, writes.get())
                assertNull(model.ui.value.pendingApproval)
                assertTrue(model.ui.value.error!!.contains(if(alwaysUndiscovered) "This proposal did not execute" else "This response did not execute an action"))
                if(alwaysUndiscovered) assertEquals(1, catalogReads.get())
                return@runBlocking
            }
            await(model) { !model.ui.value.isBusy && model.ui.value.pendingApproval != null }
            assertEquals(0, writes.get())
            val approval = requireNotNull(model.ui.value.pendingApproval)
            main { model.approveAction(approval.id) }
            await(model) { writes.get() == 1 && !model.ui.value.isBusy && model.ui.value.messages.last().text.contains("fixture-created/edit") }
            assertEquals(1, writes.get()); assertEquals(if(toolRecovery || confirmation) 6 else if (recover) 8 else 5, providerTurns.get())
            assertTrue(model.ui.value.providerStatus.contains("8,192"))
            assertEquals(8, model.ui.value.memories.size)
            KaiStores.acquire(app).use { lease ->
                val report=JSONObject(lease.store.exportChatReport(requireNotNull(model.ui.value.currentConversationId)))
                assertEquals("kai-chat-report",report.getString("format"))
                val trace=report.getJSONArray("trace")
                val events=(0 until trace.length()).map { trace.getJSONObject(it) }
                assertTrue(events.any { it.optString("event")=="model_request" })
                assertTrue(events.any { it.optString("event")=="model_response" && it.getJSONObject("data").optInt("activeContextTokens")==8192 })
                assertTrue(events.any { it.optString("event")=="user_approval_requested" && it.getJSONObject("data").getString("taskId")==approval.id })
                assertTrue(events.any { it.optString("event")=="task_state" && it.getJSONObject("data").optString("status")=="RUNNING" })
                assertFalse(report.toString().contains("fixture-ai-secret"))
            }
            wireBodies.forEach { raw ->
                val wire = JSONObject(raw); val a = wire.getJSONArray("messages")
                val messages = (0 until a.length()).map { InferenceMessage(a.getJSONObject(it).getString("role"), a.getJSONObject(it).getString("content")) }
                assertTrue(messages.any { it.content == text })
                assertTrue(messages.first().content.contains("UNKNOWN"))
                assertTrue(InferenceBudget.estimate(messages) + wire.getInt("max_tokens") + 512 <= 16384)
                assertFalse(raw.contains("do-not-reuse-this-fragment"))
                if(toolRecovery) assertFalse(raw.contains("discard-this"))
                println("Full document controller: prompt=${InferenceBudget.estimate(messages)}, output=${wire.getInt("max_tokens")}")
            }
            if(toolRecovery) {
                assertTrue(wireBodies[1].contains("ACCOUNT_NOT_AVAILABLE"))
                assertTrue(wireBodies[2].contains("TOOLS_FOUND"))
                assertTrue(wireBodies[4].contains("did not match the discovered schema"))
                assertTrue(wireBodies[4].contains("SCHEMA_READY"))
                assertEquals(1, catalogReads.get())
            } else {
                assertTrue(wireBodies[if (recover) 2 else 1].contains("No public web request was executed"))
                assertTrue(wireBodies[if (recover) 5 else 3].contains("SCHEMA_READY"))
            }
            assertTrue(wireBodies.last().contains("fixture-created"))
            if (confirmation) assertTrue(wireBodies[4].contains("PREPARE"))
            if (recover) assertTrue(wireBodies.last().contains("Phone format check"))
        } finally {
            main { owner.clear() }
            if(initialized) withTimeout(30_000) { model.shutdownComplete.await() }
            server.shutdown(); app.directory.deleteRecursively()
        }
    }
}
