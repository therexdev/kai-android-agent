package com.kai.agent.runtime

/** Versioned phone-owned execution contract. An outcome is independent of speech and tool effects. */
enum class ExecutionState { QUEUED, RUNNING, WAITING_INPUT, WAITING_APPROVAL, PAUSED, PARTIAL, RECONCILING, COMPLETED, FAILED, CANCELLED }
enum class RunOrigin { CHAT, TASK, ROUTINE }
enum class EffectState { PREPARED, AWAITING_APPROVAL, ADMITTED, SUBMITTED, ACCEPTED, VERIFIED, FAILED_BEFORE_SUBMIT, UNKNOWN, CANCELLED_BEFORE_SUBMIT }
enum class FailureCode { CONTEXT_LIMIT, OUTPUT_EXHAUSTED, REASONING_ONLY, MALFORMED_PROPOSAL, RATE_LIMIT, OVERLOAD, AUTHENTICATION, CREDITS, NETWORK, TIMEOUT, UNSUPPORTED, NO_PROGRESS, BUDGET, UNKNOWN_EFFECT, CANCELLED }
data class GoalContract(val request: String, val kind: String = "answer", val revision: Int = 1) {
    init { require(request.isNotBlank() && request.length <= 20000); require(revision == 1) }
    val requiresEffect get() = kind in setOf("external","document")
    val requiredEffects: Set<String> get() {
        if(!requiresEffect) return emptySet()
        val text=request.lowercase();val required=linkedSetOf<String>()
        if(kind=="document") required+="DOCUMENT"
        if(Regex("\\bemail\\b|\\bsend\\b.{0,40}\\bmail\\b").containsMatchIn(text)) required+="EMAIL"
        if(Regex("\\b(?:schedule|book|create|add|delete|cancel)\\b.{0,50}\\b(?:calendar|event|meeting)\\b").containsMatchIn(text)) required+="CALENDAR"
        return required.ifEmpty { setOf("EXTERNAL") }
    }
    companion object {
        fun infer(request: String): GoalContract {
            val text=request.lowercase()
            val advice=Regex("^\\s*(?:how\\b|explain\\b|what (?:is|are)\\b)").containsMatchIn(text)
            val service=Regex("\\b(?:google\\s+doc|document|doc|email|mail|calendar|event|meeting|drive|sheet|spreadsheet|slack|tweet|facebook|post)\\b").containsMatchIn(text)
            val draftOnly=Regex("\\b(?:write|draft|compose)\\b.{0,30}\\b(?:email|mail)\\b").containsMatchIn(text) &&
                !Regex("\\b(?:send|forward|publish)\\b").containsMatchIn(text) && !Regex("\\b(?:doc|document|calendar|event)\\b").containsMatchIn(text)
            val effect=!advice && !draftOnly && service && Regex("\\b(?:create|make|write|add|update|edit|put|insert|append|save|send|email|publish|post|schedule|book|delete|remove)\\b").containsMatchIn(text)
            return GoalContract(request,if(effect && Regex("\\b(?:doc|document)\\b").containsMatchIn(text)) "document" else if(effect) "external" else "answer")
        }
    }
}
data class RunBudget(val modelSteps: Int = 0, val inputTokens: Long = 0, val outputTokens: Long = 0,
    val corrections: Int = 0, val maxModelSteps: Int = 36, val maxOutputTokens: Long = 131072,
    val inference: InferenceLedger = InferenceLedger()) {
    val exhausted get() = modelSteps >= maxModelSteps || maxOf(outputTokens,inference.output) >= maxOutputTokens || inference.exhausted
    fun admit(): RunBudget { check(!exhausted) { "Run budget exhausted; progress is saved." }; return copy(modelSteps=modelSteps+1) }
    fun extend(saved: Boolean, creditLimit: Int, finalOnly: Boolean = false) = copy(
        maxModelSteps=(modelSteps+if(finalOnly) 2 else if(saved) 192 else 60).coerceAtMost(10000),
        maxOutputTokens=maxOf(outputTokens,inference.output)+(if(finalOnly) 2048 else if(saved) 524288 else 131072),
        inference=inference.extend(saved,creditLimit,finalOnly))
}
data class RunEvent(val sequence: Long, val at: Long, val kind: String, val code: String = "")
data class RunObservation(val id: String,val stepId: String,val content: String,val createdAt: Long)
data class RunArtifact(val id: String, val version: Int, val text: String, val sourceUrls: List<String>, val createdAt: Long, val kind: String = "DRAFT")
data class ToolReference(val account: String, val tool: String, val version: String)
data class ExecutionRun(
    val id: String, val conversationId: String, val sourceId: String, val origin: RunOrigin,
    val goal: GoalContract, val projectId: String = "", val temporary: Boolean = false,
    val schemaVersion: Int = 1, val revision: Long = 0, val state: ExecutionState = ExecutionState.QUEUED,
    val owner: String = "", val privacyRevision: Long, val createdAt: Long, val updatedAt: Long = createdAt,
    val archive: String = "[]", val modelRoute: String = "", val protocol: String = "",
    val tools: List<ToolReference> = emptyList(), val artifacts: List<RunArtifact> = emptyList(), val observations: List<RunObservation> = emptyList(), val budget: RunBudget = RunBudget(),
    val detail: String = "", val failure: FailureCode? = null, val events: List<RunEvent> = emptyList(),
) {
    val resumable get() = state in setOf(ExecutionState.PAUSED, ExecutionState.PARTIAL, ExecutionState.FAILED, ExecutionState.WAITING_INPUT)
    fun event(kind: String, now: Long, code: String = "") = copy(events=(events+RunEvent((events.lastOrNull()?.sequence ?: 0)+1,now,kind,code)).takeLast(200))
}
interface RunRepository {
    suspend fun get(id: String): ExecutionRun?
    /** Atomically claim a matching revision. An occupied or stale run cannot be stolen. */
    suspend fun claim(run: ExecutionRun, owner: String): ExecutionRun
    suspend fun update(id: String, owner: String, transform: (ExecutionRun)->ExecutionRun): ExecutionRun
}
class StaleRunException : kotlinx.coroutines.CancellationException("Run ownership or privacy changed.")
