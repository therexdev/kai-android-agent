package com.kai.agent.web

import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class WebResearchException(message: String) : Exception(message)
data class WebDocument(val url: String, val body: String, val contentType: String)
fun interface WebTransport { suspend fun get(url: String, headers: Map<String, String>): WebDocument }

/** No cookies, account credentials, scripts, private network addresses or implicit redirects. */
class PublicWebTransport : WebTransport {
    private val http = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = Dns.SYSTEM.lookup(hostname).also { addresses ->
                if (addresses.isEmpty() || addresses.any { !PublicWebPolicy.isPublic(it) }) throw UnknownHostException("Public web addresses only")
            }
        })
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS).build()

    override suspend fun get(url: String, headers: Map<String, String>): WebDocument = withTimeout(30_000) {
        var target = PublicWebPolicy.url(url)
        repeat(5) {
            val request = Request.Builder().url(target).header("User-Agent", "KAI-Android/0.4.11 (+public web research)")
                .header("Accept", "text/html,application/json,text/plain;q=0.9").apply {
                    // Search credentials can only reach this fixed API origin and never a redirect.
                    if (headers.isNotEmpty()) {
                        require(target.host == "api.search.brave.com" && target.encodedPath == "/res/v1/web/search")
                        headers.forEach { (name, value) -> require(name == "X-Subscription-Token"); header(name, value) }
                    }
                }.build()
            val response = read(http.newCall(request))
            if (response.status in 300..399) {
                if (headers.isNotEmpty()) throw WebResearchException("Search service redirected its API request. No credential was forwarded.")
                target = PublicWebPolicy.url(target.resolve(response.location)?.toString().orEmpty())
            } else {
                if (response.status !in 200..299) throw WebResearchException(when (response.status) {
                    401, 403 -> "The web service denied access. Check the search key, or use the default search."
                    429 -> "The web service is temporarily rate-limited. Try again later."
                    else -> "The web service could not return this page (HTTP ${response.status})."
                })
                return@withTimeout WebDocument(target.toString(), response.body, response.type)
            }
        }
        throw WebResearchException("The web page redirected too many times.")
    }

    private data class Read(val status: Int, val location: String, val type: String, val body: String)
    private suspend fun read(call: Call): Read = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(WebResearchException("Could not reach the public web service. Check your internet connection."))
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        val body = response.body
                        val type = body?.contentType()?.toString().orEmpty()
                        val text = if (response.isSuccessful) {
                            require(type.substringBefore(';').lowercase() in setOf("text/html", "application/xhtml+xml", "text/plain", "application/json"))
                            require(body != null && body.contentLength() <= MAX_BYTES)
                            val source = body.source()
                            source.request(MAX_BYTES + 1L)
                            require(source.buffer.size <= MAX_BYTES)
                            source.readString(body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
                        } else ""
                        if (continuation.isActive) continuation.resume(Read(response.code, response.header("Location").orEmpty(), type, text))
                    } catch (_: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(WebResearchException("This page is too large or is not readable web text."))
                    }
                }
            }
        })
    }
    companion object { const val MAX_BYTES = 2 * 1024 * 1024L }
}

object PublicWebPolicy {
    fun url(value: String): HttpUrl {
        val url = value.takeIf { it.length <= 2048 }?.toHttpUrlOrNull()
            ?: throw WebResearchException("Use a complete public HTTPS page address.")
        if (url.scheme != "https" || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            !url.host.contains('.') && !url.host.contains(':') ||
            url.host.lowercase().let { it == "localhost" || it.endsWith(".localhost") || it.endsWith(".local") || it.endsWith(".internal") })
            throw WebResearchException("Only public HTTPS web pages can be read.")
        // Numeric addresses are checked before networking; resolved addresses are checked by DNS.
        if (url.host.contains(':') || url.host.matches(Regex("[0-9.]+"))) {
            if (!isPublic(InetAddress.getByName(url.host))) throw WebResearchException("Private network pages cannot be read by web search.")
        }
        return url.newBuilder().fragment(null).build()
    }
    fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
        val b = address.address.map { it.toInt() and 255 }
        if (b.size == 4) return !(b[0] == 0 || b[0] == 10 || b[0] == 127 || b[0] >= 224 ||
            b[0] == 100 && b[1] in 64..127 || b[0] == 169 && b[1] == 254 || b[0] == 172 && b[1] in 16..31 ||
            b[0] == 192 && (b[1] == 168 || b[1] == 0 || b[1] == 2) || b[0] == 198 && (b[1] in 18..19 || b[1] == 51 && b[2] == 100) ||
            b[0] == 203 && b[1] == 0 && b[2] == 113)
        // Accept global unicast only; exclude documentation, Teredo, 6to4 and mapped ranges.
        return b.size == 16 && b[0] in 0x20..0x3f && !(b[0] == 0x20 && b[1] == 0x02) &&
            !(b[0] == 0x20 && b[1] == 0x01 && (b[2] < 2 || b[2] == 0x0d && b[3] == 0xb8))
    }
}
