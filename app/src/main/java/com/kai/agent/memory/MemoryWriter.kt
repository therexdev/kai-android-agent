package com.kai.agent.memory

import com.kai.agent.data.*
import java.util.Locale

internal class MemoryWriter(initial: MemoryGraph, private val u: MemoryUtterance, private val mode: MemoryMode) {
    private var graph = initial
    private var contextEvidence: List<MemoryContextQuote> = emptyList()
    private val saved = mutableListOf<String>()
    private val candidates = mutableListOf<String>()
    private val turnTargets = mutableMapOf<String, Pair<String, String?>>()
    private val turnQuotes = mutableMapOf<String, String>()
    private val self = userEntityId(u.scope)
    private val processed = initial.evidence.any { it.sourceMessageId == u.messageId && it.conversationId == u.conversationId } ||
        initial.candidates.any { it.sourceMessageId == u.messageId && it.conversationId == u.conversationId }
    private fun result() = MemoryUpdate(graph, saved.distinct(), candidates.distinct(), MemoryPatterns.parse(u).isNotEmpty())
    private fun enabled() = (mode != MemoryMode.OFF || u.explicit) && MemoryPatterns.eligible(u) && !sourceBlocked(graph, u)
    private fun current() = graph.facts.filter { it.status == FactStatus.CURRENT && it.scope == u.scope && !MemoryGraphRules.isSuppressed(it, graph) }
    private fun entities() = graph.entities.filter { it.scope == u.scope }
    private fun matches(name: String): List<MemoryEntity> {
        val key = normalized(name)
        val aliases = graph.aliases.filter { it.scope == u.scope && normalized(it.name) == key &&
            it.evidenceFactIds.any { id -> graph.facts.any { f -> f.id == id && f.status == FactStatus.CURRENT && !MemoryGraphRules.isSuppressed(f, graph) } } }.map { it.entityId }.toSet()
        return entities().filter { it.id != self && (it.id in aliases || normalized(it.displayName) == key) }
    }
    private fun entity(name: String) = MemoryEntity(displayName = name, scope = u.scope, createdAt = u.createdAt)
    private fun selfEntity() = graph.entities.firstOrNull { it.id == self } ?: MemoryEntity(id = self, displayName = "You", scope = u.scope, createdAt = u.createdAt)
    private fun relationTargets(role: String) = current().filter { it.subjectId == self && it.predicate == MemoryPatterns.relationPredicate(role) }
        .mapNotNull { fact -> entities().firstOrNull { it.id == fact.objectEntityId } }.distinctBy { it.id }
    private fun evidence(fact: StructuredFact, p: ParsedMemory, confirmed: Boolean = false) = FactEvidence(
        factId = fact.id, sourceMessageId = u.messageId, conversationId = u.conversationId, quote = p.quote,
        start = p.start, end = p.start + p.quote.length, observedAt = u.createdAt, confirmedByUser = confirmed)
    private fun proofs(fact: StructuredFact, p: ParsedMemory, confirmed: Boolean = false): List<FactEvidence> =
        listOf(evidence(fact, p, confirmed)) + contextEvidence.map { support ->
            FactEvidence(factId = fact.id, sourceMessageId = support.messageId, conversationId = u.conversationId,
                quote = support.quote, start = support.start, observedAt = support.createdAt, confirmedByUser = confirmed, sourceKind = "user_context")
        }
    private fun fact(subject: String, predicate: String, value: MemoryValue? = null, obj: String? = null) =
        StructuredFact(subjectId = subject, predicate = predicate, objectEntityId = obj, value = value,
            scope = u.scope, createdAt = u.createdAt, updatedAt = u.createdAt)

    fun ingest(): MemoryUpdate {
        if (!enabled() || processed) return result()
        val parsed = MemoryPatterns.parse(u)
        val speaker = MemoryConversationContext.speaker(u)
        parsed.forEach { p ->
            contextEvidence = if (speaker != "self" && p.subject == "self") MemoryConversationContext.speakerEvidence(u) else emptyList()
            if (p.relationship != null) {
                if (speaker == "self") relationship(p)
            } else property(if (p.subject == "self" && speaker != "self") p.copy(subject = speaker) else p)
        }
        return result()
    }

