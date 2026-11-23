package com.kai.agent.inference

enum class ProviderId { KOINOS, OPENAI }
enum class RoutingMode { LOCAL_ONLY, LOCAL_FIRST, NETWORK_ONLY }

/** A user's credential. Never include its value in diagnostics or generated prompts. */
data class ProviderConfig(
    val provider: ProviderId,
    val endpoint: String,
    val apiKey: String,
    val modelId: String,
    val routing: RoutingMode = RoutingMode.LOCAL_ONLY,
    val maxOutputTokens: Int = 2048,
    val accountSession: Boolean = false,
    val creditWallet: String = "",
    val freeOnly: Boolean = true,
    val contextTokens: Int? = null,
    val modelOutputLimit: Int? = null,
    val nativeTools: Boolean = false,
    val tools: List<InferenceTool> = emptyList(),
) {
    override fun toString() = "ProviderConfig(provider=$provider, modelId=$modelId, routing=$routing, apiKey=<redacted>)"
}

data class ToolCall(val id: String, val name: String, val arguments: String)
data class InferenceTool(val name: String, val description: String, val parameters: String)
data class InferenceMessage(val role: String, val content: String, val images: List<String> = emptyList(), val canOmit: Boolean = false, val compactContent: String? = null,
    val toolCalls: List<ToolCall> = emptyList(), val toolCallId: String = "")
data class InferenceModel(val id: String, val displayName: String = id, val contextTokens: Int? = null, val automaticContext: Boolean = false) {
    fun contextStatus(): String = when {
        contextTokens == null -> "Context limit not reported; using a conservative 4,096-token budget."
        automaticContext -> "Automatic context: up to ${String.format(java.util.Locale.US, "%,d", contextTokens)} tokens."
        else -> "API context limit: ${String.format(java.util.Locale.US, "%,d", contextTokens)} tokens."
    }
}
data class TokenUsage(val inputTokens: Int, val outputTokens: Int)
data class Completion(val text: String, val finishReason: String?, val usage: TokenUsage? = null, val activeContextTokens: Int? = null, val toolCalls: List<ToolCall> = emptyList()) {
    fun status(config: ProviderConfig): String = "Connected · ${config.modelId}" +
        if (config.provider == ProviderId.KOINOS) activeContextTokens?.let {
            " · API reports active context: ${String.format(java.util.Locale.US, "%,d", it)} tokens."
        } ?: " · Active context was not reported by this API." else ""
}
data class ConnectionCheck(val models: List<InferenceModel>, val selectedModelAvailable: Boolean, val message: String)

sealed interface StreamEvent {
    data class Delta(val text: String) : StreamEvent
    data class Finished(val finishReason: String?, val usage: TokenUsage?, val activeContextTokens: Int? = null, val toolCalls: List<ToolCall> = emptyList()) : StreamEvent
}

class InferenceException(message: String, val httpStatus: Int? = null) : Exception(message)
