package com.kai.agent.runtime
import com.kai.agent.inference.*
import kotlinx.coroutines.delay
/** Bounded model-transport recovery only. A spoken reply and submitted tools are never retried here. */
class InferenceRecovery {
    private val attempts=mutableMapOf<String,Int>()
    suspend fun retry(config: ProviderConfig, error: InferenceException, speechReleased: Boolean = false): Boolean {
        if(speechReleased || error.failureCode !in setOf(FailureCode.RATE_LIMIT,FailureCode.OVERLOAD,FailureCode.TIMEOUT)) return false
        val key="${config.provider}:${config.endpoint}:${config.modelId}"
        val count=attempts[key] ?: 0
        if(count>=2) return false
        val wait=error.retryAfterMillis ?: (1000L shl count)
        if(wait>30000) return false // Persist and let the user resume after a longer provider cooldown.
        attempts[key]=count+1
        delay(wait.coerceAtLeast(0))
        return true
    }
}
