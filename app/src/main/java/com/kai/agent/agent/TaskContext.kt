package com.kai.agent.agent

import com.kai.agent.data.AgentTask
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Bounded, conversation-scoped facts from the phone ledger. Never authority to perform an action. */
object TaskContext {
    private const val MAX_TASKS = 6
    private const val MAX_ARGUMENT_CHARS = 1_800
    private val credentialKey = Regex("(?i)(authorization|cookie|password|passwd|secret|token|api[_-]?key|private[_-]?key|credential)")

    fun ledger(tasks: List<AgentTask>, conversationId: String, maxChars: Int = 5_000, redactDetailsBefore: Long = 0): String {
        require(maxChars in 512..20_000) { "Invalid task context budget." }
        val relevant = if (conversationId.isBlank()) emptyList() else tasks.filter { it.conversationId == conversationId }
            .sortedByDescending { it.updatedAt }.distinctBy { it.id }
        val selected = relevant.take(MAX_TASKS).toMutableList()
        fun redacted(task: AgentTask) = redactDetailsBefore > 0 && task.createdAt <= redactDetailsBefore
        val entries = selected.map { baseEntry(it, redacted(it)) }.toMutableList()
        fun output() = JSONObject().put("source", "phone_task_ledger")
            .put("omittedTasks", relevant.size - entries.size)
            .put("tasks", JSONArray().apply { entries.forEach { put(it) } }).toString()

        // Preserve recent identities/statuses first. Never cut a JSON document or replace a status.
        while (entries.isNotEmpty() && output().length > maxChars) {
            entries.removeAt(entries.lastIndex)
            selected.removeAt(selected.lastIndex)
        }

        // Receipts outrank argument detail: knowing a request was submitted prevents repeats.
        selected.forEachIndexed { index, task ->
            if (redacted(task)) return@forEachIndexed
            val receipt = task.receipts.lastOrNull() ?: return@forEachIndexed
            val details = entries[index].getJSONObject("lastReceipt")
            val url = receipt.url.takeIf { it.length <= 2048 && ToolResultSummary.displayUrl(it) != null }.orEmpty()
            val summary = receipt.summary.take(300)
            details.put("url", url).put("summary", summary)
                .put("summaryTruncated", receipt.summary.length > summary.length)
                .put("detailsOmitted", receipt.url.isNotBlank() && url.isBlank())
            if (output().length > maxChars) {
                details.put("url", "").put("summary", "").put("detailsOmitted", true)
                details.remove("summaryTruncated")
            }
        }

        selected.forEachIndexed { index, task ->
            val intent = task.intent ?: return@forEachIndexed
            val entry = entries[index]
            if (redacted(task)) { entry.put("argumentsOmittedReason", "memory_change"); return@forEachIndexed }
            val raw = intent.argumentsJson
            if (raw.length > MAX_ARGUMENT_CHARS) {
                entry.put("argumentsOmittedReason", "too_large")
                return@forEachIndexed
            }
            val args = readArguments(raw)
            if (args == null) {
                entry.put("argumentsOmittedReason", "invalid_or_sensitive")
                return@forEachIndexed
            }
            entry.put("arguments", args).put("argumentsOmitted", false)
            entry.remove("argumentsOmittedReason")
            if (output().length > maxChars) {
                entry.remove("arguments")
                entry.put("argumentsOmitted", true).put("argumentsOmittedReason", "context_budget")
            }
        }
        // Reason labels can add a few characters when the minimal record fills the entire budget.
        while (entries.isNotEmpty() && output().length > maxChars) {
            entries.removeAt(entries.lastIndex)
        }
        return output()
    }

    private fun baseEntry(task: AgentTask, redactDetails: Boolean): JSONObject = JSONObject().apply {
        putIdentifier(this, "id", task.id, 256)
        put("title", if (redactDetails) JSONObject.NULL else task.title.take(120))
        if (redactDetails) put("detailsExcludedAfterMemoryChange", true)
        else if (task.title.length > 120) put("titleTruncated", true)
        put("status", task.status.name)
        put("updatedAt", task.updatedAt)
        put("submitted", task.submittedAt != null)
        task.intent?.let { intent ->
            putIdentifier(this, "tool", intent.toolName, 250)
            putIdentifier(this, "accountId", intent.accountId, 250)
            put("argumentsOmitted", true)
        }
        val receipt = task.receipts.lastOrNull()
        put("lastReceipt", if (receipt == null) JSONObject.NULL else JSONObject().apply {
            putIdentifier(this, "status", receipt.status, 64)
            putIdentifier(this, "providerId", receipt.providerId, 200)
            put("url", "").put("summary", "").put("detailsOmitted", true)
        })
    }

    /** Never provide a truncated identifier that could be mistaken for a different actual object. */
    private fun putIdentifier(target: JSONObject, name: String, value: String, max: Int) {
        if (value.length <= max && value.none { it.code < 32 }) target.put(name, value)
        else target.put(name, JSONObject.NULL).put(name + "Omitted", true)
    }

    private fun readArguments(raw: String): JSONObject? {
        var depth = 0
        var quoted = false
        var escaped = false
        for (c in raw) {
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{', '[' -> { depth++; if (depth > 12) return null }
                '}', ']' -> { depth--; if (depth < 0) return null }
            }
        }
        if (depth != 0 || quoted) return null
        return try {
            val tokener = JSONTokener(raw)
            val value = tokener.nextValue() as? JSONObject ?: return null
            if (tokener.nextClean() != '\u0000' || hasCredential(value)) null else value
        } catch (_: Exception) { null }
    }

    private fun hasCredential(value: Any?): Boolean = when (value) {
        is JSONObject -> value.keys().asSequence().any { credentialKey.containsMatchIn(it) || hasCredential(value.opt(it)) }
        is JSONArray -> (0 until value.length()).any { hasCredential(value.opt(it)) }
        else -> false
    }
}
