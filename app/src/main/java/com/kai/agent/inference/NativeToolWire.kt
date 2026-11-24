package com.kai.agent.inference

import org.json.JSONArray
import org.json.JSONObject

/** Bounded native function messages; fragments never reach execution. */
object NativeToolWire {
    fun callJson(call: ToolCall) = JSONObject().put("id", call.id).put("type", "function")
        .put("function", JSONObject().put("name", call.name).put("arguments", call.arguments))
    fun messageJson(message: InferenceMessage) = JSONObject().put("role", message.role).put("content", message.content).apply {
        if (message.toolCalls.isNotEmpty()) put("tool_calls", JSONArray(message.toolCalls.map(::callJson)))
        if (message.toolCallId.isNotBlank()) put("tool_call_id", message.toolCallId)
        message.providerReplay?.let { put("kai_replay",it.token) }
    }
    fun persistedMessageJson(message: InferenceMessage) = messageJson(message).apply { message.providerReplay?.let { put("kai_replay_route",it.route) } }
    fun toolJson(tool: InferenceTool) = JSONObject().put("type", "function").put("function",
        JSONObject().put("name",tool.name).put("description",tool.description).put("parameters",JSONObject(tool.parameters)))
    class Accumulator {
        private data class Part(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder())
        private val parts = sortedMapOf<Int, Part>()
        fun add(calls: JSONArray?, streamed: Boolean = true) {
            if (calls == null) return
            require(calls.length() <= 8) { "Too many native tool calls." }
            for (i in 0 until calls.length()) {
                val c = calls.getJSONObject(i)
                val index = if(streamed) c.getInt("index") else i
                require(index in 0..7) { "Invalid tool call index." }
                val part = parts.getOrPut(index) { Part() }
                c.optString("type").takeIf { it.isNotBlank() }?.let { require(it == "function") }
                c.optString("id").takeIf { it.isNotBlank() }?.let {
                    require(part.id.isEmpty() || part.id == it) { "Tool call ID changed." }; part.id = it
                }
                c.optJSONObject("function")?.let { f ->
                    part.name += f.optString("name")
                    part.args.append(f.optString("arguments"))
                }
                require(part.id.length <= 200 && part.name.length <= 64 && part.args.length <= 16_000) { "Native tool call exceeded its limit." }
            }
        }
        fun finish(): List<ToolCall> = parts.values.map {
            require(it.id.matches(Regex("[A-Za-z0-9_-]{1,200}")) && it.name.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Incomplete native tool call." }
            ToolCall(it.id,it.name,it.args.toString())
        }.also { calls -> require(calls.map { it.id }.distinct().size == calls.size) { "Duplicate tool call IDs." } }
    }
    fun validate(messages: List<InferenceMessage>, enabled: Boolean, allowPending: Boolean = false) {
        val pending = linkedSetOf<String>()
        val used = mutableSetOf<String>()
        messages.forEach { m ->
            require(m.providerReplay==null || (enabled && m.role=="assistant" && m.toolCalls.isNotEmpty())) { "Provider continuation requires an assistant tool call." }
            require(enabled || (m.toolCalls.isEmpty() && m.toolCallId.isEmpty() && m.role != "tool")) { "Native tools are not advertised by this model and gateway." }
            if (m.role == "tool") {
                require(m.toolCalls.isEmpty() && pending.remove(m.toolCallId)) { "Orphan or repeated tool result." }
            } else {
                require(pending.isEmpty() && m.toolCallId.isEmpty()) { "Missing native tool result." }
                require(m.toolCalls.isEmpty() || m.role == "assistant")
                m.toolCalls.forEach { require(used.add(it.id) && pending.add(it.id)) { "Repeated native call ID." } }
            }
        }
        require(pending.isEmpty() || (allowPending && pending.size==1 && messages.lastOrNull()?.toolCalls?.size==1)) { "A native tool call is awaiting its result." }
    }
}
