package com.kai.agent.runtime

import com.kai.agent.data.*
import com.kai.agent.agent.ResourceReuseGuard

/** A budget stop can report retained evidence without paying for another model response. */
object EvidenceFinalization {
    data class Result(val complete: Boolean,val text: String,val verification: List<Pair<String,DocumentVerification.Evidence>>)
    fun document(run: ExecutionRun,tasks: List<AgentTask>,owner: String,generation: String,boundary: Long): Result? {
        if(run.goal.kind!="document" || run.createdAt<=boundary) return null
        val scoped=tasks.filter { it.conversationId==run.conversationId && it.createdAt>boundary &&
            it.intent?.ownerId==owner && it.intent.connectionGeneration==generation && it.intent.executionId==run.id }
        val docs=GoalVerifier.acceptedTasks(scoped,run.conversationId,run.createdAt,run).mapNotNull { t -> ResourceReuseGuard.documentUrl(t)?.let { t to it } }
        if(docs.isEmpty()) return null
        val verification=docs.map { (task,_) -> task.id to DocumentVerification.evaluate(task,scoped) }
        val matched=verification.all { it.second.matched }
        val pending=scoped.any { it.intent?.readOnly!=true && it.status in setOf(TaskStatus.UNKNOWN,TaskStatus.RUNNING,TaskStatus.READY,TaskStatus.AWAITING_APPROVAL) }
        val complete=run.goal.requiredEffects==setOf("DOCUMENT") && matched && !pending && GoalVerifier.acceptedEffect(scoped,run.conversationId,run.createdAt,run)
        val text=when {
            pending -> "The run paused with an action still awaiting review or an outcome check. Do not repeat submitted actions."
            verification.any { it.second.status==DocumentVerification.Status.TEXT_DIFFERS } -> "The document write was accepted, but readback differs from the submitted body."
            matched -> "The document was saved and its body text matched a separate readback."
            else -> "The document write was accepted; its body text has not been independently verified."
        }
        val remaining=run.goal.requiredEffects.filter { it!="DOCUMENT" }.joinToString(", ") { it.lowercase() }
        return Result(complete,text+"\n\n"+docs.map { it.second }.distinct().take(2).joinToString("\n")+
            if(remaining.isBlank()) "" else "\n\nThe full request is incomplete. Check the remaining $remaining work before continuing.",verification)
    }
}
