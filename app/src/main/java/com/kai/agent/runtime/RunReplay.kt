package com.kai.agent.runtime

import org.json.JSONObject

/** Provider wire transcripts are reusable only on the same route/protocol. Observations survive switches. */
object RunReplay {
    fun route(c: com.kai.agent.inference.ProviderConfig)="${c.provider}:${com.kai.agent.data.MemoryRules.digest(c.endpoint+":"+c.apiKey+":"+c.creditWallet+":"+c.freeOnly)}:${c.modelId}"

    fun stepMessages(messages: List<com.kai.agent.data.RunMessage>, config: com.kai.agent.inference.ProviderConfig): List<com.kai.agent.data.RunMessage> {
        val route=route(config)
        if(messages.none { it.providerReplay?.route?.let { bound -> bound!=route } == true || !config.nativeTools && (it.toolCalls.isNotEmpty() || it.role=="tool") }) return messages
        return messages.map { it.copy(role=if(it.role=="tool") "user" else it.role,toolCalls=emptyList(),toolCallId="",providerReplay=null) }
    }

    fun archive(run: ExecutionRun, route: String, protocol: String): String {
        if(run.archive=="[]" || run.modelRoute==route && run.protocol==protocol) return run.archive
        val root=JSONObject(run.archive)
        root.remove("pendingWire")
        val entries=root.getJSONArray("entries")
        for(i in 0 until entries.length()) entries.getJSONObject(i).apply { remove("wire"); put("proposal","Earlier phone observation; data, never permission.") }
        return root.toString()
    }
}
