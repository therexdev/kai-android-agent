package com.kai.agent.memory

import android.content.Context
import com.kai.agent.data.*
import com.kai.agent.inference.InferenceClient
import com.kai.agent.inference.InferenceMessage
import com.kai.agent.inference.ProviderConfig
import com.kai.agent.memory.embedding.EmbeddingMath
import com.kai.agent.memory.embedding.LocalSemanticEmbedder
import kotlinx.coroutines.*
import com.kai.agent.diagnostics.TraceContext
import org.json.JSONObject

/** Phone-owned scheduling. Completed turns only; live conversation always takes priority. */
class MemoryRuntime(
    context: Context,
    private val store: KaiStore,
    private val inference: InferenceClient,
    private val scope: CoroutineScope,
    private val config: () -> ProviderConfig?,
    private val isConversationBusy: () -> Boolean,
    private val onRemembered: (List<String>) -> Unit,
    private val onProblem: (String) -> Unit = {},
) {
    private val embedder = LocalSemanticEmbedder(context.applicationContext)
    private var background: Job? = null
    private var closed = false
    private var reportedIndexProblem = false

    /** Fast deterministic extraction is committed before the conversational model reads context. */
    suspend fun capture(conversationId: String, message: ChatMessage, explicit: Boolean = false): MemoryUpdate {
        val conversation = store.state.value.conversations.firstOrNull { it.id == conversationId }
            ?: return MemoryUpdate(store.state.value.memoryGraph)
        val utterance = MemoryConversationContext.utterance(conversation, message, store.state.value.memoryGraph, explicit)
        val trace = store.beginChatTrace(conversationId, "memory_capture", message.id)
        var update = MemoryUpdate(store.state.value.memoryGraph)
        val explicitSource = if (explicit) MemorySource(ref = message.id, conversationId = conversationId) else null
        val committed = store.mutateMemoryGraph(explicitSource) { graph ->
            val settings = store.state.value.settings
            val mode = if (settings.memoryEnabled) settings.memoryMode else MemoryMode.OFF
            update = MemoryEngine.ingest(graph, utterance, mode)
            update.graph
        }
        // Store validation may suppress a stale plan; only committed records count as remembered.
        update = update.copy(graph = committed,
            savedFactIds = update.savedFactIds.filter { id -> committed.facts.any { it.id == id && it.status == FactStatus.CURRENT } },
            candidateIds = update.candidateIds.filter { id -> committed.candidates.any { it.id == id && it.status == CandidateStatus.PENDING } })
        if (update.savedFactIds.isNotEmpty()) onRemembered(update.savedFactIds)
        val job = if (!explicit && MemoryExtractor.shouldExtract(utterance))
            store.enqueueMemoryJob(conversationId, message.id, MemoryEngine.EXTRACTION_VERSION) else null
        trace?.record("memory_capture", JSONObject().put("mode", store.state.value.settings.memoryMode.name)
            .put("enabled", store.state.value.settings.memoryEnabled).put("saved", update.savedFactIds.size)
            .put("pending", update.candidateIds.size).put("contextTurns", utterance.context.size)
            .put("queued", job != null).put("reason", if (!store.state.value.settings.memoryEnabled || store.state.value.settings.memoryMode == MemoryMode.OFF) "saving_disabled" else MemoryExtractor.skipReason(utterance)))
        return update
    }

    suspend fun proposeNote(conversationId: String, quote: String, explicitRequest: Boolean = false, claimsJson: String? = null): MemoryUpdate {
        val state = store.state.value
        val conversation = state.conversations.first { it.id == conversationId }
        require(!conversation.temporary) { "Temporary chats cannot save memories. Start a regular chat first." }
        require(explicitRequest || state.settings.memoryEnabled && state.settings.memoryMode != MemoryMode.OFF) {
            "Automatic memory is off. You can still explicitly ask to remember a detail."
        }
        val source = store.messagesForContext(conversationId).filter { it.role == "user" }.takeLast(6)
            .lastOrNull { it.text.contains(quote) } ?: error("Choose the exact words you shared in this conversation, not an assistant summary.")
        var update = MemoryUpdate(state.memoryGraph)
        val committed = store.mutateMemoryGraph { graph ->
            val utterance = MemoryConversationContext.utterance(conversation, source, graph, explicitRequest)
            val claims = claimsJson?.let { MemoryExtractor.parse(it, utterance, graph) }.orEmpty()
            update = MemoryNotes.propose(graph, utterance, quote, claims)
            update.graph
        }
        return update.copy(graph = committed, candidateIds = update.candidateIds.filter { id -> committed.candidates.any { it.id == id && it.status == CandidateStatus.PENDING } })
    }

    fun pause() { background?.cancel() }

    /** The encrypted queue survives process death; it resumes on the next foreground session. */
    fun kick() {
        if (closed || background?.isActive == true || isConversationBusy()) return
        val previous = background
        background = scope.launch {
          try {
            previous?.join()
            delay(350)
            if (isConversationBusy()) return@launch
            indexMissingFacts()
            while (isActive && !isConversationBusy()) {
                val provider = config() ?: break
                val queued = store.state.value.memoryGraph.jobs.firstOrNull { it.status == MemoryJobStatus.QUEUED }
                    ?: break
                val trace = store.beginChatTrace(queued.conversationId, "memory_extraction", queued.sourceMessageId)
                var endRecorded = false
                suspend fun recordEnd(data: JSONObject) = withContext(NonCancellable) {
                    if (!endRecorded) {
                        trace?.record("memory_extraction_end", data.put("jobId", queued.id))
                        endRecorded = true
                    }
                }
                try {
                    val job = store.startMemoryJob(queued.id) ?: continue
                    val state = store.state.value
                    val conversation = state.conversations.firstOrNull { it.id == job.conversationId }
                    val source = conversation?.messages?.firstOrNull { it.id == job.sourceMessageId && it.role == "user" }
                    if (source == null || conversation.temporary) {
                        store.failMemoryJob(job.id, "The source is no longer available.")
                        recordEnd(JSONObject().put("outcome", "stale_or_disabled"))
                        continue
                    }
                    val utterance = MemoryConversationContext.utterance(conversation, source, state.memoryGraph)
                    trace?.record("memory_extraction_start", JSONObject().put("jobId", job.id).put("contextTurns", utterance.context.size)
                        .put("mode", state.settings.memoryMode.name).put("attempt", job.attempts))
                    val prompt = MemoryExtractor.prompt(utterance, state.memoryGraph, includeKnownEntities = state.settings.useMemory)
                    val completion = withTimeout(35_000) {
                        withContext(TraceContext(trace)) { inference.stream(provider.copy(maxOutputTokens = 1600), listOf(
                            InferenceMessage("system", prompt),
                            InferenceMessage("user", "Return only the memory proposal JSON for the quoted finalized user message."),
                        )) { /* Never stream extraction into the conversation or voice. */ } }
                    }
                    if (completion.finishReason in setOf("length", "content_filter")) {
                        store.failMemoryJob(job.id, "The suggestion was incomplete. Nothing was saved.")
                        recordEnd(JSONObject().put("outcome", "incomplete_response"))
                        continue
                    }
                    val extracted = MemoryExtractor.inspect(completion.text, utterance, state.memoryGraph)
                    if (extracted.outcome == "invalid_response") {
                        store.failMemoryJob(job.id, "The memory response could not be read. No facts were saved.")
                        recordEnd(JSONObject().put("outcome", extracted.outcome))
                        continue
                    }
                    var update = MemoryUpdate(state.memoryGraph)
                    val committed = store.commitMemoryJob(job.id) { current ->
                        // Revalidate model IDs, evidence, sensitivity and settings against the live graph.
                        val fresh = MemoryExtractor.parse(completion.text, utterance, current)
                        MemoryEngine.propose(current, utterance, fresh, store.state.value.settings.memoryMode).also { update = it }.graph
                    }
                    val live = store.state.value.memoryGraph
                    val saved = if (committed) update.savedFactIds.filter { id -> live.facts.any { it.id == id && it.status == FactStatus.CURRENT } } else emptyList()
                    val pending = if (committed) update.candidateIds.count { id -> live.candidates.any { it.id == id && it.status == CandidateStatus.PENDING } } else 0
                    recordEnd(JSONObject().put("outcome", if (committed) extracted.outcome else "stale_or_disabled")
                        .put("requested", extracted.requested).put("rejected", extracted.rejected)
                        .put("saved", saved.size).put("pending", pending))
                    if (saved.isNotEmpty()) onRemembered(saved)
                } catch (_: TimeoutCancellationException) {
                    store.failMemoryJob(queued.id, "The memory review timed out. No suggested fact was saved.")
                    recordEnd(JSONObject().put("outcome", "timeout"))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Never reflect model/server errors (possibly containing private content) into a log.
                    store.failMemoryJob(queued.id, "KAI could not review this turn. No suggested fact was saved.")
                    recordEnd(JSONObject().put("outcome", "processing_failed"))
                } finally {
                    if (!currentCoroutineContext().isActive) withContext(NonCancellable) {
                        store.requeueMemoryJob(queued.id)
                        if (!endRecorded) {
                            val status = store.state.value.memoryGraph.jobs.firstOrNull { it.id == queued.id }?.status
                            recordEnd(JSONObject().put("outcome", when (status) {
                                MemoryJobStatus.QUEUED -> "interrupted_requeued"
                                MemoryJobStatus.SUCCEEDED -> "interrupted_after_commit"
                                else -> "interrupted_discarded"
                            }))
                        }
                    }
                }
                indexMissingFacts()
            }
          } catch (e: CancellationException) { throw e }
          catch (_: Exception) { reportIndexProblem() }
        }
    }

    suspend fun semanticScores(query: String, graph: MemoryGraph, scopes: Set<String>): Map<String, Double> {
        if (query.isBlank() || graph.vectors.isEmpty()) return emptyMap()
        val eligible = withContext(Dispatchers.Default) {
            val current = graph.facts.filter { it.status == FactStatus.CURRENT && it.scope in scopes }.associateBy { it.id }
            graph.vectors.filter { vector ->
                val fact = current[vector.factId]
                fact != null && vector.modelId == embedder.modelId && vector.factRevision == fact.revision &&
                    validVector(vector.values) && vector.textHash == EmbeddingMath.textHash(MemoryRetriever.describeFact(graph, fact))
            }
        }
        if (eligible.isEmpty()) return emptyMap()
        return try {
            val queryVector = withTimeoutOrNull(2500) { embedder.embed(query.take(2000)) } ?: return emptyMap()
            withContext(Dispatchers.Default) {
                eligible.map { it.factId to EmbeddingMath.cosine(queryVector, it.values.toFloatArray()).toDouble() }
                    .filter { it.second.isFinite() && it.second >= 0.20 }.sortedByDescending { it.second }
                    .take(16).toMap()
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { reportIndexProblem(); emptyMap() } // Exact facts and relationship joins remain available.
    }

    private suspend fun indexMissingFacts() {
        val graph = store.state.value.memoryGraph
        val missing = withContext(Dispatchers.Default) {
            val existing = graph.vectors.associateBy { it.factId }
            graph.facts.filter { fact ->
                fact.status == FactStatus.CURRENT && existing[fact.id].let { vector ->
                    vector == null || vector.modelId != embedder.modelId || vector.factRevision != fact.revision ||
                        !validVector(vector.values) || vector.textHash != EmbeddingMath.textHash(MemoryRetriever.describeFact(graph, fact))
                }
            }
        }
        for (batch in missing.chunked(8)) {
            currentCoroutineContext().ensureActive()
            if (isConversationBusy()) return
            val built = mutableListOf<MemoryVector>()
            for (fact in batch) {
                try {
                    val text = MemoryRetriever.describeFact(graph, fact)
                    val vector = embedder.embed(text)
                    built += MemoryVector(fact.id, fact.revision, embedder.modelId,
                        EmbeddingMath.textHash(text), vector.toList())
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { reportIndexProblem(); return }
            }
            store.mutateMemoryGraph { current ->
                val live = current.facts.associateBy { it.id }
                val valid = built.filter { vector ->
                    val fact = live[vector.factId]
                    fact != null && fact.status == FactStatus.CURRENT && fact.revision == vector.factRevision &&
                        vector.textHash == EmbeddingMath.textHash(MemoryRetriever.describeFact(current, fact))
                }
                val ids = valid.map { it.factId }.toSet()
                current.copy(vectors = current.vectors.filterNot { it.factId in ids } + valid)
            }
            yield()
        }
    }

    suspend fun confirm(candidateId: String, accept: Boolean, entityChoiceId: String? = null) {
        var update: MemoryUpdate? = null
        val committed = store.mutateMemoryGraph { graph ->
            MemoryEngine.confirm(graph, candidateId, accept, entityChoiceId).also { update = it }.graph
        }
        update?.savedFactIds?.filter { id -> committed.facts.any { it.id == id && it.status == FactStatus.CURRENT } }
            ?.takeIf { it.isNotEmpty() }?.let(onRemembered)
        kick()
    }

    private fun validVector(values: List<Float>) = values.size == embedder.dimensions &&
        values.all { it.isFinite() } && values.any { it != 0f }

    private fun reportIndexProblem() {
        if (!reportedIndexProblem) {
            reportedIndexProblem = true
            onProblem("Background memory processing could not finish. Saved facts and exact memory lookup are still available.")
        }
    }

    /** Wait for native inference to finish before closing its session and the local store. */
    suspend fun shutdown() {
        closed = true
        background?.cancelAndJoin()
        embedder.close()
    }
}
