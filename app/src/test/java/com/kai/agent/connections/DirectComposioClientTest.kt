package com.kai.agent.connections

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DirectComposioClientTest {
    private data class Request(val path: String, val method: String, val query: Map<String, String>, val body: JSONObject?)
    private class Fake : DirectComposioTransport {
        val requests = mutableListOf<Request>()
        var user = "my-own-user"
        var omitOwner = false
        var secondPage = false
        var repeatCursor = false
        var disabled = false
        var execution = JSONObject("""{"successful":true,"data":{"id":"message-123"}}""")
        var failExecution = false
        var onAccounts: (() -> Unit)? = null
        var hostedUrl = "https://connect.composio.dev/link/session-123"
        var existingConfig = true
        var managedSupported = true
        var configOnSecondPage = false
        var categoriesFail = false
        val deniedPaths = mutableMapOf<String, Int>()
        val tool = JSONObject("""{"slug":"GMAIL_SEND_EMAIL","name":"Send email","toolkit":{"slug":"gmail"},"version":"20261101_00","input_parameters":{"to":{"type":"string","required":true},"body":{"type":"string","required":true}},"tags":[]}""")
        override suspend fun request(path: String, apiKey: String, method: String, query: Map<String, String>, body: JSONObject?): JSONObject {
            requests += Request(path, method, query.toMap(), body?.let { JSONObject(it.toString()) })
            deniedPaths[path]?.let { throw ConnectionServiceException("Unsafe remote error fixture-secret", statusCode = it) }
            return when (path) {
                "/connected_accounts" -> {
                    onAccounts?.invoke()
                    val id = if (query["cursor"] == "page2" && query["connected_account_ids"] == null) "acct2" else "acct1"
                    val account = JSONObject().put("id", id).put("toolkit", JSONObject().put("slug", "gmail"))
                        .put("status", "ACTIVE").put("is_disabled", disabled)
                        .put("state", JSONObject().put("val", JSONObject().put("access_token", "provider-secret")))
                    if (!omitOwner) account.put("user_id", user)
                    JSONObject().put("items", JSONArray().put(account)).apply {
                        if (query["connected_account_ids"] == null && secondPage && (query["cursor"] == null || repeatCursor)) put("next_cursor", "page2")
                    }
                }
                "/toolkits" -> JSONObject("""{"items":[{"slug":"gmail","name":"Gmail","meta":{"description":"Mail","categories":[{"id":"email","name":"Email"}]} }],"total_items":100,"next_cursor":"catalog-page2"}""")
                "/toolkits/categories" -> if (categoriesFail) throw ConnectionServiceException("Categories unavailable") else JSONObject("""{"items":[{"id":"email","name":"Email"}]}""")
                "/toolkits/gmail" -> JSONObject().put("slug", "gmail").put("auth_schemes", JSONArray().put("OAUTH2"))
                    .put("composio_managed_auth_schemes", JSONArray().apply { if (managedSupported) put("OAUTH2") })
                "/auth_configs" -> if (method == "POST") JSONObject("""{"auth_config":{"id":"ac_new"}}""") else
                    JSONObject().put("items", JSONArray().apply { if (existingConfig && (!configOnSecondPage || query["cursor"] == "config2"))
                        put(JSONObject("""{"id":"ac_existing","toolkit":{"slug":"gmail"},"status":"ENABLED","is_composio_managed":true}""")) })
                        .apply { if (configOnSecondPage && query["cursor"] == null) put("next_cursor", "config2") }
                "/connected_accounts/link" -> JSONObject().put("redirect_url", hostedUrl).put("connected_account_id", "acct_new").put("link_token", "private-link-token")
                "/tools" -> JSONObject().put("items", JSONArray().put(tool)).put("next_cursor", "tools-page2")
                "/tools/GMAIL_SEND_EMAIL" -> JSONObject(tool.toString())
                "/tools/execute/GMAIL_SEND_EMAIL" -> {
                    if (failExecution) throw ConnectionServiceException("Connection interrupted", outcomeMayBeUnknown = true)
                    JSONObject(execution.toString())
                }
                "/connected_accounts/acct1" -> JSONObject().put("success", true)
                else -> error("Unexpected request $method $path")
            }
        }
        val executes get() = requests.count { it.path == "/tools/execute/GMAIL_SEND_EMAIL" }
    }

    @Test fun exactUserOwnershipPaginationAndSecretStripping() = runBlocking<Unit> {
        val fake = Fake().apply { secondPage = true }
        val client = DirectComposioClient(fake)
        client.useCredentials(ComposioCredentials("fixture-secret", "my-own-user"))
        val snapshot = client.refresh()
        assertEquals("my-own-user", snapshot.ownerId)
        assertEquals(listOf("acct1", "acct2"), snapshot.accounts.map { it.id })
        assertTrue(fake.requests.all { it.query["user_ids"] == "my-own-user" && it.query["limit"] == "50" })
        assertFalse(snapshot.toString().contains("provider-secret"))
        assertFalse(snapshot.generation.contains("fixture-secret"))
        assertTrue(snapshot.generation.startsWith("composio-v1:"))
        fake.user = "kai:my-own-user"
        expect<ConnectionServiceException> { client.refresh() }
    }

    @Test fun missingDeprecatedOwnerUsesFilteredListingButExplicitMismatchFails() = runBlocking<Unit> {
        val fake = Fake().apply { omitOwner = true }
        val client = ready(fake)
        val tool = client.tool("GMAIL_SEND_EMAIL", "latest")
        client.execute("acct1", tool, ARGS, client.approveAction("acct1", tool, ARGS))
        val check = fake.requests.last { it.path == "/connected_accounts" }
        assertEquals("my-own-user", check.query["user_ids"])
        assertEquals("acct1", check.query["connected_account_ids"])
        fake.omitOwner = false
        fake.user = "other-user"
        expect<ConnectionServiceException> { client.execute("acct1", tool, ARGS, client.approveAction("acct1", tool, ARGS)) }
        assertEquals(1, fake.executes)
    }

    @Test fun pagedCatalogAndToolsUseDocumentedFiltersAndNormalizeSchemas() = runBlocking<Unit> {
        val fake = Fake()
        val client = ready(fake)
        val catalog = client.catalog("mail", "page+/=", "email")
        assertEquals("catalog-page2", catalog.nextCursor)
        assertEquals("Email", catalog.categories.single().name)
        val query = fake.requests.first { it.path == "/toolkits" }.query
        assertEquals("24", query["limit"])
        assertEquals("mail", query["search"])
        assertEquals("page+/=", query["cursor"])
        assertEquals("my-own-user", query["user_id"])
        val tools = client.tools("acct1", "send email", "tools2")
        val toolQuery = fake.requests.last().query
        assertEquals("20", toolQuery["limit"])
        assertEquals("send email", toolQuery["query"])
        assertFalse(toolQuery.containsKey("search"))
        val schema = JSONObject(tools.items.single().schemaJson)
        assertEquals(2, schema.getJSONArray("required").length())
        assertFalse(tools.items.single().readOnly)
        expect<ConnectionServiceException> { client.approveAction("acct1", tools.items.single(), "{}") }
    }

    @Test fun consentUsesHostedLinkAndExistingConfigWithoutCreatingDuplicate() = runBlocking<Unit> {
        val fake = Fake()
        val client = ready(fake)
        val link = client.connect("gmail")
        assertEquals("acct_new", link.accountId)
        assertFalse(link.toString().contains("private-link-token"))
        val request = fake.requests.last()
        assertEquals("/connected_accounts/link", request.path)
        assertEquals("my-own-user", request.body?.getString("user_id"))
        assertEquals("ac_existing", request.body?.getString("auth_config_id"))
        assertFalse(fake.requests.any { it.path == "/auth_configs" && it.method == "POST" })
        fake.hostedUrl = "https://connect.composio.dev.attacker.test/"
        expect<ConnectionServiceException> { client.connect("gmail") }
        fake.hostedUrl = "https://connect.composio.dev/link/new"
        fake.existingConfig = false
        client.connect("gmail")
        assertEquals("use_composio_managed_auth", fake.requests.first { it.path == "/auth_configs" && it.method == "POST" }
            .body!!.getJSONObject("auth_config").getString("type"))
    }

    @Test fun approvalsBindSchemaArgumentsIdentityAndAreSingleUseEvenOnUnknownResult() = runBlocking<Unit> {
        val fake = Fake()
        val client = ready(fake)
        val tool = client.tool("GMAIL_SEND_EMAIL", "latest")
        val approval = client.approveAction("acct1", tool, ARGS)
        expect<ConnectionServiceException> { client.execute("acct1", tool, """{"to":"other@example.com","body":"Hello"}""", approval) }
        fake.tool.getJSONObject("input_parameters").getJSONObject("body").put("description", "Changed semantics")
        expect<ConnectionServiceException> { client.execute("acct1", tool, ARGS, approval) }
        fake.tool.getJSONObject("input_parameters").getJSONObject("body").remove("description")
        fake.failExecution = true
        val failure = expect<ConnectionServiceException> { client.execute("acct1", tool, ARGS, approval) }
        assertTrue(failure.outcomeMayBeUnknown)
        expect<ConnectionServiceException> { client.execute("acct1", tool, ARGS, approval) }
        assertEquals(1, fake.executes)
    }

    @Test fun schemaOptionalMailContentStillFailsBeforeApprovalOrDispatch() = runBlocking<Unit> {
        val fake=Fake()
        fake.tool.getJSONObject("input_parameters").getJSONObject("body").put("required",false)
        val client=ready(fake);val tool=client.tool("GMAIL_SEND_EMAIL","latest")
        val empty="""{"to":"recipient@example.test"}"""
        expect<ConnectionServiceException> { client.approveAction("acct1",tool,empty) }
        assertEquals(0,fake.executes)
        val approved=client.approveAction("acct1",tool,ARGS)
        expect<ConnectionServiceException> { client.execute("acct1",tool,empty,approved) }
        assertEquals(0,fake.executes)
        client.execute("acct1",tool,ARGS,approved)
        assertEquals(1,fake.executes)
    }

    @Test fun optionalCategoriesCannotBreakCatalogAndCustomOAuthDoesNotInventCredentials() = runBlocking<Unit> {
        val fake = Fake().apply { categoriesFail = true; existingConfig = false; managedSupported = false }
        val client = ready(fake)
        val catalog = client.catalog()
        assertEquals("gmail", catalog.items.single().slug)
        assertTrue(catalog.categories.isEmpty())
        expect<ConnectionServiceException> { client.connect("gmail") }
        assertFalse(fake.requests.any { it.method == "POST" })
        fake.existingConfig = true; fake.configOnSecondPage = true
        client.connect("gmail")
        assertTrue(fake.requests.any { it.path == "/auth_configs" && it.query["cursor"] == "config2" })
        assertFalse(fake.requests.any { it.path == "/auth_configs" && it.method == "POST" })
        assertEquals("ac_existing", fake.requests.last().body!!.getString("auth_config_id"))
    }

    @Test fun preservesFailureEnvelopeAndRedactsKeyWithoutClaimingSuccess() = runBlocking<Unit> {
        val fake = Fake().apply { execution = JSONObject("""{"successful":false,"error":"fixture-secret echoed","data":{}}""") }
        val client = ready(fake)
        val tool = client.tool("GMAIL_SEND_EMAIL", "latest")
        val result = client.execute("acct1", tool, ARGS, client.approveAction("acct1", tool, ARGS))
        assertFalse(JSONObject(result.resultJson).getBoolean("successful"))
        assertFalse(result.resultJson.contains("fixture-secret"))
        val request = fake.requests.last().body!!
        assertEquals("my-own-user", request.getString("user_id"))
        assertEquals("acct1", request.getString("connected_account_id"))
        assertEquals("20261101_00", request.getString("version"))
    }

    @Test fun credentialsSwitchExpiryAndDisabledAccountPreventWrites() = runBlocking<Unit> {
        val fake = Fake()
        var clock = 1000L
        val client = DirectComposioClient(fake) { clock }
        client.useCredentials(ComposioCredentials("fixture-secret", "my-own-user")); val first = client.refresh()
        val tool = client.tool("GMAIL_SEND_EMAIL", "latest")
        val expired = client.approveAction("acct1", tool, ARGS)
        clock += 300001
        expect<ConnectionServiceException> { client.execute("acct1", tool, ARGS, expired) }
        val previous = client.approveAction("acct1", tool, ARGS)
        client.useCredentials(ComposioCredentials("new-fixture-key", "my-own-user")); val next = client.refresh()
        assertNotEquals(first.generation, next.generation)
        expect<ConnectionServiceException> { client.execute("acct1", tool, ARGS, previous) }
        val current = client.approveAction("acct1", tool, ARGS)
        fake.disabled = true
        expect<ConnectionServiceException> { client.execute("acct1", tool, ARGS, current) }
        assertEquals(0, fake.executes)
        assertFalse(ComposioCredentials("new-fixture-key", "my-own-user").toString().contains("new-fixture-key"))
    }

    @Test fun staleResponseRepeatedCursorAndDisconnectedApprovalAreRejected() = runBlocking<Unit> {
        val fake = Fake()
        val client = ready(fake)
        fake.onAccounts = { client.clearSession() }
        expect<CancellationException> { client.refresh() }
        fake.onAccounts = null
        client.useCredentials(ComposioCredentials("fixture-secret", "my-own-user"))
        fake.secondPage = true; fake.repeatCursor = true
        expect<ConnectionServiceException> { client.refresh() }
        fake.secondPage = false; fake.repeatCursor = false
        client.refresh()
        val tool = client.tool("GMAIL_SEND_EMAIL", "latest")
        val approval = client.approveAction("acct1", tool, ARGS)
        client.disconnect("acct1")
        client.refresh()
        expect<ConnectionServiceException> { client.execute("acct1", tool, ARGS, approval) }
        assertEquals(0, fake.executes)
    }

    @Test fun diagnosesReadScopesWithoutWritesAccountLeakageOrMarkingSessionReady() = runBlocking<Unit> {
        val fake = Fake().apply { deniedPaths["/connected_accounts"] = 401; deniedPaths["/auth_configs"] = 403 }
        val client = DirectComposioClient(fake)
        client.useCredentials(ComposioCredentials("fixture-secret", "my-own-user"))
        val report = client.diagnoseCredentials()
        assertEquals(4, report.checks.size)
        assertTrue(report.hasAnyAccess)
        assertTrue(report.canBrowseApps)
        assertFalse(report.canReadAccounts)
        assertTrue(report.summary.contains("key works for some"))
        assertEquals(ComposioAccessState.AUTHENTICATION_OR_SCOPE_DENIED, report.checks.single { it.id == "connected_accounts" }.state)
        assertEquals(ComposioAccessState.PERMISSION_DENIED, report.checks.single { it.id == "auth_configs" }.state)
        assertFalse(report.toString().contains("fixture-secret"))
        assertFalse(report.toString().contains("provider-secret"))
        assertTrue(fake.requests.all { it.method == "GET" && it.query["limit"] == "1" && it.body == null })
        assertEquals("my-own-user", fake.requests.single { it.path == "/connected_accounts" }.query["user_ids"])
        expect<ConnectionServiceException> { client.catalog() } // Diagnosis cannot authorize or initialize account state.
    }

    @Test fun allDeniedDiagnosticDoesNotConcludeInvalidKeyAndAllReadSuccessDoesNotClaimWrites() = runBlocking<Unit> {
        val fake = Fake().apply { listOf("/toolkits", "/connected_accounts", "/tools", "/auth_configs").forEach { deniedPaths[it] = 401 } }
        val client = DirectComposioClient(fake)
        client.useCredentials(ComposioCredentials("fixture-secret", "my-own-user"))
        val denied = client.diagnoseCredentials()
        assertFalse(denied.hasAnyAccess)
        assertTrue(denied.summary.contains("does not distinguish"))
        fake.deniedPaths.clear()
        val allowed = client.diagnoseCredentials()
        assertTrue(allowed.canReadAccounts && allowed.canBrowseApps)
        assertTrue(allowed.summary.contains("have not been tested"))
        assertFalse(allowed.toString().contains("provider-secret"))
    }

    @Test fun diagnosticRejectsOldCredentialResultsAfterSwitch() = runBlocking<Unit> {
        val fake = Fake()
        val client = DirectComposioClient(fake)
        client.useCredentials(ComposioCredentials("fixture-secret", "my-own-user"))
        fake.onAccounts = { client.useCredentials(ComposioCredentials("new-fixture-key", "other-user")) }
        expect<CancellationException> { client.diagnoseCredentials() }
        assertEquals(0, fake.executes)
    }

    private suspend fun ready(fake: Fake) = DirectComposioClient(fake).also {
        it.useCredentials(ComposioCredentials("fixture-secret", "my-own-user")); it.refresh()
    }
    private suspend inline fun <reified T : Throwable> expect(crossinline block: suspend () -> Unit): T {
        try { block() } catch (error: Throwable) { if (error is T) return error else throw error }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }
    companion object { private const val ARGS = """{"to":"one@example.com","body":"Hello"}""" }
}
