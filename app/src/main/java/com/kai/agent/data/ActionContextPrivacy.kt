package com.kai.agent.data

/** Remove replayable tool-result context when its privacy boundary changes. Receipts stay reconcilable. */
object ActionContextPrivacy {
    fun apply(previous: KaiState, next: KaiState): KaiState {
        val changed=previous.settings.useMemory!=next.settings.useMemory || previous.memoryGraph.deletionEpoch!=next.memoryGraph.deletionEpoch || previous.memoryTombstones!=next.memoryTombstones || previous.memoryGraph.suppressions!=next.memoryGraph.suppressions
        val tasks=next.tasks.map { task ->
            val conversation=next.conversations.firstOrNull { it.id==task.conversationId }
            val stale=changed || conversation==null || task.createdAt<=(next.memoryTombstones.maxOfOrNull { it.deletedAt } ?: 0) || task.createdAt<=(next.memoryGraph.suppressions.maxOfOrNull { it.deletedAt } ?: 0) || task.createdAt<=conversation.contextAfter ||
                task.createdAt<=(next.projects.firstOrNull { it.id==conversation.projectId }?.forgottenBefore ?: 0)
            if(stale && (task.resultContext.isNotBlank() || task.contextArchive.isNotBlank())) task.copy(resultContext="",contextArchive="") else task
        }
        val runs=next.runs.map { run ->
            if(previous.runs.none { it.id==run.id }) return@map run
            val boundaryChanged=previous.conversations.firstOrNull { it.id==run.conversationId }?.contextAfter!=next.conversations.firstOrNull { it.id==run.conversationId }?.contextAfter || previous.projects.firstOrNull { it.id==run.projectId }?.forgottenBefore!=next.projects.firstOrNull { it.id==run.projectId }?.forgottenBefore
            val contextChanged=changed || boundaryChanged || next.conversations.none { it.id==run.conversationId } || WorkspaceRules.contextRevision(previous,run.projectId,run.conversationId)!=WorkspaceRules.contextRevision(next,run.projectId,run.conversationId)
            if(!contextChanged) run else if(changed || boundaryChanged) run.copy(status=RunStatus.CANCELLED,lease="",goal="Private context removed",title="Private context removed",
                detail="Privacy changed. Create a new task using current information.",steps=run.steps.map { it.copy(instructions="Private context removed",messages=emptyList(),output="",revisionFeedback="",contextSummary="",tools=emptyList(),contextRevision=-1) })
            else run.copy(steps=run.steps.map { it.copy(messages=emptyList(),contextSummary="",tools=emptyList(),contextRevision=-1) })
        }
        val cancelled=runs.filter { it.status==RunStatus.CANCELLED }.map { it.id }.toSet()
        val guardedTasks=tasks.map { task ->
            if(task.submittedAt==null && task.status in setOf(TaskStatus.READY,TaskStatus.AWAITING_APPROVAL) && (changed || task.runId in cancelled))
                TaskRules.transition(task,TaskStatus.CANCELLED,next.settings,"Privacy changed; a new review is required") else task
        }
        return next.copy(tasks=guardedTasks,runs=runs)
    }
}
