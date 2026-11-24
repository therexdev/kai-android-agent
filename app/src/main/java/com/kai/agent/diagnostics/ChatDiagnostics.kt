package com.kai.agent.diagnostics

import com.kai.agent.data.AppSettings
import com.kai.agent.data.newId
import com.kai.agent.data.now
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Only sanitized, bounded values may cross the durable diagnostics boundary. */
class DiagnosticRedactor(settings: AppSettings) {
    private val secrets = listOf(settings.koinos.apiKey, settings.openai.apiKey, settings.composioApiKey,
        settings.braveSearchApiKey, settings.connectionsSessionToken, settings.creditAccount.token, settings.creditAccount.linkSecret).filter { it.isNotBlank() }.distinct().sortedByDescending { it.length }
    private val routes = listOf(settings.koinos.endpoint, settings.openai.endpoint, settings.connectionsBaseUrl)
        .mapNotNull { runCatching { URI(it).rawPath }.getOrNull() }.filter { it.contains("/r/") }
    private fun sensitive(key: String): Boolean = key.lowercase().replace(Regex("[^a-z0-9]"), "") in setOf(
        "apikey", "accesskey", "secret", "clientsecret", "password", "passwd", "accesstoken", "refreshtoken",
        "idtoken", "token", "authorization", "proxyauthorization", "cookie", "setcookie", "credentials",
        "base64", "b64json", "filebytes", "audiobytes", "imagebytes", "dataurl", "privatekey", "secretkey", "xapikey", "authtoken", "apitoken", "oauthtoken", "authentication", "authconfig", "authconfigid", "connectionssessiontoken", "composioapikey", "bravesearchapikey")
    fun text(input: String): String {
        var value = input
        secrets.forEach { secret ->
            value = value.replace(secret, "[credential-removed]")
                .replace(JSONObject.quote(secret).removeSurrounding("\""), "[credential-removed]")
                .replace(java.net.URLEncoder.encode(secret, "UTF-8"), "[credential-removed]")
        }
        routes.forEach { value = value.replace(it, "/r/redacted-route") }
        value = value.replace(Regex("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]+"), "$1 [credential-removed]")
            .replace(Regex("\\b(?:sk-[A-Za-z0-9_-]{12,}|eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+)"), "[credential-removed]")
            .replace(Regex("(?i)(\\b(?:api[_ -]?key|access[_ -]?token|refresh[_ -]?token|client[_ -]?secret|password|authorization)\\b[\\s\"']*[:=][\\s\"']*)([^\\s,\"'<>}]+)"), "$1[credential-removed]")
            .replace(Regex("data:[^\\s\"']+;base64,[A-Za-z0-9+/=]+"), "[attachment bytes omitted]")
        // Query strings, fragments, user-info and private relay route identifiers are never exported.
        return Regex("https?://[^\\s<>\"']+").replace(value) { match ->
            runCatching { val uri = URI(match.value)
                val path = uri.rawPath.orEmpty().replace(Regex("/r/[^/]+"), "/r/redacted-route")
                "${uri.scheme}://${uri.host ?: "[host]"}${if(uri.port >= 0) ":${uri.port}" else ""}$path" +
                    if(uri.rawQuery != null || uri.rawFragment != null) " [URL parameters removed]" else ""
            }.getOrDefault("[URL removed]")
        }
    }
    fun clean(value: Any?, depth: Int = 0): Any {
        if (depth > 24) return "[nested content omitted]"
        return when (value) {
            null, JSONObject.NULL -> JSONObject.NULL
            is JSONObject -> JSONObject().also { result -> value.keys().forEach { key ->
                result.put(text(key), if(key=="contextArchive") "[encrypted task archive omitted from report]" else if(sensitive(key)) "[credential-removed]" else clean(value.opt(key), depth + 1))
            } }
            is JSONArray -> JSONArray().also { result -> for(i in 0 until minOf(value.length(), 2000)) result.put(clean(value.opt(i), depth + 1))
                if(value.length() > 2000) result.put(JSONObject().put("omittedItems", value.length()-2000)) }
            is String -> {
                if(value.length >= 1024 && value.matches(Regex("[A-Za-z0-9+/=\\r\\n]+"))) return "[binary-looking payload omitted: ${value.length} characters]"
                val json = if(value.trimStart().startsWith('{') || value.trimStart().startsWith('[')) runCatching {
                    val parser = org.json.JSONTokener(value)
                    parser.nextValue().also { require(parser.nextClean() == '\u0000') }
                }.getOrNull() else null
                val safe = if(json is JSONObject || json is JSONArray) clean(json, depth + 1).toString() else text(value)
                if(safe.length <= 120_000) safe else safe.take(110_000) + "\n[${safe.length - 120_000} characters omitted]\n" + safe.takeLast(10_000)
            }
            is Number, is Boolean -> value
            else -> text(value.toString())
        }
    }
    fun event(value: JSONObject): String {
        val sanitized = clean(value) as JSONObject
        val safe = sanitized.toString()
        if(safe.length <= MAX_EVENT_CHARS) return safe
        return JSONObject().put("truncated", true).put("originalSanitizedCharacters", safe.length)
            .put("preview", safe.take(64_000)).put("tail", safe.takeLast(10_000)).also { bounded ->
                listOf("sessionId","userMessageId","at","timeUtc","event","purpose").forEach { key ->
                    if(sanitized.has(key)) bounded.put(key,sanitized.opt(key))
                }
            }.toString()
    }
    companion object { const val MAX_EVENT_CHARS = 196_608 }
}

/** A session is bound to a conversation and privacy epoch. Late callbacks cannot restore deleted data. */
class ChatTrace internal constructor(
    val id: String = newId(), val conversationId: String, val userMessageId: String,
    private val write: suspend (JSONObject) -> Unit,
) {
    suspend fun record(kind: String, data: JSONObject = JSONObject()) = withContext(NonCancellable) {
        write(JSONObject().put("sessionId", id).put("userMessageId", userMessageId).put("at", now())
            .put("timeUtc",java.time.Instant.now().toString()).put("event", kind).put("data", data))
    }
    suspend fun error(kind: String, error: Throwable) = record(kind, JSONObject()
        .put("class", error.javaClass.simpleName).put("message", error.message.orEmpty())
        .put("httpStatus", (error as? com.kai.agent.inference.InferenceException)?.httpStatus ?: (error as? com.kai.agent.connections.ConnectionServiceException)?.statusCode ?: JSONObject.NULL))
}

class TraceContext(val trace: ChatTrace?, val taskId: String = "") : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<TraceContext>
}
