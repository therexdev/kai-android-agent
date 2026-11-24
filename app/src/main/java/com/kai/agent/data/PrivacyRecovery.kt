package com.kai.agent.data

/** Older app versions may have retained queued work across Forget. Never reactivate those sources. */
object PrivacyRecovery {
    fun boundary(state: KaiState, cid: String): Long {
        val conversation=state.conversations.firstOrNull { it.id==cid }
        return maxOf(conversation?.contextAfter ?: 0,state.projects.firstOrNull { it.id==conversation?.projectId }?.forgottenBefore ?: 0,
            state.memoryTombstones.maxOfOrNull { it.deletedAt } ?: 0,state.memoryGraph.suppressions.maxOfOrNull { it.deletedAt } ?: 0)
    }
    fun apply(state: KaiState): KaiState {
        val tasks=state.tasks.map { task ->
            if(task.submittedAt==null && task.createdAt<=boundary(state,task.conversationId) && task.status in setOf(TaskStatus.DRAFT,TaskStatus.READY,TaskStatus.AWAITING_APPROVAL))
                TaskRules.transition(task,TaskStatus.CANCELLED,state.settings,"Original context was forgotten; a new request and review are required") else task
        }
        val runs=state.runs.map { run ->
            if(!run.terminal && run.createdAt<=boundary(state,run.conversationId)) run.copy(status=RunStatus.CANCELLED,lease="",goal="Private context removed",title="Private context removed",
                detail="Original context was forgotten; create a new task.",steps=run.steps.map { it.copy(instructions="Private context removed",output="",messages=emptyList(),tools=emptyList(),revisionFeedback="",contextSummary="") }) else run
        }
        return state.copy(tasks=tasks,runs=runs)
    }
}
