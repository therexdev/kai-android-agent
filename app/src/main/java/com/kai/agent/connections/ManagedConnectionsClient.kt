package com.kai.agent.connections

import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phone-owned account/project state over the existing KAI managed-service contract.
 * The caller owns encrypted session storage, UI grants, browser launch, and task receipts.
 * No automatic retries: execute/disconnect/connect may already have changed remote state.
 */
class ManagedConnectionsClient(
    private val transport: ConnectionsTransport = ManagedConnectionsTransport(),
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    private val lock = Any()
    private var session: ManagedSession? = null
    private var epoch = 0L
    private var generation: String? = null
    private var accounts = emptyMap<String, ConnectionAccount>()

    fun useSession(value: ManagedSession) = synchronized(lock) {
        session = value
        epoch++
        generation = null
        accounts = emptyMap()
    }

    fun clearSession() = synchronized(lock) {
        session = null
        epoch++
        generation = null
        accounts = emptyMap()
    }

    suspend fun startSignIn(): DeviceLink {
        val ticket = synchronized(lock) { ++epoch }
        val response = transport.request("/auth/device/start", body = JSONObject())
        ensureEpoch(ticket)
        val code = response.requiredString("userCode", 32)
        if (!code.matches(Regex("[A-Za-z0-9-]+"))) invalid("Invalid sign-in code.")
        val secret = response.requiredString("deviceSecret", 512)
        val verify = response.requiredString("verifyUrl", 2048)
        val uri = URI(verify)
        if (uri.scheme != "https" || uri.host != "koinosai.com" || uri.port != -1 ||
            uri.rawUserInfo != null || uri.path != "/link" || uri.rawFragment != null) {
            invalid("Unsupported KAI sign-in page.")
        }
        return DeviceLink(code, secret, verify,
            nowMillis() + response.optLong("expiresInSec", 600).coerceIn(30, 900) * 1000,
            response.optLong("pollSec", 3).coerceIn(3, 15) * 1000, ticket)
    }

    /** Poll only while the sign-in view is active; respect [DeviceLink.pollIntervalMillis]. */
    suspend fun pollSignIn(link: DeviceLink): SignInPoll {
        if (nowMillis() >= link.expiresAtMillis) invalid("Sign-in code expired. Start again.")
        val ticket = link.requestEpoch
        ensureEpoch(ticket)
        val response = transport.request("/auth/device/poll", body = JSONObject()
            .put("userCode", link.userCode).put("deviceSecret", link.deviceSecret))
        ensureEpoch(ticket)
        if (response.optBoolean("pending")) return SignInPoll.Pending
        val profile = response.getJSONObject("account")
        return SignInPoll.Approved(ManagedSession(
            profile.requiredString("id", 200), response.requiredString("token", 4096),
            profile.optionalString("name") ?: profile.optionalString("email") ?: "KAI account"
        ))
    }

    suspend fun validateSession(): ManagedSession {
        val context = capture(needsProject = false)
        val response = transport.request("/auth/session", context.session.token)
        ensureCurrent(context)
        val profile = response.getJSONObject("account")
        if (profile.requiredString("id", 200) != context.session.ownerId) invalid("The account changed. Sign in again.")
        return context.session
    }

    /** Local sign-out happens first even if revocation cannot reach the service. */
    suspend fun signOut() {
        val previous = synchronized(lock) { session.also { clearSession() } }
        if (previous != null) transport.request("/auth/logout", previous.token, JSONObject())
    }

    suspend fun refresh(): ConnectionsSnapshot {
        val context = capture(needsProject = false)
        val status = transport.request("/connections/status")
        ensureCurrent(context)
        if (!status.optBoolean("available") || status.optInt("protocol") != 1) {
            invalid("KAI Connections is unavailable or needs an app update.")
        }
        val project = status.requiredString("generation", 128)
        val response = transport.request("/connections/api/accounts", context.session.token,
            JSONObject().put("generation", project)).getJSONObject("result")
        ensureCurrent(context)
        val expectedOwner = "kai:${context.session.ownerId}"
        if (response.requiredString("userId", 240) != expectedOwner || response.requiredString("generation", 128) != project) {
            invalid("The connection account or project changed. Refresh and try again.")
        }
        val list = response.getJSONArray("accounts").objects().map { parseAccount(it) }
        if (list.any { it.userId != expectedOwner } || list.map { it.id }.distinct().size != list.size) {
            invalid("Connection ownership could not be verified.")
        }
        synchronized(lock) {
            ensureCurrent(context)
            if (generation != project) epoch++
            generation = project
            accounts = list.associateBy { it.id }
        }
        return ConnectionsSnapshot(context.session.ownerId, project, list)
    }

    suspend fun catalog(search: String = "", cursor: String? = null, category: String = ""): ConnectionPage<ConnectionToolkit> {
        val response = call(capture(), "catalog", JSONObject()
            .put("search", search.take(160)).put("cursor", cursor.orEmpty().take(2000)).put("category", category.take(80))) as JSONObject
        val items = response.getJSONArray("items").objects().map { item ->
            ConnectionToolkit(item.requiredSlug("slug"), item.requiredString("name", 120), item.optString("description", ""),
                item.optString("logo", ""), item.optJSONArray("categories")?.strings().orEmpty(), item.optBoolean("noAuth"))
        }
        val categories = response.optJSONArray("categories")?.objects()?.map {
            ConnectionCategory(it.requiredString("id", 80), it.requiredString("name", 80))
        }.orEmpty()
        return ConnectionPage(items, response.optionalString("nextCursor"), response.optInt("total"), categories)
    }

    suspend fun connect(slug: String): HostedConnectionLink {
        val response = call(capture(), "connect", JSONObject().put("slug", validSlug(slug))) as JSONObject
        return HostedConnectionLink(validateHostedUrl(response.requiredString("url", 8192)),
            response.optionalString("id"), response.requiredSlug("toolkit"), response.optionalString("expiresAt"))
    }

    suspend fun disconnect(accountId: String) {
        val context = capture()
        ownedAccount(context, accountId)
        val response = call(context, "disconnect", JSONObject().put("id", validSlug(accountId)))
        if (response != true) invalid("The account disconnection was not confirmed.")
        synchronized(lock) {
            ensureCurrent(context)
            accounts = accounts - accountId
            // Invalidate any approvals made before the account was disconnected.
            epoch++
        }
    }

    suspend fun tools(accountId: String, search: String = "", cursor: String? = null): ConnectionPage<ConnectionTool> {
        val context = capture()
        val account = ownedAccount(context, accountId)
        val response = call(context, "tools", JSONObject().put("toolkit", account.toolkit)
            .put("search", search.take(160)).put("cursor", cursor.orEmpty().take(2000))) as JSONObject
        val items = response.getJSONArray("items").objects().map { parseTool(it) }
        if (items.any { it.toolkit != account.toolkit }) invalid("An action did not match the selected app.")
        return ConnectionPage(items, response.optionalString("nextCursor"), response.optInt("total"))
    }

    suspend fun tool(slug: String, version: String): ConnectionTool = fetchTool(capture(), slug, version)

    /** Call after the user reviews the action, or a matching stored permission grant is checked. */
    fun approveAction(accountId: String, tool: ConnectionTool, argumentsJson: String): ActionApproval {
        val context = capture()
        val account = ownedAccount(context, accountId)
        if (!account.active || account.toolkit != tool.toolkit) invalid("Reconnect the selected account before using this action.")
        val args = ConnectionArguments.validate(argumentsJson, tool.schemaJson)
        return ActionApproval(context.session.ownerId, context.project!!, context.epoch,
            accountId, tool.id, tool.version, ConnectionArguments.digest(args), nowMillis() + 5 * 60_000L)
    }

    suspend fun execute(accountId: String, tool: ConnectionTool, argumentsJson: String, approval: ActionApproval): ExecutionReceipt {
        val context = capture()
        val account = ownedAccount(context, accountId)
        if (!account.active || account.toolkit != tool.toolkit) invalid("Reconnect the selected account before using this action.")
        // Fetch exact version metadata; do not execute a stale or substituted schema.
        val currentTool = fetchTool(context, tool.id, tool.version)
        if (currentTool.toolkit != account.toolkit || currentTool.version != tool.version) invalid("The action changed. Review it again.")
        val arguments = ConnectionArguments.validate(argumentsJson, currentTool.schemaJson)
        if (approval.ownerId != context.session.ownerId || approval.generation != context.project ||
            approval.epoch != context.epoch || approval.accountId != accountId || approval.toolId != currentTool.id ||
            approval.version != currentTool.version || approval.argumentDigest != ConnectionArguments.digest(arguments) ||
            nowMillis() >= approval.expiresAtMillis) {
            invalid("The reviewed action changed. Review it again before running it.")
        }
        if (!approval.consumed.compareAndSet(false, true)) invalid("This action was already submitted. Check its receipt before trying again.")
        val result = call(context, "execute", JSONObject().put("id", accountId).put("tool", currentTool.id)
            .put("version", currentTool.version).put("arguments", arguments))
        if (result !is String || result.toByteArray(Charsets.UTF_8).size > 200_000) {
            throw ConnectionServiceException("The action returned an unreadable receipt. Check the connected app before retrying.", outcomeMayBeUnknown = true)
        }
        return ExecutionReceipt(accountId, currentTool.id, result)
    }

    private suspend fun fetchTool(context: RequestContext, slug: String, version: String): ConnectionTool {
        require(version.isNotBlank() && version.length <= 60)
        val result = parseTool(call(context, "tool", JSONObject().put("slug", validSlug(slug)).put("version", version)) as JSONObject)
        if (result.id != slug) invalid("The action did not match the request.")
        return result
    }

    private suspend fun call(context: RequestContext, action: String, body: JSONObject): Any {
        ensureCurrent(context)
        val response = transport.request("/connections/api/$action", context.session.token,
            JSONObject(body.toString()).put("generation", context.project))
        ensureCurrent(context)
        return response.get("result")
    }

    private data class RequestContext(val session: ManagedSession, val epoch: Long, val project: String?)
    private fun capture(needsProject: Boolean = true): RequestContext = synchronized(lock) {
        val current = session ?: invalid("Sign in to your KAI account first.")
        if (needsProject && generation == null) invalid("Refresh Connections first.")
        RequestContext(current, epoch, generation)
    }
    private fun ensureEpoch(ticket: Long) = synchronized(lock) {
        if (ticket != epoch) throw CancellationException("The account operation was superseded.")
    }
    private fun ensureCurrent(context: RequestContext) = synchronized(lock) {
        if (context.epoch != epoch || session !== context.session) throw CancellationException("The account or project changed.")
    }
    private fun ownedAccount(context: RequestContext, id: String): ConnectionAccount = synchronized(lock) {
        ensureCurrent(context)
        val account = accounts[id] ?: invalid("Refresh the selected account before continuing.")
        if (account.userId != "kai:${context.session.ownerId}") invalid("Connection ownership could not be verified.")
        account
    }

    companion object {
        internal fun validateHostedUrl(raw: String): String {
            val uri = try { URI(raw) } catch (_: Exception) { invalid("Invalid app sign-in URL.") }
            if (uri.scheme != "https" || uri.rawUserInfo != null || uri.port != -1 ||
                uri.host !in setOf("connect.composio.dev", "backend.composio.dev")) {
                invalid("Unsupported app sign-in URL.")
            }
            return raw
        }
        private fun parseAccount(a: JSONObject) = ConnectionAccount(a.requiredSlug("id"), a.requiredSlug("toolkit"),
            a.requiredString("userId", 240), a.requiredString("name", 120), a.requiredString("status", 40),
            a.optBoolean("disabled"), a.optString("createdAt", ""))
        private fun parseTool(t: JSONObject) = ConnectionTool(t.requiredSlug("id"), t.requiredString("name", 160),
            t.optString("description", ""), t.requiredSlug("toolkit"), t.requiredString("version", 60),
            t.optBoolean("readOnly", false), t.getJSONObject("schema").toString())
    }
}

