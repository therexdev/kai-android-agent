package com.kai.agent.runtime

import com.kai.agent.agent.AgentDecision
import com.kai.agent.agent.ReplyOutcome

/** Model outcome labels are proposals. Completion still requires host-owned effect evidence. */
object GoalVerifier {
    /** An unfinished effect is not an ordinary answer. This checks the existing outcome contract,
     * not the wording of a question, and never constructs or authorizes an action. */
    fun validateReply(decision: AgentDecision, actionOutstanding: Boolean) {
        if(actionOutstanding && decision is AgentDecision.Reply && decision.outcome==ReplyOutcome.ANSWER)
            throw com.kai.agent.agent.AgentResponseException("The requested action needs a concrete proposal, a missing-input question, or an explicit incomplete outcome.",actionOutstanding=true)
    }
    /** Nonfinal research/planning steps may finish with text; the final step owes the whole goal. */
    fun stepGoal(run: ExecutionRun, instructions: String, finalStep: Boolean) =
        if(finalStep) run.goal else GoalContract.infer(instructions)

    private fun unfinished(text: String)=Regex("(?i)\\b(?:can['’]t|cannot|unable to|couldn['’]t|haven['’]t|have not|not yet)\\b.{0,100}\\b(?:populate|fill|write|written|add|added|finish|complete|update|updated|send|sent)\\b").containsMatchIn(text)
    fun outcome(reply: AgentDecision.Reply, externalGoal: Boolean, hasAcceptedWrite: Boolean): ExecutionState = when(reply.outcome) {
        ReplyOutcome.QUESTION -> ExecutionState.WAITING_INPUT
        ReplyOutcome.BLOCKED, ReplyOutcome.PARTIAL -> ExecutionState.PARTIAL
        ReplyOutcome.ANSWER -> if(externalGoal && reply.text.trimEnd().endsWith("?")) ExecutionState.WAITING_INPUT
            else if(externalGoal && unfinished(reply.text)) ExecutionState.PARTIAL
            else if(externalGoal && !hasAcceptedWrite) ExecutionState.PARTIAL else ExecutionState.COMPLETED
    }
    fun acceptedTasks(tasks: List<com.kai.agent.data.AgentTask>, cid: String, since: Long, run: ExecutionRun) = tasks.filter { task ->
            task.conversationId==cid && task.createdAt>=since && task.submittedAt!=null && task.intent?.readOnly!=true &&
                (task.intent?.executionId==run.id || run.events.any { it.kind=="effect_recorded" && it.code==task.id }) &&
                task.status in setOf(com.kai.agent.data.TaskStatus.ACCEPTED,com.kai.agent.data.TaskStatus.SUCCEEDED) &&
                task.receipts.any { it.operationId==task.intent?.id && it.status in setOf("ACCEPTED","SUCCEEDED") }
        }
    fun acceptedEffect(tasks: List<com.kai.agent.data.AgentTask>, cid: String, since: Long, run: ExecutionRun): Boolean {
        val accepted=acceptedTasks(tasks,cid,since,run)
        val kinds=accepted.mapNotNull { it.intent?.let { intent -> intent.effectKind.ifBlank { EffectKinds.classify(intent.toolName) } } }.toSet()
        return accepted.isNotEmpty() && run.goal.requiredEffects.all { it=="EXTERNAL" || it in kinds } && SpreadsheetEvidence.complete(accepted,run.goal.request)
    }
    fun present(reply: AgentDecision.Reply, requiresEffect: Boolean, accepted: Boolean): String {
        if(!requiresEffect || accepted || unfinished(reply.text) || reply.text.trimEnd().endsWith("?")) return reply.text
        val claimed=reply.text.split(Regex("[.!\\n]")).any { sentence ->
            Regex("(?i)\\b(?:sent|emailed|created|updated|saved|published|posted|scheduled|booked|deleted|uploaded|submitted)\\b").containsMatchIn(sentence) &&
                !Regex("(?i)\\b(?:not|no|haven't|have not|hasn't|couldn't|cannot|can't|unable|unconfirmed|unverified)\\b").containsMatchIn(sentence)
        }
        return if(claimed) "The full request is not confirmed complete. Check the retained action receipts and continue only the remaining work. Progress is saved." else reply.text
    }
    fun claimsDocumentCompletion(text: String): Boolean {
        val value=text.lowercase()
        // Negated or explicitly partial outcomes cannot be converted into forced write attempts.
        if(Regex("\\b(?:haven't|have not|hasn't|not yet|couldn't|cannot|can't|unable|not (?:created|saved|updated|submitted|confirmed)|unverified)\\b").containsMatchIn(value)) return false
        return Regex("\\b(?:done|completed|successfully|created|updated|saved|submitted|published|updating|creating|writing)\\b").containsMatchIn(value) ||
            Regex("(?:here.{0,20}(?:document|doc|link)|(?:document|doc).{0,40}https://)").containsMatchIn(value)
    }
}