    fun review(parsed: List<ParsedMemory>): MemoryUpdate {
        if (!enabled()) return result()
        if (processed) return MemoryUpdate(graph,
            savedFactIds = graph.evidence.filter { it.sourceMessageId == u.messageId && it.conversationId == u.conversationId }
                .map { it.factId }.filter { id -> current().any { it.id == id } }.distinct(),
            candidateIds = graph.candidates.filter { it.status == CandidateStatus.PENDING && it.sourceMessageId == u.messageId && it.conversationId == u.conversationId }.map { it.id },
            handled = true)
        parsed.forEach { p -> if (p.relationship != null) relationship(p.copy(review = true)) else property(p.copy(review = true)) }
        return result()
    }

    private fun relationship(p: ParsedMemory) {
        val name = requireNotNull(p.name)
        turnQuotes[normalized(name)] = p.quote
        val role = requireNotNull(p.relationship)
        val names = matches(name)
        val related = relationTargets(role)
        val explicitlyNew = p.another
        val pendingRelated = graph.candidates.filter { it.status == CandidateStatus.PENDING && it.kind != CandidateKind.IDENTITY && it.conversationId == u.conversationId }
            .flatMap { c -> c.proposedFacts.filter { it.scope == u.scope && it.subjectId == self && it.predicate == MemoryPatterns.relationPredicate(role) }
                .mapNotNull { rel -> c.proposedEntities.firstOrNull { it.id == rel.objectEntityId && normalized(it.displayName) == normalized(name) } } }.distinctBy { it.id }
        // Reuse a unique unanchored named person (e.g. birthday was learned before the family relation).
        // Never merge two existing IDs, or silently turn a colleague/friend with the same name into a daughter.
        val unanchored = names.filter { e -> current().none { it.objectEntityId == e.id && it.predicate.startsWith("has_") } }
        var chosen: MemoryEntity? = when {
            explicitlyNew -> entity(name)
            names.isEmpty() && related.isEmpty() && pendingRelated.size == 1 -> pendingRelated.single()
            names.size == 1 && related.any { it.id == names.single().id } -> names.single()
            names.size == 1 && unanchored.size == 1 && related.isEmpty() -> names.single()
            names.isEmpty() && related.size == 1 && related.single().displayName.startsWith("Your ") -> related.single()
            names.isEmpty() && related.size == 1 && p.correction -> related.single()
            names.isEmpty() && related.isEmpty() -> entity(name)
            else -> null
        }
        val choices = (names + related).distinctBy { it.id }.map { it.id }
        if (chosen == null) {
            chosen = entity(name)
            val rel = fact(self, p.predicate, obj = chosen.id)
            val named = fact(chosen.id, "name", MemoryValue(text = name))
            queue(p, listOf(rel, named), listOf(selfEntity(), chosen), CandidateKind.IDENTITY,
                "Which person is $name, your $role?", choices)
            turnTargets[normalized(name)] = chosen.id to candidates.lastOrNull()
            return
        }
        val rel = fact(self, p.predicate, obj = chosen.id)
        val named = fact(chosen.id, "name", MemoryValue(text = name))
        val before = candidates.size
        write(p, listOf(rel, named), listOf(selfEntity(), chosen))
        turnTargets[normalized(name)] = chosen.id to candidates.lastOrNull().takeIf { candidates.size > before }
    }

