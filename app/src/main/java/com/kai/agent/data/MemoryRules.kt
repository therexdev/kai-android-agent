package com.kai.agent.data

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import kotlin.math.min

/** Deterministic evidence lookup. No remote embeddings and no model-authored memory as truth. */
object MemoryRules {
    private val words = Regex("[\\p{L}\\p{N}]+")
    private val stopWords = setOf("the", "and", "that", "this", "what", "with", "about", "have", "does", "please", "remember", "you", "your", "for", "are", "was", "did", "can")

    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")

    fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun textHash(text: String): String = digest(normalize(text))

    /** A whole source message is excluded after forget; snippets are not retained in tombstones. */
    fun sourceHash(source: MemorySource): String = if (source.ref.isBlank()) "" else
        digest(source.conversationId + "\u0000" + source.ref)

    fun tombstone(fact: MemoryFact, reason: String = "forgotten", at: Long = now()): MemoryTombstone =
        MemoryTombstone(fact.id, textHash(fact.text), sourceHash(fact.source), at,
            fact.revision, fact.lineageIds, reason)

    fun isSuppressed(fact: MemoryFact, tombstones: List<MemoryTombstone>): Boolean = tombstones.any { tomb ->
        // An explicit correction keeps its ID and advances the revision; the old source stays blocked
        // for automatic re-extraction under a new ID.
        if (fact.id == tomb.factId && fact.revision > tomb.revision) false
        else {
            val matching = fact.id == tomb.factId ||
                tomb.normalizedTextHash == textHash(fact.text) ||
                (tomb.sourceHash.isNotEmpty() && tomb.sourceHash == sourceHash(fact.source)) ||
                fact.lineageIds.any { it == tomb.factId || it in tomb.lineageIds }
            // A later explicit user request may intentionally remember something again. Imports of
            // old backups cannot revive it merely by import time; original timestamps are preserved.
            val explicitNewInstruction = fact.origin == MemoryOrigin.EXPLICIT_USER &&
                fact.createdAt > tomb.deletedAt && fact.id != tomb.factId
            matching && !explicitNewInstruction
        }
    }

    fun search(facts: List<MemoryFact>, tombstones: List<MemoryTombstone>, query: String,
               scopes: Set<String> = setOf("personal"), limit: Int = 8,
               at: Long = now()): List<MemoryEvidence> {
        val normalizedQuery = normalize(query)
        val queryTokens = words.findAll(normalizedQuery).map { it.value }
            .filter { it.length > 1 && it !in stopWords }.toSet()
        return facts.asSequence().filter { it.scope in scopes && !isSuppressed(it, tombstones) }
            .mapNotNull { fact ->
                val text = normalize(fact.text)
                val tokens = words.findAll(text).map { it.value }.toSet()
                val matched = queryTokens.count { it in tokens }
                val exact = normalizedQuery.isNotBlank() && text.contains(normalizedQuery)
                if (queryTokens.isNotEmpty() && matched == 0 && !exact) null
                else if (normalizedQuery.isNotBlank() && queryTokens.isEmpty() && !exact) null
                else {
                    val coverage = if (queryTokens.isEmpty()) 0.0 else matched.toDouble() / queryTokens.size
                    val recency = 1.0 / (1.0 + (at - fact.updatedAt).coerceAtLeast(0) / 86_400_000.0)
                    val score = coverage * 8 + matched + (if (exact) 5 else 0) +
                        (if (fact.pinned) 1.5 else 0.0) + recency * 0.2 +
                        fact.confidence.coerceIn(0.0, 1.0) * 0.2
                    MemoryEvidence(fact, score)
                }
            }.sortedWith(compareByDescending<MemoryEvidence> { it.score }.thenByDescending { it.fact.updatedAt })
            .take(limit.coerceIn(0, 100)).toList()
    }
}
