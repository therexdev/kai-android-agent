package com.kai.agent.connections

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

/** User-owned project key. Never include this object in prompts, analytics or logs. */
class ComposioCredentials(apiKey: String, val userId: String) {
    val apiKey: String = normalizeApiKey(apiKey)
    init {
        require(userId.isNotBlank() && userId.length <= 240 && userId.none { it.isISOControl() }) {
            "Enter a stable Composio user ID."
        }
    }
    override fun toString() = "ComposioCredentials(apiKey=[redacted], userId=[redacted])"

    companion object {
        /** Removes copy/paste wrappers only. Never repairs, truncates, or guesses the key itself. */
        fun normalizeApiKey(raw: String): String {
            require(raw.length <= 8192) { "Paste only the Composio project API key, not a request or configuration file." }
            fun trimOuter(value: String) = value.trim { it.isWhitespace() || it == '\uFEFF' || it == '\u200B' }
            fun unquote(value: String): String {
                val pairs = mapOf('"' to '"', '\'' to '\'', '\u201C' to '\u201D', '\u2018' to '\u2019')
                return if (value.length >= 2 && pairs[value.first()] == value.last()) trimOuter(value.substring(1, value.length - 1)) else value
            }
            var value = unquote(trimOuter(raw))
            // Common copies from curl or documentation. These are not alternate auth modes.
            value = when {
                value.startsWith("x-api-key:", ignoreCase = true) -> value.substringAfter(':')
                Regex("(?i)^Bearer[ \\t]+").containsMatchIn(value) -> value.replaceFirst(Regex("(?i)^Bearer[ \\t]+"), "")
                else -> value
            }
            value = unquote(trimOuter(value))
            require(!value.startsWith("http://", true) && !value.startsWith("https://", true) &&
                !value.startsWith("x-org-api-key:", true) && !value.startsWith("x-user-api-key:", true)) {
                "Use a Composio project API key, not a link, organization token, or user key."
            }
            require(value.isNotBlank() && value.length <= 4096 && value.all { it.code in 33..126 } &&
                value.none { it == '\'' || it == '"' }) {
                "Paste the complete project API key on one line. Remove embedded spaces, line breaks, or extra request text."
            }
            require(!value.startsWith("uak_")) {
                "This is a Composio user key. KAI needs a project API key from Composio Platform > your project > Settings > API Keys."
            }
            return value
        }
    }
}

interface DirectComposioTransport {
    suspend fun request(path: String, apiKey: String, method: String = "GET",
        query: Map<String, String> = emptyMap(), body: JSONObject? = null): JSONObject
}