    private fun property(p: ParsedMemory) {
        val target = p.subject
        turnTargets[normalized(target)]?.let { (entityId, pendingId) ->
            if (pendingId == null && matches(target).size > 1 && turnQuotes[normalized(target)] != p.quote) return@let
            if (pendingId == null) write(p, listOf(fact(entityId, p.predicate, p.value)), emptyList())
            else {
                val pending = graph.candidates.first { it.id == pendingId }
                val extra = fact(entityId, p.predicate, p.value)
                val projected = graph.copy(entities = (graph.entities + pending.proposedEntities).distinctBy { it.id })
                val replaced = pending.proposedFacts.filter { MemoryReconciliation.sameSlot(it, extra) ||
                    it.subjectId == extra.subjectId && it.predicate == extra.predicate && sameValue(it, extra) }.map { it.id }.toSet()
                val changed = pending.copy(proposedFacts = pending.proposedFacts.filterNot { it.id in replaced } + extra,
                    evidence = pending.evidence.filterNot { it.factId in replaced } + evidence(extra, p),
                    proposedAliases = pending.proposedAliases + aliasesFor(listOf(extra), projected),
                    summary = (pending.proposedFacts.filterNot { it.id in replaced } + extra).joinToString("\n") { MemoryRetriever.describeFact(projected, it) }.take(1000))
                graph = graph.copy(candidates = graph.candidates.map { if (it.id == pendingId) changed else it })
            }
            return
        }
        if (target == "self") {
            write(p, listOf(fact(self, p.predicate, p.value)), listOf(selfEntity()))
            return
        }
        val role = MemoryPatterns.relation(target)
        if (role != null) {
            val related = relationTargets(role)
            when (related.size) {
                1 -> write(p, listOf(fact(related.single().id, p.predicate, p.value)), emptyList())
                0 -> {
                    val unnamed = entity("Your $role")
                    write(p, listOf(fact(self, MemoryPatterns.relationPredicate(role), obj = unnamed.id),
                        fact(unnamed.id, p.predicate, p.value)), listOf(selfEntity(), unnamed))
                }
                else -> {
                    val placeholder = entity("The $role you mean")
                    queue(p, listOf(fact(placeholder.id, p.predicate, p.value)), listOf(placeholder), CandidateKind.IDENTITY,
                        "Which $role do you mean?", related.map { it.id })
                }
            }
            return
        }
        if (target.lowercase(Locale.ROOT) in setOf("her", "his", "their")) {
            val choices = recentFocus()
            val placeholder = entity("The person you mean")
            queue(p, listOf(fact(placeholder.id, p.predicate, p.value)), listOf(placeholder), CandidateKind.IDENTITY,
                "Whose ${p.predicate.replace('_', ' ')} should I remember?", if (choices.isNotEmpty()) choices else entities().filter { it.id != self }.map { it.id })
            return
        }
        val named = matches(target)
        when (named.size) {
            1 -> write(p, listOf(fact(named.single().id, p.predicate, p.value)), emptyList())
            0 -> {
                val person = entity(target)
                write(p, listOf(fact(person.id, p.predicate, p.value)), listOf(person))
            }
            else -> {
                val placeholder = entity(target)
                queue(p, listOf(fact(placeholder.id, p.predicate, p.value)), listOf(placeholder), CandidateKind.IDENTITY,
                    "Which $target do you mean?", named.map { it.id })
            }
        }
    }

    private fun recentFocus(): List<String> {
        val previous = graph.evidence.filter { it.conversationId == u.conversationId && it.sourceMessageId != u.messageId && it.observedAt <= u.createdAt }
        val latest = previous.maxByOrNull { it.observedAt }?.sourceMessageId ?: return emptyList()
        val ids = previous.filter { it.sourceMessageId == latest }.map { it.factId }.toSet()
        return current().filter { it.id in ids }.flatMap { listOfNotNull(it.subjectId.takeUnless { id -> id == self }, it.objectEntityId) }
            .distinct().take(20)
    }

    private fun write(p: ParsedMemory, facts: List<StructuredFact>, proposedEntities: List<MemoryEntity>) {
        val conflict = facts.any { proposed -> isSingleValue(proposed.predicate) &&
            MemoryReconciliation.current(graph, proposed).any { !sameValue(MemoryReconciliation.canonical(it), proposed) } }
        val sensitive = MemoryPatterns.isSensitive(p.quote)
        when {
            p.review || MemoryReconciliation.pending(graph, facts) != null -> queue(p, facts, proposedEntities,
                if (conflict) CandidateKind.CONFLICT else CandidateKind.ASSERTION, "Check these facts before saving.")
            conflict && !p.correction -> queue(p, facts, proposedEntities, CandidateKind.CONFLICT,
                "This differs from a saved memory. Replace it with this version?")
            sensitive && !u.explicit -> queue(p, facts, proposedEntities, CandidateKind.SENSITIVE,
                "Save this sensitive information in your memory?")
            mode == MemoryMode.ASK_FIRST && !u.explicit -> queue(p, facts, proposedEntities, CandidateKind.ASSERTION,
                "Remember this for future conversations?")
            else -> commit(facts, proposedEntities, facts.flatMap { proofs(it, p, u.explicit) }, p.correction)
        }
    }

