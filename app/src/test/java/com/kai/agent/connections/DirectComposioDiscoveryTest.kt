package com.kai.agent.connections

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DirectComposioDiscoveryTest {
    private data class Request(val path: String, val method: String, val query: Map<String, String>, val body: JSONObject?)
    private class Fixture : DirectComposioTransport {
        val requests = mutableListOf<Request>()
        var displayName = ""
        var disabled = false
        var profileReadOnly = true
        var profileFailure: Int? = null
        var profileResponse = JSONObject("""{"successful":true,"data":{"emailAddress":"personal@example.com"}}""")
        var catalog: (Map<String, String>) -> JSONObject = { page(listOf(tool("GMAIL_FETCH_EMAILS"))) }
        override suspend fun request(path: String, apiKey: String, method: String, query: Map<String, String>, body: JSONObject?): JSONObject {
            requests += Request(path, method, query.toMap(), body?.let { JSONObject(it.toString()) })
            return when {
                path == "/connected_accounts" -> page(listOf("acct_personal", "acct_work")
                    .filter { query["connected_account_ids"] == null || query["connected_account_ids"] == it }
                    .map { id -> JSONObject().put("id", id).put("toolkit", JSONObject().put("slug", "gmail"))
                        .put("user_id", "phone-owner").put("status", "ACTIVE").put("is_disabled", disabled)
                        .put("alias", "gmail_random_alias")
                        .put("state", JSONObject().put("val", JSONObject().put("access_token", "provider-secret")
                            .apply { if (displayName.isNotBlank()) put("displayName", displayName) })) })
                path == "/tools" -> catalog(query)
                path == "/tools/GMAIL_GET_PROFILE" -> tool("GMAIL_GET_PROFILE", profileReadOnly)
                path == "/tools/GMAIL_FETCH_EMAILS" -> tool("GMAIL_FETCH_EMAILS")
                path == "/tools/execute/GMAIL_GET_PROFILE" -> {
                    profileFailure?.let { throw ConnectionServiceException("Profile denied", it) }
                    JSONObject(profileResponse.toString())
                }
                path == "/tools/execute/GMAIL_FETCH_EMAILS" -> JSONObject().put("successful", true)
                    .put("data", JSONObject().put("mailbox", body!!.getString("connected_account_id")))
                else -> error("Unexpected fixture request $method $path")
            }
        }
    }

    @Test fun emptyNaturalLanguageSearchFallsBackPaginatesAndReadsBothExactAccounts() = runBlocking<Unit> {
        val fake = Fixture().apply {
            catalog = { query -> when {
                query["query"] != null -> page(emptyList())
                query["cursor"] == null -> page(listOf(tool("GMAIL_SEND_EMAIL", false)), "page2")
                else -> page(listOf(tool("GMAIL_FETCH_EMAILS")))
            } }
        }
        val client = ready(fake)
        val first = client.discoverTools("acct_personal", "check my important Gmail emails")
        assertTrue(first.usedFallback)
        assertFalse(first.hasMore)
        assertEquals(2, first.examinedCount)
        assertEquals("GMAIL_FETCH_EMAILS", first.tools.first().id)
        assertEquals(3, fake.requests.count { it.path == "/tools" })
        assertTrue(fake.requests.filter { it.path == "/tools" }.all {
            it.query["toolkit_slug"] == "gmail" && it.query["limit"] == "20" && it.query["toolkit_versions"] == "latest"
        })
        val second = client.discoverTools("acct_work", "read email")
        assertEquals("GMAIL_FETCH_EMAILS", second.tools.first().id)
        assertEquals(3, fake.requests.count { it.path == "/tools" })
        for (id in listOf("acct_personal", "acct_work")) {
            val read = first.tools.first()
            val receipt = client.execute(id, read, "{}", client.approveAction(id, read, "{}"))
            assertEquals(id, JSONObject(receipt.resultJson).getJSONObject("data").getString("mailbox"))
        }
        val executions = fake.requests.filter { it.path == "/tools/execute/GMAIL_FETCH_EMAILS" }
        assertEquals(listOf("acct_personal", "acct_work"), executions.map { it.body!!.getString("connected_account_id") })
        assertTrue(executions.all { it.body!!.getString("user_id") == "phone-owner" && it.body.getString("version") == "20261101_00" })
    }

    @Test fun boundedPartialCatalogReportsCoverageAndCannotSuppressAQueriedSearch() = runBlocking<Unit> {
        val fake = Fixture().apply {
            catalog = { query ->
                val n = query["cursor"]?.removePrefix("page")?.toInt() ?: 0
                page((0 until 20).map { tool("GMAIL_ACTION_${n}_$it") }, "page${n + 1}")
            }
        }
        val client = ready(fake)
        val first = client.discoverTools("acct_personal")
        assertEquals(100, first.examinedCount)
        assertTrue(first.hasMore)
        assertEquals(5, fake.requests.count { it.path == "/tools" })
        client.discoverTools("acct_work")
        assertEquals(5, fake.requests.count { it.path == "/tools" })
        fake.catalog = { page(listOf(tool("GMAIL_FETCH_EMAILS"))) }
        val matching = client.discoverTools("acct_work", "fetch email")
        assertEquals("GMAIL_FETCH_EMAILS", matching.tools.single().id)
        assertEquals(6, fake.requests.count { it.path == "/tools" })
        assertFalse(matching.usedFallback)
    }

    @Test fun catalogPermissionErrorIsNotConvertedToNoToolsOrDisconnectedState() = runBlocking<Unit> {
        val fake = Fixture().apply { catalog = { throw ConnectionServiceException("Tools: Read denied", 403) } }
        val client = ready(fake)
        val error = expect<ConnectionServiceException> { client.discoverTools("acct_personal", "email") }
        assertEquals(403, error.statusCode)
        assertEquals(2, client.refresh().accounts.size)
        fake.catalog = { page(listOf(tool("GMAIL_FETCH_EMAILS"))) }
        assertEquals(1, client.discoverTools("acct_work", "email").tools.size)
        assertFalse(fake.requests.any { it.method != "GET" })
    }

    @Test fun repeatedCursorDuplicateIdsAndWrongToolkitFailExplicitly() = runBlocking<Unit> {
        for (mode in 0..2) {
            val fake = Fixture().apply { catalog = { query -> when (mode) {
                0 -> page(listOf(tool(if (query["cursor"] == null) "GMAIL_FIRST" else "GMAIL_SECOND")), "same")
                1 -> page(listOf(tool("GMAIL_FIRST")), if (query["cursor"] == null) "next" else null)
                else -> page(listOf(tool("GMAIL_FIRST").put("toolkit", JSONObject().put("slug", "github"))))
            } } }
            val client = ready(fake)
            expect<ConnectionServiceException> { client.discoverTools("acct_personal") }
        }
    }

    @Test fun metadataEmailIsPreferredAndProfileLookupIsBoundToExactAccountAndCachesBriefly() = runBlocking<Unit> {
        val fake = Fixture().apply { displayName = "verified@example.com" }
        var clock = 1000L
        val client = ready(fake) { clock }
        val snapshot = client.refresh()
        assertEquals("verified@example.com", snapshot.accounts.first().displayLabel)
        assertEquals("gmail_random_alias", snapshot.accounts.first().name)
        assertFalse(snapshot.toString().contains("provider-secret"))
        assertEquals("verified@example.com", client.resolveAccountIdentity("acct_personal").emailAddress)
        assertFalse(fake.requests.any { it.path.startsWith("/tools") })
        fake.displayName = ""
        client.refresh()
        val profile = client.resolveAccountIdentity("acct_personal")
        assertEquals("personal@example.com", profile.emailAddress)
        assertEquals(AccountIdentity.SOURCE_GMAIL_PROFILE, profile.identitySource)
        val execution = fake.requests.single { it.path == "/tools/execute/GMAIL_GET_PROFILE" }
        assertEquals("acct_personal", execution.body!!.getString("connected_account_id"))
        assertEquals("me", execution.body.getJSONObject("arguments").getString("user_id"))
        assertEquals("phone-owner", fake.requests.last { it.path == "/connected_accounts" }.query["user_ids"])
        client.resolveAccountIdentity("acct_personal")
        assertEquals(1, fake.requests.count { it.path == "/tools/execute/GMAIL_GET_PROFILE" })
        clock += 15 * 60_000L + 1
        client.resolveAccountIdentity("acct_personal")
        assertEquals(2, fake.requests.count { it.path == "/tools/execute/GMAIL_GET_PROFILE" })
    }

    @Test fun profileFailureCooldownPreservesAccountsAndForceRetriesOnlyWhenExplicit() = runBlocking<Unit> {
        val fake = Fixture().apply { profileFailure = 403 }
        var clock = 1000L
        val client = ready(fake) { clock }
        repeat(2) { assertEquals(403, expect<ConnectionServiceException> { client.resolveAccountIdentity("acct_personal") }.statusCode) }
        assertEquals(1, fake.requests.count { it.path == "/tools/execute/GMAIL_GET_PROFILE" })
        assertEquals(2, client.refresh().accounts.size)
        clock += 60_001
        expect<ConnectionServiceException> { client.resolveAccountIdentity("acct_personal") }
        assertEquals(2, fake.requests.count { it.path == "/tools/execute/GMAIL_GET_PROFILE" })
        fake.profileFailure = null
        assertEquals("personal@example.com", client.resolveAccountIdentity("acct_personal", force = true).emailAddress)
        assertEquals(3, fake.requests.count { it.path == "/tools/execute/GMAIL_GET_PROFILE" })
    }

    @Test fun profileWithoutReadOnlyMetadataAndFailedEnvelopeCannotSupplyAnIdentity() = runBlocking<Unit> {
        val fake = Fixture().apply { profileReadOnly = false }
        val client = ready(fake)
        expect<ConnectionServiceException> { client.resolveAccountIdentity("acct_personal") }
        assertFalse(fake.requests.any { it.path.startsWith("/tools/execute/") })
        fake.profileReadOnly = true
        fake.profileResponse = JSONObject("""{"successful":false,"data":{"emailAddress":"attacker@example.com"}}""")
        expect<ConnectionServiceException> { client.resolveAccountIdentity("acct_personal", force = true) }
        assertEquals(2, client.refresh().accounts.size)
    }

    @Test fun credentialChangesDuringCacheClockLookupCannotReturnOldToolsOrIdentity() = runBlocking<Unit> {
        for (identity in listOf(false, true)) {
            val fake = Fixture()
            var onClock: (() -> Unit)? = null
            val client = ready(fake) { onClock?.also { onClock = null }?.invoke(); 1000L }
            if (identity) client.resolveAccountIdentity("acct_personal") else client.discoverTools("acct_personal")
            onClock = { client.useCredentials(ComposioCredentials("new-project-key", "different-user")) }
            expect<CancellationException> {
                if (identity) client.resolveAccountIdentity("acct_personal") else client.discoverTools("acct_personal", "email")
            }
        }
        val disabled = Fixture()
        val client = ready(disabled)
        client.discoverTools("acct_personal")
        client.resolveAccountIdentity("acct_personal")
        disabled.disabled = true
        client.refresh()
        val before = disabled.requests.size
        expect<ConnectionServiceException> { client.discoverTools("acct_personal") }
        expect<ConnectionServiceException> { client.resolveAccountIdentity("acct_personal") }
        assertEquals(before, disabled.requests.size)
    }

    @Test fun conflictingDestructiveOrWriteTagsDoNotAutoAuthorizeRead() = runBlocking<Unit> {
        for (tag in listOf("destructiveHint", "write", "write-only")) {
            val fake = Fixture().apply { catalog = { page(listOf(tool("GMAIL_FETCH_EMAILS").put("tags", JSONArray().put(tag)))) } }
            val client = ready(fake)
            assertFalse(client.discoverTools("acct_personal").tools.single().readOnly)
        }
    }

    private suspend fun ready(fake: Fixture, now: () -> Long = System::currentTimeMillis) = DirectComposioClient(fake, now).also {
        it.useCredentials(ComposioCredentials("project-fixture-key", "phone-owner"))
        it.refresh()
    }
    private suspend inline fun <reified T : Throwable> expect(crossinline block: suspend () -> Unit): T {
        try { block() } catch (error: Throwable) { if (error is T) return error else throw error }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }
    companion object {
        private fun tool(id: String, readOnly: Boolean = true): JSONObject = JSONObject().put("slug", id)
            .put("name", id.replace('_', ' ')).put("toolkit", JSONObject().put("slug", "gmail"))
            .put("version", "20261101_00").put("annotations", JSONObject().put("readOnlyHint", readOnly))
            .put("input_parameters", JSONObject().put("type", "object").put("properties", JSONObject().apply {
                if (id == "GMAIL_GET_PROFILE") put("user_id", JSONObject().put("type", "string"))
            }))
        private fun page(items: List<JSONObject>, cursor: String? = null) = JSONObject().put("items", JSONArray(items))
            .apply { if (cursor != null) put("next_cursor", cursor) }
    }
}
