package com.kai.agent.data

private fun withRun(state: KaiState, run: AgentRun) = state.copy(runs = state.runs.map { if (it.id == run.id) run else it })
private fun makeRun(state: KaiState, title: String, goal: String, projectId: String, characterId: String,
                    steps: List<String>, id: String = newId(), routineId: String = ""): Pair<KaiState, AgentRun> {
    require(projectId.isBlank() || state.projects.any { it.id == projectId }) { "Project not found." }
    require(state.characters.any { it.id == characterId }) { "Character not found." }
    val conversation = Conversation(title = title.take(160), characterId = characterId, projectId = projectId,
        messages = listOf(ChatMessage(role = "user", text = goal)))
    val run = AgentRun(id = id, title = title.trim(), goal = goal.trim(), projectId = projectId,
        characterId = characterId, conversationId = conversation.id, routineId = routineId,
        steps = steps.filter { it.isNotBlank() }.map { RunStep(title = it.take(120), instructions = it.trim()) })
    RunRules.validate(run)
    return state.copy(runs = state.runs + run, conversations = state.conversations + conversation) to run
}
suspend fun KaiStore.createRun(title: String, goal: String, projectId: String = "", characterId: String = "kai",
                               steps: List<String> = emptyList(), routineId: String = ""): AgentRun = change {
    makeRun(it,title,goal,projectId,characterId,steps,routineId=routineId)
}
suspend fun KaiStore.claimRun(id: String, lease: String): AgentRun? = change { state ->
    val run = state.runs.firstOrNull { it.id == id } ?: return@change state to null
    if (run.status != RunStatus.QUEUED) return@change state to null
    val next = run.copy(status = RunStatus.RUNNING, lease = lease, detail = "Working on your request", updatedAt = now())
    withRun(state,next) to next
}
suspend fun KaiStore.editRun(id: String, lease: String, transform: (AgentRun) -> AgentRun): AgentRun = change { state ->
    val old = state.runs.firstOrNull { it.id == id } ?: error("Task no longer exists.")
    RunRules.requireOwner(old,lease)
    val next = transform(old).copy(updatedAt = now())
    require(next.id == old.id && next.conversationId == old.conversationId && next.projectId == old.projectId)
    withRun(state,next) to next
}
suspend fun KaiStore.setRunStatus(id: String, status: RunStatus): AgentRun = change { state ->
    val old = state.runs.first { it.id == id }
    require(!old.terminal) { "This task is already finished." }
    require(status in setOf(RunStatus.QUEUED,RunStatus.PAUSED,RunStatus.CANCELLED))
    val next = old.copy(status = status, lease = "", updatedAt = now(), detail = when(status) {
        RunStatus.QUEUED -> "Continuing from saved progress"; RunStatus.PAUSED -> "Paused by you"; else -> "Cancelled by you" })
    val tasks = if (status != RunStatus.CANCELLED) state.tasks else state.tasks.map { task ->
        if (task.runId == id && task.submittedAt == null && task.status in setOf(TaskStatus.DRAFT,TaskStatus.READY,TaskStatus.AWAITING_APPROVAL))
            TaskRules.transition(task,TaskStatus.CANCELLED,state.settings,"Parent task cancelled") else task
    }
    withRun(state.copy(tasks=tasks),next) to next
}
suspend fun KaiStore.skipRunStep(id: String): AgentRun = change { state ->
    val run = state.runs.first { it.id == id }
    require(run.status in setOf(RunStatus.BLOCKED,RunStatus.PAUSED,RunStatus.FAILED)) { "Pause this task before skipping a step." }
    val step = requireNotNull(run.currentStep)
    val action = state.tasks.firstOrNull { it.id == step.pendingActionId }
    require(action?.status != TaskStatus.RUNNING) { "The submitted action is still settling. Check its result before continuing." }
    val nextSteps = run.steps.map { if (it.id == step.id) it.copy(status=StepStatus.SKIPPED,
        output="Skipped by you; any external action outcome remains in its receipt.",completedAt=now(),messages=emptyList()) else it }
    val complete = nextSteps.all { it.status in setOf(StepStatus.COMPLETED,StepStatus.SKIPPED) }
    val next = run.copy(steps=nextSteps,status=if(complete) RunStatus.COMPLETED else RunStatus.QUEUED,
        lease="",detail=if(complete) "Finished with a skipped step" else "Continuing after your review",updatedAt=now())
    val tasks = state.tasks.map { if(it.id==step.pendingActionId && it.submittedAt==null && it.status in setOf(TaskStatus.DRAFT,TaskStatus.READY,TaskStatus.AWAITING_APPROVAL))
        TaskRules.transition(it,TaskStatus.CANCELLED,state.settings,"Step skipped by you") else it }
    withRun(state.copy(tasks=tasks),next) to next
}
suspend fun KaiStore.deleteRun(id: String) = change { state ->
    require(state.runs.firstOrNull { it.id == id }?.status != RunStatus.RUNNING) { "Pause the task first." }
    state.copy(runs=state.runs.filterNot { it.id==id }) to Unit
}
suspend fun KaiStore.finishRunStep(id: String, lease: String, stepId: String, output: String): AgentRun = change { state ->
    val run=state.runs.first { it.id==id }; RunRules.requireOwner(run,lease)
    val step=requireNotNull(run.currentStep); require(step.id==stepId && step.pendingActionId.isBlank())
    require(output.isNotBlank() && output.length<=24000)
    val steps=run.steps.map { if(it.id==stepId) it.copy(status=StepStatus.COMPLETED,output=output,
        messages=emptyList(),tools=emptyList(),completedAt=now()) else it }
    val completed=steps.all { it.status in setOf(StepStatus.COMPLETED,StepStatus.SKIPPED) }
    val next=run.copy(steps=steps,status=if(completed) RunStatus.COMPLETED else RunStatus.RUNNING,
        detail=if(completed) "Finished" else "Saved ${steps.count { it.status==StepStatus.COMPLETED }} of ${steps.size} steps",updatedAt=now())
    val conversations=state.conversations.map { if(it.id==run.conversationId) it.copy(updatedAt=now(),messages=it.messages+
        ChatMessage(role="assistant",text="${step.title}\n\n$output")) else it }
    withRun(state.copy(conversations=conversations),next) to next
}

