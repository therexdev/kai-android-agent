package com.kai.agent.extensions
import com.kai.agent.data.*
import org.json.JSONObject
/** At most two reviews: after the final requested edit the writer proceeds without a third review. */
object QualityLoop {
    fun revise(run: AgentRun, review: RunStep, output: String): AgentRun? {
        if(review.kind!="REVIEW") return null
        val result=runCatching { JSONObject(output.trim().removePrefix("```json").removeSuffix("```").trim()) }.getOrNull()
            ?: error("The review must return a verdict and specific changes. Progress is saved.")
        val verdict=result.getString("verdict")
        require(verdict in setOf("PASS","REVISE")) { "Unsupported review verdict." }
        if(verdict=="PASS" || review.maxRevisions==0) return null
        val target=run.steps.firstOrNull { it.nodeId==review.reviewTarget } ?: error("Revision target is missing.")
        require(target.pendingActionId.isBlank()) { "Review the target action outcome before revising." }
        val count=review.revisions+1
        val changes=result.getString("changes").take(4000)
        require(changes.isNotBlank()) { "A revision needs specific changes." }
        return run.copy(steps=run.steps.map { step -> when(step.id) {
            target.id -> step.copy(status=StepStatus.PENDING,messages=emptyList(),contextRevision=-1,revisionFeedback=changes,output=target.output)
            review.id -> step.copy(status=if(count>=review.maxRevisions) StepStatus.SKIPPED else StepStatus.PENDING,revisions=count,
                output=output.take(24000),messages=emptyList(),completedAt=if(count>=review.maxRevisions) now() else 0)
            else -> step
        } },detail="Applying review edits ($count/${review.maxRevisions})")
    }
}
