package com.kai.agent.connections.mcp

import com.kai.agent.connections.ConnectionServiceException
import com.kai.agent.inference.SseDecoder
import com.kai.agent.web.PublicWebPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.Proxy
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Streamable HTTP tools profile. No stdio, server sampling, roots, MRTR replay or implicit OAuth. */
class McpHttpClient(private val server: McpServer, client: OkHttpClient? = null) {
    private val http=(client ?: OkHttpClient.Builder().proxy(Proxy.NO_PROXY).dns(object: Dns { override fun lookup(hostname: String): List<java.net.InetAddress> =
        Dns.SYSTEM.lookup(hostname).also { addresses -> if(addresses.isEmpty() || addresses.any { !PublicWebPolicy.isPublic(it) }) throw UnknownHostException("Public MCP addresses only") }
    }).build()).newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(15,TimeUnit.SECONDS).readTimeout(45,TimeUnit.SECONDS).callTimeout(60,TimeUnit.SECONDS).build()
    fun close() { http.dispatcher.cancelAll() }
    private val mutex=Mutex()
    private var initialized=false
    private var session=""
    suspend fun request(method: String, params: JSONObject = JSONObject()): JSONObject {
        require(method in setOf("server/discover","tools/list","tools/call"))
        server.validate()
        if(server.protocol=="2025-11-25") mutex.withLock {
            if(!initialized) {
                val result=rpc("initialize",JSONObject().put("protocolVersion",server.protocol).put("capabilities",JSONObject())
                    .put("clientInfo",JSONObject().put("name","KAI Android").put("version","1")))
                require(result.getString("protocolVersion")==server.protocol) { "MCP server selected an unsupported protocol version." }
                rpc("notifications/initialized",JSONObject(),notification=true);initialized=true
            }
        }
        return rpc(method,params)
    }
    private suspend fun rpc(method: String, params: JSONObject, notification: Boolean=false): JSONObject {
        val id=UUID.randomUUID().toString()
        val arguments=JSONObject(params.toString())
        if(server.protocol=="2026-07-28") arguments.put("_meta",JSONObject()
            .put("io.modelcontextprotocol/protocolVersion",server.protocol)
            .put("io.modelcontextprotocol/clientInfo",JSONObject().put("name","KAI Android").put("version","1"))
            .put("io.modelcontextprotocol/clientCapabilities",JSONObject()))
        val envelope=JSONObject().put("jsonrpc","2.0").put("method",method).put("params",arguments).apply { if(!notification) put("id",id) }
        val request=Request.Builder().url(server.endpoint).header("Accept","application/json, text/event-stream").header("MCP-Protocol-Version",server.protocol)
            .apply {
                if(server.protocol=="2026-07-28") { header("Mcp-Method",method);if(method=="tools/call") header("Mcp-Name",params.getString("name")) }
                if(session.isNotBlank() && server.protocol=="2025-11-25") header("Mcp-Session-Id",session)
                if(server.token.isNotBlank()) header("Authorization","Bearer "+server.token)
            }.post(envelope.toString().toRequestBody("application/json".toMediaType())).build()
        val value=exchange(http.newCall(request),id,notification)
        if(!notification) require(value.optString("jsonrpc")=="2.0") { "Unsupported MCP envelope." }
        if(value.has("error")) throw ConnectionServiceException("MCP server rejected $method (code ${value.getJSONObject("error").optInt("code")}).",outcomeMayBeUnknown=method=="tools/call")
        val result=value.optJSONObject("result") ?: if(notification) return JSONObject() else throw ConnectionServiceException("MCP returned no matching result.",outcomeMayBeUnknown=method=="tools/call")
        if(result.optString("resultType","complete")!="complete") throw ConnectionServiceException("This MCP operation needs an unsupported continuation. It was not replayed; check the service outcome.",outcomeMayBeUnknown=method=="tools/call")
        return result
    }
    private suspend fun exchange(call: Call, id: String, notification: Boolean): JSONObject = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object: Callback {
            override fun onFailure(call: Call,e: java.io.IOException) { if(continuation.isActive) continuation.resumeWithException(ConnectionServiceException("MCP connection interrupted.",outcomeMayBeUnknown=true)) }
            override fun onResponse(call: Call,response: Response) { response.use {
                try {
                    if(!response.isSuccessful) throw ConnectionServiceException("MCP endpoint returned HTTP ${response.code}. No redirect or automatic retry was followed.",response.code,outcomeMayBeUnknown=true)
                    response.header("Mcp-Session-Id")?.takeIf { server.protocol=="2025-11-25" }?.let { require(it.length<=256 && it.none { c -> c.code<32 });session=it }
                    val value=if(notification && response.code in setOf(202,204)) JSONObject() else {
                        val body=requireNotNull(response.body)
                        if(body.contentType()?.subtype=="event-stream") {
                            var matched: JSONObject?=null
                            SseDecoder.read(body.charStream().buffered()) { frame ->
                                val o=JSONObject(frame)
                                if(o.optString("id")==id) { matched=o;false } else { require(!o.has("method") || !o.has("id")) { "Server-initiated requests are unsupported." };true }
                            }
                            requireNotNull(matched)
                        } else {
                            require(body.contentLength()<=1_000_000)
                            val source=body.source();source.request(1_000_001);require(source.buffer.size<=1_000_000)
                            JSONObject(source.readUtf8()).also { require(it.optString("id")==id && it.optString("jsonrpc")=="2.0") }
                        }
                    }
                    if(continuation.isActive) continuation.resume(value)
                } catch(e: Exception) { if(continuation.isActive) continuation.resumeWithException(if(e is ConnectionServiceException) e else ConnectionServiceException("MCP returned an unsupported or oversized response.",outcomeMayBeUnknown=true)) }
            } }
        })
    }
}