/** The step pointer and exact external intent commit together, before any request can be submitted. */
suspend fun KaiStore.prepareRunAction(runId: String, lease: String, intent: ActionIntent, title: String,
                                      assistantJson: String, readOnly: Boolean): AgentTask = change { state ->
    val run=state.runs.first { it.id==runId }; RunRules.requireOwner(run,lease)
    val step=requireNotNull(run.currentStep); require(step.pendingActionId.isBlank())
    require(step.contextRevision == WorkspaceRules.contextRevision(state,run.projectId,run.conversationId)) { "Project context changed; resume to refresh the task." }
    TaskRules.validateBinding(intent,state.settings); TaskRules.fingerprint(intent)
    var task=AgentTask(title=title,instructions=run.goal,intent=intent,status=TaskStatus.AWAITING_APPROVAL,
        conversationId=run.conversationId,runId=runId,events=listOf(TaskEvent(status=TaskStatus.AWAITING_APPROVAL,detail="Prepared exact action")))
    if(readOnly) task=TaskRules.approve(task,state.settings)
    val updatedStep=step.copy(pendingActionId=task.id,messages=step.messages+RunMessage("assistant",assistantJson))
    val updatedRun=run.copy(steps=run.steps.map { if(it.id==step.id) updatedStep else it },updatedAt=now())
    withRun(state.copy(tasks=state.tasks+task),updatedRun) to task
}
suspend fun KaiStore.beginRunAction(runId: String, lease: String, taskId: String): AgentTask = change { state ->
    val run=state.runs.first { it.id==runId }; RunRules.requireOwner(run,lease)
    require(run.currentStep?.pendingActionId==taskId)
    val task=state.tasks.first { it.id==taskId && it.runId==runId }
    val next=TaskRules.transition(task,TaskStatus.RUNNING,state.settings,"Submitting approved action")
    state.copy(tasks=state.tasks.map { if(it.id==taskId) next else it }) to next
}
/** A cancelled worker may still receive a real receipt; retain it without rearming its parent. */
suspend fun KaiStore.recordRunAction(taskId: String, receipt: ActionReceipt, status: TaskStatus, context: String): AgentTask = change { state ->
    val task=state.tasks.first { it.id==taskId }; val intent=requireNotNull(task.intent)
    require(receipt.operationId==intent.id || receipt.operationId==intent.idempotencyKey)
    require(status in setOf(TaskStatus.SUCCEEDED,TaskStatus.ACCEPTED,TaskStatus.UNKNOWN,TaskStatus.FAILED))
    // A late receipt is still evidence, but cannot restore the context of a deleted project/run.
    val contextLive=state.runs.any { it.id==task.runId } && state.conversations.any { it.id==task.conversationId }
    val evidence=task.copy(receipts=(task.receipts+receipt).distinctBy { Triple(it.operationId,it.status,it.providerId) },
        resultContext=if(contextLive)context.take(24000) else "")
    val next=if(evidence.status==status) evidence else TaskRules.transition(evidence,status,state.settings,receipt.summary)
    state.copy(tasks=state.tasks.map { if(it.id==taskId) next else it }) to next
}
suspend fun KaiStore.consumeRunAction(runId: String, lease: String, taskId: String): AgentRun = change { state ->
    val run=state.runs.first { it.id==runId }; RunRules.requireOwner(run,lease)
    val step=requireNotNull(run.currentStep); require(step.pendingActionId==taskId)
    val action=state.tasks.first { it.id==taskId }
    require(action.status in setOf(TaskStatus.SUCCEEDED,TaskStatus.FAILED,TaskStatus.CANCELLED))
    val result=action.resultContext.ifBlank { action.receipts.lastOrNull()?.summary ?: action.lastError.ifBlank { "Action was not executed." } }
    val updated=step.copy(pendingActionId="",messages=step.messages+RunMessage("user",
        "Phone action outcome: ${action.status}. Do not repeat this operation. Untrusted result data:\n$result"))
    val next=run.copy(steps=run.steps.map { if(it.id==step.id) updated else it },updatedAt=now())
    withRun(state,next) to next
}

