package com.kai.agent.connections

import java.util.Locale

/** Catalog coverage is distinct from permission to read a provider's data. */
data class ToolDiscovery(
    val tools: List<ConnectionTool>,
    val usedFallback: Boolean,
    val hasMore: Boolean,
    val examinedCount: Int
) {
    companion object {
        /** Relevance only: the read-only bit still comes from verified provider metadata. */
        fun rank(tools: List<ConnectionTool>, search: String): List<ConnectionTool> {
            val words = tokens(search)
            if (words.isEmpty()) return tools
            val groups = listOf(
                setOf("mail", "email", "emails", "message", "messages", "inbox", "gmail"),
                setOf("read", "get", "fetch", "list", "check", "search", "find"),
                setOf("send", "compose", "reply", "forward"),
                setOf("calendar", "event", "events", "meeting", "meetings"),
                setOf("create", "add", "insert"),
                setOf("delete", "remove", "trash")
            )
            val requestedGroups = groups.filter { words.any(it::contains) }
            val readIntent = words.any(groups[1]::contains) && words.none(groups[2]::contains) && words.none(groups[5]::contains)
            return tools.withIndex().sortedWith(compareByDescending<IndexedValue<ConnectionTool>> { (_, tool) ->
                val names = tokens(tool.id + " " + tool.name)
                val description = tokens(tool.description)
                val requestName = words - nameNoise
                val displayName = tokens(tool.name) - nameNoise
                words.count(names::contains) * 8 + words.count(description::contains) * 2 +
                    requestedGroups.count { group -> names.any(group::contains) } * 5 +
                    (if (readIntent && tool.readOnly) 6 else 0) +
                    (if (requestName.isNotEmpty() && requestName == displayName) 40 else 0)
            }.thenBy { it.index }).map { it.value }
        }
        private val nameNoise = setOf("a", "an", "the", "in", "of", "to", "for", "google", "googledocs")

        /** Recovery relevance, never permission or an automatic replacement for a rejected action.
         * A shared verb such as "create" is insufficient evidence that a tool fits "create doc".
         * Only used for a guessed tool name; explicit discovery and exact tool inspection remain available.
         */
        fun matchingObjects(tools: List<ConnectionTool>, search: String, toolkit: String): List<ConnectionTool> {
            val verbs = setOf("create", "add", "insert", "make", "new", "read", "get", "fetch", "list", "check", "search", "find",
                "send", "compose", "reply", "forward", "update", "edit", "modify", "delete", "remove", "trash", "with", "by", "from")
            val objects = tokens(search) - nameNoise - verbs - tokens(toolkit)
            if (objects.isEmpty()) return emptyList() // Ask for a specific discovery query instead of dumping a whole catalog.
            return tools.filter { tool ->
                tool.toolkit == toolkit && objects.any(tokens(tool.id + " " + tool.name + " " + tool.description)::contains)
            }
        }
        private fun tokens(value: String) = value.lowercase(Locale.ROOT).split(Regex("[^a-z0-9]+"))
            .filter { it.isNotBlank() }.map { if (it in setOf("doc", "docs", "documents")) "document" else it }.toSet()
    }
}
