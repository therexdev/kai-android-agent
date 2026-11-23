package com.kai.agent.data

object WorkspaceRules {
    fun scope(projectId: String) = if (projectId.isBlank()) "personal" else "project:$projectId"
    fun contextRevision(state: KaiState, projectId: String, conversationId: String): Long {
        val projectRevision=state.projects.firstOrNull { it.id==projectId }?.revision ?: 0L
        val files=state.documents.filter { it.id !in state.deletedDocumentIds &&
            (it.projectId.isNotBlank() && it.projectId==projectId || it.projectId.isBlank() && it.conversationId==conversationId) }
            .map { it.id }.sorted().joinToString(",")
        return MemoryRules.digest("$projectId:$projectRevision:${state.memoryGraph.deletionEpoch}:${state.settings.useMemory}:$files").take(15).toLong(16)
    }
    fun validate(state: KaiState) {
        require(state.projects.size <= 100 && state.documents.size <= 200) { "Project storage is full. Remove unused files first." }
        require(state.projects.map { it.id }.distinct().size == state.projects.size)
        require(state.documents.map { it.id }.distinct().size == state.documents.size)
        require(state.runs.map { it.id }.distinct().size == state.runs.size)
        require(state.routines.map { it.id }.distinct().size == state.routines.size)
        require(state.runs.size <= 500 && state.routines.size <= 100) { "Remove old tasks or routines before adding more." }
        state.projects.forEach { require(it.name.isNotBlank() && it.name.length <= 100 && it.memory.length <= 20000 && it.id !in state.deletedProjectIds) }
        state.documents.forEach {
            require(it.id !in state.deletedDocumentIds && it.name.length in 1..200 && it.text.length <= 200000 && it.imageBase64.length <= 1000000)
            require(it.projectId.isBlank() || state.projects.any { p -> p.id == it.projectId }) { "The file's project is no longer available." }
            require(it.projectId.isNotBlank() || state.conversations.any { c -> c.id == it.conversationId }) { "The file's conversation is no longer available." }
        }
        require(state.documents.sumOf { it.text.length.toLong() } <= 4000000 && state.documents.sumOf { it.imageBase64.length.toLong() } <= 12000000) { "File storage is full. Remove unused files first." }
        state.runs.forEach(RunRules::validate)
        state.routines.forEach {
            require(it.title.isNotBlank() && it.title.length <= 200 && it.goal.isNotBlank() && it.goal.length <= 12000)
            require(it.projectId.isBlank() || state.projects.any { p -> p.id == it.projectId })
            RoutineRules.next(it, it.nextRunAt)
        }
    }

    /** Imported work is historical data, never an instruction to schedule or replay operations. */
    fun imported(current: KaiState, incoming: KaiState): KaiState {
        val deletedProjects = (current.deletedProjectIds + incoming.deletedProjectIds.filter { id -> current.projects.none { it.id == id } }).distinct()
        val deletedDocuments = (current.deletedDocumentIds + incoming.deletedDocumentIds.filter { id -> current.documents.none { it.id == id } }).distinct()
        val projects = current.projects + incoming.projects.filter { p -> p.id !in deletedProjects && current.projects.none { it.id == p.id } }
        val projectIds = projects.map { it.id }.toSet()
        val conversations = current.conversations.filter { it.projectId.isBlank() || it.projectId in projectIds }
        val conversationIds = conversations.map { it.id }.toSet()
        val documents = current.documents + incoming.documents.filter { d ->
            d.id !in deletedDocuments && current.documents.none { it.id == d.id } &&
                (if (d.projectId.isNotBlank()) d.projectId in projectIds else d.conversationId in conversationIds)
        }
        val runs = current.runs + incoming.runs.filter { r -> current.runs.none { it.id == r.id } &&
            r.conversationId in conversationIds && (r.projectId.isBlank() || r.projectId in projectIds) }.map {
            it.copy(status = if (it.terminal) it.status else RunStatus.PAUSED, lease = "",
                detail = "Imported history. Review before resuming.", steps = it.steps.map { step -> step.copy(messages = emptyList(), tools = emptyList()) })
        }
        val routines = current.routines + incoming.routines.filter { r -> current.routines.none { it.id == r.id } &&
            (r.projectId.isBlank() || r.projectId in projectIds) }.map { it.copy(enabled = false, revision = it.revision + 1) }
        return current.copy(projects = projects, documents = documents, runs = runs, routines = routines,
            conversations = conversations, deletedProjectIds = deletedProjects, deletedDocumentIds = deletedDocuments)
    }
}