suspend fun KaiStore.saveRoutine(routine: AgentRoutine): AgentRoutine = change { state ->
    val previous=state.routines.firstOrNull { it.id==routine.id }
    val next=routine.copy(revision=(previous?.revision ?: 0)+1,nextRunAt=RoutineRules.next(routine,now()),
        createdAt=previous?.createdAt ?: routine.createdAt,lastRunId=previous?.lastRunId.orEmpty())
    state.copy(routines=state.routines.filterNot { it.id==next.id }+next) to next
}
suspend fun KaiStore.deleteRoutine(id: String) = change { it.copy(routines=it.routines.filterNot { r -> r.id==id }) to Unit }
/** One occurrence and the next schedule commit atomically; late alarms never create a backlog. */
suspend fun KaiStore.claimRoutine(id: String, revision: Long, due: Long, at: Long=now()): AgentRun? = change { state ->
    val routine=state.routines.firstOrNull { it.id==id && it.revision==revision && it.enabled } ?: return@change state to null
    val runId=RoutineRules.occurrenceId(id,revision,due)
    state.runs.firstOrNull { it.id==runId }?.let { return@change state to it }
    if(routine.nextRunAt!=due || due>at) return@change state to null
    // A previous occurrence waiting for review must not accumulate more copies of that work.
    if(state.runs.any { it.routineId==id && !it.terminal }) {
        val next=routine.copy(nextRunAt=RoutineRules.next(routine,at))
        return@change state.copy(routines=state.routines.map { if(it.id==id) next else it }) to null
    }
    val (created,run)=makeRun(state,routine.title,routine.goal,routine.projectId,routine.characterId,emptyList(),runId,id)
    val next=routine.copy(nextRunAt=RoutineRules.next(routine,at),lastRunId=run.id)
    created.copy(routines=created.routines.map { if(it.id==id) next else it }) to run
}

