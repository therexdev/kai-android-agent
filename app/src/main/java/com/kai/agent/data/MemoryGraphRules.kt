package com.kai.agent.data

import com.kai.agent.memory.MemoryRetriever
import org.json.JSONArray
import java.time.LocalDate

/** Deterministic storage boundary. The inference engine cannot bypass these provenance checks. */
object MemoryGraphRules {
    fun sourceHash(conversationId: String, messageId: String): String = MemoryRules.sourceHash(MemorySource(ref=messageId,conversationId=conversationId))
    fun claimHash(f: StructuredFact): String = MemoryRules.digest(JSONArray(listOf(f.subjectId,f.predicate,f.objectEntityId ?: "",f.scope,
        f.value?.type ?: "",f.value?.text ?: "",f.value?.month?.toString() ?: "",f.value?.day?.toString() ?: "",f.value?.year?.toString() ?: "",f.value?.precision ?: "")).toString())
    fun sourceBlocked(graph: MemoryGraph, conversationId: String, messageId: String): Boolean {
        val hash=sourceHash(conversationId,messageId)
        return hash.isNotBlank() && graph.suppressions.any { it.sourceHash == hash }
    }
    fun isSuppressed(f: StructuredFact, graph: MemoryGraph): Boolean = graph.suppressions.any {
        // A manual correction keeps its stable note ID. Only its replaced revision is blocked;
        // permanent forget records always dominate, including a forged higher-revision import.
        if (it.reason == "corrected" && it.factId == f.id && f.revision > it.revision) false
        else it.factId == f.id || (it.claimHash.isNotBlank() && it.claimHash == claimHash(f)) ||
            f.lineageIds.any { id -> id == it.factId || id in it.lineageIds }
    } || graph.evidence.any { it.factId == f.id && sourceBlocked(graph,it.conversationId,it.sourceMessageId) }

    /** Repairs pre-database manual edits without attributing their new text to an old quotation. */
    fun migrateLegacyCorrections(state: KaiState): KaiState {
        val corrections=state.memoryTombstones.filter { it.reason == "corrected" }
        if(corrections.isEmpty()) return state
        val memories=state.memories.map { note ->
            if(corrections.any { it.factId==note.id && note.revision>it.revision && it.sourceHash==MemoryRules.sourceHash(note.source) }) {
                note.copy(source=MemorySource(kind="manual_correction",ref="manual-correction:${note.id}:${note.revision}"))
            } else note
        }
        val suppressions=state.memoryGraph.suppressions.map { suppression ->
            // Older graph rows had no reason field. Upgrade only an exact legacy correction record;
            // a separate real forget tombstone, including one with the same fact ID, stays absolute.
            if(suppression.claimHash.isEmpty() && corrections.any { tomb -> tomb.factId==suppression.factId &&
                    tomb.sourceHash==suppression.sourceHash && tomb.deletedAt==suppression.deletedAt && tomb.revision==suppression.revision &&
                    tomb.lineageIds==suppression.lineageIds }) suppression.copy(reason="corrected") else suppression
        }
        return state.copy(memories=memories,memoryGraph=state.memoryGraph.copy(suppressions=suppressions))
    }

    /** Existing plain notes remain notes; migration never fabricates people or relationships. */
    fun projectLegacy(state: KaiState): MemoryGraph {
        var graph=state.memoryGraph
        val noteIds=state.memories.map { it.id }.toSet()
        val oldNotes=graph.facts.filter { it.predicate == "legacy_note" }
        val removed=oldNotes.filter { it.id !in noteIds }.map { it.id }
        removed.forEach { graph=forget(graph,it) }
        val entities=graph.entities.toMutableList()
        val notes=state.memories.filterNot { MemoryRules.isSuppressed(it,state.memoryTombstones) }.map { m ->
            val self="self:${m.scope}"
            if (entities.none { it.id == self }) entities += MemoryEntity(id=self,displayName="You",scope=m.scope,createdAt=m.createdAt)
            StructuredFact(id=m.id,subjectId=self,predicate="legacy_note",value=MemoryValue(text=m.text),scope=m.scope,
                revision=m.revision,createdAt=m.createdAt,updatedAt=m.updatedAt,displayText=m.text,lineageIds=m.lineageIds,
                status = oldNotes.firstOrNull { it.id == m.id && it.revision == m.revision }?.status ?: FactStatus.CURRENT)
        }
        val evidence=state.memories.map { m -> FactEvidence(id="legacy:${m.id}",factId=m.id,sourceMessageId=m.source.ref,
            conversationId=m.source.conversationId,quote=m.source.snippet,sourceKind="legacy",observedAt=m.createdAt,confirmedByUser=true) }
        val suppressed=state.memoryTombstones.map { GraphSuppression(factId=it.factId,sourceHash=it.sourceHash,deletedAt=it.deletedAt,revision=it.revision,lineageIds=it.lineageIds,reason=it.reason) }
        return graph.copy(entities=entities,facts=graph.facts.filterNot { it.predicate == "legacy_note" }+notes,
            evidence=graph.evidence.filterNot { it.id.startsWith("legacy:") }+evidence,
            suppressions=(graph.suppressions+suppressed).distinct())
    }