internal fun invalid(message: String): Nothing = throw ConnectionServiceException(message)
internal fun validSlug(value: String): String {
    if (!value.matches(Regex("[a-zA-Z0-9_-]{1,160}"))) invalid("Invalid app, account or action identifier.")
    return value
}
internal fun JSONObject.requiredSlug(key: String) = validSlug(requiredString(key, 160))
internal fun JSONObject.requiredString(key: String, limit: Int): String {
    val value = opt(key)
    if (value !is String || value.isBlank() || value.length > limit) invalid("The connection service returned an invalid $key field.")
    return value
}
internal fun JSONObject.optionalString(key: String): String? = (opt(key) as? String)?.takeIf { it.isNotBlank() }
internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
internal fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

internal object ConnectionArguments {
    fun validate(raw: String, schemaJson: String): JSONObject {
        if (raw.toByteArray(Charsets.UTF_8).size > 16_000) invalid("Action inputs must be smaller than 16 KB.")
        val values = try { JSONObject(raw) } catch (_: Exception) { invalid("Action inputs must be a JSON object.") }
        val schema = JSONObject(schemaJson)
        validateObject(values, schema, "arguments", 0, rejectUnknown = true)
        return values
    }

    /** Validate the service's schema subset, including nested arrays and object properties. */
    private fun validateObject(values: JSONObject, schema: JSONObject, path: String, depth: Int, rejectUnknown: Boolean) {
        if (depth > 12) invalid("Action inputs are nested too deeply.")
        val props = schema.optJSONObject("properties") ?: JSONObject()
        schema.optJSONArray("required")?.strings()?.forEach { key ->
            if (!values.has(key) || values.opt(key) == "") invalid("Missing action input: $path.$key")
        }
        values.keys().asSequence().forEach { key ->
            val property = props.optJSONObject(key) ?: schema.optJSONObject("additionalProperties")
            if (property == null && (rejectUnknown || schema.opt("additionalProperties") == false)) invalid("Unknown action input: $path.$key")
            if (property != null) validateValue(values.get(key), property, "$path.$key", depth + 1)
        }
    }

