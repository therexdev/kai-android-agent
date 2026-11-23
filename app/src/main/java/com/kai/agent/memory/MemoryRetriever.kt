package com.kai.agent.memory

import com.kai.agent.data.*
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Scores retrieve candidates only. Identity and joined answers always follow explicit stored IDs. */
data class MemoryRetrieval(val contextJson: String, val factIds: List<String>, val clarification: String? = null)

object MemoryRetriever {
    private val words = Regex("[\\p{L}\\p{N}]+")
    private val stop = setOf("the", "is", "are", "my", "a", "an", "when", "what", "who", "about", "do", "you", "know", "remember", "tell", "me", "s")
    private val months = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

    fun describeFact(graph: MemoryGraph, fact: StructuredFact): String {
        fun name(id: String): String = graph.entities.firstOrNull { it.id == id && it.scope == fact.scope }?.displayName ?: "Unknown person"
        val subject = name(fact.subjectId)
        val objectId = fact.objectEntityId
        if (objectId != null) return if (fact.predicate.startsWith("has_"))
            "${name(objectId)} is ${if (fact.subjectId.startsWith("self:")) "your" else "$subject's"} ${fact.predicate.removePrefix("has_").replace('_', ' ')}."
            else "$subject ${fact.predicate.replace('_', ' ')} ${name(objectId)}."
        val value = requireNotNull(fact.value)
        val rendered = if (value.type == "DATE") buildString {
            value.month?.takeIf { it in 1..12 }?.let { append(months[it - 1]) }
            value.day?.let { append(" $it") }
            value.year?.let { if (isNotEmpty()) append(", "); append(it) }
        } else value.text
        return when (fact.predicate) {
            "legacy_note", "note" -> if (fact.subjectId.startsWith("self:")) rendered else "$subject: $rendered"
            "name" -> if (fact.subjectId.startsWith("self:")) "Your name is $rendered." else "$subject's name is $rendered."
            "first_name", "last_name" -> "${if (fact.subjectId.startsWith("self:")) "Your" else "$subject's"} ${fact.predicate.replace('_', ' ')} is $rendered."
            "alias" -> "$subject also goes by $rendered."
            "birthday" -> "${if (fact.subjectId.startsWith("self:")) "Your" else "$subject's"} birthday is $rendered."
            "preference" -> "${if (fact.subjectId.startsWith("self:")) "You prefer" else "$subject prefers"} $rendered."
            "dislikes" -> "${if (fact.subjectId.startsWith("self:")) "You dislike" else "$subject dislikes"} $rendered."
            else -> "$subject: ${fact.predicate.replace('_', ' ')} = $rendered."
        }.take(2000)
    }