    fun validateAndClean(previous: MemoryGraph, proposed: MemoryGraph, state: KaiState): MemoryGraph {
        var graph=proposed.copy(suppressions=(previous.suppressions+proposed.suppressions).distinct(),deletionEpoch=maxOf(previous.deletionEpoch,proposed.deletionEpoch))
        require(graph.entities.size <= 100_000 && graph.facts.size <= 200_000 && graph.candidates.size <= 10_000) { "Memory storage limit reached." }
        fun unique(ids: List<String>) { require(ids.none { it.isBlank() } && ids.distinct().size == ids.size) { "Memory IDs must be unique." } }
        unique(graph.entities.map { it.id }); unique(graph.facts.map { it.id }); unique(graph.aliases.map { it.id }); unique(graph.evidence.map { it.id }); unique(graph.candidates.map { it.id }); unique(graph.jobs.map { it.id })
        val entities=graph.entities.associateBy { it.id }
        graph.entities.forEach { require(it.displayName.isNotBlank() && it.displayName.length <= 500 && it.scope.isNotBlank()) { "Invalid memory entity." } }
        graph.facts.forEach { f ->
            require(f.revision > 0 && f.predicate.isNotBlank() && f.predicate.length <= 100 && f.displayText.length <= 20_000) { "Invalid structured fact." }
            require((f.objectEntityId != null) xor (f.value != null)) { "A fact needs one entity or typed value." }
            require(entities[f.subjectId]?.scope == f.scope) { "Fact subject is missing or outside its scope." }
            if (f.objectEntityId != null) require(entities[f.objectEntityId]?.scope == f.scope) { "Fact object is missing or outside its scope." }
            f.value?.let(::validateValue)
        }
        val facts=graph.facts.associateBy { it.id }
        val oldEvidence=previous.evidence.associateBy { it.id }
        graph.evidence.forEach { e ->
            require(e.factId in facts) { "Evidence references a missing fact." }
            if (e.sourceKind == "legacy") {
                require(facts[e.factId]?.predicate == "legacy_note" && e.id == "legacy:${e.factId}" && state.memories.any { it.id == e.factId }) { "Legacy evidence must reference an existing legacy note." }
            } else if (oldEvidence[e.id] != e) validateEvidence(e,state)
        }
        graph.facts.filter { it.predicate != "legacy_note" }.forEach { f -> require(graph.evidence.any { it.factId == f.id }) { "A structured fact requires source evidence." } }
        graph.aliases.forEach { a ->
            require(entities[a.entityId]?.scope == a.scope && a.name.isNotBlank()) { "Alias entity is missing or outside scope." }
            require(a.evidenceFactIds.isNotEmpty() && a.evidenceFactIds.all { facts[it]?.scope == a.scope }) { "Aliases require supported facts." }
        }
        val previousCandidates=previous.candidates.associateBy { it.id }
        graph.candidates.filter { it.status == CandidateStatus.PENDING && previousCandidates[it.id] != it }.forEach { candidate ->
            require(candidate.question.isNotBlank() && candidate.question.length <= 2_000) { "Invalid memory confirmation." }
            candidate.evidence.forEach { validateEvidence(it,state) }
            require(candidate.evidence.isNotEmpty()) { "A memory suggestion needs evidence." }
        }
        val blocked=graph.facts.filter { isSuppressed(it,graph) }.map { it.id }.toSet()
        val current=graph.facts.filterNot { it.id in blocked }
        val currentIds=current.map { it.id }.toSet()
        val sourceBlockedCandidates=graph.candidates.filter { c -> sourceBlocked(graph,c.conversationId,c.sourceMessageId) ||
            c.evidence.any { sourceBlocked(graph,it.conversationId,it.sourceMessageId) } || c.proposedFacts.any { isSuppressed(it,graph) } }.map { it.id }.toSet()
        graph=graph.copy(facts=current,evidence=graph.evidence.filter { it.factId in currentIds },
            aliases=graph.aliases.mapNotNull { a -> a.evidenceFactIds.filter { it in currentIds }.takeIf { it.isNotEmpty() }?.let { a.copy(evidenceFactIds=it) } },
            candidates=graph.candidates.filterNot { it.id in sourceBlockedCandidates },
            jobs=graph.jobs.map { j -> if (sourceBlocked(graph,j.conversationId,j.sourceMessageId) && j.status in setOf(MemoryJobStatus.QUEUED,MemoryJobStatus.RUNNING)) j.copy(status=MemoryJobStatus.CANCELLED,error="Source forgotten") else j })
        graph=graph.copy(facts=graph.facts.map { fact -> fact.copy(displayText=MemoryRetriever.describeFact(graph,fact)) })
        val changedText=graph.facts.filter { f -> previous.facts.firstOrNull { it.id==f.id }?.displayText != f.displayText }.map { it.id }.toSet()
        val live=graph.facts.filter { it.status == FactStatus.CURRENT }.associateBy { it.id }
        graph=graph.copy(vectors=graph.vectors.filter { v -> live[v.factId]?.revision == v.factRevision && v.modelId.isNotBlank() && v.textHash.isNotBlank() &&
            v.factId !in changedText &&
            v.values.isNotEmpty() && v.values.size <= 4096 && v.values.all { it.isFinite() } && v.dependencyRevisions.all { (id,rev) -> live[id]?.revision == rev } }
            .distinctBy { it.factId to it.modelId })
        val referenced=graph.facts.flatMap { listOfNotNull(it.subjectId,it.objectEntityId) }.toSet()
        graph=graph.copy(entities=graph.entities.filter { it.id in referenced || it.id.startsWith("self:") })
        return graph
    }