    private fun validateValue(value: Any, schema: JSONObject, path: String, depth: Int) {
        if (depth > 12) invalid("Action inputs are nested too deeply.")
        val declared = schema.opt("type")
        val types = when (declared) {
            is String -> listOf(declared)
            is JSONArray -> declared.strings()
            else -> emptyList()
        }
        val matches = types.isEmpty() || types.any { type ->
            when (type) {
                "null" -> value == JSONObject.NULL
                "string" -> value is String
                "boolean" -> value is Boolean
                "number" -> value is Number && value.toDouble().isFinite()
                "integer" -> value is Number && value.toDouble().isFinite() && value.toDouble() % 1.0 == 0.0
                "object" -> value is JSONObject
                "array" -> value is JSONArray
                else -> true // The managed service remains authoritative for schema extensions.
            }
        }
        if (!matches) invalid("Invalid type for action input: $path")
        schema.optJSONArray("enum")?.let { choices ->
            if ((0 until choices.length()).none { canonical(choices.get(it)) == canonical(value) }) invalid("Invalid choice for action input: $path")
        }
        when (value) {
            is JSONObject -> validateObject(value, schema, path, depth, rejectUnknown = false)
            is JSONArray -> {
                if (value.length() > 1000 || (schema.has("maxItems") && value.length() > schema.optInt("maxItems"))) invalid("Too many items in action input: $path")
                if (schema.has("minItems") && value.length() < schema.optInt("minItems")) invalid("Too few items in action input: $path")
                schema.optJSONObject("items")?.let { item ->
                    for (i in 0 until value.length()) validateValue(value.get(i), item, "$path[$i]", depth + 1)
                }
            }
            is String -> {
                if (schema.has("minLength") && value.length < schema.optInt("minLength")) invalid("Action input is too short: $path")
                if (schema.has("maxLength") && value.length > schema.optInt("maxLength")) invalid("Action input is too long: $path")
            }
            is Number -> {
                if (schema.has("minimum") && value.toDouble() < schema.optDouble("minimum")) invalid("Action input is below the minimum: $path")
                if (schema.has("maximum") && value.toDouble() > schema.optDouble("maximum")) invalid("Action input is above the maximum: $path")
            }
        }
    }

    fun digest(value: JSONObject): String = MessageDigest.getInstance("SHA-256")
        .digest(canonical(value).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
            JSONObject.quote(it) + ":" + canonical(value.get(it))
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        null, JSONObject.NULL -> "null"
        else -> value.toString()
    }
}
