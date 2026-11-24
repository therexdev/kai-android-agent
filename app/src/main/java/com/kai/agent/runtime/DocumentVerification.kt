package com.kai.agent.runtime

import com.kai.agent.data.AgentTask
import com.kai.agent.data.TaskStatus
import org.commonmark.node.*
import org.commonmark.parser.Parser
import org.json.JSONObject
import java.text.Normalizer

/** Derived from retained execution records, never from a model's completion claim.
 * Verification covers visible body text at the read's observation time, not formatting,
 * link destinations, factual accuracy, permissions, or delivery to another person.
 */
object DocumentVerification {
    enum class Status { UNVERIFIED, TEXT_MATCHED, TEXT_DIFFERS }
    data class Evidence(val status: Status = Status.UNVERIFIED, val readTaskId: String = "", val observedAt: Long = 0) {
        val matched get() = status == Status.TEXT_MATCHED
    }
    private data class Body(val text: String, val markdown: Boolean, val whole: Boolean)
    private val accepted = setOf(TaskStatus.ACCEPTED, TaskStatus.SUCCEEDED)
    private val reads = setOf("GOOGLEDOCS_GET_DOCUMENT_PLAINTEXT", "GOOGLEDOCS_GET_DOCUMENT", "GOOGLEDOCS_GET_DOCUMENT_BY_ID")
    private val creates = setOf("GOOGLEDOCS_CREATE_DOCUMENT", "GOOGLEDOCS_CREATE_DOCUMENT_MARKDOWN")
    private val replaces = setOf("GOOGLEDOCS_UPDATE_DOCUMENT_MARKDOWN")
    private val idPattern = Regex("[A-Za-z0-9_-]{1,200}")

    fun evaluate(write: AgentTask, tasks: List<AgentTask>): Evidence {
        val intent = write.intent ?: return Evidence()
        if (intent.ownerId.isBlank() || intent.accountId.isBlank() || intent.connectionGeneration.isBlank() ||
            (intent.executionId.isBlank() && write.runId.isBlank())) return Evidence()
        if (intent.readOnly || intent.transportId != "composio" || write.status !in accepted) return Evidence()
        val submitted = write.submittedAt ?: return Evidence()
        val receipt = write.receipts.lastOrNull { it.operationId == intent.id && it.status in setOf("ACCEPTED", "SUCCEEDED") && it.observedAt >= submitted } ?: return Evidence()
        val args = parse(intent.argumentsJson) ?: return Evidence()
        val body = body(intent.toolName, args) ?: return Evidence()
        val target = if (intent.toolName in creates) {
            val ids = listOf(receipt.providerId, documentUrlId(receipt.url)).filter(String::isNotBlank).distinct()
            ids.singleOrNull()
        } else target(args)
        if (target == null || !idPattern.matches(target)) return Evidence()
        val expected = if (body.markdown) markdownPattern(body.text) ?: return Evidence()
            else normalize(body.text).takeIf(String::isNotBlank)?.let { Regex(Regex.escape(it)) } ?: return Evidence()

        // Only a same-run, same-owner, same-account read dispatched AFTER acknowledgement.
        // Prefer the newest read, including a failed/incomplete one: old evidence cannot hide it.
        val candidates = tasks.filter { read ->
            val ri = read.intent ?: return@filter false
            ri.readOnly && ri.transportId == intent.transportId && ri.toolName in reads &&
                ri.ownerId == intent.ownerId && ri.accountId == intent.accountId &&
                ri.connectionGeneration == intent.connectionGeneration &&
                ri.executionId == intent.executionId && read.runId == write.runId &&
                read.conversationId == write.conversationId && read.instructions == write.instructions &&
                (read.submittedAt ?: Long.MIN_VALUE) > receipt.observedAt &&
                parse(ri.argumentsJson)?.let(::target) == target
        }
        val latest = candidates.maxOfOrNull { it.submittedAt ?: 0 } ?: return Evidence()
        val read = candidates.filter { it.submittedAt == latest }.singleOrNull() ?: return Evidence()
        val ri = requireNotNull(read.intent)
        val rr = read.receipts.lastOrNull { it.operationId == ri.id && it.status == "SUCCEEDED" && it.observedAt >= requireNotNull(read.submittedAt) }
        if (read.status != TaskStatus.SUCCEEDED || rr == null) return Evidence()
        val readArgs = parse(ri.argumentsJson) ?: return Evidence()
        if (listOf("tab_ids", "tab_id", "range", "start_index", "end_index").any(readArgs::has)) return Evidence()
        if (listOf("include_tabs_content", "include_headers", "include_footers", "include_footnotes").any { readArgs.opt(it) == true }) return Evidence()
        val envelope = parse(read.resultContext) ?: return Evidence()
        if (envelope.optString("source") != "untrusted_connected_app_output" ||
            envelope.optString("reportedStatus") != "SUCCEEDED" || envelope.opt("truncated") != false) return Evidence()
        val data = when (val raw = envelope.opt("data")) {
            is String -> parse(raw)
            is JSONObject -> raw
            else -> null
        } ?: return Evidence()
        val document = document(data) ?: return Evidence()
        if (target(document) != target) return Evidence()
        val actualText = when (ri.toolName) {
            "GOOGLEDOCS_GET_DOCUMENT_PLAINTEXT" -> document.opt("plain_text") as? String
            "GOOGLEDOCS_GET_DOCUMENT", "GOOGLEDOCS_GET_DOCUMENT_BY_ID" -> structuredText(document)
            else -> null
        } ?: return Evidence()
        val actual = normalize(actualText)
        val matches = if (body.whole) expected.matches(actual) else expected.containsMatchIn(actual)
        return Evidence(if (matches) Status.TEXT_MATCHED else Status.TEXT_DIFFERS, read.id, rr.observedAt)
    }

