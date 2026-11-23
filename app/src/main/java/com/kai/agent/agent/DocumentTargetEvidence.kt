package com.kai.agent.agent

import com.kai.agent.connections.ConnectionServiceException
import com.kai.agent.connections.ConnectionTool
import com.kai.agent.connections.ConnectionAccount
import com.kai.agent.data.AgentTask
import com.kai.agent.data.TaskStatus
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Target evidence is not permission. Exact action approval is still required separately. */
class DocumentTargetEvidence(userTexts: List<String>, private val accounts: List<ConnectionAccount> = emptyList()) {
    private val supplied = userTexts.flatMap(::userIds).toSet()
    private val observed = mutableMapOf<String, MutableSet<String>>()
    private val confirmedDocumentLinks = mutableMapOf<String, MutableSet<String>>()

    // Same Google resource, independently verified Google identity. Aliases/display names never qualify.
    private fun linkedDocument(accountId: String, value: String): Boolean {
        val target = accounts.firstOrNull { it.id == accountId && it.active && it.toolkit in documentServices &&
            it.identitySource == "google_profile" && it.emailAddress.isNotBlank() } ?: return false
        return accounts.any { source -> source.active && source.toolkit in documentServices && source.userId == target.userId &&
            source.identitySource == "google_profile" && source.emailAddress.equals(target.emailAddress, ignoreCase = true) &&
            value in confirmedDocumentLinks[source.id].orEmpty() }
    }

    fun validate(accountId: String, tool: ConnectionTool, arguments: JSONObject) {
        if (tool.toolkit !in documentServices) return
        val schema = JSONObject(tool.schemaJson)
        val required = schema.optJSONArray("required")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }.orEmpty()
        val fields = (arguments.keys().asSequence().toSet() + required).filter { documentField(it) }
        for (field in fields) {
            val value = arguments.opt(field) as? String
            if (value == null || !validId(value) || (value !in supplied && value !in observed[accountId].orEmpty() && !linkedDocument(accountId, value))) {
                throw ConnectionServiceException("The document target is missing or unverified ($field). " +
                    "Use an exact document ID supplied by the user or returned by this account's successful lookup/creation. " +
                    "For a new document, discover and run the document-creation action first; an action that edits an existing document cannot create it. " +
                    "Never put a question or placeholder in an ID field. If the user must identify an existing document, ask them in a reply.")
            }
        }
    }

    /** Only real successful/accepted execution results; never model text or tool catalog examples. */
    fun observeResult(accountId: String, toolkit: String, raw: String) {
        if (toolkit !in documentServices) return
        val summary = ToolResultSummary.parseExecution(raw, true)
        if (summary.status !in confirmed) return
        val parsed = runCatching { strictJson(raw) }.getOrNull() ?: return
        fun visit(value: Any?, depth: Int = 0) {
            if (depth > 12) return
            when (value) {
                is JSONObject -> value.keys().forEach { key ->
                    val child = value.opt(key)
                    if ((documentField(key) || key.lowercase() in setOf("id", "fileid", "file_id")) && child is String && validId(child)) {
                        observed.getOrPut(accountId) { mutableSetOf() }.add(child)
                    } else if (child is JSONObject || child is JSONArray) visit(child, depth + 1)
                }
                is JSONArray -> for (i in 0 until minOf(value.length(), 1000)) visit(value.opt(i), depth + 1)
            }
        }
        visit(parsed)
        summary.evidenceUrls.flatMap(::urlIds).forEach {
            observed.getOrPut(accountId) { mutableSetOf() }.add(it)
            confirmedDocumentLinks.getOrPut(accountId) { mutableSetOf() }.add(it)
        }
    }

    fun observeTasks(tasks: List<AgentTask>, conversationId: String, boundary: Long, ownerId: String, generation: String) {
        tasks.filter { it.conversationId == conversationId && it.updatedAt > boundary && it.status in confirmed }.forEach { task ->
            val intent = task.intent ?: return@forEach
            if (intent.ownerId != ownerId || intent.connectionGeneration != generation ||
                !(intent.toolName.startsWith("GOOGLEDOCS_") || intent.toolName.startsWith("GOOGLEDRIVE_"))) return@forEach
            val ids = observed.getOrPut(intent.accountId) { mutableSetOf() }
            task.receipts.filter { it.status in confirmed.map(TaskStatus::name) && it.observedAt > boundary }.forEach { receipt ->
                if (validId(receipt.providerId)) ids.add(receipt.providerId)
                ids.addAll(urlIds(receipt.url))
                confirmedDocumentLinks.getOrPut(intent.accountId) { mutableSetOf() }.addAll(urlIds(receipt.url))
            }
            // Saved-task results contain a sanitized envelope with serialized service data.
            val result = runCatching { JSONObject(task.resultContext) }.getOrNull()
            if (result?.optString("reportedStatus") in confirmed.map(TaskStatus::name)) {
                val data = result?.opt("data")
                if (data is String) observeResult(intent.accountId, "googledocs", data)
            }
        }
    }

    companion object {
        private val documentServices = setOf("googledocs", "googledrive")
        private val confirmed = setOf(TaskStatus.SUCCEEDED, TaskStatus.ACCEPTED)
        private val identifier = Regex("[A-Za-z0-9_-]{1,200}")
        private val placeholder = Regex("(?i)(?:your[_-]?)?(?:document|doc|file)[_-]?id|(?:unknown|undefined|null|placeholder|todo|tbd|example)")
        private fun validId(value: String) = identifier.matches(value) && !placeholder.matches(value)
        private fun documentField(key: String) = key.replace("_", "").lowercase() in setOf("documentid", "sourcedocumentid", "destinationdocumentid", "fileid")
        private fun urlIds(text: String) = Regex("https://docs\\.google\\.com/document/d/([A-Za-z0-9_-]+)(?=[/?#\\s]|$)")
            .findAll(text).map { it.groupValues[1] }.filter(::validId).toList()
        private fun userIds(text: String): List<String> = urlIds(text) +
            Regex("(?i)\\b(?:document|doc|file)\\s*(?:id)?\\s*[:=]\\s*([A-Za-z0-9_-]+)")
                .findAll(text).map { it.groupValues[1] }.filter(::validId).toList() +
            Regex("(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{20,200}(?![A-Za-z0-9_-])")
                .findAll(text).map { it.value }.filter(::validId).toList()
        private fun strictJson(raw: String): Any? {
            val parser = JSONTokener(raw)
            val value = parser.nextValue()
            require(parser.nextClean() == '\u0000')
            return value
        }
    }
}
