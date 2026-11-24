package com.kai.agent.runtime

import com.kai.agent.inference.*

/** Immutable serving-route limits. Advertised tool support is distinct from qualification evidence. */
data class ModelProfile(val route: String, val contextTokens: Int?, val outputLimit: Int,
    val nativeTools: Boolean, val protocolVersion: Int = 1, val qualification: String = "advertised") {
    companion object {
        fun from(c: ProviderConfig) = ModelProfile(RunReplay.route(c),c.contextTokens,c.modelOutputLimit ?: 8192,c.nativeTools,if(c.nativeTools) 2 else 1,c.nativeQualificationId.ifBlank { "unqualified" })
    }
}
/** Only explicit same-account fallback configurations are accepted; this class never changes credentials. */
class ModelPolicy(private val fallbacks: List<ProviderConfig>) {
    private val tried=mutableSetOf<String>()
    fun next(current: ProviderConfig, error: InferenceException): ProviderConfig? {
        tried+=current.modelId
        if((error.retryAfterMillis ?: 0)>30000) return null
        if(error.failureCode !in setOf(FailureCode.OUTPUT_EXHAUSTED,FailureCode.REASONING_ONLY,FailureCode.OVERLOAD,FailureCode.RATE_LIMIT,FailureCode.TIMEOUT)) return null
        return fallbacks.firstOrNull {
            it.modelId !in tried && it.endpoint==current.endpoint && it.apiKey==current.apiKey &&
                it.creditWallet==current.creditWallet && it.freeOnly==current.freeOnly && it.provider==current.provider &&
                it.nativeTools==current.nativeTools
        }?.also { tried+=it.modelId }
    }
}
