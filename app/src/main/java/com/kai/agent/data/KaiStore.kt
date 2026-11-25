package com.kai.agent.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

class KaiStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Authoritative phone state in SQLCipher; its random secret is wrapped by Android Keystore.
 * A failed read/decryption is surfaced, never treated as an empty account and never overwritten.
 * The no-backup directory avoids OS backup restoring encrypted bytes without their device key.
 */
class KaiStore(context: Context) {
    private val directory = File(context.applicationContext.noBackupFilesDir, "kai-state")
    private val disk = AtomicFile(File(directory, "state.enc")) // Legacy recovery input only.
    private val database = EncryptedKaiDatabase(context)
    private val keyAlias = context.applicationContext.packageName + ".kai.state.aes.v1"
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(KaiState())
    val state: StateFlow<KaiState> = mutableState.asStateFlow()
    private var loaded = false

    suspend fun load() = withContext(Dispatchers.IO) { mutex.withLock { loadLocked() } }

    private fun loadLocked() {
        if (loaded) return
        try {
            val saved = database.openOrMigrate { readLegacyState() }
            val corrected = KikiVoiceDefaults.migrate(MemoryGraphRules.migrateLegacyCorrections(saved))
            val settings = ComposioIdentity.migrate(corrected.settings)
            val recovered = corrected.copy(settings = settings, executions=corrected.executions.map(ExecutionRunRepository::recover), runs = corrected.runs.map(RunRules::recover), tasks = corrected.tasks.map {
                TaskRules.invalidatePendingBinding(TaskRules.recover(it), settings)
            }, memoryGraph = corrected.memoryGraph.copy(jobs = corrected.memoryGraph.jobs.map { job ->
                if (job.status == MemoryJobStatus.RUNNING) job.copy(status = MemoryJobStatus.QUEUED, updatedAt = now()) else job
            }))
            val normalized = ExecutionRunRepository.invalidate(saved,PrivacyRecovery.apply(recovered.copy(memoryGraph = MemoryGraphRules.validateAndClean(corrected.memoryGraph,
                MemoryGraphRules.projectLegacy(recovered), recovered))))
            WorkspaceRules.validate(normalized)
            val committed = if (normalized != saved) normalized.copy(revision = saved.revision + 1) else saved
            if (committed != saved) writeLocked(committed)
            mutableState.value = committed
            loaded = true
        } catch (e: Exception) {
            database.close()
            throw KaiStoreException("KAI could not unlock its saved data. Existing data has been preserved; do not reset the app to retry.", e)
        }
    }