    private fun queue(p: ParsedMemory, facts: List<StructuredFact>, newEntities: List<MemoryEntity>,
                      kind: CandidateKind, question: String, choices: List<String> = emptyList()) {
        // A delayed extraction of an earlier turn must not overwrite a newer correction/card.
        val newerProofs = graph.evidence + graph.candidates.filter { it.status == CandidateStatus.PENDING }.flatMap { it.evidence }
        val existingFacts = graph.facts.filter { it.status == FactStatus.CURRENT } +
            graph.candidates.filter { it.status == CandidateStatus.PENDING }.flatMap { it.proposedFacts }
        if (facts.any { next -> existingFacts.any { old -> MemoryReconciliation.sameSlot(old, next) &&
                !sameValue(MemoryReconciliation.canonical(old), next) && newerProofs.any { it.factId == old.id && it.observedAt > u.createdAt } } }) return
        if (kind != CandidateKind.IDENTITY && facts.all { next -> MemoryReconciliation.current(graph, next)
                .any { sameValue(MemoryReconciliation.canonical(it), next) } }) {
            saved += facts.mapNotNull { next -> MemoryReconciliation.current(graph, next)
                .firstOrNull { sameValue(MemoryReconciliation.canonical(it), next) }?.id }
            return
        }
        val new = newEntities.filter { proposed -> graph.entities.none { it.id == proposed.id } }
        val all = graph.copy(entities = (graph.entities + new).distinctBy { it.id })
        val aliases = aliasesFor(facts, all)
        val summary = facts.joinToString("; ") { MemoryRetriever.describeFact(all, it) }.take(1000)
        val candidate = MemoryCandidate(kind = kind, question = question, summary = summary, proposedFacts = facts,
            proposedEntities = new, proposedAliases = aliases, evidence = facts.flatMap { proofs(it, p) },
            entityChoices = (choices.take(19) + if (kind == CandidateKind.IDENTITY && new.any { MemoryPatterns.isName(it.displayName) && !it.id.startsWith("self:") }) listOf(MemoryEngine.NEW_PERSON_CHOICE) else emptyList()).distinct(), sourceMessageId = u.messageId, conversationId = u.conversationId, createdAt = u.createdAt)
        val previous = if (kind == CandidateKind.IDENTITY) null else MemoryReconciliation.pending(graph, facts)
        val replaced = previous?.proposedFacts.orEmpty().filter { old -> facts.any { next ->
            MemoryReconciliation.sameSlot(old, next) || old.subjectId == next.subjectId && old.predicate == next.predicate && sameValue(old, next)
        } }
        val retained = previous?.proposedFacts.orEmpty() - replaced.toSet()
        val before = (replaced + facts.flatMap { MemoryReconciliation.current(graph, it) })
            .filter { old -> facts.any { next -> MemoryReconciliation.sameSlot(old, next) && !sameValue(MemoryReconciliation.canonical(old), next) } }.distinctBy { it.id }
        val combinedEntities = (previous?.proposedEntities.orEmpty() + new).distinctBy { it.id }
        val combinedGraph = all.copy(entities = (all.entities + combinedEntities).distinctBy { it.id })
        val merged = candidate.copy(id = previous?.id ?: candidate.id,
            kind = if (before.isNotEmpty()) CandidateKind.CONFLICT else candidate.kind,
            question = if (before.isNotEmpty()) "Replace the earlier " + (if (previous != null) "suggestion" else "saved detail") + "?\n" +
                before.joinToString("\n") { "Before: " + MemoryRetriever.describeFact(combinedGraph, it) } else question,
            summary = (retained + facts).joinToString("\n") { MemoryRetriever.describeFact(combinedGraph, it) }.take(1000),
            proposedFacts = retained + facts, proposedEntities = combinedEntities,
            proposedAliases = previous?.proposedAliases.orEmpty().filter { a -> a.evidenceFactIds.none { id -> replaced.any { it.id == id } } } + aliases,
            evidence = previous?.evidence.orEmpty().filter { e -> retained.any { it.id == e.factId } } + candidate.evidence)
        graph = graph.copy(candidates = if (previous == null) graph.candidates + merged else graph.candidates.map { if (it.id == previous.id) merged else it })
        candidates += merged.id
    }

    private fun commit(facts: List<StructuredFact>, newEntities: List<MemoryEntity>, proofs: List<FactEvidence>, correction: Boolean) {
        val update = commitClaims(graph, facts, newEntities, proofs, correction)
        graph = update.graph
        saved += update.savedFactIds
    }

