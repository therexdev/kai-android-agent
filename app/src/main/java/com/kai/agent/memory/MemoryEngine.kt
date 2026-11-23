package com.kai.agent.memory

import com.kai.agent.data.*
import java.util.Locale
import java.util.UUID

/** Only finalized human turns enter this boundary; callers must never substitute assistant/tool text. */
data class MemoryUtterance(
    val messageId: String,
    val conversationId: String,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
    val scope: String = "personal",
    val temporary: Boolean = false,
    val explicit: Boolean = false,
    val role: String = "user",
    val context: List<MemoryContextTurn> = emptyList(),
    val speakerUnknown: Boolean = false,
)

data class MemoryUpdate(val graph: MemoryGraph, val savedFactIds: List<String> = emptyList(),
    val candidateIds: List<String> = emptyList(), val handled: Boolean = false)

/** Pure, conservative writer. Call inside the store's transaction using the current graph. */
object MemoryEngine {
    const val NEW_PERSON_CHOICE = "__new_person__"
    const val EXTRACTION_VERSION = "contextual-v2"
    fun ingest(graph: MemoryGraph, utterance: MemoryUtterance, mode: MemoryMode): MemoryUpdate =
        MemoryWriter(graph, utterance, mode).ingest()

    fun propose(graph: MemoryGraph, utterance: MemoryUtterance, proposals: List<ModelMemoryProposal>,
                mode: MemoryMode): MemoryUpdate = MemoryWriter(graph, utterance, mode).propose(proposals)

    fun confirm(graph: MemoryGraph, candidateId: String, accept: Boolean,
                entityChoiceId: String? = null): MemoryUpdate = confirmCandidate(graph, candidateId, accept, entityChoiceId)
}

internal fun memoryId(): String = UUID.randomUUID().toString()
internal fun normalized(value: String): String = MemoryRules.normalize(value).replace('’', '\'')
internal fun userEntityId(scope: String): String = "self:$scope"
internal fun sourceBlocked(graph: MemoryGraph, utterance: MemoryUtterance): Boolean = graph.suppressions.any {
    it.sourceHash.isNotEmpty() && it.sourceHash == MemoryGraphRules.sourceHash(utterance.conversationId, utterance.messageId)
}
