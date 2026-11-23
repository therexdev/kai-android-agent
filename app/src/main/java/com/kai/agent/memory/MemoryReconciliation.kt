package com.kai.agent.memory

import com.kai.agent.data.*

/** Match a property of a stable subject, never a person by fuzzy name or embedding similarity. */
internal object MemoryReconciliation {
    fun canonical(fact: StructuredFact): StructuredFact {
        if (fact.predicate !in setOf("note", "legacy_note") || !fact.subjectId.startsWith("self:")) return fact
        val text = fact.value?.text ?: return fact
        val parsed = MemoryPatterns.parse(MemoryUtterance("note-review", "note-review", text, scope = fact.scope))
            .singleOrNull() ?: return fact
        if (parsed.subject != "self" || parsed.relationship != null || !isSingleValue(parsed.predicate)) return fact
        // A partial match must never discard the other information in a mixed note.
        val rest = text.removeRange(parsed.start, parsed.start + parsed.quote.length)
        if (rest.any { it.isLetterOrDigit() }) return fact
        return fact.copy(predicate = parsed.predicate, value = parsed.value)
    }
    fun sameSlot(a: StructuredFact, b: StructuredFact): Boolean {
        val old = canonical(a)
        return old.scope == b.scope && old.subjectId == b.subjectId && old.predicate == b.predicate && isSingleValue(b.predicate)
    }
    fun pending(graph: MemoryGraph, facts: List<StructuredFact>): MemoryCandidate? =
        graph.candidates.lastOrNull { it.status == CandidateStatus.PENDING && it.kind != CandidateKind.IDENTITY &&
            it.proposedFacts.any { old -> facts.any { next -> sameSlot(old, next) ||
                old.scope == next.scope && old.subjectId == next.subjectId && old.predicate == next.predicate && sameValue(old, next) } } }
    fun current(graph: MemoryGraph, fact: StructuredFact) = graph.facts.filter {
        it.status == FactStatus.CURRENT && !MemoryGraphRules.isSuppressed(it, graph) &&
            (sameSlot(it, fact) || it.scope == fact.scope && it.subjectId == fact.subjectId && it.predicate == fact.predicate)
    }
}