    fun propose(proposals: List<ModelMemoryProposal>): MemoryUpdate {
        if (!enabled()) return result()
        // A mixed turn may contain new assertions beyond the locally handled clauses. Protect each
        // handled source span, rather than discarding every proposal from the same message.
        val previousEvidence = graph.evidence.filter { it.sourceMessageId == u.messageId && it.conversationId == u.conversationId } +
            graph.candidates.filter { it.sourceMessageId == u.messageId && it.conversationId == u.conversationId }.flatMap { it.evidence }
        val handled = locallyHandledRanges(u) + previousEvidence.map { it.start until it.end }
        proposals.take(6).forEach { proposal ->
            val end = proposal.start + proposal.quote.length
            if (proposal.start < 0 || end > u.text.length || proposal.quote.isBlank() ||
                u.text.substring(proposal.start, end) != proposal.quote ||
                handled.any { overlaps(it, proposal.start until end) }) return@forEach
            val p = ParsedMemory(proposal.subjectName, proposal.predicate, proposal.value,
                quote = proposal.quote, start = proposal.start, review = !proposal.autoSave)
            contextEvidence = proposal.contextEvidence
            if (contextEvidence.any { MemoryGraphRules.sourceBlocked(graph, u.conversationId, it.messageId) }) return@forEach
            val selected = if (proposal.subjectId == "self") selfEntity()
                else graph.entities.firstOrNull { it.id == proposal.subjectId && it.scope == u.scope }
            val target = selected ?: entity(proposal.subjectName.ifBlank { "Unresolved person" })
            val named = matches(proposal.subjectName)
            val existing = if (selected == null || named.size > 1) named else emptyList()
            val choices = existing.map { it.id }
            val proposedFact = fact(target.id, proposal.predicate, proposal.value)
            val kind = when { choices.isNotEmpty() -> CandidateKind.IDENTITY
                isSingleValue(proposal.predicate) && MemoryReconciliation.current(graph, proposedFact)
                    .any { !sameValue(MemoryReconciliation.canonical(it), proposedFact) } -> CandidateKind.CONFLICT
                (listOf(proposal.quote, proposal.value.text) + contextEvidence.map { it.quote }).any(MemoryPatterns::isSensitive) -> CandidateKind.SENSITIVE
                else -> CandidateKind.ASSERTION }
            if (kind == CandidateKind.ASSERTION && proposal.autoSave) write(p, listOf(proposedFact), listOf(target))
            else queue(p, listOf(proposedFact), listOf(target), kind,
                if (choices.isNotEmpty()) "Which ${proposal.subjectName} should this describe?" else "Is this interpretation right, and should I remember it?", choices)
        }
        return result()
    }
}

internal fun isSingleValue(predicate: String) = predicate in setOf("name", "first_name", "last_name", "birthday", "home_city", "occupation", "favorite_color")
internal fun sameValue(a: StructuredFact, b: StructuredFact): Boolean = a.objectEntityId == b.objectEntityId &&
    a.value?.copy(text = normalized(a.value.text)) == b.value?.copy(text = normalized(b.value.text))

internal fun aliasesFor(facts: List<StructuredFact>, graph: MemoryGraph): List<MemoryAlias> = facts.flatMap { fact ->
    val entity = graph.entities.firstOrNull { it.id == fact.subjectId }
    val name = when {
        fact.predicate in setOf("name", "alias") -> fact.value?.text
        entity != null && !entity.id.startsWith("self:") && !entity.displayName.startsWith("Your ") -> entity.displayName
        else -> null
    }
    if (name.isNullOrBlank()) emptyList() else listOf(MemoryAlias(entityId = fact.subjectId, name = name, scope = fact.scope, evidenceFactIds = listOf(fact.id)))
}