    private fun readLegacyState(): KaiState? {
        if (!disk.baseFile.exists() && !File(disk.baseFile.path + ".bak").exists()) return null
        val bytes = disk.readFully()
        require(bytes.size in 32..(32 * 1024 * 1024 + 64)) { "Invalid encrypted data size." }
        require(bytes.copyOfRange(0, 4).contentEquals(HEADER)) { "Unsupported encrypted file version." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, bytes.copyOfRange(4, 16)))
        cipher.updateAAD(HEADER)
        val decoded = KaiJson.decode(String(cipher.doFinal(bytes.copyOfRange(16, bytes.size)), Charsets.UTF_8))
        return MemoryGraphRules.migrateLegacyCorrections(decoded.copy(settings = ComposioIdentity.migrate(decoded.settings)))
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        check(create) { "The encryption key is unavailable." }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256).build())
        }.generateKey()
    }

    private fun writeLocked(next: KaiState) {
        val current = mutableState.value
        val privacyChange = next.memoryGraph.suppressions != current.memoryGraph.suppressions ||
            next.memoryTombstones != current.memoryTombstones ||
            current.memories.any { old -> next.memories.none { it.id == old.id && it.text == old.text } } ||
            current.memoryGraph.facts.any { old -> next.memoryGraph.facts.none { it.id == old.id && it.status == old.status && it.value == old.value && it.objectEntityId == old.objectEntityId } } ||
            current.conversations.any { old -> next.conversations.none { it.id == old.id } || next.conversations.any { it.id==old.id && it.contextAfter!=old.contextAfter } } ||
            next.deletedProjectIds != current.deletedProjectIds || next.deletedDocumentIds != current.deletedDocumentIds ||
            current.projects.any { old -> next.projects.none { it.id == old.id && it.memory == old.memory } }
        if (loaded && privacyChange) {
            // The verified SQLCipher database is already authoritative. Retire the obsolete recovery
            // copy BEFORE changing it: failed deletion cannot leave RAM and committed SQL disagreeing.
            // Migration/load failures never reach this path and preserve their legacy input.
            disk.delete()
            val leftovers = listOf(disk.baseFile, File(disk.baseFile.path + ".bak"), File(disk.baseFile.path + ".new"))
            if (leftovers.any { it.exists() }) {
                throw KaiStoreException("KAI could not remove its old encrypted recovery copy. This change was not applied; existing saved data remains.")
            }
        }
        try { database.save(next, clearDiagnostics = privacyChange || diagnosticCredentialsChanged(current.settings, next.settings)) }
        catch (e: Exception) { throw KaiStoreException("KAI could not save this change. The previous saved state is preserved.", e) }
    }

    /** Call after cancelling memory/inference jobs; this never deletes state or resets its key. */
    fun close() { database.close(); loaded = false }

    internal suspend fun <T> change(block: (KaiState) -> Pair<KaiState, T>): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadLocked()
            val (proposed, result) = block(mutableState.value)
            WorkspaceRules.validate(proposed)
            val projected = MemoryGraphRules.projectLegacy(proposed)
            val next = ExecutionRunRepository.invalidate(mutableState.value, ActionContextPrivacy.apply(mutableState.value,proposed.copy(memoryGraph = MemoryGraphRules.validateAndClean(mutableState.value.memoryGraph, projected, proposed))))
            if (next != mutableState.value) {
                val committed = next.copy(revision = mutableState.value.revision + 1)
                writeLocked(committed)
                val before=mutableState.value
                mutableState.value = committed
                val redactor=com.kai.agent.diagnostics.DiagnosticRedactor(committed.settings)
                committed.tasks.filter { task -> before.tasks.firstOrNull { it.id==task.id } != task &&
                    committed.conversations.any { it.id==task.conversationId && !it.temporary } }.forEach { task ->
                    try { database.appendDiagnostic(task.conversationId,redactor.event(JSONObject().put("at",now()).put("event","task_state")
                        .put("recordedAppVersion",com.kai.agent.BuildConfig.VERSION_NAME).put("recordedAppVersionCode",com.kai.agent.BuildConfig.VERSION_CODE)
                        .put("data",JSONObject().put("taskId",task.id).put("intentId",task.intent?.id.orEmpty()).put("status",task.status.name)
                            .put("tool",task.intent?.toolName.orEmpty()).put("accountId",task.intent?.accountId.orEmpty())
                            .put("approvedAt",task.approvedAt ?: JSONObject.NULL).put("submittedAt",task.submittedAt ?: JSONObject.NULL))))
                    } catch(_: Exception) { diagnosticWriteFailures++ }
                }
            }
            result
        }
    }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings): AppSettings = change { current ->
        val next = transform(current.settings)
        require(next.connectionGeneration.length <= 256) { "Invalid connection generation." }
        require(next.composioUserId.isNotBlank() && next.composioUserId.length <= 512) { "A stable Composio user ID is required." }
        require(next.speechRate.isFinite() && next.speechRate in 0.5f..2f) { "Choose a supported speaking speed." }
        val changedConnection = next.mcpServers != current.settings.mcpServers || next.creditAccount.token != current.settings.creditAccount.token ||
            next.creditAccount.wallet != current.settings.creditAccount.wallet || next.accountId != current.settings.accountId ||
            next.connectionGeneration != current.settings.connectionGeneration ||
            next.connectionsSessionToken != current.settings.connectionsSessionToken ||
            next.composioApiKey != current.settings.composioApiKey ||
            next.composioUserId != current.settings.composioUserId
        val tasks = if (!changedConnection) current.tasks else current.tasks.map { task ->
            if (task.status in setOf(TaskStatus.READY, TaskStatus.AWAITING_APPROVAL) && task.intent != null) {
                task.copy(status = TaskStatus.DRAFT, approvedFingerprint = "", approvedAt = null,
                    updatedAt = now(), events = task.events + TaskEvent(status = TaskStatus.DRAFT,
                        detail = "Connection changed; prepare and review this action again"))
            } else task
        }
        val graph = if (next.memoryMode == current.settings.memoryMode && next.memoryEnabled == current.settings.memoryEnabled) current.memoryGraph else current.memoryGraph.copy(
            deletionEpoch = current.memoryGraph.deletionEpoch + 1,
            jobs = current.memoryGraph.jobs.map { if (it.status in setOf(MemoryJobStatus.QUEUED, MemoryJobStatus.RUNNING)) it.copy(status = MemoryJobStatus.CANCELLED, error = "", updatedAt = now()) else it })
        current.copy(settings = next, tasks = tasks, memoryGraph = graph) to next
    }

    suspend fun upsertCharacter(character: CharacterProfile): CharacterProfile = change { current ->
        require(character.id.isNotBlank() && character.name.isNotBlank()) { "A character needs an ID and name." }
        val restored = KikiVoiceDefaults.restore(character)
        current.copy(characters = current.characters.filterNot { it.id == restored.id } + restored) to restored
    }

    suspend fun updateCharacter(id: String, transform: (CharacterProfile) -> CharacterProfile): CharacterProfile = change { current ->
        val old = current.characters.firstOrNull { it.id == id } ?: error("Character not found.")
        val updated = KikiVoiceDefaults.restore(transform(old))
        require(updated.id == old.id && updated.name.isNotBlank()) { "Character identity cannot change." }
        current.copy(characters = current.characters.map { if (it.id == id) updated else it }) to updated
    }

    suspend fun createConversation(characterId: String = "kai", title: String = "New conversation", temporary: Boolean = false, projectId: String = ""): Conversation = change { current ->
        require(current.characters.any { it.id == characterId }) { "Character not found." }
        require(projectId.isBlank() || current.projects.any { it.id == projectId }) { "Project not found." }
        val conversation = Conversation(title = title.take(160), characterId = characterId, temporary = temporary, projectId = projectId)
        current.copy(conversations = current.conversations + conversation) to conversation
    }

    suspend fun appendMessage(conversationId: String, role: String, text: String, provider: String = "", model: String = "", documentIds: List<String> = emptyList(), includeImages: Boolean = false): ChatMessage = change { current ->
        require(role in setOf("user", "assistant", "system", "tool")) { "Unsupported message role." }
        require(text.length <= 1_000_000) { "Message too large." }
        val conversation = current.conversations.firstOrNull { it.id == conversationId } ?: error("Conversation not found.")
        require(documentIds.size <= 4 && documentIds.all { id -> current.documents.any { d -> d.id == id && (d.conversationId == conversationId || d.projectId.isNotBlank() && d.projectId == conversation.projectId) } }) { "An attached file is no longer available in this conversation." }
        val message = ChatMessage(role = role, text = text, provider = provider, model = model, documentIds = documentIds.distinct(), includeImages = includeImages)
        val title = if (conversation.messages.isEmpty() && role == "user" && conversation.title == "New conversation") text.take(72) else conversation.title
        val updated = conversation.copy(title = title, updatedAt = now(), messages = conversation.messages + message)
        current.copy(conversations = current.conversations.map { if (it.id == conversationId) updated else it }) to message
    }

    /** A streamed voice reply starts unheard. A process death must never imply it was played. */
    suspend fun beginVoiceReply(conversationId: String, provider: String, model: String): ChatMessage = change { current ->
        val conversation = current.conversations.firstOrNull { it.id == conversationId } ?: error("Conversation not found.")
        val message = ChatMessage(role = "assistant", text = "", provider = provider, model = model,
            interrupted = true, spokenCharacters = 0)
        val updated = conversation.copy(updatedAt = now(), messages = conversation.messages + message)
        current.copy(conversations = current.conversations.map { if (it.id == conversationId) updated else it }) to message
    }

    suspend fun updateMessage(conversationId: String, messageId: String, transform: (ChatMessage) -> ChatMessage): ChatMessage = change { current ->
        val conversation = current.conversations.firstOrNull { it.id == conversationId } ?: error("Conversation not found.")
        val old = conversation.messages.firstOrNull { it.id == messageId } ?: error("Message not found.")
        val updated = transform(old)
        require(updated.id == old.id && updated.role == old.role) { "Message identity cannot change." }
        require(updated.spokenCharacters == null || updated.spokenCharacters in 0..updated.text.length) { "Invalid playback cursor." }
        val updatedConversation = conversation.copy(updatedAt = now(), messages = conversation.messages.map { if (it.id == messageId) updated else it })
        current.copy(conversations = current.conversations.map { if (it.id == conversationId) updatedConversation else it }) to updated
    }

    suspend fun deleteConversation(id: String) { change { current ->
        val derived = current.memories.filter { it.source.conversationId == id }
        var graph = current.memoryGraph
        graph.evidence.filter { it.conversationId == id }.map { it.factId }.distinct().forEach { graph = MemoryGraphRules.forget(graph, it) }
        graph = graph.copy(candidates = graph.candidates.filterNot { it.conversationId == id },
            jobs = graph.jobs.map { if (it.conversationId == id) it.copy(status = MemoryJobStatus.CANCELLED, error = "") else it }, deletionEpoch = graph.deletionEpoch + 1)
        current.copy(conversations = current.conversations.filterNot { it.id == id },
            documents = current.documents.filterNot { it.conversationId == id && it.projectId.isBlank() },
            deletedDocumentIds = (current.deletedDocumentIds + current.documents.filter { it.conversationId == id && it.projectId.isBlank() }.map { it.id }).distinct(),
            runs = current.runs.map { if (it.conversationId == id && !it.terminal) it.copy(status = RunStatus.CANCELLED, lease = "", detail = "Conversation deleted") else it },
            memories = current.memories.filterNot { it.source.conversationId == id },
            memoryTombstones = current.memoryTombstones + derived.map { MemoryRules.tombstone(it, "source deleted") }, memoryGraph = graph) to Unit
    } }

    suspend fun remember(text: String, source: MemorySource = MemorySource(), scope: String = "personal",
                         origin: MemoryOrigin = MemoryOrigin.EXPLICIT_USER, pinned: Boolean = false): MemoryFact = change { current ->
        require(text.isNotBlank() && text.length <= 20_000 && scope.isNotBlank()) { "Enter a bounded fact and memory scope." }
        require(current.settings.memoryEnabled || origin == MemoryOrigin.EXPLICIT_USER) { "Automatic memory is turned off." }
        require(current.conversations.none { it.id == source.conversationId && it.temporary }) { "Temporary conversations do not create durable memory." }
        val duplicate = current.memories.firstOrNull { it.scope == scope && MemoryRules.textHash(it.text) == MemoryRules.textHash(text) }
        if (duplicate != null) current to duplicate
        else {
            val fact = MemoryFact(text = text.trim(), scope = scope, source = source, origin = origin,
                pinned = pinned, confidence = if (origin == MemoryOrigin.INFERRED) 0.65 else 1.0)
            require(!MemoryRules.isSuppressed(fact, current.memoryTombstones)) { "This memory or its source was forgotten; automatic re-learning is blocked." }
            current.copy(memories = current.memories + fact) to fact
        }
    }

    suspend fun remember(text: String, source: String, scope: String = "personal"): MemoryFact =
        remember(text, MemorySource(ref = source), scope)

    suspend fun correctMemory(id: String, text: String): MemoryFact = change { current ->
        require(text.isNotBlank() && text.length <= 20_000) { "Enter a bounded replacement fact." }
        val old = current.memories.firstOrNull { it.id == id } ?: error("Memory not found.")
        val updated = old.copy(text = text.trim(), revision = old.revision + 1, updatedAt = now(), origin = MemoryOrigin.EXPLICIT_USER, confidence = 1.0,
            source = MemorySource(kind = "manual_correction", ref = "manual-correction:${old.id}:${old.revision + 1}"))
        val tombstones = if (MemoryRules.textHash(old.text) == MemoryRules.textHash(updated.text)) current.memoryTombstones
            else current.memoryTombstones + MemoryRules.tombstone(old, "corrected")
        current.copy(memories = current.memories.map { if (it.id == id) updated else it }, memoryTombstones = tombstones) to updated
    }

    suspend fun forgetMemory(id: String) { change { current ->
        val old = current.memories.firstOrNull { it.id == id } ?: return@change current to Unit
        val removed = current.memories.filter { it.id == id || id in it.lineageIds }
        current.copy(memories = current.memories.filterNot { it in removed },
            memoryTombstones = current.memoryTombstones + removed.map { MemoryRules.tombstone(it) },
            memoryGraph = MemoryGraphRules.forget(current.memoryGraph, id)) to Unit
    } }

    fun searchMemory(query: String, scope: String = "personal", limit: Int = 8): List<MemoryEvidence> =
        searchMemory(query, setOf(scope), limit)

    fun searchMemory(query: String, scopes: Set<String>, limit: Int = 8): List<MemoryEvidence> =
        mutableState.value.let { MemoryRules.search(it.memories, it.memoryTombstones, query, scopes, limit) }

    /** Use this for model context: forgotten source messages are excluded and unheard speech trimmed. */
    fun messagesForContext(conversationId: String): List<ChatMessage> {
        val current = mutableState.value
        val conversation = current.conversations.firstOrNull { it.id == conversationId } ?: return emptyList()
        val blockedSources = (current.memoryTombstones.map { it.sourceHash } + current.memoryGraph.suppressions.map { it.sourceHash }).filter { it.isNotEmpty() }.toSet()
        return conversation.messages.filterNot { message ->
            MemoryRules.sourceHash(MemorySource(ref = message.id, conversationId = conversationId)) in blockedSources ||
                MemoryRules.sourceHash(MemorySource(ref = message.id)) in blockedSources
        }.map { message ->
            if (message.interrupted && message.spokenCharacters != null) message.copy(text = message.text.take(message.spokenCharacters)) else message
        }.filter { it.text.isNotBlank() }
    }

    /** Apply a plan to the CURRENT graph. Never replace a stale graph captured before inference. */
    suspend fun mutateMemoryGraph(explicitSource: MemorySource? = null, transform: (MemoryGraph) -> MemoryGraph): MemoryGraph = change { current ->
        val proposed = transform(current.memoryGraph)
        val added = proposed.facts.filter { f -> current.memoryGraph.facts.none { it.id == f.id } }
        if ((!current.settings.memoryEnabled || current.settings.memoryMode == MemoryMode.OFF) && added.isNotEmpty()) {
            val sourceConversation = explicitSource?.let { source -> current.conversations.firstOrNull { it.id == source.conversationId && !it.temporary } }
            val sourceMessage = sourceConversation?.messages?.firstOrNull { it.id == explicitSource?.ref && it.role == "user" }
            val explicit = sourceMessage != null && com.kai.agent.memory.MemoryRequests.save(sourceMessage.text)?.body != null &&
                !MemoryGraphRules.sourceBlocked(current.memoryGraph, sourceConversation.id, sourceMessage.id) && added.all { f ->
                    proposed.evidence.any { it.factId == f.id && it.sourceMessageId == sourceMessage.id && it.conversationId == sourceConversation.id }
                }
            val confirmed = proposed.candidates.filter { it.status == CandidateStatus.ACCEPTED &&
                current.memoryGraph.candidates.any { old -> old.id == it.id && old.status == CandidateStatus.PENDING } }
                .flatMap { it.proposedFacts }.map { it.id }.toSet()
            require(explicit || added.all { it.id in confirmed }) { "Automatic memory is off." }
        }
        val cleaned = MemoryGraphRules.validateAndClean(current.memoryGraph, proposed, current)
        current.copy(memoryGraph = cleaned) to cleaned
    }

    private fun memorySourceLive(state: KaiState, conversationId: String, messageId: String): Boolean =
        state.settings.memoryEnabled && state.settings.memoryMode != MemoryMode.OFF && state.conversations.any { c ->
            c.id == conversationId && !c.temporary && c.messages.any { it.id == messageId && it.role == "user" }
        } && !MemoryGraphRules.sourceBlocked(state.memoryGraph, conversationId, messageId)

    suspend fun enqueueMemoryJob(conversationId: String, messageId: String, extractionVersion: String = "1"): MemoryJob? = change { current ->
        require(extractionVersion.length in 1..100)
        if (!memorySourceLive(current, conversationId, messageId)) return@change current to null
        val id = MemoryRules.digest(listOf(conversationId, messageId, extractionVersion).joinToString("\u0000"))
        val old = current.memoryGraph.jobs.firstOrNull { it.id == id }
        if (old != null) return@change current to old.takeIf { it.status == MemoryJobStatus.QUEUED }
        val job = MemoryJob(id, messageId, conversationId, extractionVersion, deletionEpoch = current.memoryGraph.deletionEpoch)
        current.copy(memoryGraph = current.memoryGraph.copy(jobs = current.memoryGraph.jobs + job)) to job
    }

    suspend fun startMemoryJob(jobId: String): MemoryJob? = change { current ->
        val job = current.memoryGraph.jobs.firstOrNull { it.id == jobId } ?: return@change current to null
        if (job.status != MemoryJobStatus.QUEUED) return@change current to null
        val valid = memorySourceLive(current, job.conversationId, job.sourceMessageId) && job.deletionEpoch == current.memoryGraph.deletionEpoch && job.attempts < 3
        val next = job.copy(status = if (valid) MemoryJobStatus.RUNNING else MemoryJobStatus.CANCELLED,
            attempts = if (valid) job.attempts + 1 else job.attempts, updatedAt = now())
        current.copy(memoryGraph = current.memoryGraph.copy(jobs = current.memoryGraph.jobs.map { if (it.id == jobId) next else it })) to next.takeIf { valid }
    }

    suspend fun commitMemoryJob(jobId: String, transform: (MemoryGraph) -> MemoryGraph): Boolean = change { current ->
        val job = current.memoryGraph.jobs.firstOrNull { it.id == jobId } ?: return@change current to false
        val valid = job.status == MemoryJobStatus.RUNNING && memorySourceLive(current, job.conversationId, job.sourceMessageId) && job.deletionEpoch == current.memoryGraph.deletionEpoch
        if (!valid) return@change current to false
        val proposed = transform(current.memoryGraph)
        val cleaned = MemoryGraphRules.validateAndClean(current.memoryGraph, proposed, current)
        val finished = cleaned.copy(jobs = cleaned.jobs.map { if (it.id == jobId) it.copy(status = MemoryJobStatus.SUCCEEDED, updatedAt = now(), error = "") else it })
        current.copy(memoryGraph = finished) to true
    }

    suspend fun failMemoryJob(jobId: String, error: String) { change { current ->
        val jobs = current.memoryGraph.jobs.map { if (it.id == jobId && it.status == MemoryJobStatus.RUNNING)
            it.copy(status = MemoryJobStatus.FAILED, updatedAt = now(), error = error.take(500)) else it }
        current.copy(memoryGraph = current.memoryGraph.copy(jobs = jobs)) to Unit
    } }

    suspend fun requeueMemoryJob(jobId: String) { change { current ->
        val jobs = current.memoryGraph.jobs.map { job -> if (job.id != jobId || job.status != MemoryJobStatus.RUNNING) job else {
            val valid = memorySourceLive(current, job.conversationId, job.sourceMessageId) && job.deletionEpoch == current.memoryGraph.deletionEpoch
            job.copy(status = if (valid) MemoryJobStatus.QUEUED else MemoryJobStatus.CANCELLED,
                attempts = (job.attempts - 1).coerceAtLeast(0), updatedAt = now(), error = "")
        } }
        current.copy(memoryGraph = current.memoryGraph.copy(jobs = jobs)) to Unit
    } }

    suspend fun forgetStructuredFact(id: String) { change { current ->
        val legacy = current.memories.filter { it.id == id || id in it.lineageIds }
        current.copy(memoryGraph = MemoryGraphRules.forget(current.memoryGraph, id),
            memories = current.memories.filterNot { it in legacy },
            memoryTombstones = current.memoryTombstones + legacy.map { MemoryRules.tombstone(it) }) to Unit
    } }

    suspend fun undoStructuredFact(id: String) = undoStructuredFacts(listOf(id))

    /** Snapshot every predecessor before source suppression; a whole save notice is one transaction. */
    suspend fun undoStructuredFacts(ids: List<String>) { change { current ->
        require(ids.size <= 500) { "Undo batch is too large." }
        val selected = ids.toSet()
        val original = current.memoryGraph
        // An intervening correction marks an old target SUPERSEDED; Undo must not overwrite it.
        val targets = original.facts.filter { it.id in selected && it.status == FactStatus.CURRENT }
        if (targets.isEmpty()) return@change current to Unit
        val predecessors = targets.mapNotNull { fact -> fact.supersedesId?.let { oldId ->
            original.facts.firstOrNull { it.id == oldId && it.status == FactStatus.SUPERSEDED && it.id !in selected }
        } }.distinctBy { it.id }
        var graph = original
        targets.forEach { graph = MemoryGraphRules.forget(graph, it.id, includeHistory = false) }
        predecessors.forEach { predecessor ->
            if (!MemoryGraphRules.isSuppressed(predecessor, graph) && graph.facts.none {
                    it.status == FactStatus.CURRENT && it.subjectId == predecessor.subjectId && it.predicate == predecessor.predicate && it.scope == predecessor.scope
                }) {
                graph = graph.copy(facts = graph.facts.filterNot { it.id == predecessor.id } + predecessor.copy(status = FactStatus.CURRENT, validUntil = null, updatedAt = now()),
                    evidence = (graph.evidence + original.evidence.filter { it.factId == predecessor.id }).distinctBy { it.id })
                if (predecessor.predicate == "name" && !predecessor.value?.text.isNullOrBlank()) {
                    val name = requireNotNull(predecessor.value).text
                    graph = graph.copy(entities = graph.entities.map { if (it.id == predecessor.subjectId) it.copy(displayName = name) else it },
                        aliases = graph.aliases + MemoryAlias(entityId = predecessor.subjectId, name = name, scope = predecessor.scope, evidenceFactIds = listOf(predecessor.id)))
                }
            }
        }
        val legacy = current.memories.filter { old -> targets.any { it.id == old.id } }
        current.copy(memoryGraph = graph, memories = current.memories.filterNot { it in legacy },
            memoryTombstones = current.memoryTombstones + legacy.map { MemoryRules.tombstone(it) }) to Unit
    } }

    suspend fun searchStructuredFacts(query: String, scopes: Set<String>, limit: Int = 20): List<StructuredFact> = withContext(Dispatchers.IO) { mutex.withLock {
        loadLocked()
        database.search(query, scopes, limit)
    } }

    /** Temporary chats stay in RAM and cannot leave durable extraction jobs or memory evidence. */
    suspend fun setConversationTemporary(id: String, temporary: Boolean) { change { current ->
        val conversation = current.conversations.firstOrNull { it.id == id } ?: error("Conversation not found.")
        require(conversation.messages.isEmpty() || conversation.temporary == temporary) { "Choose temporary mode before starting this conversation." }
        current.copy(conversations = current.conversations.map { if (it.id == id) it.copy(temporary = temporary) else it }) to Unit
    } }

    suspend fun createTask(title: String, instructions: String = "", conversationId: String = ""): AgentTask = change { current ->
        require(title.isNotBlank()) { "A task needs a title." }
        require(current.conversations.none { it.id==conversationId && it.temporary }) { "Connected actions need a saved chat so approvals and outcomes can be retained. Start a regular conversation." }
        val task = AgentTask(title = title.take(200), instructions = instructions, conversationId = conversationId,
            events = listOf(TaskEvent(status = TaskStatus.DRAFT, detail = "Task saved on this phone")))
        current.copy(tasks = current.tasks + task) to task
    }

    suspend fun prepareTaskIntent(taskId: String, intent: ActionIntent): AgentTask = editTask(taskId) { task, settings ->
        require(task.submittedAt == null && task.status in setOf(TaskStatus.DRAFT, TaskStatus.AWAITING_APPROVAL, TaskStatus.READY, TaskStatus.FAILED)) {
            "A submitted or unknown action cannot be replaced. Reconcile its outcome first."
        }
        TaskRules.fingerprint(intent)
        TaskRules.validateBinding(intent, settings)
        task.copy(intent = intent, status = TaskStatus.AWAITING_APPROVAL, approvedFingerprint = "", approvedAt = null,
            updatedAt = now(), lastError = "", events = task.events + TaskEvent(status = TaskStatus.AWAITING_APPROVAL, detail = "Review the exact account and action"))
    }

    suspend fun approveTask(taskId: String): AgentTask = editTask(taskId) { task, settings -> TaskRules.approve(task, settings) }

    /** Persist RUNNING before sending the network request; a restart recovers it as UNKNOWN. */
    suspend fun transitionTask(taskId: String, status: TaskStatus, detail: String = ""): AgentTask =
        editTask(taskId) { task, settings -> TaskRules.transition(task, status, settings, detail) }

    suspend fun recordReceipt(taskId: String, receipt: ActionReceipt, status: TaskStatus, resultContext: String? = null): AgentTask = editTask(taskId) { task, settings ->
        task.intent?.let { intent ->
            require(receipt.operationId == intent.idempotencyKey || receipt.operationId == intent.id) { "The receipt does not belong to this action." }
        }
        require(status in setOf(TaskStatus.ACCEPTED, TaskStatus.SUCCEEDED, TaskStatus.UNKNOWN, TaskStatus.FAILED)) { "Invalid receipt outcome." }
        val conversation=state.value.conversations.firstOrNull { it.id==task.conversationId }
        val contextLive=conversation!=null && task.createdAt>conversation.contextAfter && task.createdAt>(state.value.projects.firstOrNull { it.id==conversation.projectId }?.forgottenBefore ?: 0) &&
            task.createdAt>(state.value.memoryTombstones.maxOfOrNull { it.deletedAt } ?: 0) && task.createdAt>(state.value.memoryGraph.suppressions.maxOfOrNull { it.deletedAt } ?: 0)
        val withReceipt = task.copy(resultContext=if(contextLive) resultContext?.take(24000) ?: task.resultContext else "", receipts = (task.receipts + receipt).distinctBy { Triple(it.operationId, it.status, it.providerId) })
        if (withReceipt.status == status) withReceipt.copy(updatedAt = now())
        else TaskRules.transition(withReceipt, status, settings, receipt.summary)
    }

    private suspend fun editTask(taskId: String, edit: (AgentTask, AppSettings) -> AgentTask): AgentTask = change { current ->
        val task = current.tasks.firstOrNull { it.id == taskId } ?: error("Task not found.")
        val updated = edit(task, current.settings)
        current.copy(tasks = current.tasks.map { if (it.id == taskId) updated else it }) to updated
    }

    private fun diagnosticCredentialsChanged(a: AppSettings, b: AppSettings) =
        a.mcpServers != b.mcpServers || a.creditAccount.token != b.creditAccount.token || a.creditAccount.linkSecret != b.creditAccount.linkSecret ||
        a.koinos.apiKey != b.koinos.apiKey || a.openai.apiKey != b.openai.apiKey ||
        a.composioApiKey != b.composioApiKey || a.braveSearchApiKey != b.braveSearchApiKey || a.connectionsSessionToken != b.connectionsSessionToken

    private var diagnosticWriteFailures = 0
    suspend fun beginChatTrace(cid: String, purpose: String, sourceMessageId: String? = null): com.kai.agent.diagnostics.ChatTrace? = withContext(Dispatchers.IO) { mutex.withLock {
        loadLocked()
        val conversation=mutableState.value.conversations.firstOrNull { it.id==cid && !it.temporary } ?: return@withLock null
        if (sourceMessageId != null && conversation.messages.none { it.id == sourceMessageId && it.role == "user" }) return@withLock null
        if (sourceMessageId != null && MemoryGraphRules.sourceBlocked(mutableState.value.memoryGraph, cid, sourceMessageId)) return@withLock null
        val epoch=database.diagnosticEpoch()
        val redactor=com.kai.agent.diagnostics.DiagnosticRedactor(mutableState.value.settings)
        com.kai.agent.diagnostics.ChatTrace(conversationId=cid, userMessageId=sourceMessageId ?: conversation.messages.lastOrNull { it.role=="user" }?.id.orEmpty()) { event ->
            withContext(Dispatchers.IO) { mutex.withLock {
                try {
                    if(loaded && database.diagnosticEpoch()==epoch && mutableState.value.conversations.any { it.id==cid && !it.temporary })
                        database.appendDiagnostic(cid, redactor.event(event.put("purpose",purpose)
                            .put("recordedAppVersion",com.kai.agent.BuildConfig.VERSION_NAME).put("recordedAppVersionCode",com.kai.agent.BuildConfig.VERSION_CODE)))
                } catch(_: Exception) { diagnosticWriteFailures++ } // Logging must never trigger action replay.
            } }
        }
    } }

    suspend fun exportChatReport(cid: String): String = withContext(Dispatchers.IO) { mutex.withLock {
        loadLocked()
        val current=mutableState.value
        val conversation=current.conversations.firstOrNull { it.id==cid } ?: error("This conversation was removed.")
        require(!conversation.temporary) { "Temporary chats are not recorded. Use a regular chat to collect a report." }
        com.kai.agent.diagnostics.ChatReportWriter.report(current, conversation, database.diagnosticReport(cid),
            reportApp(), diagnosticWriteFailures, now()).toString(2)
    } }

    /** Streams one redacted chat at a time, under the same snapshot/privacy lock. No plaintext staging file. */
    suspend fun writeAllChatReports(writer: java.io.Writer) = withContext(Dispatchers.IO) { mutex.withLock {
        loadLocked()
        com.kai.agent.diagnostics.ChatReportWriter.writeAll(mutableState.value, writer, database::diagnosticReport,
            reportApp(), diagnosticWriteFailures, now())
    } }

    suspend fun writeAllChatReportsArchive(output: java.io.OutputStream) = withContext(Dispatchers.IO) { mutex.withLock {
        loadLocked()
        com.kai.agent.diagnostics.ChatReportWriter.writeArchive(mutableState.value, output, database::diagnosticReport,
            reportApp(), diagnosticWriteFailures, now())
    } }

    private fun reportApp() = JSONObject().put("version", com.kai.agent.BuildConfig.VERSION_NAME)
        .put("versionCode", com.kai.agent.BuildConfig.VERSION_CODE).put("androidSdk", android.os.Build.VERSION.SDK_INT)
        .put("androidRelease", android.os.Build.VERSION.RELEASE)

    /** Portable plaintext export. Configured credentials and connection identity are excluded. */
    suspend fun exportJson(): String = withContext(Dispatchers.IO) { mutex.withLock {
        loadLocked()
        val current = mutableState.value
        val portable = current.copy(memoryGraph = current.memoryGraph.copy(jobs = emptyList(), vectors = emptyList()))
        JSONObject().put("format", "kai-phone-export").put("version", 1)
            .put("state", JSONObject(KaiJson.encode(portable, includeSecrets = false))).toString(2)
    } }

    /** Import merges local data, preserves current credentials, and never rearms imported actions. */
    suspend fun importJson(json: String): Unit = change { current ->
        require(json.length <= 32 * 1024 * 1024) { "Import is too large." }
        val root = JSONObject(json)
        require(root.getString("format") == "kai-phone-export" && root.getInt("version") == 1) { "Unsupported KAI export." }
        val imported = KaiJson.decode(root.getJSONObject("state").toString())
        val tombstones = (current.memoryTombstones + imported.memoryTombstones)
            .distinctBy { listOf(it.factId, it.revision.toString(), it.normalizedTextHash, it.sourceHash) }
        // Local deletion wins over every imported record, including a backup with an advanced clock.
        val incomingMemory = imported.memories.filter { it.scope.removePrefix("project:") !in current.deletedProjectIds }.filterNot { fact -> current.memoryTombstones.any { tomb ->
            tomb.factId == fact.id || tomb.normalizedTextHash == MemoryRules.textHash(fact.text) ||
                (tomb.sourceHash.isNotBlank() && tomb.sourceHash == MemoryRules.sourceHash(fact.source)) || tomb.factId in fact.lineageIds
        } }.map { it.copy(origin = MemoryOrigin.IMPORTED) }
        val memories = (incomingMemory + current.memories).groupBy { it.id }.values.map { records ->
            records.maxWithOrNull(compareBy<MemoryFact> { it.revision }.thenBy { it.updatedAt })!!
        }.filterNot { MemoryRules.isSuppressed(it, tombstones) }
        val deletedConversations = imported.conversations.filter { it.projectId in current.deletedProjectIds }.map { it.id }.toSet()
        val deletedRuns = imported.runs.filter { it.projectId in current.deletedProjectIds }.map { it.id }.toSet()
        val tasks = imported.tasks.filterNot { t -> current.tasks.any { it.id == t.id } ||
            t.conversationId in deletedConversations || t.runId in deletedRuns }.map { task ->
            val recoverable = task.status !in setOf(TaskStatus.SUCCEEDED, TaskStatus.FAILED, TaskStatus.CANCELLED)
            if (!recoverable) task else task.copy(status = TaskStatus.UNKNOWN, approvedAt = null, approvedFingerprint = "",
                lastError = "Imported task: verify its prior outcome. Automatic execution is disabled.",
                events = task.events + TaskEvent(status = TaskStatus.UNKNOWN, detail = "Imported without execution permission"))
        }
        val conversations = imported.conversations.filterNot { it.temporary || it.projectId in current.deletedProjectIds || current.conversations.any { c -> c.id == it.id } }
        val characters = imported.characters.filterNot { character -> current.characters.any { it.id == character.id } }
            .map(KikiVoiceDefaults::restore)
        val combinedSources = current.copy(conversations = current.conversations + conversations)
        val suppressions = (current.memoryGraph.suppressions + imported.memoryGraph.suppressions).distinct()
        val blockedGraph = current.memoryGraph.copy(suppressions = suppressions)
        val incomingFacts = imported.memoryGraph.facts.filter { f ->
            f.scope.removePrefix("project:") !in current.deletedProjectIds && f.predicate != "legacy_note" && current.memoryGraph.facts.none { it.id == f.id } && !MemoryGraphRules.isSuppressed(f, blockedGraph) &&
                imported.memoryGraph.evidence.any { e -> e.factId == f.id && e.sourceKind == "user" && runCatching { MemoryGraphRules.validateEvidence(e, combinedSources) }.isSuccess } &&
                imported.memoryGraph.evidence.filter { it.factId == f.id && it.sourceKind == "user_context" }.all { e ->
                    !MemoryGraphRules.sourceBlocked(blockedGraph, e.conversationId, e.sourceMessageId) &&
                        runCatching { MemoryGraphRules.validateEvidence(e, combinedSources) }.isSuccess
                }
        }
        val incomingIds = incomingFacts.map { it.id }.toSet()
        val incomingEvidence = imported.memoryGraph.evidence.filter { it.factId in incomingIds && it.sourceKind in setOf("user", "user_context") && runCatching { MemoryGraphRules.validateEvidence(it, combinedSources) }.isSuccess }
        val graph = current.memoryGraph.copy(
            entities = (current.memoryGraph.entities + imported.memoryGraph.entities).distinctBy { it.id },
            facts = current.memoryGraph.facts + incomingFacts,
            evidence = (current.memoryGraph.evidence + incomingEvidence).distinctBy { it.id },
            aliases = (current.memoryGraph.aliases + imported.memoryGraph.aliases.filter { a -> a.evidenceFactIds.isNotEmpty() && a.evidenceFactIds.all { it in incomingIds } }).distinctBy { it.id },
            suppressions = suppressions,
            // Never resume imported work or trust imported derived vectors/caches.
            deletionEpoch = maxOf(current.memoryGraph.deletionEpoch, imported.memoryGraph.deletionEpoch) + if (suppressions != current.memoryGraph.suppressions) 1 else 0)
        WorkspaceRules.imported(current.copy(memories = memories, memoryTombstones = tombstones, tasks = current.tasks + tasks,
            conversations = current.conversations + conversations, characters = current.characters + characters, memoryGraph = graph), imported) to Unit
    }

    private companion object { val HEADER = byteArrayOf(75, 65, 73, 1) }
}
