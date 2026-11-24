package com.kai.agent.runtime

import kotlinx.coroutines.*
import java.util.UUID

/** The single bounded execution driver for interactive and scheduled adapters. Never replays effects. */
class RunController(private val repository: RunRepository, private val clock: ()->Long = System::currentTimeMillis,
    private val elapsedClock: ()->Long = { System.nanoTime()/1_000_000 }) {
    suspend fun open(record: ExecutionRun): Session = Session(repository.claim(record.copy(budget=record.budget.copy(inference=record.budget.inference.recover())),UUID.randomUUID().toString()))
    inner class Session internal constructor(initial: ExecutionRun) {
        var record: ExecutionRun = initial; private set
        private val owner=initial.owner
        private var released=false
        suspend fun check() { currentCoroutineContext().ensureActive(); val r=repository.get(record.id); if(r?.owner!=owner || r.state!=ExecutionState.RUNNING || r.privacyRevision!=record.privacyRevision) throw StaleRunException() }
        suspend fun checkpoint(archive: String, tools: List<ToolReference> = record.tools) {
            check(); mutate { it.copy(archive=archive,tools=tools.distinct().take(100)).event("checkpoint",clock()) }
        }
        suspend fun observe(stepId: String, content: String) {
            mutate { r ->
                val observations=r.observations+RunObservation(UUID.randomUUID().toString(),stepId,content,clock())
                require(observations.size<=256 && observations.sumOf { it.content.length.toLong() }<=2_000_000) { "Saved findings are full; progress is preserved." }
                r.copy(observations=observations).event("observation_saved",clock())
            }
        }
        suspend fun draft(text: String, sources: List<String> = emptyList()) {
            if(text.isBlank() || record.artifacts.lastOrNull()?.text==text) return
            mutate { r -> r.copy(artifacts=(r.artifacts+RunArtifact(r.id+":draft",(r.artifacts.lastOrNull()?.version ?: 0)+1,text.take(48000),sources.distinct().take(100),clock())).takeLast(4)).event("draft_saved",clock()) }
        }
        suspend fun documentVerification(evidence: List<Pair<String, DocumentVerification.Evidence>>) {
            for ((writeId, read) in evidence.filter { it.second.matched }) {
                val code = "$writeId:${read.readTaskId}"
                if(record.events.none { it.kind=="document_text_verified" && it.code==code })
                    mark("document_text_verified",code)
            }
        }
        /** Durable admission precedes EVERY transport call, including helpers and retries.
         * A missing result retains token/cost reservations; a restart retains the full time reservation.
         */
        suspend fun infer(config: com.kai.agent.inference.ProviderConfig, messages: List<com.kai.agent.inference.InferenceMessage>,
            purpose: InferencePurpose, call: suspend ()->com.kai.agent.inference.Completion): com.kai.agent.inference.Completion {
            check()
            val remaining=record.budget.inference.maxMillis-record.budget.inference.millis
            val duration=minOf(remaining,com.kai.agent.inference.InferenceTimeoutPolicy.taskTurnMillis(config.provider))
            val a=InferenceLedger.request(config,messages,purpose,clock(),duration)
            mutate { r -> r.copy(budget=r.budget.copy(inference=r.budget.inference.reserve(a,r.budget.maxOutputTokens))).event("model_admitted",clock(),purpose.name) }
            val start=elapsedClock()
            var response: com.kai.agent.inference.Completion? = null
            var interrupted=false
            try {
                response=withTimeout(duration.coerceAtLeast(1)) { withContext(InferenceAttemptContext(a.id,a.purpose)) { call() } }
                return requireNotNull(response)
            } catch(e: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                if(duration==remaining) throw RunBudgetExceeded("The run's model time allowance is used up. Progress is saved.")
                throw e
            } catch(e: CancellationException) { interrupted=true;throw e }
            finally {
                withContext(NonCancellable) {
                    val reported=InferenceLedger.reported(response?.usage)
                    // Ownership/privacy checks still apply: a late result cannot resurrect deleted work.
                    mutate { r -> r.copy(budget=r.budget.copy(
                        inputTokens=r.budget.inputTokens+(reported?.inputTokens ?: 0),outputTokens=r.budget.outputTokens+(reported?.outputTokens ?: 0),
                        inference=r.budget.inference.settle(a.id,reported,elapsedClock()-start,interrupted)))
                        .event("model_accounted",clock(),if(reported==null) "USAGE_UNKNOWN" else purpose.name) }
                }
            }
        }
        suspend fun switchModel(route: String, protocol: String, archive: String) {
            mutate { it.copy(modelRoute=route,protocol=protocol,archive=archive).event("model_changed",clock()) }
        }
        suspend fun mark(kind: String, code: String = "") { mutate { it.event(kind,clock(),code) } }
        private suspend fun mutate(transform: (ExecutionRun)->ExecutionRun) { record=repository.update(record.id,owner,transform) }
        suspend fun finish(state: ExecutionState, detail: String = "", failure: FailureCode? = null) {
            require(state!=ExecutionState.RUNNING && state!=ExecutionState.QUEUED)
            mutate { it.copy(state=state,owner="",detail=detail.take(600),failure=failure).event("outcome",clock(),state.name) }; released=true
        }
        /** Exact approval settlement has no new inference spend and must survive an exhausted model budget. */
        suspend fun release() { withContext(NonCancellable) { if(!released) runCatching { finish(ExecutionState.PAUSED,"Progress saved; the adapter stopped.") } } }
        suspend fun settleEffect(block: suspend Session.()->Unit) {
            try { check();block() } finally {
                withContext(NonCancellable) { if(!released) runCatching { finish(ExecutionState.PAUSED,"Effect settlement stopped; check Tasks before continuing.") } }
            }
        }
        /** The adapter performs exactly one safe step. Policy, lifetime and admission have one owner. */
        suspend fun drive(settlePending: suspend Session.()->Unit = {}, step: suspend Session.()->Unit) {
            try {
                while(!released) {
                    check()
                    // An already approved effect may settle without admitting new inference.
                    settlePending()
                    if(released) break
                    check()
                    if(record.budget.exhausted) { finish(ExecutionState.PARTIAL,"Run budget reached. Findings are saved; continue to authorize another bounded run.",FailureCode.BUDGET); break }
                    admit(); step()
                }
            } catch(e: RunBudgetExceeded) {
                if(!released) finish(ExecutionState.PARTIAL,e.message.orEmpty(),FailureCode.BUDGET)
            } catch(e: CancellationException) {
                withContext(NonCancellable) { if(!released) runCatching { finish(ExecutionState.PAUSED,"Interrupted; progress saved.",FailureCode.CANCELLED) } }; throw e
            } catch(e: Exception) {
                if(!released) runCatching { finish(ExecutionState.FAILED,"The run stopped; progress is saved.",RecoveryPolicy.classify(e)) }; throw e
            }
        }
        suspend fun admit() { mutate { it.copy(budget=it.budget.admit()).event("step_admitted",clock()) } }
    }
}
