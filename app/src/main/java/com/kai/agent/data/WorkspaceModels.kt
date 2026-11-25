package com.kai.agent.data

import java.time.*
import java.util.UUID

enum class RunStatus { QUEUED, RUNNING, PAUSED, NEEDS_APPROVAL, BLOCKED, COMPLETED, FAILED, CANCELLED }
enum class StepStatus { PENDING, RUNNING, COMPLETED, SKIPPED }
data class RunMessage(val role: String, val content: String, val toolCalls: List<com.kai.agent.inference.ToolCall> = emptyList(), val toolCallId: String = "", val providerReplay: com.kai.agent.inference.ProviderReplay? = null, val preparationSlot: com.kai.agent.inference.PreparationSlot? = null)
data class RunTool(val accountId: String, val toolId: String, val version: String)
data class RunStep(
    val id: String = newId(), val title: String, val instructions: String,
    val status: StepStatus = StepStatus.PENDING, val output: String = "",
    val messages: List<RunMessage> = emptyList(), val tools: List<RunTool> = emptyList(),
    val pendingActionId: String = "", val turns: Int = 0, val contextRevision: Long = -1,
    val completedAt: Long = 0, val contextSummary: String = "",
    val nodeId: String = "", val modelId: String = "", val kind: String = "AGENT", val reviewTarget: String = "", val revisions: Int = 0, val maxRevisions: Int = 2, val revisionFeedback: String = "",
)
data class AgentRun(
    val id: String = newId(), val title: String, val goal: String,
    val projectId: String = "", val characterId: String = "kai", val conversationId: String,
    val status: RunStatus = RunStatus.QUEUED, val steps: List<RunStep> = emptyList(),
    val lease: String = "", val detail: String = "Waiting to start", val routineId: String = "",
    val createdAt: Long = now(), val updatedAt: Long = createdAt,
) {
    val currentStep: RunStep? get() = steps.firstOrNull { it.status !in setOf(StepStatus.COMPLETED, StepStatus.SKIPPED) }
    val terminal: Boolean get() = status in setOf(RunStatus.COMPLETED, RunStatus.CANCELLED)
}
enum class RoutineFrequency { HOURLY, DAILY, WEEKLY }
data class AgentRoutine(
    val id: String = newId(), val title: String, val goal: String,
    val projectId: String = "", val characterId: String = "kai",
    val frequency: RoutineFrequency = RoutineFrequency.DAILY,
    val hour: Int = 9, val minute: Int = 0, val weekday: Int = 1,
    val zoneId: String = ZoneId.systemDefault().id, val enabled: Boolean = true,
    val revision: Long = 1, val nextRunAt: Long, val lastRunId: String = "",
    val createdAt: Long = now(),
)
data class KaiProject(
    val id: String = newId(), val name: String, val memory: String = "",
    val revision: Long = 1, val forgottenBefore: Long = 0,
    val createdAt: Long = now(), val updatedAt: Long = createdAt,
)
data class ProjectDocument(
    val id: String = newId(), val name: String, val mimeType: String,
    val projectId: String = "", val conversationId: String = "", val text: String,
    val imageBase64: String = "", val sourceKind: String = "file", val sha256: String = "",
    val createdAt: Long = now(),
)

/** Calendar schedules retain the user's time zone, including daylight-saving transitions. */
object RoutineRules {
    fun next(routine: AgentRoutine, after: Long): Long {
        require(routine.hour in 0..23 && routine.minute in 0..59 && routine.weekday in 1..7)
        val zone = ZoneId.of(routine.zoneId)
        val instant = Instant.ofEpochMilli(after)
        val local = instant.atZone(zone)
        if (routine.frequency == RoutineFrequency.HOURLY) return instant.plusSeconds(3600).toEpochMilli()
        var date = local.toLocalDate()
        repeat(15) {
            if (routine.frequency != RoutineFrequency.WEEKLY || date.dayOfWeek.value == routine.weekday) {
                // atZone moves nonexistent spring times forward and chooses the first fall occurrence.
                val candidate = date.atTime(routine.hour, routine.minute).atZone(zone).toInstant()
                if (candidate > instant) return candidate.toEpochMilli()
            }
            date = date.plusDays(1)
        }
        error("Could not schedule this routine.")
    }
    fun occurrenceId(id: String, revision: Long, due: Long): String =
        UUID.nameUUIDFromBytes("kai-routine:$id:$revision:$due".toByteArray(Charsets.UTF_8)).toString()
}

object RunRules {
    fun recover(run: AgentRun): AgentRun = if (run.status == RunStatus.RUNNING)
        run.copy(status = RunStatus.QUEUED, lease = "", detail = "Resuming saved progress", updatedAt = now()) else run
    fun validate(run: AgentRun) {
        require(run.goal.isNotBlank() && run.goal.length <= 12000 && run.title.isNotBlank() && run.title.length <= 200)
        require(run.steps.size <= 8 && run.steps.map { it.id }.distinct().size == run.steps.size)
        run.steps.forEach {
            require(it.instructions.isNotBlank() && it.instructions.length <= 12000 && it.title.length <= 200)
            require(it.output.length <= 24000 && it.messages.sumOf { m -> m.content.length.toLong() } <= 1_000_000)
            require(it.messages.all { m -> m.role in setOf("system", "user", "assistant", "tool") })
            com.kai.agent.inference.NativeToolWire.validate(it.messages.map { m -> com.kai.agent.inference.InferenceMessage(m.role,m.content,toolCalls=m.toolCalls,toolCallId=m.toolCallId,providerReplay=m.providerReplay) },true,allowPending=it.pendingActionId.isNotBlank())
            require(it.contextSummary.length<=8000 && it.modelId.length<=300 && it.kind in setOf("AGENT","REVIEW") && it.revisions in 0..2 && it.maxRevisions in 0..2)
            require(it.tools.size <= 100 && it.turns in 0..24)
        }
    }
    fun requireOwner(run: AgentRun, lease: String) {
        check(run.status == RunStatus.RUNNING && lease.isNotBlank() && run.lease == lease) { "Task ownership changed." }
    }
}