    private fun body(tool: String, args: JSONObject): Body? {
        fun scoped(value: JSONObject) = listOf("tab_id", "tabId", "segmentId", "segment_id").any(value::has) ||
            listOf("location", "endOfSegmentLocation").any { key -> value.optJSONObject(key)?.let { it.has("segmentId") || it.has("tabId") } == true }
        if (scoped(args)) return null
        if (tool == "GOOGLEDOCS_BATCH_UPDATE_DOCUMENT") {
            val requests = args.optJSONArray("requests") ?: return null
            if (requests.length() != 1) return null
            val insert = requests.optJSONObject(0)?.optJSONObject("insertText") ?: return null
            if (scoped(insert)) return null
            return (insert.opt("text") as? String)?.takeIf(String::isNotBlank)?.let { Body(it, false, false) }
        }
        if (tool !in creates + replaces + setOf("GOOGLEDOCS_INSERT_TEXT")) return null
        val fields = listOf("text", "content", "markdown_text", "markdown").mapNotNull { key ->
            (args.opt(key) as? String)?.takeIf(String::isNotBlank)?.let { key to it }
        }
        val (key, value) = fields.singleOrNull() ?: return null
        return Body(value, key in setOf("markdown", "markdown_text") || tool.endsWith("_MARKDOWN"), tool in creates + replaces)
    }

