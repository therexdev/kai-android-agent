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
            val boundaryChanged=previous.conversations.firstOrNull { it.id==run.conversationId }?.contextAfter!=next.conversations.firstOrNull { it.id==run.conversationId }?.contextAfter || previous.projects.firstOrNull { it.id==run.projectId }?.forgottenBefore!=next.projects.firstOrNull { it.id==run.projectId }?.forgottenBefore
            val contextChanged=changed || boundaryChanged || next.conversations.none { it.id==run.conversationId } || WorkspaceRules.contextRevision(previous,run.projectId,run.conversationId)!=WorkspaceRules.contextRevision(next,run.projectId,run.conversationId)
            if(!contextChanged) run else run.copy(steps=run.steps.map { it.copy(messages=emptyList(),contextSummary="",tools=emptyList(),contextRevision=-1) })
        }
        return next.copy(tasks=tasks,runs=runs)
    }
}
