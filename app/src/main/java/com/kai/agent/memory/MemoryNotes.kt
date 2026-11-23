package com.kai.agent.memory

import com.kai.agent.data.*

/** Extracted properties are the memory; the original user quotation remains their evidence. */
object MemoryNotes {
    fun propose(graph: MemoryGraph, source: MemoryUtterance, quote: String,
                proposals: List<ModelMemoryProposal> = emptyList()): MemoryUpdate {
        require(!source.temporary && source.role == "user") { "Temporary chats cannot save memories." }
        require(quote.length in 3..2000 && !MemoryPatterns.hasSecret(quote)) { "Choose a short detail without passwords or access keys." }
        val start = source.text.indexOf(quote)
        require(start >= 0 && !sourceBlocked(graph, source)) { "That detail is not available from your current conversation." }
        require(!MemoryPatterns.ambiguousName(quote)) { "The spelling is unclear. Ask the user to type the exact name before changing memory." }
        val local = MemoryPatterns.parse(source).filter { it.start >= start && it.start + it.quote.length <= start + quote.length }
        val remainder = quote.toCharArray()
        local.forEach { fact -> for (index in fact.start - start until fact.start - start + fact.quote.length) remainder[index] = ' ' }
        // Revisit a pending 0.4.12 transcript card only when every meaningful word is covered.
        // Preserve mixed/unknown notes and all committed facts; this is a review conversion.
        val oldDrafts = if (local.isNotEmpty() && remainder.none { it.isLetterOrDigit() } &&
            graph.evidence.none { it.sourceMessageId == source.messageId && it.conversationId == source.conversationId })
            graph.candidates.filter { it.status == CandidateStatus.PENDING && it.sourceMessageId == source.messageId &&
                it.conversationId == source.conversationId && it.proposedFacts.size == 1 &&
                it.proposedFacts.single().predicate == "note" && it.proposedFacts.single().value?.text == quote }
            else emptyList()
        val working = graph.copy(candidates = graph.candidates - oldDrafts.toSet())
        val reviewed = MemoryWriter(working, source, MemoryMode.ASK_FIRST).review(local)
        val grounded = proposals.filter { it.start >= start && it.start + it.quote.length <= start + quote.length }
        val interpreted = MemoryEngine.propose(reviewed.graph, source, grounded, MemoryMode.ASK_FIRST)
        val saved = (reviewed.savedFactIds + interpreted.savedFactIds).distinct()
        val pending = (reviewed.candidateIds + interpreted.candidateIds).distinct()
        require(local.isNotEmpty() || grounded.isNotEmpty() || pending.isNotEmpty() || saved.isNotEmpty()) {
            "No fact was extracted. Include normalized claims with subjectId, predicate, value and exact user quote; do not copy the whole message into a note."
        }
        if (oldDrafts.isNotEmpty() && pending.size == 1) {
            val id = oldDrafts.last().id
            return MemoryUpdate(interpreted.graph.copy(candidates = interpreted.graph.candidates.map {
                if (it.id == pending.single()) it.copy(id = id) else it
            }), saved, listOf(id), handled = true)
        }
        return MemoryUpdate(interpreted.graph, saved, pending, handled = true)
    }
}
