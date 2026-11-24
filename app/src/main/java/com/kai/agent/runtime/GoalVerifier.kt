package com.kai.agent.runtime

import com.kai.agent.agent.AgentDecision
import com.kai.agent.agent.ReplyOutcome

/** Model outcome labels are proposals. Completion still requires host-owned effect evidence. */
object GoalVerifier {
    fun outcome(reply: AgentDecision.Reply, externalGoal: Boolean, hasAcceptedWrite: Boolean): ExecutionState = when(reply.outcome) {
        ReplyOutcome.QUESTION -> ExecutionState.WAITING_INPUT
        ReplyOutcome.BLOCKED, ReplyOutcome.PARTIAL -> ExecutionState.PARTIAL
        ReplyOutcome.ANSWER -> if(externalGoal && reply.text.trimEnd().endsWith("?")) ExecutionState.WAITING_INPUT
            else if(externalGoal && !hasAcceptedWrite) ExecutionState.PARTIAL else ExecutionState.COMPLETED
    }
    fun acceptedEffect(tasks: List<com.kai.agent.data.AgentTask>, cid: String, since: Long, run: ExecutionRun): Boolean {
        val accepted=tasks.filter { task ->
            task.conversationId==cid && task.createdAt>=since && task.submittedAt!=null && task.intent?.readOnly!=true &&
                (task.intent?.executionId==run.id || run.events.any { it.kind=="effect_recorded" && it.code==task.id }) &&
                task.status in setOf(com.kai.agent.data.TaskStatus.ACCEPTED,com.kai.agent.data.TaskStatus.SUCCEEDED) &&
                task.receipts.any { it.operationId==task.intent?.id && it.status in setOf("ACCEPTED","SUCCEEDED") }
        }
        val kinds=accepted.mapNotNull { it.intent?.let { intent -> intent.effectKind.ifBlank { EffectKinds.classify(intent.toolName) } } }.toSet()
        return accepted.isNotEmpty() && run.goal.requiredEffects.all { it=="EXTERNAL" || it in kinds }
    }
    fun present(reply: AgentDecision.Reply, requiresEffect: Boolean, accepted: Boolean): String {
        if(!requiresEffect || accepted || reply.text.trimEnd().endsWith("?")) return reply.text
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
