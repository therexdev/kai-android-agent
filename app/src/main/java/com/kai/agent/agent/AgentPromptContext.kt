package com.kai.agent.agent

import org.json.JSONObject

/** Optional retrieval is replaceable; the user request, operation ledger and receipts are not. */
object AgentPromptContext {
    fun deferredMemory(memory: String): String = JSONObject(memory).also {
        it.remove("reviewContext")
        it.remove("linkedFacts")
        it.put("contextDeferred", true)
        it.put("instructions", "Optional memory context omitted for space, not deleted. Use search_memory for needed facts before relying on them; the phone reconciles proposals against all current and pending facts.")
    }.toString()
}
