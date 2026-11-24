package com.kai.agent.runtime

import com.kai.agent.inference.InferenceException
import com.kai.agent.agent.AgentResponseException

/** Classification is separate from admission. No retry of an external effect is ever proposed here. */
object RecoveryPolicy {
    fun classify(error: Exception): FailureCode = when(error) {
        is InferenceException -> error.failureCode
        is AgentResponseException -> if(error.outputLimit) FailureCode.OUTPUT_EXHAUSTED else FailureCode.MALFORMED_PROPOSAL
        is java.net.SocketTimeoutException, is kotlinx.coroutines.TimeoutCancellationException -> FailureCode.TIMEOUT
        is java.io.IOException -> FailureCode.NETWORK
        else -> FailureCode.UNSUPPORTED
    }
    fun mayRetry(code: FailureCode, attempt: Int, effect: EffectState? = null): Boolean =
        effect !in setOf(EffectState.SUBMITTED,EffectState.UNKNOWN,EffectState.ACCEPTED,EffectState.VERIFIED) && attempt<2 &&
            code in setOf(FailureCode.CONTEXT_LIMIT,FailureCode.RATE_LIMIT,FailureCode.OVERLOAD,FailureCode.TIMEOUT)
}