    fun validateValue(v: MemoryValue) {
        require(v.text.length <= 20_000) { "Memory value is too large." }
        if (v.type.equals("DATE",true)) {
            require(v.month == null || v.month in 1..12) { "Invalid birthday month." }
            require(v.year == null || v.year in 1..9999) { "Invalid date year." }
            require(v.day == null || v.month != null) { "A day needs a month." }
            if (v.day != null) LocalDate.of(v.year ?: 2000,requireNotNull(v.month),v.day)
            require(v.month != null || v.year != null) { "A date needs a known component." }
            require(v.precision.isNotBlank()) { "Date precision is required." }
        }
    }

    fun validateEvidence(e: FactEvidence, state: KaiState) {
        val conversation=state.conversations.firstOrNull { it.id == e.conversationId }
        require(conversation != null && !conversation.temporary) { "Memory source is missing or temporary." }
        val message=conversation.messages.firstOrNull { it.id == e.sourceMessageId }
        require(message != null && message.role == "user" && e.sourceKind in setOf("user", "user_context")) { "Only user assertions can create personal facts." }
        require(e.quote.isNotBlank() && e.start >= 0 && e.end <= message.text.length && e.end > e.start &&
            message.text.substring(e.start,e.end) == e.quote) { "Memory evidence does not match the source message." }
        require(!sourceBlocked(state.memoryGraph,e.conversationId,e.sourceMessageId)) { "This source was forgotten." }
    }

