package com.kai.agent.data

/** Remove replayable tool-result context when its privacy boundary changes. Receipts stay reconcilable. */
object ActionContextPrivacy {
    fun apply(previous: KaiState, next: KaiState): KaiState {
        val changed=previous.memoryGraph.deletionEpoch!=next.memoryGraph.deletionEpoch || previous.memoryTombstones!=next.memoryTombstones || previous.memoryGraph.suppressions!=next.memoryGraph.suppressions
        val tasks=next.tasks.map { task ->
            val conversation=next.conversations.firstOrNull { it.id==task.conversationId }
            val stale=changed || conversation==null || task.createdAt<=conversation.contextAfter ||
                task.createdAt<=(next.projects.firstOrNull { it.id==conversation.projectId }?.forgottenBefore ?: 0)
            if(stale && task.resultContext.isNotBlank()) task.copy(resultContext="") else task
        }
        return next.copy(tasks=tasks)
    }
}
