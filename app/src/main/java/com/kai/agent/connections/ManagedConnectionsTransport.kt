package com.kai.agent.connections

import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject

/** Fixed first-party managed service. Never forwards a project key or uses a desktop plugin. */
class ManagedConnectionsTransport : ConnectionsTransport {
    override suspend fun request(path: String, token: String?, body: JSONObject?): JSONObject =
        suspendCancellableCoroutine { continuation ->
            require(path in ALLOWED_PATHS) { "Unsupported managed connection operation" }
            require(token == null || (token.length <= 4096 && token.none { it == '\r' || it == '\n' }))
            val bodyBytes = body?.toString()?.toByteArray(Charsets.UTF_8)
            require(bodyBytes == null || bodyBytes.size <= 32 * 1024) { "Request is too large" }
            val active = AtomicReference<HttpsURLConnection?>()
            val future = executor.submit {
                var connection: HttpsURLConnection? = null
                var dispatched = false
                try {
                    if (!continuation.isActive) return@submit
                    connection = URL(ORIGIN + path).openConnection() as HttpsURLConnection
                    active.set(connection)
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 20_000
                    connection.readTimeout = 40_000
                    connection.useCaches = false
                    connection.setRequestProperty("Accept", "application/json")
                    connection.setRequestProperty("User-Agent", "KAI-Android/0.1")
                    if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
                    if (!continuation.isActive) return@submit
                    if (bodyBytes != null) {
                        connection.requestMethod = "POST"
                        connection.doOutput = true
                        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        connection.setFixedLengthStreamingMode(bodyBytes.size)
                        // From this point, a timeout/cancellation may hide a completed remote action.
                        dispatched = true
                        connection.outputStream.use { it.write(bodyBytes) }
                    }
                    val code = connection.responseCode
                    if (code in 300..399) throw ConnectionServiceException("The connection service redirected the request. Try again later.", code)
                    val source = if (code >= 400) connection.errorStream else connection.inputStream
                    val bytes = ByteArrayOutputStream()
                    source?.use { input ->
                        val buffer = ByteArray(8192)
                        while (continuation.isActive) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (bytes.size() + read > MAX_RESPONSE_BYTES) throw ConnectionServiceException("The connection response is too large. Narrow the request.", code)
                            bytes.write(buffer, 0, read)
                        }
                    }
                    if (!continuation.isActive) return@submit
                    val json = try { JSONObject(bytes.toString("UTF-8")) }
                    catch (_: Exception) { throw ConnectionServiceException("The connection service returned an unreadable response.", code) }
                    if (code !in 200..299 || !json.optBoolean("ok", true)) {
                        // Do not reflect remote error bodies: they may contain tool inputs or credentials.
                        throw ConnectionServiceException(messageFor(code), code,
                            path == EXECUTE && dispatched && code >= 500)
                    }
                    continuation.resume(json)
                } catch (error: Exception) {
                    if (continuation.isActive) {
                        val safe = ConnectionServiceException(
                            if (error is ConnectionServiceException) error.message ?: "Connection request failed."
                            else "Could not reach KAI Connections. Check your connection.",
                            (error as? ConnectionServiceException)?.statusCode,
                            // No idempotency contract exists. Even malformed success responses may hide a write.
                            outcomeMayBeUnknown = path == EXECUTE && dispatched
                        )
                        continuation.resumeWithException(safe)
                    }
                } finally {
                    active.set(null)
                    connection?.disconnect()
                }
            }
            continuation.invokeOnCancellation {
                active.getAndSet(null)?.disconnect()
                future.cancel(true)
            }
        }

    companion object {
        const val ORIGIN = "https://koinosai.com"
        private const val EXECUTE = "/connections/api/execute"
        private const val MAX_RESPONSE_BYTES = 512 * 1024
        private val executor = Executors.newFixedThreadPool(3) { runnable ->
            Thread(runnable, "kai-connections").apply { isDaemon = true }
        }
        private val ALLOWED_PATHS = setOf(
            "/auth/device/start", "/auth/device/poll", "/auth/session", "/auth/logout",
            "/connections/status", "/connections/api/catalog", "/connections/api/accounts",
            "/connections/api/connect", "/connections/api/disconnect", "/connections/api/tools",
            "/connections/api/tool", EXECUTE
        )
        private fun messageFor(code: Int) = when (code) {
            400 -> "Check the selected app and action inputs."
            401 -> "Your KAI sign-in expired. Sign in again."
            403 -> "This account does not have access to this action."
            404 -> "This app, action or sign-in code is no longer available."
            409 -> "The connection project changed. Refresh Connections."
            429 -> "KAI Connections is busy or its limit was reached. Try again shortly."
            503 -> "KAI Connections is not available right now."
            else -> "The connection service could not complete this request."
        }
    }
}
