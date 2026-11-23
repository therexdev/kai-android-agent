package com.kai.agent.inference

import com.kai.agent.diagnostics.TraceContext
import kotlinx.coroutines.currentCoroutineContext
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/** Both providers only infer; this client never executes tools or stores agent memory. */
class InferenceClient(client: OkHttpClient? = null,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val timeoutPolicy: (ProviderId) -> InferenceTimeoutPolicy = InferenceTimeoutPolicy::forProvider
) {
    private data class CatalogKey(val provider: ProviderId, val endpoint: String, val credentialHash: String, val model: String)
    private data class ContextCapability(val window: Int?, val automatic: Boolean, val checkedAt: Long)
    private val contextWindows = java.util.concurrent.ConcurrentHashMap<CatalogKey, ContextCapability>()
    private val contextRefresh = Mutex()
    private fun catalogKey(config: ProviderConfig, model: String = config.modelId) = CatalogKey(config.provider, config.endpoint, java.security.MessageDigest.getInstance("SHA-256").digest(config.apiKey.toByteArray()).joinToString("") { "%02x".format(it) }, model)
    private val http = (client ?: OkHttpClient()).newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()
    private val catalogHttp = http.newBuilder().callTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()

    /** Use the authenticated model's limit, not the provider label or last allocation. */
    suspend fun usesCompactAgentContext(config: ProviderConfig): Boolean {
        val window = if(config.accountSession) config.contextTokens else if (config.provider == ProviderId.KOINOS) contextCapability(config).window ?: 4096 else null
        val compact = window != null && window < 16384
        currentCoroutineContext()[TraceContext]?.trace?.record("agent_context_policy", JSONObject()
            .put("model", config.modelId).put("contextLimit", window ?: JSONObject.NULL).put("compact", compact))
        return compact
    }

    private suspend fun contextCapability(config: ProviderConfig): ContextCapability = contextRefresh.withLock {
        val key = catalogKey(config)
        contextWindows[key]?.takeIf { clockMillis() - it.checkedAt in 0 until 60_000 }?.let { return@withLock it }
        // Every new client (including saved tasks and app restarts) discovers its
        // own authenticated endpoint. A stale or another account's limit is never reused.
        contextWindows.remove(key)
        try { discoverModels(config) }
        catch (error: CancellationException) { throw error }
        catch (error: InferenceException) {
            currentCoroutineContext()[TraceContext]?.trace?.error("model_catalog_error",error)
            if (error.httpStatus in setOf(401, 403, 429) || error.httpStatus?.let { it in 300..399 } == true) throw error
            // Older endpoints can omit discovery. Small requests retain the
            // conservative fallback; this is not a claim about runtime capacity.
        }
        contextWindows[key] ?: ContextCapability(null, false, clockMillis()).also { contextWindows[key] = it }
    }

    suspend fun discoverModels(config: ProviderConfig): List<InferenceModel> {
        validate(config, requireModel = false)
        val request = baseRequest(config, "models").get().build()
        val json = executeJson(catalogHttp.newCall(request))
        val data = json.optJSONArray("data") ?: throw InferenceException("The API did not return an OpenAI-compatible model catalog.")
        val scope = catalogKey(config)
        contextWindows.keys.removeAll { it.copy(model = scope.model) == scope }
        val checkedAt = clockMillis()
        val models = (0 until minOf(data.length(), 2000)).mapNotNull { index ->
            val model = data.optJSONObject(index) ?: return@mapNotNull null
            val id = model.optString("id").trim()
            val window = sequenceOf("context_window", "context_length", "contextSize").mapNotNull { key ->
                val number = model.opt(key) as? Number
                number?.toDouble()?.takeIf { it.isFinite() && it in 1024.0..1_048_576.0 && it == it.toInt().toDouble() }?.toInt()
            }.firstOrNull()
            if (id.isEmpty() || id.length > 300) null else {
                if(contextWindows.size > 2000) contextWindows.clear()
                contextWindows[catalogKey(config,id)] = ContextCapability(window, window != null && model.optString("context_mode") == "auto", checkedAt)
                InferenceModel(id, model.optString("name").ifBlank { id }, window, window != null && model.optString("context_mode") == "auto")
            }
        }.distinctBy { it.id }.sortedBy { it.displayName.lowercase() }.take(2000)
        currentCoroutineContext()[TraceContext]?.trace?.record("model_catalog",JSONObject().put("models",JSONArray(models.map {
            JSONObject().put("id",it.id).put("allowedContextTokens",it.contextTokens ?: JSONObject.NULL).put("automaticContext",it.automaticContext) })))
        return models
    }

    /** Model discovery is a read-only connection check; no paid generation is hidden in this action. */
    suspend fun testConnection(config: ProviderConfig): ConnectionCheck {
        val models = discoverModels(config)
        val available = config.modelId.isNotBlank() && models.any { it.id == config.modelId }
        return ConnectionCheck(models, available, when {
            models.isEmpty() -> "Connected. This API returned no ready models."
            config.modelId.isBlank() -> "Connected. Choose one of ${models.size} available models."
            available -> "Connected · ${config.modelId}." + if (config.provider == ProviderId.KOINOS) " " + models.first { it.id == config.modelId }.contextStatus() else ""
            else -> "Connected, but the selected model is not in the current catalog."
        })
    }

    /** Explicit local inference check. Uses synthetic text only and never enters the agent loop. */
    suspend fun testResponse(config: ProviderConfig): String {
        require(config.provider == ProviderId.KOINOS && config.routing == RoutingMode.LOCAL_ONLY) { "Test response is available for local Koinos AI models." }
        val check = testConnection(config)
        if (!check.selectedModelAvailable) throw InferenceException("Choose an installed model before testing a response.")
        val result = stream(config.copy(maxOutputTokens = 128), listOf(
            InferenceMessage("system", "This is a connection test. Reply with OK."),
            InferenceMessage("user", "Reply with OK.")
        )) {}
        if (result.text.isBlank() || result.finishReason !in setOf("stop", "eos")) {
            throw InferenceException("The API responded, but the model did not finish the short test reply. ${result.status(config)}")
        }
        return "Model response received. ${result.status(config)}"
    }

    suspend fun stream(
        config: ProviderConfig,
        messages: List<InferenceMessage>,
        onDelta: suspend (String) -> Unit,
    ): Completion {
        val trace=currentCoroutineContext()[TraceContext]?.trace
        val requestId=com.kai.agent.data.newId()
        val started=clockMillis()
        val pending=java.util.concurrent.ConcurrentLinkedQueue<Pair<String,JSONObject>>()
        suspend fun drain() { while(true) { val item=pending.poll() ?: break; trace?.record(item.first,item.second.put("requestId",requestId)) } }
        trace?.record("model_start",JSONObject().put("requestId",requestId).put("provider",config.provider.name).put("model",config.modelId)
            .put("endpoint",config.endpoint).put("routing",config.routing.name).put("messageCount",messages.size).put("toolProtocol",if(config.nativeTools) "native" else "legacy_json").put("toolCount",config.tools.size)
            .put("inputEstimateBeforeBudget",InferenceBudget.estimate(messages)).put("requestedMaxOutputTokens",config.maxOutputTokens))
        val text = StringBuilder()
        var finishReason: String? = null
        var usage: TokenUsage? = null
        var activeContext: Int? = null
        var toolCalls = emptyList<ToolCall>()
        try {
        events(config, messages, requestId) { kind,data -> if(trace!=null && pending.size<24) pending.add(kind to data) }.collect { event ->
            drain()
            when (event) {
                is StreamEvent.Delta -> { text.append(event.text); onDelta(event.text) }
                is StreamEvent.Finished -> { finishReason = event.finishReason; usage = event.usage; activeContext = event.activeContextTokens; toolCalls = event.toolCalls }
            }
        }
        drain()
        trace?.record("model_response",JSONObject().put("requestId",requestId).put("elapsedMs",clockMillis()-started)
            .put("output",text.toString()).put("toolCalls",JSONArray(toolCalls.map(NativeToolWire::callJson))).put("finishReason",finishReason ?: JSONObject.NULL)
            .put("inputTokens",usage?.inputTokens ?: JSONObject.NULL).put("outputTokens",usage?.outputTokens ?: JSONObject.NULL)
            .put("activeContextTokens",activeContext ?: JSONObject.NULL))
        return Completion(text.toString(), finishReason, usage, activeContext, toolCalls)
        } catch(error: Exception) {
            drain()
            trace?.record("model_error",JSONObject().put("requestId",requestId).put("elapsedMs",clockMillis()-started)
                .put("class",error.javaClass.simpleName).put("message",error.message.orEmpty())
                .put("httpStatus",(error as? InferenceException)?.httpStatus ?: JSONObject.NULL).put("partialOutput",text.toString()))
            throw error
        }
    }

    fun events(config: ProviderConfig, messages: List<InferenceMessage>, requestId: String = "",
        diagnostic: (String,JSONObject)->Unit = { _,_ -> }): Flow<StreamEvent> = callbackFlow {
        validate(config, requireModel = true)
        validateMessages(messages)
        NativeToolWire.validate(messages, config.nativeTools)
        require(messages.sumOf { it.images.size } <= 2) { "Attach at most two photos per message." }
        require(config.provider == ProviderId.OPENAI || messages.all { it.images.isEmpty() }) { "Photo analysis needs an OpenAI-compatible vision model. Extracted text works with either provider." }
        messages.forEach { message -> require(message.images.all { message.role == "user" && it.startsWith("data:image/jpeg;base64,") && it.length <= 1000100 && it.substringAfter(',').matches(Regex("[A-Za-z0-9+/=]+")) }) { "Invalid image attachment." } }
        if(config.provider == ProviderId.KOINOS && messages.sumOf { it.content.toByteArray(Charsets.UTF_8).size.toLong() } > 128 * 1024) {
            throw InferenceException("This request exceeds Koinos AI's 128 KB limit. Shorten the conversation or attached content.")
        }
        val context = if(config.accountSession) ContextCapability(config.contextTokens, false, clockMillis()) else if (config.provider == ProviderId.KOINOS) contextCapability(config) else null
        currentCoroutineContext()[TraceContext]?.trace?.record("model_context",JSONObject().put("requestId",requestId)
            .put("allowedContextTokens",context?.window ?: JSONObject.NULL).put("automaticContext",context?.automatic ?: false)
            .put("fallbackContextTokens",if(context?.window == null && config.provider==ProviderId.KOINOS) 4096 else JSONObject.NULL))
        val schemaReserve = if(config.tools.isEmpty()) 0 else (JSONArray(config.tools.map(NativeToolWire::toolJson)).toString().toByteArray().size + 2) / 3 + 64
        val prepared = try { InferenceBudget.prepare(config, messages, context?.window, schemaReserve) }
        catch(error: InferenceException) {
            currentCoroutineContext()[TraceContext]?.trace?.record("model_budget_rejected",JSONObject().put("requestId",requestId)
                .put("message",error.message).put("messages",JSONArray(messages.map { JSONObject().put("role",it.role)
                    .put("content",it.content).put("canOmit",it.canOmit).put("compactContent",it.compactContent ?: JSONObject.NULL).put("imageCount",it.images.size) })))
            throw error
        }
        val body = JSONObject().put("model", config.modelId)
            .put("stream", true)
            .put("messages", JSONArray().apply {
                prepared.messages.forEach { message ->
                    val content: Any = if(message.images.isEmpty()) message.content else JSONArray().apply {
                        put(JSONObject().put("type","text").put("text",message.content))
                        message.images.forEach { put(JSONObject().put("type","image_url").put("image_url",JSONObject().put("url",it).put("detail","auto"))) }
                    }
                    put(NativeToolWire.messageJson(message).put("content",content))
                }
            })
        val tokenKey = if (config.provider == ProviderId.OPENAI) "max_completion_tokens" else "max_tokens"
        body.put(tokenKey, prepared.outputTokens)
        if(config.accountSession) {
            // Omitted means automatic connected-account selection. Explicit
            // null pins the walletless account and cannot use linked funds.
            if(config.creditWallet.isNotBlank())body.put("creditWallet",config.creditWallet)
            body.put("freeOnly",config.freeOnly).put("stream_options",JSONObject().put("include_usage",true))
        }
        if (context?.automatic == true) body.put("context_tokens", InferenceBudget.estimate(prepared.messages) + prepared.outputTokens + 512)
        currentCoroutineContext()[TraceContext]?.trace?.record("model_request",JSONObject().put("requestId",requestId)
            .put("model",config.modelId).put("stream",true).put("generation",JSONObject().put(tokenKey,prepared.outputTokens)
                .put("context_tokens",body.opt("context_tokens") ?: JSONObject.NULL))
            .put("inputEstimateAfterBudget",InferenceBudget.estimate(prepared.messages)).put("templateReserveTokens",if(config.provider==ProviderId.KOINOS) 512 else 0)
            .put("omittedMessages",messages.size-prepared.messages.size)
            .put("compactedMessages",prepared.messages.count { item -> messages.none { it.role==item.role && it.content==item.content } })
            .put("messages",JSONArray(prepared.messages.map { NativeToolWire.messageJson(it).put("imageCount",it.images.size) })))
        var payload = body.toString()
        if (config.provider == ProviderId.KOINOS && payload.toByteArray(Charsets.UTF_8).size > 128 * 1024) {
            throw InferenceException("This request exceeds Koinos AI's 128 KB limit. Shorten the conversation or attached content.")
        }
        if(config.tools.isNotEmpty()) {
            require(config.nativeTools) { "This model and gateway have not advertised native tool support." }
            body.put("tools",JSONArray(config.tools.map(NativeToolWire::toolJson))).put("tool_choice","auto").put("parallel_tool_calls",false)
            payload = body.toString()
            require(payload.toByteArray().size <= 128 * 1024) { "The tool schemas exceed the request limit. Discover fewer actions." }
        }
        val request = baseRequest(config, "chat/completions")
            .header("Accept", "text/event-stream")
            .post(payload.toRequestBody(JSON)).build()
        val timing = timeoutPolicy(config.provider)
        val submittedAt = clockMillis()
        val phase = java.util.concurrent.atomic.AtomicReference("connecting")
        val headersReceived = java.util.concurrent.atomic.AtomicBoolean(false)
        val contentReceived = java.util.concurrent.atomic.AtomicBoolean(false)
        fun elapsed() = clockMillis() - submittedAt
        fun firstContent() {
            phase.set("receiving_content")
            if (contentReceived.compareAndSet(false, true)) diagnostic("model_first_content", JSONObject().put("elapsedMs", elapsed()))
        }
        fun failure(error: Exception): Exception {
            val timeout = error is java.io.InterruptedIOException
            diagnostic("model_transport_error", JSONObject().put("elapsedMs", elapsed()).put("phase", phase.get())
                .put("headersReceived", headersReceived.get()).put("contentReceived", contentReceived.get())
                .put("timeout", timeout).put("causeClass", error.javaClass.simpleName)
                .put("connectTimeoutMs", timing.connectMillis).put("readTimeoutMs", timing.readMillis).put("callTimeoutMs", timing.callMillis))
            if (!timeout) return sanitize(error)
            val waitingFor = when (phase.get()) {
                "connecting" -> "connecting to the AI provider"
                "awaiting_response" -> if(config.accountSession) "waiting for your selected network model to respond" else if (config.provider == ProviderId.KOINOS) "waiting for the local model to respond" else "waiting for the AI provider to respond"
                else -> "waiting for the rest of the model response"
            }
            return InferenceException("Timed out $waitingFor. " +
                if (config.provider == ProviderId.KOINOS && !config.accountSession && phase.get() != "connecting")
                    "KAI Core may still be loading or processing the request. Export Report for timing details."
                else "Check the connection and export Report for timing details.")
        }
        diagnostic("model_transport_start", JSONObject().put("connectTimeoutMs", timing.connectMillis)
            .put("readTimeoutMs", timing.readMillis).put("callTimeoutMs", timing.callMillis))
        val call = http.newBuilder()
            .connectTimeout(timing.connectMillis, TimeUnit.MILLISECONDS)
            .readTimeout(timing.readMillis, TimeUnit.MILLISECONDS)
            .callTimeout(timing.callMillis, TimeUnit.MILLISECONDS)
            .eventListener(object : okhttp3.EventListener() {
                override fun requestBodyEnd(call: Call, byteCount: Long) {
                    phase.set("awaiting_response")
                    diagnostic("model_request_sent", JSONObject().put("elapsedMs", elapsed()).put("payloadBytes", byteCount))
                }
            }).build().newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!isClosedForSend) close(failure(e))
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        headersReceived.set(true)
                        phase.set("awaiting_content")
                        diagnostic("model_http",JSONObject().put("httpStatus",response.code)
                            .put("elapsedMs", elapsed())
                            .put("contentType",response.header("Content-Type").orEmpty())
                            .put("serverRequestId",response.header("x-request-id") ?: response.header("request-id") ?: "")
                            .put("errorBody",if(response.isSuccessful) "" else runCatching { response.peekBody(16_384).string() }.getOrDefault("[unavailable]")))
                        if (!response.isSuccessful) throw responseError(response,config)
                        val responseBody = response.body ?: throw InferenceException("The model returned an empty response.")
                        val type = response.header("Content-Type").orEmpty()
                        var activeContext = response.header("x-kai-context-size")?.toIntOrNull()?.takeIf { it in 1024..1_048_576 }
                        if (!type.contains("text/event-stream", ignoreCase = true)) {
                            // Some compatible servers ignore stream=true and return a standard completion.
                            val json = JSONObject(readBounded(responseBody.charStream().buffered()))
                            if(!json.isNull("error")) diagnostic("model_api_error",json)
                            checkApiError(json)
                            val choice = json.optJSONArray("choices")?.optJSONObject(0)
                                ?: throw InferenceException("The model returned an unsupported response.")
                            val message = choice.optJSONObject("message")
                            if(!config.nativeTools && (message?.has("tool_calls")==true || message?.has("function_call")==true)) diagnostic("unsupported_tool_format",json)
                            if(!config.nativeTools) rejectToolCalls(message)
                            val native = NativeToolWire.Accumulator().apply { add(message?.optJSONArray("tool_calls"), false) }.finish()
                            val text = message?.optString("content")?.takeUnless { it == "null" }.orEmpty()
                            if (text.length > MAX_OUTPUT_CHARS) throw InferenceException("The model response exceeded the supported size.")
                            if (text.isNotEmpty()) { firstContent(); trySendBlocking(StreamEvent.Delta(text)).getOrThrow() }
                            trySendBlocking(StreamEvent.Finished(choice.optString("finish_reason").takeIf { it.isNotBlank() && it != "null" }, usage(json), activeContext ?: verifiedContext(json), native)).getOrThrow()
                            close()
                            return
                        }
                        var finished = false
                        var reason: String? = null
                        var tokenUsage: TokenUsage? = null
                        var characters = 0
                        val native = NativeToolWire.Accumulator()
                        SseDecoder.read(responseBody.charStream().buffered()) { frame ->
                            if (frame.trim() == "[DONE]") { finished = true; return@read false }
                            val json = try { JSONObject(frame) } catch (_: Exception) {
                                throw InferenceException("The model returned an invalid streaming frame.")
                            }
                            if(!json.isNull("error")) diagnostic("model_api_error",json)
                            checkApiError(json)
                            verifiedContext(json)?.let { activeContext = it }
                            usage(json)?.let { tokenUsage = it }
                            val choice = json.optJSONArray("choices")?.optJSONObject(0)
                            if (choice != null) {
                                val delta = choice.optJSONObject("delta")
                                if(!config.nativeTools && (delta?.has("tool_calls")==true || delta?.has("function_call")==true)) diagnostic("unsupported_tool_format",json)
                                if(!config.nativeTools) rejectToolCalls(delta)
                                native.add(delta?.optJSONArray("tool_calls"))
                                val value = delta?.optString("content")?.takeUnless { it == "null" }.orEmpty()
                                characters += value.length
                                if (characters > MAX_OUTPUT_CHARS) throw InferenceException("The model response exceeded the supported size.")
                                if (value.isNotEmpty()) {
                                    firstContent()
                                    if (trySendBlocking(StreamEvent.Delta(value)).isFailure) return@read false
                                }
                                if (!choice.isNull("finish_reason")) reason = choice.optString("finish_reason").takeIf { it.isNotBlank() }
                            }
                            !isClosedForSend
                        }
                        if (isClosedForSend) return
                        if (!finished && reason == null) throw InferenceException("The model stream ended early. The partial response was not completed.")
                        trySendBlocking(StreamEvent.Finished(reason, tokenUsage, activeContext, native.finish())).getOrThrow()
                        close()
                    } catch (error: Exception) {
                        if (!isClosedForSend) close(failure(error))
                    }
                }
            }
        })
        awaitClose { call.cancel() }
    }

    private fun baseRequest(config: ProviderConfig, resource: String) = Request.Builder()
        .url(EndpointPolicy.resource(config.endpoint, if(config.accountSession && resource=="chat/completions") "chat" else resource))
        .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
        .header("Authorization", "Bearer ${config.apiKey.trim()}")
        .header("Accept", "application/json")

    private fun validate(config: ProviderConfig, requireModel: Boolean) {
        EndpointPolicy.validateRoute(config)
        EndpointPolicy.base(config.endpoint)
        if (config.apiKey.isBlank() || config.apiKey.length > 8192 || config.apiKey.trim().any { it.code !in 33..126 }) {
            throw InferenceException("Sign in from Account to use KAI.")
        }
        if (requireModel && (config.modelId.isBlank() || config.modelId.length > 300)) {
            throw InferenceException("Choose an available model in Account first.")
        }
    }

    private fun validateMessages(messages: List<InferenceMessage>) {
        if (messages.isEmpty() || messages.size > 100) throw InferenceException("This conversation needs a shorter context window.")
        if (messages.any { it.role !in setOf("system", "user", "assistant", "tool") }) throw InferenceException("Unsupported message role.")
        if (messages.sumOf { it.content.length.toLong() } > MAX_INPUT_CHARS) throw InferenceException("This conversation is too large. Start a new conversation or shorten the attached content.")
    }

    private suspend fun executeJson(call: Call): JSONObject = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(networkError(e))
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (!response.isSuccessful) throw responseError(response)
                        val reader = response.body?.charStream()?.buffered() ?: throw InferenceException("The API returned an empty response.")
                        val json = JSONObject(readBounded(reader))
                        checkApiError(json)
                        if (continuation.isActive) continuation.resume(json)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(sanitize(e))
                    }
                }
            }
        })
    }

    private fun readBounded(reader: java.io.Reader): String {
        val text = StringBuilder()
        val buffer = CharArray(4096)
        while (true) {
            val count = reader.read(buffer)
            if (count == -1) break
            if (text.length + count > SseDecoder.MAX_RESPONSE_CHARS) throw InferenceException("The API response exceeded the supported size.")
            text.append(buffer, 0, count)
        }
        return text.toString()
    }

    private fun rejectToolCalls(value: JSONObject?) {
        if ((value?.optJSONArray("tool_calls")?.length() ?: 0) > 0 || value?.optJSONObject("function_call") != null) {
            throw InferenceException("This response used an unsupported tool-call format. No action was executed.")
        }
    }

    private fun checkApiError(json: JSONObject) {
        if (!json.isNull("error")) {
            val reported = json.optJSONObject("error")?.opt("status") as? Number
            val status = reported?.toDouble()?.takeIf { it.isFinite() && it in 400.0..599.0 && it == it.toInt().toDouble() }?.toInt()
            throw classifiedError(json.opt("error")?.toString().orEmpty(), status)
        }
    }

    private fun verifiedContext(json: JSONObject): Int? {
        val reported = json.optJSONObject("kai_context")?.takeIf { it.opt("verified") == true }
            ?.opt("contextSize") as? Number
        return reported?.toDouble()?.takeIf { it.isFinite() && it in 1024.0..1_048_576.0 && it == it.toInt().toDouble() }?.toInt()
    }

    private fun responseError(response: Response, config: ProviderConfig? = null): InferenceException {
        if(config?.accountSession == true) {
            val accountMessage = when(response.code) {
                401 -> "Your sign-in expired. Open Account and sign in again."
                402 -> "This request needs available credits. Open Account, choose a connected credit account and enable Use purchased credits, or add credits."
                403 -> "Your account or selected wallet is not authorized for this request. Open Account and refresh your access."
                404 -> "This model is no longer available. Open Account and choose an available model."
                429 -> "The account service is limiting requests or credits. Wait a moment, then check your balance in Account."
                in 300..399 -> "The account service redirected this request. Credentials were not forwarded. Try again later."
                else -> null
            }
            if(accountMessage != null) return InferenceException(accountMessage,response.code)
            if(response.code == 400) {
                val detail=runCatching { response.peekBody(16_384).string() }.getOrDefault("")
                if(Regex("insufficient.{0,40}credit|not enough.{0,40}credit|free.only|free credits only|purchased credits",RegexOption.IGNORE_CASE).containsMatchIn(detail))
                    return InferenceException("Open Account to check your connected credit account and Use purchased credits setting, then try again.",400)
            }
        }
        if(response.code !in setOf(400,404,413,422,500,502,503)) return statusError(response.code)
        val body = runCatching { response.peekBody(16_384).string() }.getOrDefault("")
        return classifiedError(body, response.code)
    }
    /** Classify only. Never echo provider text, which may contain credentials or the prompt. */
    private fun classifiedError(body: String, status: Int?): InferenceException {
        val message = InferenceRejection.describe(body, status) ?: return statusError(status ?: 400)
        return InferenceException(message,status)
    }

    private fun usage(json: JSONObject): TokenUsage? = json.optJSONObject("usage")?.let {
        TokenUsage(it.optInt("prompt_tokens", 0).coerceAtLeast(0), it.optInt("completion_tokens", 0).coerceAtLeast(0))
    }

    private fun statusError(code: Int) = InferenceException(when (code) {
        401, 403 -> "The API did not authorize this request. Check your API key and model access."
        404 -> "The API endpoint or selected model was not found. Check the API base URL."
        408, 504 -> "The AI provider timed out. Your saved conversation is unchanged."
        429 -> "The AI provider is rate-limiting requests or its quota is exhausted. Try later or check usage."
        in 300..399 -> "The API redirected this request. Enter its final HTTPS endpoint; credentials were not forwarded."
        in 500..599 -> "The AI provider is temporarily unavailable. Try again when the service recovers."
        else -> "The AI provider rejected this request (HTTP $code). Check model compatibility and input size."
    }, code)

    private fun networkError(e: IOException) = InferenceException(
        if (e is java.net.SocketTimeoutException) "The AI connection timed out. Check the endpoint and connection."
        else "Could not reach the AI provider securely. Check connectivity and the API endpoint."
    )
    private fun sanitize(e: Exception): Exception = when (e) {
        is InferenceException, is CancellationException -> e
        is IOException -> networkError(e)
        else -> InferenceException("The AI provider returned a response this app could not read.")
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        const val MAX_INPUT_CHARS = 100_000L
        const val MAX_OUTPUT_CHARS = 100_000
    }
}
