package com.kai.agent.inference

import org.json.JSONObject

/** Fixed diagnostic vocabulary only: no provider text, prompts, paths or credentials reach the UI. */
internal object InferenceRejection {
    fun isContextOverflow(body: String): Boolean {
        val text=body.lowercase()
        return listOf("context_length_exceeded","context_window_exceeded","context_limit","maximum context length","context limit exceeded","prompt too large","too many tokens","128 kb","request entity too large").any { it in text }
    }
    fun describe(body: String, status: Int?): String? {
        val root = runCatching { JSONObject(body) }.getOrNull()
        val error = root?.optJSONObject("error") ?: root
        val text = body.lowercase()
        val code = error?.optString("code").orEmpty()
        val source = when (error?.optString("source")) {
            "request_validation" -> "Remote Access validation"
            "local_model" -> "Local model"
            else -> when (error?.optString("type")) {
                "remote_access_error" -> "Remote Access"
                "relay_error", "relay_device_error" -> "Remote relay"
                else -> "AI API"
            }
        }
        val reason = when {
            code == "request_timeout" -> "The local model took longer than Remote Access allows. The request was not retried automatically."
            code == "memory_exhausted" || listOf("out of memory", "failed to allocate", "cannot allocate").any { it in text } ->
                "The model could not allocate enough RAM or GPU memory. Close other model work or lower the allowed context in KAI Core."
            code == "runtime_busy" || "runtime_busy" in text -> "The local model is busy. Wait for its current request to finish."
            code == "context_limit" || listOf("context_length", "context window", "token context", "context size", "too many tokens", "prompt is", "context limit", "context_tokens", "context_limit", "n_ctx").any { it in text } ->
                "The model rejected the context window requirement. Check the model's allowed context in KAI Core. Saved memories and history are unchanged."
            code == "chat_template" || listOf("chat template", "chat_template", "roles must alternate", "system role", "jinja").any { it in text } ->
                "The model rejected the conversation format. Check its chat template in KAI Core or choose a chat-compatible model."
            code == "model_not_found" || listOf("unknown model", "model_not_found", "installed local model").any { it in text } ->
                "The selected model is unavailable. Reload models in AI settings."
            "only local text chat is supported" in text -> "This Remote Access service rejected a request field. Fully quit and reopen KAI Core after updating Remote Access."
            "automatic context allocation" in text -> "This serving instance does not support automatic context. Fully quit and reopen KAI Core after updating Remote Access."
            "use text messages with supported roles" in text -> "Remote Access rejected the message format."
            code == "invalid_generation" || listOf("output limit", "max_tokens", "max_completion_tokens", "invalid temperature", "invalid top_p", "invalid stream options").any { it in text } ->
                "The model rejected a generation parameter."
            "invalid json" in text || "body must be json" in text -> "The service could not parse the request body."
            "128 kb" in text -> "The request exceeded Remote Access's 128 KB body limit."
            "local model request failed" in text -> "The gateway hid the local model's rejection reason. Update Remote Access to get a specific error category."
            code == "model_rejected" -> "The local model rejected the request without a recognized reason."
            status in setOf(400, 413, 422) || status == null -> "The service rejected the request without a recognized reason."
            else -> return null
        }
        val format = if (root != null) "JSON" else if (body.trimStart().startsWith("<")) "HTML" else if (body.isBlank()) "empty response" else "text response"
        return "$reason [$source; ${status?.let { "HTTP $it" } ?: "stream error"}; $format]"
    }
}