    private fun target(args: JSONObject): String? {
        val ids = listOf("documentId", "document_id").mapNotNull { args.opt(it) as? String }.distinct()
        return ids.singleOrNull()?.takeIf(idPattern::matches)
    }
    private fun documentUrlId(url: String) = Regex("https://docs\\.google\\.com/document/d/([A-Za-z0-9_-]+)(?:[/?#].*)?").matchEntire(url)?.groupValues?.get(1).orEmpty()
    private fun parse(raw: String): JSONObject? = if (raw.length > 48_000) null else runCatching { JSONObject(raw) }.getOrNull()
    private fun document(value: JSONObject, depth: Int = 0): JSONObject? {
        if (value.optString("status").lowercase() in setOf("failed", "error", "unknown", "pending", "queued", "processing") ||
            listOf("status_code", "statusCode", "status").any { (value.opt(it) as? Number)?.toInt()?.let { code -> code >= 400 || code == 202 } == true }) return null
        if (depth > 3 || listOf("success", "successful", "ok").any { value.opt(it) == false } ||
            listOf("error", "errors").any { value.has(it) && !value.isNull(it) && value.opt(it)?.toString() !in setOf("", "[]", "{}") }) return null
        if (target(value) != null) return value
        // Known transport wrappers only. Request echoes, metadata, comments and arbitrary strings
        // are never searched for a copy of the expected text.
        val children = listOf("data", "result", "response").mapNotNull { value.optJSONObject(it) }
        return children.singleOrNull()?.let { document(it, depth + 1) }
    }
    private fun structuredText(document: JSONObject): String? {
        if (document.has("tabs")) return null // Tab scoping needs a separate adapter contract.
        val content = document.optJSONObject("body")?.optJSONArray("content") ?: return null
        val out = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: return null
            if (block.has("sectionBreak")) continue
            val elements = block.optJSONObject("paragraph")?.optJSONArray("elements") ?: return null
            for (j in 0 until elements.length()) {
                val element = elements.optJSONObject(j) ?: return null
                out.append(element.optJSONObject("textRun")?.opt("content") as? String ?: return null)
            }
        }
        return out.toString()
    }
    private fun normalize(text: String) = Normalizer.normalize(text, Normalizer.Form.NFC).replace(Regex("[\\s\\p{Z}]+"), " ").trim()
    private fun markdownPattern(source: String): Regex? {
        // The supported Docs Markdown action also expands escaped line separators. This does
        // not mutate approved arguments. Code/HTML/images are deliberately not verified as prose.
        if ('\uE000' in source || '\uE001' in source) return null
        val sourceText = if ('\n' !in source) source.replace("\\n", "\n") else source
        if (Regex("(?m)^\\s*\\|.*\\|\\s*$").containsMatchIn(sourceText)) return null
        val root = Parser.builder().build().parse(sourceText)
        val out = StringBuilder(); var supported = true
        fun visit(node: Node) {
            when (node) {
                is Text -> out.append(node.literal)
                is SoftLineBreak, is HardLineBreak -> out.append(' ')
                is ThematicBreak -> out.append(" \uE001 ")
                is Code, is FencedCodeBlock, is IndentedCodeBlock, is HtmlBlock, is HtmlInline, is Image -> supported = false
                else -> {
                    if (node is Block) out.append(' ')
                    if (node is ListItem) out.append('\uE000')
                    var child = node.firstChild
                    while (child != null) { visit(child); child = child.next }
                    if (node is Block) out.append(' ')
                }
            }
        }
        visit(root)
        if (!supported) return null
        val text = normalize(out.toString()).replace("\uE000 ", "\uE000")
        if (text.replace("\uE000", "").isBlank()) return null
        // Rendered list markers are optional ONLY at positions of actual Markdown list items.
        // Never strip a number or punctuation from an ordinary paragraph or literal write.
        fun prose(value: String) = value.split('\uE000').joinToString("(?:(?:[●•▪◦*+\\-]|[0-9]+[.)]) )?") { Regex.escape(it) }
        // The Docs Markdown converter renders a thematic break as exactly 50 underscores.
        // Permit that rendering only at the corresponding AST node; never strip underscores
        // or punctuation elsewhere, or accept missing body text as a formatting difference.
        val parts = text.split('\uE001').map(String::trim)
        if (parts.all { it.replace("\uE000", "").isBlank() }) return null
        val pattern = StringBuilder(prose(parts.first()))
        for (index in 1 until parts.size) {
            val left = parts[index-1].isNotEmpty(); val right = parts[index].isNotEmpty()
            pattern.append(when {
                left && right -> " (?:_{50} )?"
                left -> "(?: _{50})?"
                right -> "(?:_{50} )?"
                else -> "(?:_{50} ?)?"
            }).append(prose(parts[index]))
        }
        return Regex(pattern.toString())
    }
}