    fun retrieve(graph: MemoryGraph, query: String, scopes: Set<String>, vectorScores: Map<String, Double> = emptyMap(),
                 limit: Int = 12, maxChars: Int = 8000, keywordFactIds: Set<String> = emptySet()): MemoryRetrieval {
        val allowed = scopes.take(20).toSet()
        val entities = graph.entities.filter { it.scope in allowed }.associateBy { it.id }
        val facts = graph.facts.filter { it.status == FactStatus.CURRENT && it.scope in allowed && it.subjectId in entities &&
            (it.objectEntityId == null || it.objectEntityId in entities) && !MemoryGraphRules.isSuppressed(it, graph) }
        val factIds = facts.map { it.id }.toSet()
        val text = normalized(query.take(4000))
        val queryWords = tokens(text)
        fun containsName(name: String): Boolean {
            val n = normalized(name)
            return n.length >= 2 && Regex("(?<![\\p{L}\\p{N}])${Regex.escape(n)}(?![\\p{L}\\p{N}])").containsMatchIn(text)
        }
        val namedIds = (entities.values.filter { !it.id.startsWith("self:") && containsName(it.displayName) }.map { it.id } +
            graph.aliases.filter { it.scope in allowed && it.entityId in entities && containsName(it.name) && it.evidenceFactIds.any { id -> id in factIds } }.map { it.entityId }).toSet()
        val roleMatch = Regex("\\bmy (daughter|son|child|kid|children|wife|husband|partner|mother|father|sister|brother|friend|colleague)(?:'s|s)?\\b").find(text)
        val role = roleMatch?.groupValues?.get(1)
        val roles = if (role in setOf("child", "kid", "children")) setOf("has_child", "has_daughter", "has_son") else role?.let { setOf("has_$it") }.orEmpty()
        val relations = facts.filter { it.subjectId.startsWith("self:") && it.predicate in roles }
        val targets = if (role != null) relations.mapNotNull { it.objectEntityId }.toSet().let { all -> if (namedIds.isEmpty()) all else all intersect namedIds }
            else namedIds
        val birthday = Regex("\\b(?:birthday|birthdays|born|birth date|birth month)\\b").containsMatchIn(text)
        val clarification = when {
            role != null && targets.size > 1 -> "Which $role do you mean: ${targets.mapNotNull { entities[it]?.displayName?.take(80) }.distinct().take(5).joinToString(", ")}?"
            role == null && birthday && namedIds.size > 1 -> "More than one person matches that name. Which person do you mean?"
            role != null && targets.isEmpty() -> "I do not have a confirmed ${role.replace("kid", "child")} relationship matching that request."
            else -> null
        }
        // Required join edges are included before scalar facts, keeping the complete relationship evidence chain.
        val primary = mutableListOf<StructuredFact>()
        if (targets.isNotEmpty()) {
            val boundedTargets = targets.take(20).toSet()
            primary += relations.filter { it.objectEntityId in boundedTargets }.take(24)
            primary += facts.filter { it.subjectId in boundedTargets && (if (birthday) it.predicate == "birthday" else true) }
            if (!birthday) primary += facts.filter { it.objectEntityId in boundedTargets }.take(24)
        }
        val directIds = primary.map { it.id }.toSet()
        val ranked = facts.filterNot { it.id in directIds }.mapNotNull { fact ->
            val content = tokens(describeFact(graph, fact))
            val overlap = queryWords.count { it in content }
            val lexical = if (queryWords.isEmpty()) 0.0 else overlap.toDouble() / queryWords.size
            val semantic = vectorScores[fact.id]?.takeIf { it.isFinite() }?.coerceIn(-1.0, 1.0) ?: 0.0
            val fts = if (fact.id in keywordFactIds) 1.0 else 0.0
            val relevant = overlap > 0 || semantic >= 0.30 || fts > 0
            if (!relevant || (birthday && role != null && targets.isEmpty()) || (birthday && targets.isNotEmpty() && fact.predicate == "birthday" && fact.subjectId !in targets)) null
            else fact to (lexical * 3.0 + semantic.coerceAtLeast(0.0) + fts)
        }.sortedWith(compareByDescending<Pair<StructuredFact, Double>> { it.second }.thenBy { it.first.id }).map { it.first }
        val selected = (primary + ranked).distinctBy { it.id }.take(limit.coerceIn(1, 24))
        val array = JSONArray()
        val included = mutableListOf<String>()
        for (fact in selected) {
            val sources = JSONArray()
            graph.evidence.filter { it.factId == fact.id && !MemoryGraphRules.sourceBlocked(graph, it.conversationId, it.sourceMessageId) }
                .sortedByDescending { it.observedAt }.take(2).forEach { e -> sources.put(JSONObject()
                    .put("messageId", e.sourceMessageId).put("conversationId", e.conversationId)
                    .put("quote", e.quote.take(400)).put("confirmed", e.confirmedByUser)) }
            val row = JSONObject().put("id", fact.id).put("revision", fact.revision).put("subjectId", fact.subjectId)
                .put("subject", entities[fact.subjectId]?.displayName).put("predicate", fact.predicate)
                .put("text", describeFact(graph, fact)).put("sources", sources)
            fact.objectEntityId?.let { row.put("objectEntityId", it).put("object", entities[it]?.displayName) }
            fact.value?.let { row.put("value", JSONObject().put("type", it.type).put("text", it.text)
                .put("month", it.month ?: JSONObject.NULL).put("day", it.day ?: JSONObject.NULL)
                .put("year", it.year ?: JSONObject.NULL).put("precision", it.precision)) }
            if (array.toString().length + row.toString().length > (maxChars - 600).coerceAtLeast(100)) break
            array.put(row); included += fact.id
        }
        val root = JSONObject().put("facts", array).put("clarification", clarification ?: JSONObject.NULL)
            .put("rules", "Only current scoped facts. Similarity is retrieval, not identity. Missing date parts are unknown. Ask the clarification rather than choosing a person.")
        return MemoryRetrieval(root.toString(), included, clarification)
    }

    private fun tokens(text: String) = words.findAll(normalized(text)).map { it.value }.filter { it.length > 1 && it !in stop }.toSet()
}