/** No configurable origin, redirects, automatic retries, cookies, or response-body logging. */
class DirectComposioHttpTransport internal constructor(httpClient: OkHttpClient) : DirectComposioTransport {
    private class Attempt { val sent = AtomicBoolean(false) }
    private val http = httpClient.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).addNetworkInterceptor { chain ->
            // OkHttp can retry an explicit 503 Retry-After: 0 independently of connection retries.
            // Block any second network exchange, including that case, before transmitting it.
            if (chain.request().tag(Attempt::class.java)?.sent?.compareAndSet(false, true) != true)
                throw IOException("Automatic replay blocked")
            chain.proceed(chain.request())
        }.build()
    constructor() : this(OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build())

    override suspend fun request(path: String, apiKey: String, method: String,
        query: Map<String, String>, body: JSONObject?): JSONObject {
        val traceContext=kotlin.coroutines.coroutineContext[com.kai.agent.diagnostics.TraceContext]
        val trace=traceContext?.trace
        val requestId=com.kai.agent.data.newId()
        val started=System.nanoTime()
        val transportDetails=java.util.concurrent.atomic.AtomicReference(JSONObject())
        fun attachTransportDetails(target: JSONObject) { val details=transportDetails.get(); details.keys().forEach { key -> target.put(key,details.opt(key)) } }
        val diagnostic=JSONObject().put("requestId",requestId).put("taskId",traceContext?.taskId.orEmpty()).put("method",method).put("path",path)
        trace?.record("connection_prepare",JSONObject(diagnostic.toString()).put("query",JSONObject(query)).put("body",body ?: JSONObject.NULL))
        val limit = responseLimit(path, method)
        val normalizedKey = ComposioCredentials.normalizeApiKey(apiKey)
        require(query.size <= 12 && query.all { (key, value) ->
            key.matches(Regex("[a-z_]{1,40}")) && value.length <= 2000 && value.none { it.isISOControl() }
        })
        require(method == "POST" || body == null)
        val bytes = body?.toString()?.toByteArray(Charsets.UTF_8)
        require(bytes == null || bytes.size <= 32 * 1024)
        val url = (BASE_URL + path).toHttpUrl().newBuilder().apply {
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val request = Request.Builder().url(url).header("x-api-key", normalizedKey)
            .header("Accept", "application/json").header("User-Agent", "KAI-Android/0.1")
            .tag(Attempt::class.java, Attempt())
            .method(method, if (method == "POST") (bytes ?: ByteArray(0)).toRequestBody(JSON) else null).build()
        trace?.record("connection_dispatch",JSONObject(diagnostic.toString()))
        try {
        val result = suspendCancellableCoroutine<JSONObject> { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(ConnectionServiceException(
                        "Could not reach Composio. Check your connection.", outcomeMayBeUnknown = method != "GET"))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            transportDetails.set(JSONObject().put("httpStatus",response.code).put("serverRequestId",response.header("x-request-id").orEmpty())
                                .put("errorBody",if(response.isSuccessful) "" else runCatching { response.peekBody(16_384).string() }.getOrDefault("[unavailable]")))
                            if (!continuation.isActive) return
                            if (!response.isSuccessful) throw ConnectionServiceException(messageFor(response.code, path, method), response.code,
                                outcomeMayBeUnknown = method != "GET" && (response.code >= 500 || response.code == 408))
                            val content = response.body ?: throw IOException("Missing body")
                            if (content.contentLength() > limit) throw ConnectionServiceException(oversizeMessage(path))
                            val bytesOut = ByteArrayOutputStream()
                            content.byteStream().use { input ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (bytesOut.size() + count > limit) throw ConnectionServiceException(oversizeMessage(path))
                                    bytesOut.write(buffer, 0, count)
                                }
                            }
                            val result = JSONObject(bytesOut.toString("UTF-8"))
                            if (continuation.isActive) continuation.resume(result)
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(ConnectionServiceException(
                                (error as? ConnectionServiceException)?.message ?: "Composio returned an unreadable response.",
                                (error as? ConnectionServiceException)?.statusCode,
                                outcomeMayBeUnknown = (error as? ConnectionServiceException)?.outcomeMayBeUnknown == true ||
                                    (method != "GET" && response.isSuccessful)))
                        }
                    }
                }
            })
        }
        attachTransportDetails(diagnostic)
        diagnostic.put("elapsedMs",(System.nanoTime()-started)/1_000_000)
        if(path.startsWith("/tools")) diagnostic.put("result",result)
        else diagnostic.put("result","[account/auth payload omitted]")
        trace?.record("connection_response",diagnostic)
        return result
        } catch(error: Exception) {
            attachTransportDetails(diagnostic)
        diagnostic.put("elapsedMs",(System.nanoTime()-started)/1_000_000).put("class",error.javaClass.simpleName)
                .put("message",error.message.orEmpty()).put("outcomeMayBeUnknown",(error as? ConnectionServiceException)?.outcomeMayBeUnknown ?: (method != "GET"))
            trace?.record("connection_error",diagnostic)
            throw error
        }
    }

    companion object {
        const val BASE_URL = ComposioDiagnostics.API_BASE
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val slug = "[a-zA-Z0-9_-]{1,160}"
        /** Bounded per endpoint, after decompression; listing calls also request small pages. */
        internal fun responseLimit(path: String, method: String): Int = when {
            method == "GET" && path == "/toolkits" -> 4 * 1024 * 1024
            method == "GET" && path == "/tools" -> 6 * 1024 * 1024
            method == "GET" && path == "/connected_accounts" -> 4 * 1024 * 1024
            method == "GET" && (path == "/auth_configs" || path == "/toolkits/categories") -> 1024 * 1024
            method == "GET" && path.matches(Regex("/(tools|toolkits)/$slug")) -> 2 * 1024 * 1024
            method == "POST" && path == "/tools/execute/proxy" -> 128 * 1024
            method == "POST" && path.matches(Regex("/tools/execute/$slug")) -> 2 * 1024 * 1024
            method == "POST" && path in setOf("/auth_configs", "/connected_accounts/link") -> 128 * 1024
            method == "DELETE" && path.matches(Regex("/connected_accounts/$slug")) -> 64 * 1024
            else -> throw IllegalArgumentException("Unsupported Composio operation.")
        }
        private fun oversizeMessage(path: String) = if (path.startsWith("/tools/execute/"))
            "The action receipt is too large. Check the connected app before trying again."
            else "Composio returned an unusually large page. Narrow the search and try again."
        internal fun requiredPermission(path: String, method: String): String = when {
            path == "/tools/execute/proxy" -> "Proxy execution: Write"
            path.startsWith("/tools/execute/") -> "Tool execution (Legacy): Write"
            path.startsWith("/tools") -> "Tools: Read"
            path.startsWith("/toolkits") -> "Toolkits: Read"
            path.startsWith("/connected_accounts") -> "Connected accounts: ${if (method == "GET") "Read" else "Write"}"
            path.startsWith("/auth_configs") -> "Auth configs: ${if (method == "GET") "Read" else "Write"}"
            else -> "the requested operation"
        }
        private fun messageFor(code: Int, path: String, method: String) = when (code) {
            in 300..399 -> "Composio redirected this request. The API key was not forwarded."
            400, 422 -> "Composio could not accept these settings or action inputs. Check the app's auth configuration."
            401 -> "Composio denied this request (401). This can mean a wrong or revoked credential, the wrong key type, or missing permissions. " +
                "Use a project API key and check ${requiredPermission(path, method)}. Run Check access to narrow the cause."
            402 -> "Your Composio project needs additional credits or a different plan."
            403 -> "Composio denied permission (403). Check ${requiredPermission(path, method)} in this project's API key settings, " +
                "and the connected account's access. This does not prove the key is invalid."
            404 -> "The requested Composio resource was not found or is inaccessible in this key's project. Check that the key belongs to the intended project."
            409 -> "Composio reports a conflicting connection. Refresh Connections before trying again."
            429 -> "Composio's request limit was reached. Try again shortly."
            else -> "Composio could not complete this request."
        }
    }
}