internal fun commitClaims(initial: MemoryGraph, proposed: List<StructuredFact>, newEntities: List<MemoryEntity>,
                          proofs: List<FactEvidence>, replace: Boolean): MemoryUpdate {
    var graph = initial.copy(entities = (initial.entities + newEntities).distinctBy { it.id })
    val saved = mutableListOf<String>()
    proposed.forEach { candidate ->
        if (MemoryGraphRules.isSuppressed(candidate, graph) || proofs.filter { it.factId == candidate.id }.any {
                MemoryGraphRules.sourceBlocked(graph, it.conversationId, it.sourceMessageId) }) return@forEach
        val old = MemoryReconciliation.current(graph, candidate)
        val duplicate = old.firstOrNull { sameValue(MemoryReconciliation.canonical(it), candidate) }
        if (duplicate != null) {
            val extra = proofs.filter { it.factId == candidate.id }.map { it.copy(factId = duplicate.id) }
                .filterNot { proof -> graph.evidence.any { it.factId == proof.factId && it.sourceMessageId == proof.sourceMessageId && it.conversationId == proof.conversationId && it.quote == proof.quote } }
            graph = graph.copy(evidence = graph.evidence + extra)
            saved += duplicate.id
        } else {
            val replaced = if (replace && isSingleValue(candidate.predicate)) old else emptyList()
            val stored = candidate.copy(revision = (replaced.maxOfOrNull { it.revision } ?: 0) + 1,
                supersedesId = replaced.maxByOrNull { it.revision }?.id,
                lineageIds = (candidate.lineageIds + replaced.flatMap { it.lineageIds + it.id }).distinct())
            graph = graph.copy(facts = graph.facts.map { if (it in replaced) it.copy(status = FactStatus.SUPERSEDED, validUntil = candidate.updatedAt) else it } + stored,
                evidence = graph.evidence + proofs.filter { it.factId == candidate.id },
                vectors = graph.vectors.filterNot { v -> replaced.any { it.id == v.factId || it.id in v.dependencyRevisions } })
            saved += stored.id
            if (stored.predicate == "name" && !stored.value?.text.isNullOrBlank()) {
                val previousName = graph.entities.firstOrNull { it.id == stored.subjectId }?.displayName
                graph = graph.copy(entities = graph.entities.map {
                    if (it.id == stored.subjectId) it.copy(displayName = stored.value!!.text) else it
                }, aliases = graph.aliases.filterNot { a -> a.entityId == stored.subjectId && normalized(a.name) == normalized(previousName.orEmpty()) &&
                    normalized(a.name) != normalized(stored.value!!.text) && a.evidenceFactIds.none { id -> graph.facts.any { it.id == id && it.predicate == "alias" && it.status == FactStatus.CURRENT } } })
            }
            val freshAliases = aliasesFor(listOf(stored), graph)
            freshAliases.forEach { alias ->
                val existing = graph.aliases.firstOrNull { it.entityId == alias.entityId && it.scope == alias.scope && normalized(it.name) == normalized(alias.name) }
                graph = graph.copy(aliases = if (existing == null) graph.aliases + alias else graph.aliases.map {
                    if (it.id == existing.id) it.copy(evidenceFactIds = (it.evidenceFactIds + stored.id).distinct()) else it
                })
            }
        }
    }
    return MemoryUpdate(graph, saved.distinct())
}

internal fun confirmCandidate(graph: MemoryGraph, candidateId: String, accept: Boolean, choice: String?): MemoryUpdate {
    val candidate = graph.candidates.firstOrNull { it.id == candidateId } ?: return MemoryUpdate(graph)
    if (candidate.status != CandidateStatus.PENDING) return MemoryUpdate(graph)
    val timestamp = System.currentTimeMillis()
    fun resolved(updated: MemoryGraph, status: CandidateStatus) = updated.copy(candidates = updated.candidates.map {
        if (it.id == candidateId) it.copy(status = status, resolvedAt = timestamp) else it
    })
    if (!accept) return MemoryUpdate(resolved(graph, CandidateStatus.DISMISSED))
    require(candidate.evidence.none { MemoryPatterns.ambiguousName(it.quote) }) { "The spelling is unclear. Type the exact name in chat before saving." }
    require(!MemoryGraphRules.sourceBlocked(graph, candidate.conversationId, candidate.sourceMessageId)) { "This memory source was forgotten." }
    if (candidate.kind == CandidateKind.IDENTITY) {
        require(!choice.isNullOrBlank() && choice in candidate.entityChoices) { "Choose the person this memory describes." }
    }
    val chosen = choice?.takeUnless { it == MemoryEngine.NEW_PERSON_CHOICE }?.let { id -> graph.entities.firstOrNull { it.id == id } ?: error("That person is no longer in memory.") }
    require(chosen == null || candidate.proposedFacts.all { it.scope == chosen.scope }) { "Memory scopes do not match." }
    val replacements = if (chosen == null) emptyMap() else candidate.proposedEntities.filterNot { it.id.startsWith("self:") }.associate { it.id to chosen.id }
    val facts = candidate.proposedFacts.map { it.copy(subjectId = replacements[it.subjectId] ?: it.subjectId,
        objectEntityId = replacements[it.objectEntityId] ?: it.objectEntityId, updatedAt = timestamp) }
    require(facts.none { MemoryGraphRules.isSuppressed(it, graph) }) { "This memory was forgotten." }
    val entities = candidate.proposedEntities.filterNot { it.id in replacements }
    val update = commitClaims(graph, facts, entities, candidate.evidence.map { it.copy(confirmedByUser = true) }, replace = true)
    return update.copy(graph = resolved(update.graph, CandidateStatus.ACCEPTED))
}
