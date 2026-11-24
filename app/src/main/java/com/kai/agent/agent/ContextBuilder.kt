package com.kai.agent.agent

import com.kai.agent.data.ChatMessage
import com.kai.agent.data.MemoryEvidence
import com.kai.agent.inference.InferenceMessage
import org.json.JSONArray
import org.json.JSONObject

/** Local deterministic context selection. It never extracts a fact from assistant output. */
object ContextBuilder {
    const val MAX_EXPLICIT_MEMORY = 2_000
    /** Only direct, anchored commands are durable saves; a quoted or negated request is not one. */
    fun explicitRemember(text: String): String? {
        return com.kai.agent.memory.MemoryRequests.save(text)?.body
    }

    /**
     * Keeps recent complete turns within a hard character budget. Whole older messages are omitted;
     * we never cut a recipient, number or command in half. The current user message must fit.
     */
    fun build(
        systemPrompt: String,
        messages: List<ChatMessage>,
        suppressedMessageIds: Set<String> = emptySet(),
        maxCharacters: Int = 28_000,
        maxMessages: Int = 24,
        afterTimestamp: Long = 0, managed: Boolean = false,
    ): List<InferenceMessage> {
        require(maxCharacters in 2_000..100_000 && maxMessages in 1..99) { "Invalid context budget." }
        require(managed || systemPrompt.length < maxCharacters) { "Connected-tool context is too large. Use a more specific request." }
        val remaining = maxCharacters - systemPrompt.length
        val eligible = messages.filter { it.createdAt >= afterTimestamp && it.id !in suppressedMessageIds && it.role in setOf("user", "assistant") }
            .mapNotNull { message ->
                val content = if (message.role == "assistant" && message.interrupted) {
                    message.spokenCharacters?.let { message.text.take(it.coerceAtLeast(0)) }.orEmpty()
                } else message.text
                content.takeIf { it.isNotBlank() }?.let { InferenceMessage(message.role, it) }
            }
        val latestUser = eligible.indexOfLast { it.role == "user" }
        if (!managed && latestUser >= 0) require(eligible[latestUser].content.length <= remaining) {
            "Your message is too long for this context. Split it into smaller parts."
        }
        if(managed) return listOf(InferenceMessage("system",systemPrompt))+eligible.mapIndexed { index,item -> item.copy(canOmit=index<latestUser) }
        val selected = mutableListOf<InferenceMessage>()
        var used = 0
        for (index in eligible.indices.reversed()) {
            val item = eligible[index]
            if (selected.size >= maxMessages || used + item.content.length > remaining) break
            selected += item
            used += item.content.length
        }
        selected.reverse()
        // Avoid an orphaned assistant answer whose user turn fell outside the budget.
        while (selected.firstOrNull()?.role == "assistant") selected.removeAt(0)
        if (latestUser >= 0 && selected.none { it === eligible[latestUser] }) {
            // A large trailing reply should never displace the user's latest complete instruction.
            selected.clear()
            selected += eligible[latestUser]
        }
        val current = selected.indexOfLast { it.role == "user" }
        return listOf(InferenceMessage("system", systemPrompt)) + selected.mapIndexed { index, item -> item.copy(canOmit=index < current) }
    }

    /** JSON-quoted evidence makes the source/scope explicit; all input is still untrusted data. */
    fun memoryText(evidence: List<MemoryEvidence>, maxFacts: Int = 8, maxCharacters: Int = 4_000): String {
        require(maxFacts in 0..50 && maxCharacters in 2..20_000) { "Invalid memory context budget." }
        val values = JSONArray()
        for (item in evidence.distinctBy { it.fact.id }.take(maxFacts)) {
            val fact = item.fact
            val entry = JSONObject()
                .put("id", fact.id).put("fact", fact.text).put("scope", fact.scope)
                .put("origin", fact.origin.name).put("revision", fact.revision)
            val candidate = JSONArray(values.toString()).put(entry)
            if (candidate.toString().length > maxCharacters) continue
            values.put(entry)
        }
        return values.toString()
    }
}
