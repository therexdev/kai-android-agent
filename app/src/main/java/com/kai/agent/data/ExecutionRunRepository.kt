package com.kai.agent.data

import com.kai.agent.runtime.*

/** SQLCipher-backed, serialized with all memory, account, action and privacy changes. */
class ExecutionRunRepository(private val store: KaiStore): RunRepository {
    override suspend fun get(id: String) = store.state.value.executions.firstOrNull { it.id==id }
    override suspend fun claim(run: ExecutionRun, owner: String): ExecutionRun = store.change { state ->
        require(owner.isNotBlank())
        val old=state.executions.firstOrNull { it.id==run.id }
        if(old==null && (run.revision!=0L || run.owner.isNotBlank() || run.state!=ExecutionState.QUEUED)) throw StaleRunException()
        if(old!=null && (old.revision!=run.revision || old.owner.isNotBlank() || old.state in setOf(ExecutionState.COMPLETED,ExecutionState.CANCELLED))) throw StaleRunException()
        require(run.privacyRevision==privacy(state,run.conversationId)) { "Run context changed. Start with current information." }
        val claimed=run.copy(owner=owner,state=ExecutionState.RUNNING,revision=run.revision+1,updatedAt=now()).event("claimed",now())
        var history=state.executions.filterNot { it.id==run.id }
        if(history.size>=500) {
            // Completed plain answers remain in the transcript. Retain drafts, findings and every effect run.
            val removable=history.filter { it.state==ExecutionState.COMPLETED && it.origin==RunOrigin.CHAT && !it.goal.requiresEffect && it.artifacts.isEmpty() && it.observations.isEmpty() && it.events.none { e -> e.kind=="effect_recorded" } }
                .sortedBy { it.updatedAt }.take(history.size-499).map { it.id }.toSet()
            history=history.filterNot { it.id in removable }
        }
        val records=history+claimed
        require(records.size<=500) { "Run history is full. Remove old conversations before continuing." }
        state.copy(executions=records) to claimed
    }
    override suspend fun update(id: String, owner: String, transform: (ExecutionRun)->ExecutionRun): ExecutionRun = store.change { state ->
        val old=state.executions.firstOrNull { it.id==id } ?: throw StaleRunException()
        if(old.owner!=owner || owner.isBlank() || old.state!=ExecutionState.RUNNING || old.privacyRevision!=privacy(state,old.conversationId)) throw StaleRunException()
        val next=transform(old).copy(revision=old.revision+1,updatedAt=now())
        require(next.id==old.id && next.goal==old.goal && next.privacyRevision==old.privacyRevision)
        require(next.archive.length<=2_000_000 && next.events.size<=200 && next.tools.size<=100)
        state.copy(executions=state.executions.map { if(it.id==id) next else it }) to next
    }
    companion object {
        fun privacy(state: KaiState, cid: String): Long {
            val c=state.conversations.firstOrNull { it.id==cid } ?: throw StaleRunException()
            val project=state.projects.firstOrNull { it.id==c.projectId }
            return MemoryRules.digest("${c.contextAfter}:${project?.forgottenBefore}:${state.memoryGraph.deletionEpoch}:${state.settings.useMemory}:${state.memoryTombstones}:${state.memoryGraph.suppressions}:${state.deletedDocumentIds}").take(15).toLong(16)
        }
        fun recover(r: ExecutionRun) = if(r.state==ExecutionState.RUNNING) r.copy(state=ExecutionState.PAUSED,owner="",detail="Interrupted. Continue saved work.",revision=r.revision+1) else r
        fun invalidate(previous: KaiState, next: KaiState): KaiState {
            val kept=next.executions.mapNotNull { r ->
                if(next.conversations.none { it.id==r.conversationId }) return@mapNotNull null
                val invalid=runCatching { privacy(next,r.conversationId)!=r.privacyRevision }.getOrDefault(true)
                if(!invalid && r.state==ExecutionState.WAITING_APPROVAL && r.owner.isBlank()) {
                    val tasks=next.tasks.filter { it.conversationId==r.conversationId && it.instructions==r.goal.request && it.createdAt>=r.createdAt }
                    if(tasks.any { it.status==TaskStatus.READY && it.submittedAt==null }) return@mapNotNull r.copy(state=ExecutionState.PAUSED,detail="An approved action is ready. Continue to settle the same intent.",revision=r.revision+1)
                    if(tasks.any { it.status in setOf(TaskStatus.RUNNING,TaskStatus.UNKNOWN) }) return@mapNotNull r.copy(state=ExecutionState.RECONCILING,detail="Check the action outcome in Tasks.",revision=r.revision+1)
                    if(tasks.isNotEmpty() && tasks.none { it.status in setOf(TaskStatus.AWAITING_APPROVAL,TaskStatus.READY) }) return@mapNotNull r.copy(state=ExecutionState.PAUSED,detail="Action review finished or changed. Continue from saved progress.",revision=r.revision+1)
                }
                if(!invalid) r else r.copy(archive="[]",tools=emptyList(),artifacts=emptyList(),observations=emptyList(),owner="",state=ExecutionState.CANCELLED,
                    goal=GoalContract("Private context removed"),detail="Context changed; old run cannot resume.",events=emptyList(),revision=r.revision+1)
            }
            return next.copy(executions=kept)
        }
    }
}
