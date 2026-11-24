package com.kai.agent.data

import org.json.JSONArray
import org.json.JSONObject

/** Phone-owned approval and replay guards; Composio must also validate connected-account ownership. */
object TaskRules {
    fun fingerprint(intent: ActionIntent): String {
        require(intent.toolName.isNotBlank()) { "An action needs a tool name." }
        require(intent.accountId.isNotBlank()) { "Choose a connected account before preparing an action." }
        val arguments = canonical(JSONObject(intent.argumentsJson))
        val schema = canonical(JSONObject(intent.toolSchemaJson))
        return MemoryRules.digest(listOf(intent.id, intent.toolName, intent.accountId,
            intent.ownerId, intent.connectionGeneration, intent.toolVersion, schema, arguments,
            intent.idempotencyKey).let { if(intent.transportId=="composio" && !intent.readOnly && intent.executionId.isBlank() && intent.effectKind.isBlank()) it else it+listOf(intent.transportId,intent.readOnly.toString(),intent.executionId,intent.effectKind) }.joinToString("\u0000"))
    }

    private fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
            JSONObject.quote(it) + ":" + canonical(value.get(it))
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }

    fun approve(task: AgentTask, settings: AppSettings, at: Long = now()): AgentTask {
        require(task.status == TaskStatus.AWAITING_APPROVAL) { "This task is not awaiting approval." }
        val intent = requireNotNull(task.intent) { "Prepare an action before approving it." }
        validateBinding(intent, settings)
        return task.copy(status = TaskStatus.READY, approvedFingerprint = fingerprint(intent),
            approvedAt = at, updatedAt = at,
            events = task.events + TaskEvent(status = TaskStatus.READY, detail = "Exact action approved", createdAt = at))
    }

    fun validateBinding(intent: ActionIntent, settings: AppSettings) {
        if(intent.transportId.startsWith("mcp:")) {
            val server=settings.mcpServers.firstOrNull { it.enabled && "mcp:${it.id}"==intent.transportId } ?: error("MCP connection is disabled or removed.")
            require(intent.ownerId==settings.composioUserId && intent.accountId==server.accountId && intent.connectionGeneration==server.generation) { "MCP identity or credentials changed; review a new action." }
            return
        }
        require(intent.transportId=="composio") { "Unsupported action transport." }
        require(intent.ownerId.isNotBlank() && intent.ownerId == settings.composioUserId) { "The Composio user identity changed. Prepare this action again." }
        require(intent.connectionGeneration.isNotBlank() && intent.connectionGeneration == settings.connectionGeneration) {
            "The connection generation changed or has not been refreshed. Prepare this action again."
        }
        require(settings.composioApiKey.isNotBlank()) { "Add your Composio API key before taking this action." }
    }

    fun invalidatePendingBinding(task: AgentTask, settings: AppSettings, at: Long = now()): AgentTask {
        val intent = task.intent ?: return task
        if (task.status !in setOf(TaskStatus.READY, TaskStatus.AWAITING_APPROVAL)) return task
        val valid = runCatching { validateBinding(intent,settings) }.isSuccess
        if (valid) return task
        return task.copy(status = TaskStatus.DRAFT, approvedFingerprint = "", approvedAt = null,
            updatedAt = at, events = task.events + TaskEvent(status = TaskStatus.DRAFT,
                detail = "Connection credentials changed; prepare and review this action again", createdAt = at))
    }

    fun transition(task: AgentTask, next: TaskStatus, settings: AppSettings, detail: String = "", at: Long = now()): AgentTask {
        require(task.status != next) { "The task is already in that state." }
        val allowed = when (task.status) {
            TaskStatus.DRAFT -> setOf(TaskStatus.AWAITING_APPROVAL, TaskStatus.READY, TaskStatus.CANCELLED, TaskStatus.FAILED)
            TaskStatus.AWAITING_APPROVAL -> setOf(TaskStatus.DRAFT, TaskStatus.CANCELLED)
            TaskStatus.READY -> setOf(TaskStatus.RUNNING, TaskStatus.DRAFT, TaskStatus.CANCELLED, TaskStatus.FAILED)
            TaskStatus.RUNNING -> setOf(TaskStatus.ACCEPTED, TaskStatus.SUCCEEDED, TaskStatus.UNKNOWN, TaskStatus.FAILED)
            TaskStatus.ACCEPTED -> setOf(TaskStatus.SUCCEEDED, TaskStatus.UNKNOWN, TaskStatus.FAILED)
            TaskStatus.UNKNOWN -> setOf(TaskStatus.ACCEPTED, TaskStatus.SUCCEEDED, TaskStatus.FAILED)
            TaskStatus.FAILED -> if (task.submittedAt == null) setOf(TaskStatus.DRAFT, TaskStatus.CANCELLED) else emptySet()
            TaskStatus.SUCCEEDED, TaskStatus.CANCELLED -> emptySet()
        }
        require(next in allowed) { "Unsafe task transition: ${task.status} to $next. Unknown or submitted actions must be reconciled, not retried." }
        if (next == TaskStatus.READY && task.intent != null) {
            error("An external action must be approved with approveTask().")
        }
        if (next == TaskStatus.RUNNING && task.intent != null) {
            validateBinding(task.intent, settings)
            require(task.approvedFingerprint == fingerprint(task.intent)) { "Action details changed after approval." }
            require(task.submittedAt == null) { "This action was already submitted. Reconcile its receipt before continuing." }
        }
        if (task.status == TaskStatus.UNKNOWN || next == TaskStatus.SUCCEEDED || next == TaskStatus.ACCEPTED) {
            require(task.receipts.isNotEmpty() || task.intent == null) { "A service receipt is required to establish this outcome." }
        }
        return task.copy(status = next, updatedAt = at,
            submittedAt = if (next == TaskStatus.RUNNING && task.intent != null) at else task.submittedAt,
            lastError = if (next in setOf(TaskStatus.FAILED, TaskStatus.UNKNOWN)) detail else "",
            events = task.events + TaskEvent(status = next, detail = detail, createdAt = at))
    }

    fun recover(task: AgentTask, at: Long = now()): AgentTask = if (task.status == TaskStatus.RUNNING) {
        task.copy(status = TaskStatus.UNKNOWN, updatedAt = at,
            lastError = "KAI stopped while this task was running. Check its outcome before any new action.",
            events = task.events + TaskEvent(status = TaskStatus.UNKNOWN,
                detail = "Recovered interrupted task; automatic retry disabled", createdAt = at))
    } else task
}