suspend fun KaiStore.saveProject(id: String?, name: String, memory: String): KaiProject = change { state ->
    require(name.trim().isNotEmpty() && name.length<=100 && memory.length<=20000)
    val old=id?.let { key -> state.projects.first { it.id==key } }
    val project=old?.copy(name=name.trim(),memory=memory,revision=old.revision+1,updatedAt=now(),
        forgottenBefore=if(old.memory!=memory) now() else old.forgottenBefore) ?: KaiProject(name=name.trim(),memory=memory)
    state.copy(projects=state.projects.filterNot { it.id==project.id }+project,
        runs=if(old!=null && old.memory!=memory)state.runs.map { run -> if(run.projectId==project.id)run.copy(steps=run.steps.map { it.copy(messages=emptyList(),tools=emptyList()) }) else run } else state.runs) to project
}
suspend fun KaiStore.addDocument(document: ProjectDocument): ProjectDocument = change { state ->
    require(document.text.isNotBlank() || document.imageBase64.isNotBlank()) { "No readable content was found." }
    val duplicate=state.documents.firstOrNull { document.sha256.isNotBlank() && it.sha256==document.sha256 &&
        it.projectId==document.projectId && it.conversationId==document.conversationId }
    if(duplicate!=null) return@change state to duplicate
    state.copy(documents=state.documents+document,projects=state.projects.map { if(it.id==document.projectId) it.copy(revision=it.revision+1,updatedAt=now()) else it }) to document
}
suspend fun KaiStore.deleteDocument(id: String) = change { state ->
    val doc=state.documents.firstOrNull { it.id==id } ?: return@change state to Unit
    state.copy(documents=state.documents.filterNot { it.id==id },deletedDocumentIds=(state.deletedDocumentIds+id).distinct(),
        projects=state.projects.map { if(it.id==doc.projectId) it.copy(revision=it.revision+1,forgottenBefore=now(),updatedAt=now()) else it },
        conversations=state.conversations.map { c -> c.copy(messages=c.messages.map { it.copy(documentIds=it.documentIds-id) },
            contextAfter=if(doc.projectId.isBlank() && c.id==doc.conversationId)now() else c.contextAfter) },
        runs=state.runs.map { run -> if(doc.projectId.isNotBlank() && run.projectId==doc.projectId || doc.projectId.isBlank() && run.conversationId==doc.conversationId)
            run.copy(steps=run.steps.map { it.copy(messages=emptyList(),tools=emptyList()) }) else run },
        memoryGraph=state.memoryGraph.copy(deletionEpoch=state.memoryGraph.deletionEpoch+1)) to Unit
}
suspend fun KaiStore.deleteProject(id: String) = change { state ->
    val scope=WorkspaceRules.scope(id)
    val conversationIds=state.conversations.filter { it.projectId==id }.map { it.id }.toSet()
    val runIds=state.runs.filter { it.projectId==id }.map { it.id }.toSet()
    val notes=state.memories.filter { it.scope==scope || it.source.conversationId in conversationIds }
    var graph=state.memoryGraph
    graph.facts.filter { it.scope==scope }.forEach { graph=MemoryGraphRules.forget(graph,it.id) }
    graph=graph.copy(candidates=graph.candidates.filterNot { it.conversationId in conversationIds },
        jobs=graph.jobs.map { if(it.conversationId in conversationIds) it.copy(status=MemoryJobStatus.CANCELLED,error="") else it },deletionEpoch=graph.deletionEpoch+1)
    val tasks=state.tasks.mapNotNull { task ->
        if(task.runId !in runIds && task.conversationId !in conversationIds) task
        else if(task.submittedAt==null) null
        else task.copy(instructions="",resultContext="") // Keep exact submission/receipt evidence for reconciliation.
    }
    state.copy(projects=state.projects.filterNot { it.id==id },deletedProjectIds=(state.deletedProjectIds+id).distinct(),
        documents=state.documents.filterNot { it.projectId==id },deletedDocumentIds=(state.deletedDocumentIds+state.documents.filter { it.projectId==id }.map { it.id }).distinct(),
        conversations=state.conversations.filterNot { it.id in conversationIds },runs=state.runs.filterNot { it.id in runIds },
        routines=state.routines.filterNot { it.projectId==id },tasks=tasks,memories=state.memories-notes.toSet(),
        memoryTombstones=state.memoryTombstones+notes.map { MemoryRules.tombstone(it,"project deleted") },memoryGraph=graph) to Unit
}

/** New directions supersede an unsubmitted proposal; submitted unknown outcomes still require review. */
suspend fun KaiStore.directRun(id: String, text: String): AgentRun = change { state ->
    val run=state.runs.first { it.id==id }
    require(run.status in setOf(RunStatus.PAUSED,RunStatus.BLOCKED,RunStatus.FAILED,RunStatus.NEEDS_APPROVAL)) { "Pause this task before changing its directions." }
    require(text.trim().length in 1..4000)
    val step=requireNotNull(run.currentStep) { "Resume to create the initial plan." }
    val action=state.tasks.firstOrNull { it.id==step.pendingActionId }
    require(action?.status !in setOf(TaskStatus.RUNNING,TaskStatus.UNKNOWN,TaskStatus.ACCEPTED)) { "Review the submitted action in its service before skipping this step." }
    val tasks=state.tasks.map { if(it.id==step.pendingActionId && it.submittedAt==null && it.status in setOf(TaskStatus.DRAFT,TaskStatus.READY,TaskStatus.AWAITING_APPROVAL))
        TaskRules.transition(it,TaskStatus.CANCELLED,state.settings,"Superseded by your new directions") else it }
    val instructions=step.instructions+"\nAdditional directions from you: "+text.trim()
    require(instructions.length<=12000) { "These directions are too long. Start a smaller task." }
    val updated=step.copy(instructions=instructions,messages=emptyList(),tools=emptyList(),pendingActionId="",turns=0)
    val next=run.copy(steps=run.steps.map { if(it.id==step.id)updated else it },status=RunStatus.QUEUED,lease="",detail="Continuing with your directions",updatedAt=now())
    val conversations=state.conversations.map { if(it.id==run.conversationId)it.copy(messages=it.messages+ChatMessage(role="user",text=text.trim()),updatedAt=now()) else it }
    withRun(state.copy(tasks=tasks,conversations=conversations),next) to next
}
