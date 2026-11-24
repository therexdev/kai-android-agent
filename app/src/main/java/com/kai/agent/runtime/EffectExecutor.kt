package com.kai.agent.runtime

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** One intent-before-submit boundary for chat and scheduled tools. Transport is invoked once only. */
object EffectExecutor {
    suspend fun <T> submit(admit: suspend ()->Unit, dispatch: suspend ()->T,
        commit: suspend (T)->Unit, failed: suspend (Exception)->Unit): T {
        // A rejection here proves dispatch has not begun; do not fabricate an uncertain receipt.
        admit()
        try {
            val result=dispatch()
            withContext(NonCancellable) { commit(result) }
            return result
        } catch(error: Exception) {
            withContext(NonCancellable) { failed(error) }
            throw error
        }
    }
}
