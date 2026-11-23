package com.kai.agent.agent

import com.kai.agent.inference.InferenceMessage

/** Bound a whole agent turn without dropping the user's request or tool execution evidence. */
class AgentTurnContext(initial: List<InferenceMessage>, private val maxCharacters: Int = 56_000, private val checkpointSeed: String = "[]") {
    private data class Exchange(val assistant: String, val runtime: String, val compactRuntime: String, val wire: List<InferenceMessage>? = null)
    private val initialMessages = initial.toList()
    private var exchanges = emptyList<Exchange>()

    init {
        require(maxCharacters > 0)
        require(size(emptyList()) <= maxCharacters) { "This conversation is too large. Start a new conversation for this task." }
    }

    fun messages(): List<InferenceMessage> = initialMessages + exchanges.flatMapIndexed { index, item ->
        // The newest result is required to make the next decision. In particular, replacing a
        // just-inspected schema with "inspect again" makes a small-context model loop forever.
        item.wire ?: listOf(InferenceMessage("assistant", item.assistant), InferenceMessage("user", item.runtime,
            compactContent = item.compactRuntime.takeIf { index < exchanges.lastIndex }))
    }

    /** A failed append leaves the previous valid context unchanged. The newest result stays complete. */
    fun append(assistant: String, runtime: String, compactRuntime: String = runtime, wire: List<InferenceMessage>? = null) {
        val candidate = (exchanges + Exchange(assistant, runtime, compactRuntime, wire)).toMutableList()
        for (index in 0 until candidate.lastIndex) {
            if (size(candidate) <= maxCharacters) break
            val old = candidate[index]
            if (old.compactRuntime.length < old.runtime.length) candidate[index] = old.copy(runtime = old.compactRuntime, wire = old.wire?.map { if(it.role=="tool") it.copy(content=old.compactRuntime) else it })
        }
        require(size(candidate) <= maxCharacters) {
            "The task results exceed this conversation's limit. Try one account at a time or request fewer messages."
        }
        exchanges = candidate.toList()
    }

    /** Data-only checkpoint excludes system instructions, memory and account credentials. */
    fun checkpoint(): String = org.json.JSONArray(checkpointSeed).apply { exchanges.forEach { put(org.json.JSONObject().put("proposal",it.assistant).put("result",it.runtime)) } }.toString().also {
        require(it.length <= 64_000) { "Research context is too large to checkpoint. Request fewer sources." }
    }

    private fun size(items: List<Exchange>): Long = initialMessages.sumOf { it.content.length.toLong() } +
        items.sumOf { item -> item.wire?.sumOf { com.kai.agent.inference.NativeToolWire.messageJson(it).toString().length.toLong() } ?: (item.assistant.length.toLong() + item.runtime.length.toLong()) }
}