    fun forget(graph: MemoryGraph, factId: String, at: Long = now(), includeHistory: Boolean = true): MemoryGraph {
        val linked=mutableSetOf(factId)
        if (includeHistory) {
            var changed=true
            while(changed) {
                val size=linked.size
                graph.facts.filter { it.id in linked || it.supersedesId in linked || it.lineageIds.any { id -> id in linked } }.forEach {
                    linked += it.id; it.supersedesId?.let(linked::add); linked += it.lineageIds
                }
                // Source-level suppression is deliberately conservative: retained proof excerpts
                // from the same utterance must not reveal a claim the user has just forgotten.
                val sources=graph.evidence.filter { it.factId in linked && it.sourceKind != "user_context" }.map { sourceHash(it.conversationId,it.sourceMessageId) }.filter { it.isNotBlank() }.toSet()
                linked += graph.evidence.filter { sourceHash(it.conversationId,it.sourceMessageId) in sources }.map { it.factId }
                val forgottenNames=graph.facts.filter { it.id in linked && it.predicate in setOf("name","alias") && !it.value?.text.isNullOrBlank() }
                forgottenNames.forEach { name ->
                    val text=requireNotNull(name.value).text
                    graph.facts.filter { f -> f.subjectId==name.subjectId || f.objectEntityId==name.subjectId }.forEach { f ->
                        if (graph.evidence.any { e -> e.factId==f.id && e.quote.contains(text,ignoreCase=true) }) linked += f.id
                    }
                }
                changed=linked.size!=size
            }
        }
        val removed=graph.facts.filter { it.id in linked }
        if (removed.isEmpty()) return graph
        val ids=removed.map { it.id }.toSet()
        val evidence=graph.evidence.filter { it.factId in ids }
        val tombs=removed.map { GraphSuppression(it.id,claimHash(it),deletedAt=at,revision=it.revision,lineageIds=if(includeHistory) it.lineageIds else emptyList()) } +
            evidence.filter { it.sourceMessageId.isNotBlank() && it.sourceKind != "user_context" }.map { GraphSuppression(factId=it.factId,sourceHash=sourceHash(it.conversationId,it.sourceMessageId),deletedAt=at) }
        val blockedSources=tombs.map { it.sourceHash }.filter { it.isNotBlank() }.toSet()
        val remaining=graph.facts.filterNot { it.id in ids }
        val referenced=remaining.flatMap { listOfNotNull(it.subjectId,it.objectEntityId) }.toSet()
        val aliases=graph.aliases.mapNotNull { a -> a.evidenceFactIds.filterNot { it in ids }.takeIf { it.isNotEmpty() }?.let { a.copy(evidenceFactIds=it) } }
        val names=remaining.filter { it.status==FactStatus.CURRENT && it.predicate=="name" && !it.value?.text.isNullOrBlank() }.associate { it.subjectId to requireNotNull(it.value).text }
        val entities=graph.entities.filter { it.id in referenced || it.id.startsWith("self:") }.map { entity ->
            val supported=names[entity.id] ?: aliases.firstOrNull { it.entityId==entity.id }?.name
            if (supported!=null) entity.copy(displayName=supported)
            else if (entity.id.startsWith("self:")) entity.copy(displayName="You") else entity.copy(displayName=entity.type.replaceFirstChar { it.uppercase() })
        }
        val renamed=entities.associate { it.id to it.displayName }
        val renamedFacts=remaining.map { fact ->
            var text=fact.displayText
            graph.entities.filter { it.id==fact.subjectId || it.id==fact.objectEntityId }.forEach { entity ->
                val replacement=renamed[entity.id]
                if(replacement!=null && replacement!=entity.displayName) text=text.replace(entity.displayName,replacement,ignoreCase=true)
            }
            if(text==fact.displayText) fact else fact.copy(displayText=text,revision=fact.revision+1,updatedAt=at)
        }
        return graph.copy(facts=renamedFacts,evidence=graph.evidence.filterNot { it.factId in ids },aliases=aliases,entities=entities,
            candidates=graph.candidates.filterNot { c -> c.proposedFacts.any { it.id in ids || factId in it.lineageIds } || sourceHash(c.conversationId,c.sourceMessageId) in blockedSources },
            jobs=graph.jobs.map { j -> if (sourceHash(j.conversationId,j.sourceMessageId) in blockedSources) j.copy(status=MemoryJobStatus.CANCELLED,error="",updatedAt=at) else j },
            vectors=graph.vectors.filterNot { it.factId in ids || it.dependencyRevisions.keys.any { id -> id in ids } },
            suppressions=(graph.suppressions+tombs).distinct(),deletionEpoch=graph.deletionEpoch+1)
    }
}
