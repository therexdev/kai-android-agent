package com.kai.agent.inference

/** Conservative UTF-8 estimate, not an exact tokenizer. Reserve space for the server's template. */
object InferenceBudget {
    data class Prepared(val messages: List<InferenceMessage>, val outputTokens: Int)
    fun estimate(messages: List<InferenceMessage>): Int = 32 + messages.sumOf {
        (it.content.toByteArray(Charsets.UTF_8).size + 2) / 3 + 12 + if(it.toolCalls.isNotEmpty() || it.toolCallId.isNotBlank()) (NativeToolWire.messageJson(it).toString().length-it.content.length+2)/3 else 0
    }
    fun prepare(config: ProviderConfig, messages: List<InferenceMessage>, reportedWindow: Int? = null, schemaTokens: Int = 0): Prepared {
        if(config.provider != ProviderId.KOINOS) return Prepared(messages, config.maxOutputTokens.coerceIn(128,8192))
        // Use a server-advertised served window when available, otherwise a conservative fallback.
        // A model family's advertised maximum does not establish the runtime's served window.
        val window = reportedWindow?.takeIf { it in 1024..2_000_000 } ?: 4096
        val wantedOutput = if(config.accountSession) minOf(config.maxOutputTokens.coerceAtLeast(1),config.modelOutputLimit ?: 2048,8192,window/4) else config.maxOutputTokens.coerceIn(128, minOf(8192, window / 4))
        val selected = messages.toMutableList()
        val target = window - 512 - wantedOutput - schemaTokens
        // Drop whole old conversation turns only. Current instructions, system rules, receipts,
        // and all current agent actions are pinned; never silently forget execution evidence.
        while(estimate(selected) > target) {
            val index = selected.indexOfFirst { it.canOmit && it.toolCalls.isEmpty() && it.toolCallId.isEmpty() }
            if(index < 0) break
            selected.removeAt(index)
            while(selected.getOrNull(index)?.let { it.canOmit && it.role == "assistant" } == true) selected.removeAt(index)
        }
        // Compact obsolete discovery/schema detail before deferring optional memory retrieval.
        val compactOrder = selected.indices.sortedBy { if (selected[it].role == "system") 1 else 0 }
        if(estimate(selected) > target) for(index in compactOrder) {
            if(estimate(selected) <= target) break
            val item = selected[index]
            item.compactContent?.takeIf { it.length < item.content.length }?.let { selected[index] = item.copy(content=it) }
        }
        val remaining = window - 512 - estimate(selected) - schemaTokens
        if(remaining < 256) throw InferenceException((if(config.accountSession) "This request exceeds the selected model’s context limit. Choose a model with a larger context or shorten the request." else if (reportedWindow == null)
            "The API did not report a context limit, and this request exceeds the conservative 4096-token budget. Update KAI Core and Remote Access, then reload models in AI settings."
            else "This request exceeds the API's reported $window-token context limit. Use a smaller task or increase the model's allowed context in KAI Core.") +
            " Older chat was reduced; the current request, instructions and action receipts were preserved. Saved memories and history are unchanged.")
        return Prepared(selected, minOf(wantedOutput, remaining))
    }
}
