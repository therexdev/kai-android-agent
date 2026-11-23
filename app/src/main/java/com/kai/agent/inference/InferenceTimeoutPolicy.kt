package com.kai.agent.inference

/** Local prompt evaluation can be slow before a server emits headers or its first token. */
data class InferenceTimeoutPolicy(val connectMillis: Long, val readMillis: Long, val callMillis: Long) {
    init { require(connectMillis > 0 && readMillis > 0 && callMillis > 0) }

    companion object {
        fun forProvider(provider: ProviderId): InferenceTimeoutPolicy = when (provider) {
            ProviderId.KOINOS -> InferenceTimeoutPolicy(20_000, 300_000, 360_000)
            ProviderId.OPENAI -> InferenceTimeoutPolicy(20_000, 90_000, 180_000)
        }

        // Include model discovery and cleanup; saved tasks must not cut off a local call at 120s.
        fun taskTurnMillis(provider: ProviderId): Long = forProvider(provider).callMillis + 20_000
    }
}
