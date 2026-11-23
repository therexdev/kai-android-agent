package com.kai.agent.connections

import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** Phone-owned state over Composio's documented v3.1 REST API; no KAI login or desktop. */
class DirectComposioClient(
    private val transport: DirectComposioTransport = DirectComposioHttpTransport(),
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    private val lock = Any()
    private val connectMutex = Mutex()
    private var credentials: ComposioCredentials? = null
    private var epoch = 0L
    private var refreshNumber = 0L
    private var ready = false
    private var accounts = emptyMap<String, ConnectionAccount>()
    private var categories: List<ConnectionCategory>? = null
    private val activeRequests = mutableSetOf<Job>()
    private data class CachedCatalog(val tools: List<ConnectionTool>, val hasMore: Boolean, val expiresAt: Long)
    private data class CachedIdentity(val toolkit: String, val identity: AccountIdentity?, val failure: ConnectionServiceException?, val expiresAt: Long)
    private val toolCatalogs = mutableMapOf<String, CachedCatalog>()
    private val accountIdentities = mutableMapOf<String, CachedIdentity>()

    fun useCredentials(value: ComposioCredentials) = synchronized(lock) {
        credentials = value
        epoch++
        ready = false
        accounts = emptyMap()
        categories = null
        toolCatalogs.clear()
        accountIdentities.clear()
        cancelOutstanding()
    }

    fun clearSession() = synchronized(lock) {
        credentials = null
        epoch++
        ready = false
        accounts = emptyMap()
        categories = null
        toolCatalogs.clear()
        accountIdentities.clear()
        cancelOutstanding()
    }

    private fun cancelOutstanding() {
        activeRequests.toList().forEach { it.cancel(CancellationException("The Composio key or user changed.")) }
        activeRequests.clear()
    }

    /** Optional, read-only diagnosis. Does not mark credentials verified or authorize any action. */
    suspend fun diagnoseCredentials(): ComposioDiagnostics {
        val context = capture(requireReady = false)
        val probes = listOf(
            Triple("toolkits", "Toolkits", mapOf("limit" to "1", "user_id" to context.credentials.userId)),
            Triple("connected_accounts", "Connected accounts", mapOf("limit" to "1", "user_ids" to context.credentials.userId)),
            Triple("tools", "Tools", mapOf("limit" to "1", "toolkit_versions" to "latest")),
            Triple("auth_configs", "Auth configs", mapOf("limit" to "1"))
        )
        val checks = coroutineScope {
            probes.map { (id, label, query) -> async {
                try {
                    val response = call(context, "/$id", query = query)
                    // Do not keep account/auth-config contents; even diagnostic responses can contain secrets.
                    if (response.optJSONArray("items") == null) invalid("Composio returned an unreadable page.")
                    ComposioAccessCheck(id, label, ComposioAccessState.ALLOWED, 200, "$label: Read is available.")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: ConnectionServiceException) {
                    val code = error.statusCode
                    val state = when (code) {
                        401 -> ComposioAccessState.AUTHENTICATION_OR_SCOPE_DENIED
                        403 -> ComposioAccessState.PERMISSION_DENIED
                        else -> ComposioAccessState.UNAVAILABLE
                    }
                    val detail = when (code) {
                        401 -> "Denied (401). Check the project key's type, active status, and $label: Read permission."
                        403 -> "Permission denied (403). Check $label: Read for this project's key."
                        404 -> "Not found or inaccessible in this project (404)."
                        429 -> "Composio request limit reached (429). Try again later."
                        else -> "Could not check $label access${code?.let { " (HTTP $it)" }.orEmpty()}. Try again later."
                    }
                    ComposioAccessCheck(id, label, state, code, detail)
                }
            } }.awaitAll()
        }
        ensureCurrent(context)
        return ComposioDiagnostics(checks)
    }

    suspend fun refresh(): ConnectionsSnapshot {
        val context = capture(requireReady = false)
        val ticket = synchronized(lock) { ++refreshNumber }
        val result = mutableListOf<ConnectionAccount>()
        var cursor: String? = null
        val seen = mutableSetOf<String>()
        do {
            val query = mutableMapOf("user_ids" to context.credentials.userId, "limit" to "50")
            cursor?.let { query["cursor"] = it }
            val page = call(context, "/connected_accounts", query = query)
            result += pageItems(page, 50).map { parseAccount(it, context.credentials.userId) }
            cursor = nextCursor(page)
            if (cursor != null && (!seen.add(cursor) || seen.size >= 20)) invalid("Too many account pages. Use a dedicated Composio user ID.")
        } while (cursor != null)
        if (result.map { it.id }.distinct().size != result.size) invalid("Composio returned duplicate accounts. Refresh Connections.")
        synchronized(lock) {
            ensureCurrent(context)
            if (ticket != refreshNumber) throw CancellationException("A newer account refresh replaced this request.")
            accounts = result.associateBy { it.id }
            ready = true
        }
        return synchronized(lock) {
            ensureCurrent(context)
            val now = nowMillis()
            ensureCurrent(context)
            val enriched = result.map { account ->
                accountIdentities[account.id]?.takeIf { it.toolkit == account.toolkit && it.expiresAt > now && account.active }
                    ?.identity?.takeIf { account.providerDisplayName.isBlank() || account.toolkit == "gmail" && account.emailAddress.isBlank() }
                    ?.applyTo(account) ?: account
            }
            ConnectionsSnapshot(context.credentials.userId, context.generation, enriched)
        }
    }

    suspend fun catalog(search: String = "", cursor: String? = null, category: String = ""): ConnectionPage<ConnectionToolkit> {
        val context = capture()
        val query = mutableMapOf("limit" to "24", "sort_by" to "usage", "include_deprecated" to "false",
            "user_id" to context.credentials.userId)
        addFilters(query, search, cursor, "search")
        if (category.isNotBlank()) query["category"] = category.take(80)
        val response = call(context, "/toolkits", query = query)
        val items = pageItems(response, 24).map { item ->
            val meta = item.optJSONObject("meta") ?: JSONObject()
            ConnectionToolkit(item.requiredSlug("slug"), item.requiredString("name", 300).take(120),
                (meta.optionalString("description") ?: item.optionalString("description")).orEmpty().take(4000),
                (meta.optionalString("logo") ?: item.optionalString("logo")).orEmpty().take(2048),
                meta.optJSONArray("categories")?.let { list -> (0 until list.length()).mapNotNull { i ->
                    when (val value = list.opt(i)) { is String -> value; is JSONObject -> value.optionalString("name"); else -> null }
                }.take(40) }.orEmpty(), item.optBoolean("no_auth"))
        }
        val cached = synchronized(lock) { categories }
        val available = cached ?: try {
            call(context, "/toolkits/categories").let { list ->
                pageItems(list, 1000).map { ConnectionCategory(it.requiredString("id", 80), it.requiredString("name", 160)) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { emptyList() } // Optional filters must not discard a valid catalog page.
        synchronized(lock) { ensureCurrent(context); categories = available }
        return ConnectionPage(items, nextCursor(response), response.optInt("total_items", items.size), available)
    }

    /** Creates a hosted consent link, never treats browser return as successful authentication. */
    suspend fun connect(slug: String): HostedConnectionLink {
        val context = capture()
        return connectMutex.withLock {
        ensureCurrent(context)
        val toolkit = validSlug(slug)
        val metadata = call(context, "/toolkits/$toolkit")
        if (metadata.requiredSlug("slug") != toolkit) invalid("The returned app did not match the selection.")
        var cursor: String? = null
        val seen = mutableSetOf<String>()
        var selected: String? = null
        var fallback: String? = null
        do {
            val query = mutableMapOf("toolkit_slug" to toolkit, "show_disabled" to "false", "limit" to "25")
            cursor?.let { query["cursor"] = it }
            val page = call(context, "/auth_configs", query = query)
            pageItems(page, 25).forEach { config ->
                if (config.optJSONObject("toolkit")?.optString("slug") == toolkit && config.optString("status") == "ENABLED") {
                    val id = config.requiredSlug("id")
                    if (fallback == null) fallback = id
                    if (config.optBoolean("is_composio_managed") && selected == null) selected = id
                }
            }
            cursor = nextCursor(page)
            if (cursor != null && (!seen.add(cursor) || seen.size >= 10)) invalid("Too many auth configurations. Select a dedicated Composio project.")
        } while (selected == null && cursor != null)
        val authConfigId = selected ?: fallback ?: createConfig(context, toolkit, metadata)
        val response = call(context, "/connected_accounts/link", "POST", body = JSONObject()
            .put("auth_config_id", authConfigId).put("user_id", context.credentials.userId))
        HostedConnectionLink(ManagedConnectionsClient.validateHostedUrl(response.requiredString("redirect_url", 8192)),
            response.optionalString("connected_account_id")?.let(::validSlug), toolkit, response.optionalString("expires_at"))
        }
    }

    private suspend fun createConfig(context: Context, toolkit: String, metadata: JSONObject): String {
        val managed = metadata.optJSONArray("composio_managed_auth_schemes")?.length()?.let { it > 0 } == true
        val options = JSONObject().put("credentials", JSONObject())
        if (managed) options.put("type", "use_composio_managed_auth")
        else {
            val schemes = metadata.optJSONArray("auth_schemes")?.strings().orEmpty().map { it.uppercase(Locale.ROOT) }
            val supported = schemes.firstOrNull { it in setOf("API_KEY", "BEARER_TOKEN", "BASIC", "NO_AUTH") }
                ?: if (metadata.optBoolean("no_auth")) "NO_AUTH" else null
                ?: invalid("This app needs a custom auth configuration in your Composio project before connecting.")
            options.put("type", "use_custom_auth").put("auth_scheme", supported)
        }
        val result = call(context, "/auth_configs", "POST", body = JSONObject()
            .put("toolkit", JSONObject().put("slug", toolkit)).put("auth_config", options))
        return (result.optJSONObject("auth_config") ?: invalid("Composio did not return an auth configuration.")).requiredSlug("id")
    }

    suspend fun disconnect(accountId: String) {
        val context = capture()
        ownedAccount(context, accountId)
        verifyAccount(context, accountId)
        val response = call(context, "/connected_accounts/${validSlug(accountId)}", "DELETE")
        if (response.opt("success") != true) throw ConnectionServiceException(
            "Composio did not confirm disconnection. Refresh before trying again.", outcomeMayBeUnknown = true)
        synchronized(lock) {
            ensureCurrent(context)
            accounts = accounts - accountId
            toolCatalogs.clear()
            accountIdentities.clear()
            epoch++
        }
    }

    suspend fun tools(accountId: String, search: String = "", cursor: String? = null): ConnectionPage<ConnectionTool> {
        val context = capture()
        val account = ownedAccount(context, accountId)
        return toolsPage(context, account, search, cursor)
    }

    private suspend fun toolsPage(context: Context, account: ConnectionAccount, search: String, cursor: String?): ConnectionPage<ConnectionTool> {
        val query = mutableMapOf("toolkit_slug" to account.toolkit, "limit" to "20", "toolkit_versions" to "latest", "include_deprecated" to "false")
        addFilters(query, search, cursor, "query")
        val response = call(context, "/tools", query = query)
        val items = pageItems(response, 20).map(::parseTool)
        if (items.any { it.toolkit != account.toolkit }) invalid("An action did not match the selected app.")
        ensureCurrent(context)
        return ConnectionPage(items, nextCursor(response), response.optInt("total_items", items.size))
    }

    /** Bounded catalog discovery. Empty semantic search is never interpreted as revoked Gmail access. */
    suspend fun discoverTools(accountId: String, search: String = ""): ToolDiscovery {
        val context = capture()
        val selected = ownedActiveAccount(context, accountId)
        val query = search.trim().take(160)
        val cached = synchronized(lock) {
            val now = nowMillis()
            ensureCurrent(context)
            ownedActiveAccount(context, accountId)
            toolCatalogs[selected.toolkit]?.takeIf { it.expiresAt > now && (!it.hasMore || query.isBlank()) }
        }
        if (cached != null) return synchronized(lock) {
            ensureCurrent(context)
            val current = ownedActiveAccount(context, accountId)
            if (current.toolkit != selected.toolkit) invalid("The selected app changed. Refresh Connections.")
            ToolDiscovery(ToolDiscovery.rank(cached.tools, query), query.isNotBlank(), cached.hasMore, cached.tools.size)
        }
        try {
            return withTimeout(30_000L) {
                var requests = 0
                suspend fun collect(text: String): Pair<List<ConnectionTool>, Boolean> {
                    val result = mutableListOf<ConnectionTool>()
                    val ids = mutableSetOf<String>()
                    val cursors = mutableSetOf<String>()
                    var cursor: String? = null
                    do {
                        val page = toolsPage(context, selected, text, cursor)
                        requests++
                        page.items.forEach {
                            if (!ids.add(it.id)) invalid("Composio returned duplicate actions. Try discovery again.")
                            result += it
                        }
                        cursor = page.nextCursor
                        if (cursor != null && !cursors.add(cursor)) invalid("Composio repeated an action page. Try discovery again.")
                    } while (cursor != null && requests < 5)
                    return result.toList() to (cursor != null)
                }
                var collected = collect(query)
                var fallback = false
                if (query.isNotBlank() && collected.first.isEmpty() && !collected.second && requests < 5) {
                    fallback = true
                    collected = collect("")
                }
                synchronized(lock) {
                    val expiry = nowMillis() + 5 * 60_000L
                    ensureCurrent(context)
                    val current = ownedActiveAccount(context, accountId)
                    if (current.toolkit != selected.toolkit) invalid("The selected app changed. Refresh Connections.")
                    if (query.isBlank() || fallback) toolCatalogs[selected.toolkit] = CachedCatalog(collected.first, collected.second, expiry)
                    ToolDiscovery(ToolDiscovery.rank(collected.first, query), fallback, collected.second, collected.first.size)
                }
            }
        } catch (error: TimeoutCancellationException) {
            // Only our own deadline becomes a discovery error; an outer cancelled turn stays cancelled.
            currentCoroutineContext().ensureActive()
            ensureCurrent(context)
            throw ConnectionServiceException("Finding actions took too long. Try a shorter request.")
        }
    }

    /** Optional read-only identity enrichment; neither aliases nor arbitrary auth fields become email identities. */
    suspend fun resolveAccountIdentity(accountId: String, force: Boolean = false): ConnectionAccount {
        val context = capture()
        val account = ownedActiveAccount(context, accountId)
        if (account.emailAddress.isNotBlank() || account.toolkit != "gmail" && account.providerDisplayName.isNotBlank() ||
            account.toolkit != "gmail" && ProviderAccountProfiles.reads(account.toolkit).isEmpty()) return synchronized(lock) {
            ensureCurrent(context)
            ownedActiveAccount(context, accountId)
        }
        if (!force) {
            val cached = synchronized(lock) {
                val now = nowMillis()
                ensureCurrent(context)
                ownedActiveAccount(context, accountId)
                accountIdentities[accountId]?.takeIf { it.toolkit == account.toolkit && it.expiresAt > now }
            }
            if (cached != null) return synchronized(lock) {
                ensureCurrent(context)
                val current = ownedActiveAccount(context, accountId)
                if (current.toolkit != account.toolkit) invalid("The selected app changed. Refresh Connections.")
                cached.failure?.let { throw it }
                cached.identity?.applyTo(current) ?: current
            }
        }
        try {
            val identity = if (account.toolkit == "gmail") {
                val profile = fetchTool(context, "GMAIL_GET_PROFILE", "latest")
                requireActive(account, profile)
                if (!profile.readOnly) invalid("Gmail profile lookup is not marked read-only. Identity lookup was skipped.")
                val args = JSONObject().put("user_id", "me").toString()
                ensureCurrent(context)
                val result = execute(accountId, profile, args, approveAction(accountId, profile, args))
                AccountIdentity.fromGmailProfile(JSONObject(result.resultJson))
            } else {
                // The proxy can only make these compiled, current-user profile reads. Verify exact
                // ownership/status again before each request; never borrow another account's auth.
                var found: AccountIdentity? = null
                for (read in ProviderAccountProfiles.reads(account.toolkit)) {
                    val verified = verifyAccount(context, accountId)
                    if (!verified.active || verified.toolkit != account.toolkit) invalid("The selected app changed. Refresh Connections.")
                    val response = try {
                        call(context, "/tools/execute/proxy", "POST", body = read.request(accountId, context.credentials.userId))
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: ConnectionServiceException) { continue }
                    found = ProviderAccountProfiles.identity(read.kind, response)
                    if (found != null) break
                }
                found
            } ?: invalid("Account details are unavailable with this connection's profile access. Your connection was kept.")
            if (identity.toString().contains(context.credentials.apiKey)) invalid("Account identity could not be displayed safely.")
            return synchronized(lock) {
                val expiry = nowMillis() + 15 * 60_000L
                ensureCurrent(context)
                val current = ownedActiveAccount(context, accountId)
                if (current.toolkit != account.toolkit) invalid("The selected app changed. Refresh Connections.")
                accountIdentities[accountId] = CachedIdentity(account.toolkit, identity, null, expiry)
                identity.applyTo(current)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            val failure = error as? ConnectionServiceException
                ?: ConnectionServiceException("Could not read this account's identity. Your connection was kept.")
            synchronized(lock) {
                val expiry = nowMillis() + 60_000L
                ensureCurrent(context)
                accountIdentities[accountId] = CachedIdentity(account.toolkit, null, failure, expiry)
            }
            throw failure
        }
    }

    suspend fun tool(slug: String, version: String): ConnectionTool = fetchTool(capture(), slug, version)

    fun approveAction(accountId: String, tool: ConnectionTool, argumentsJson: String): ActionApproval {
        val context = capture()
        requireActive(ownedAccount(context, accountId), tool)
        val args = ConnectionArguments.validate(argumentsJson, tool.schemaJson)
        return ActionApproval(context.credentials.userId, context.generation, context.epoch, accountId, tool.id,
            tool.version, reviewDigest(args, tool), nowMillis() + 5 * 60_000L)
    }

    suspend fun execute(accountId: String, tool: ConnectionTool, argumentsJson: String, approval: ActionApproval): ExecutionReceipt {
        val context = capture()
        requireActive(ownedAccount(context, accountId), tool)
        val current = fetchTool(context, tool.id, tool.version)
        requireActive(verifyAccount(context, accountId), current)
        val args = ConnectionArguments.validate(argumentsJson, current.schemaJson)
        synchronized(lock) {
            ensureCurrent(context)
            if (approval.ownerId != context.credentials.userId || approval.generation != context.generation ||
                approval.epoch != context.epoch || approval.accountId != accountId || approval.toolId != current.id ||
                approval.version != current.version || approval.argumentDigest != reviewDigest(args, current) ||
                nowMillis() >= approval.expiresAtMillis) invalid("The reviewed action changed or expired. Review it again.")
            if (!approval.consumed.compareAndSet(false, true)) invalid("This action was already submitted. Check its receipt before trying again.")
        }
        val response = call(context, "/tools/execute/${current.id}", "POST", body = JSONObject()
            .put("connected_account_id", accountId).put("user_id", context.credentials.userId)
            .put("version", current.version).put("arguments", args))
        // Keep Composio's successful/error envelope: a 200 transport response is not action success.
        // Redact even an accidental project-key echo before the result can reach a model or disk.
        return ExecutionReceipt(accountId, current.id, response.toString().replace(context.credentials.apiKey, "[redacted]"))
    }

    private suspend fun fetchTool(context: Context, slug: String, version: String): ConnectionTool {
        if (!version.matches(Regex("[A-Za-z0-9_.-]{1,60}"))) invalid("Invalid action version.")
        val result = parseTool(call(context, "/tools/${validSlug(slug)}", query = mapOf("version" to version)))
        if (result.id != slug || (version != "latest" && result.version != version)) invalid("The action version changed. Review it again.")
        return result
    }

    /** Official guidance uses filtered listing because individual-account user_id is deprecated. */
    private suspend fun verifyAccount(context: Context, id: String): ConnectionAccount {
        val response = call(context, "/connected_accounts", query = mapOf("user_ids" to context.credentials.userId,
            "connected_account_ids" to validSlug(id), "limit" to "2"))
        val list = pageItems(response, 2)
        if (list.size != 1 || nextCursor(response) != null) invalid("Connection ownership could not be verified. Refresh Connections.")
        val account = parseAccount(list.single(), context.credentials.userId)
        if (account.id != id) invalid("The connection did not match the selected account.")
        return account
    }

    private suspend fun call(context: Context, path: String, method: String = "GET",
        query: Map<String, String> = emptyMap(), body: JSONObject? = null): JSONObject = coroutineScope {
        val job = currentCoroutineContext()[Job]!!
        synchronized(lock) { ensureCurrent(context); activeRequests += job }
        try {
            val response = transport.request(path, context.credentials.apiKey, method, query, body)
            ensureCurrent(context)
            response
        } finally { synchronized(lock) { activeRequests -= job } }
    }
    private data class Context(val credentials: ComposioCredentials, val epoch: Long, val generation: String)
    private fun capture(requireReady: Boolean = true): Context = synchronized(lock) {
        val current = credentials ?: invalid("Add your Composio API key and user ID in Connections.")
        if (requireReady && !ready) invalid("Save and verify your Composio connection first.")
        Context(current, epoch, fingerprint(current.apiKey))
    }
    private fun ensureCurrent(context: Context) = synchronized(lock) {
        if (credentials !== context.credentials || epoch != context.epoch) throw CancellationException("The Composio key or user changed.")
    }
    private fun ownedAccount(context: Context, id: String): ConnectionAccount = synchronized(lock) {
        ensureCurrent(context)
        val account = accounts[id] ?: invalid("Refresh the selected account before continuing.")
        if (account.userId != context.credentials.userId) invalid("Connection ownership could not be verified.")
        account
    }

    private fun ownedActiveAccount(context: Context, id: String): ConnectionAccount = ownedAccount(context, id).also {
        if (!it.active) invalid("The selected connection is not active. Refresh Connections before using it.")
    }

    companion object {
        private fun fingerprint(key: String): String = "composio-v1:" + MessageDigest.getInstance("SHA-256")
            .digest(("KAI-Composio-project\u0000" + key).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        private fun requireActive(account: ConnectionAccount, tool: ConnectionTool) {
            if (!account.active || account.toolkit != tool.toolkit) invalid("Reconnect the selected account before using this action.")
        }
        private fun reviewDigest(args: JSONObject, tool: ConnectionTool): String = ConnectionArguments.digest(JSONObject()
            .put("arguments", args).put("schema", JSONObject(tool.schemaJson)).put("readOnly", tool.readOnly))
        private fun pageItems(response: JSONObject, limit: Int): List<JSONObject> {
            val items = response.optJSONArray("items") ?: invalid("Composio returned an unreadable page.")
            if (items.length() > limit) invalid("Composio returned more items than requested. Narrow the search.")
            return (0 until items.length()).map { items.optJSONObject(it) ?: invalid("Composio returned an unreadable item.") }
        }
        private fun nextCursor(response: JSONObject): String? = response.optionalString("next_cursor")?.also {
            if (it.length > 2000 || it.any(Char::isISOControl)) invalid("Composio returned an invalid page cursor.")
        }
        private fun addFilters(query: MutableMap<String, String>, search: String, cursor: String?, searchKey: String) {
            if (search.isNotBlank()) query[searchKey] = search.take(160)
            if (!cursor.isNullOrBlank()) {
                if (cursor.length > 2000) invalid("Invalid page cursor.")
                query["cursor"] = cursor
            }
        }
        private fun parseAccount(raw: JSONObject, expectedUser: String): ConnectionAccount {
            // Only called on responses to a request filtered to this exact user ID.
            if (raw.has("user_id") && !raw.isNull("user_id") && raw.opt("user_id") != expectedUser)
                invalid("Composio returned an account for a different user.")
            val toolkit = (raw.optJSONObject("toolkit") ?: invalid("Missing account app.")).requiredSlug("slug")
            val account = ConnectionAccount(raw.requiredSlug("id"), toolkit, expectedUser,
                (raw.optionalString("alias") ?: raw.optionalString("word_id") ?: toolkit).take(120),
                raw.requiredString("status", 40), raw.optBoolean("is_disabled") || raw.optJSONObject("auth_config")?.optBoolean("is_disabled") == true,
                raw.optString("created_at", "").take(80))
            return AccountIdentity.fromAccount(raw, toolkit)?.applyTo(account) ?: account
        }
        private fun parseTool(raw: JSONObject): ConnectionTool {
            val input = raw.optJSONObject("input_parameters") ?: invalid("The action has no input schema.")
            val schema = if (input.has("properties") || input.opt("type") == "object") JSONObject(input.toString()) else {
                val required = JSONArray()
                input.keys().asSequence().forEach { key -> if (input.optJSONObject(key)?.opt("required") == true) required.put(key) }
                JSONObject().put("type", "object").put("properties", JSONObject(input.toString())).put("required", required)
            }
            if (schema.toString().toByteArray(Charsets.UTF_8).size > 256 * 1024) invalid("This action's input schema is too large for mobile review.")
            val annotations = raw.optJSONObject("annotations")
            val hints = raw.optJSONArray("tags")?.strings().orEmpty().map { it.lowercase(Locale.ROOT) }
            val readOnly = annotations?.opt("readOnlyHint") != false && annotations?.opt("destructiveHint") != true &&
                hints.none { it in setOf("destructivehint", "destructive", "write", "write_only", "write-only") } &&
                (annotations?.opt("readOnlyHint") == true || hints.any { it in setOf("readonlyhint", "read_only", "read-only") })
            val version = raw.requiredString("version", 60)
            if (version == "latest" || !version.matches(Regex("[A-Za-z0-9_.-]{1,60}"))) invalid("Composio did not resolve a pinned action version.")
            return ConnectionTool(raw.requiredSlug("slug"), raw.requiredString("name", 500).take(160),
                raw.optString("description", "").take(8000),
                (raw.optJSONObject("toolkit") ?: invalid("Missing action app.")).requiredSlug("slug"), version, readOnly, schema.toString())
        }
    }
}
