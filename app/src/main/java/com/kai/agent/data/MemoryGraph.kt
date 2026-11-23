package com.kai.agent.data

/** Canonical phone records. Derived answers are never additional independently writable facts. */
enum class MemoryMode { AUTOMATIC, ASK_FIRST, OFF }
enum class FactStatus { CURRENT, SUPERSEDED }
enum class CandidateStatus { PENDING, ACCEPTED, DISMISSED }
enum class CandidateKind { ASSERTION, CONFLICT, IDENTITY, SENSITIVE }
enum class MemoryJobStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

data class MemoryEntity(val id: String = newId(), val type: String = "person", val displayName: String,
    val scope: String = "personal", val createdAt: Long = now())

data class MemoryAlias(val id: String = newId(), val entityId: String, val name: String,
    val scope: String = "personal", val evidenceFactIds: List<String> = emptyList())

/** DATE permits partial precision; missing day/year remain null, never invented. */
data class MemoryValue(val type: String = "TEXT", val text: String = "", val month: Int? = null,
    val day: Int? = null, val year: Int? = null, val precision: String = "text")

data class StructuredFact(val id: String = newId(), val subjectId: String, val predicate: String,
    val objectEntityId: String? = null, val value: MemoryValue? = null, val scope: String = "personal",
    val status: FactStatus = FactStatus.CURRENT, val revision: Long = 1, val createdAt: Long = now(),
    val updatedAt: Long = createdAt, val validFrom: Long? = null, val validUntil: Long? = null,
    val supersedesId: String? = null, val displayText: String = "", val lineageIds: List<String> = emptyList())

data class FactEvidence(val id: String = newId(), val factId: String, val sourceMessageId: String,
    val conversationId: String, val quote: String, val start: Int = 0, val end: Int = start + quote.length,
    val sourceKind: String = "user", val observedAt: Long = now(), val confirmedByUser: Boolean = false)

data class MemoryCandidate(val id: String = newId(), val kind: CandidateKind = CandidateKind.ASSERTION,
    val status: CandidateStatus = CandidateStatus.PENDING, val question: String, val summary: String = "",
    val proposedFacts: List<StructuredFact> = emptyList(), val proposedEntities: List<MemoryEntity> = emptyList(),
    val proposedAliases: List<MemoryAlias> = emptyList(), val evidence: List<FactEvidence> = emptyList(),
    val entityChoices: List<String> = emptyList(), val sourceMessageId: String = "", val conversationId: String = "",
    val createdAt: Long = now(), val resolvedAt: Long? = null)

data class MemoryJob(val id: String = newId(), val sourceMessageId: String, val conversationId: String,
    val extractionVersion: String = "1", val status: MemoryJobStatus = MemoryJobStatus.QUEUED,
    val deletionEpoch: Long = 0, val attempts: Int = 0, val createdAt: Long = now(),
    val updatedAt: Long = createdAt, val error: String = "")

data class MemoryVector(val factId: String, val factRevision: Long, val modelId: String,
    val textHash: String, val values: List<Float>, val createdAt: Long = now(),
    val dependencyRevisions: Map<String, Long> = emptyMap())

/** Content-free tombstones; source digests block late jobs and old import resurrection. */
data class GraphSuppression(val factId: String = "", val claimHash: String = "", val sourceHash: String = "",
    val deletedAt: Long = now(), val revision: Long = 1, val lineageIds: List<String> = emptyList(),
    val reason: String = "forgotten")

data class MemoryGraph(val entities: List<MemoryEntity> = emptyList(), val aliases: List<MemoryAlias> = emptyList(),
    val facts: List<StructuredFact> = emptyList(), val evidence: List<FactEvidence> = emptyList(),
    val candidates: List<MemoryCandidate> = emptyList(), val jobs: List<MemoryJob> = emptyList(),
    val vectors: List<MemoryVector> = emptyList(), val suppressions: List<GraphSuppression> = emptyList(),
    val deletionEpoch: Long = 0)
