package com.kai.agent.data

import java.util.UUID
import java.util.Locale

/** The phone is authoritative. Providers receive selected context, never this whole state. */
data class KaiState(
    val schemaVersion: Int = 1,
    val revision: Long = 0,
    val settings: AppSettings = AppSettings(),
    val characters: List<CharacterProfile> = defaultCharacters(),
    val conversations: List<Conversation> = emptyList(),
    val memories: List<MemoryFact> = emptyList(),
    val memoryTombstones: List<MemoryTombstone> = emptyList(),
    val tasks: List<AgentTask> = emptyList(),
    val memoryGraph: MemoryGraph = MemoryGraph(),
    val runs: List<AgentRun> = emptyList(),
    val routines: List<AgentRoutine> = emptyList(),
    val projects: List<KaiProject> = emptyList(),
    val documents: List<ProjectDocument> = emptyList(),
    val deletedProjectIds: List<String> = emptyList(),
    val deletedDocumentIds: List<String> = emptyList(),
)

enum class Provider { KOINOS, OPENAI }
enum class RoutingMode { LOCAL_ONLY, LOCAL_FIRST_NETWORK, NETWORK_ONLY }
enum class SpeechPacing { NATURAL, PATIENT, QUICK }

/** Empty means follow the device. Invalid language tags never replace a saved preference. */
fun normalizedSpeechLanguageTag(value: String): String? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return ""
    if (trimmed.length > 64) return null
    return runCatching {
        Locale.Builder().setLanguageTag(trimmed.replace('_', '-')).build()
            .takeIf { it.language.isNotBlank() && it.language != "und" }?.toLanguageTag()
    }.getOrNull()
}

data class ProviderProfile(
    val endpoint: String = "",
    val apiKey: String = "",
    val model: String = "",
) {
    override fun toString(): String = "ProviderProfile(endpoint=$endpoint, apiKey=<redacted>, model=$model)"
}

data class AppSettings(
    val creditAccount: com.kai.agent.account.CreditAccount = com.kai.agent.account.CreditAccount(),
    val selectedProvider: Provider = Provider.KOINOS,
    val koinos: ProviderProfile = ProviderProfile(),
    val openai: ProviderProfile = ProviderProfile(endpoint = "https://api.openai.com/v1"),
    val routing: RoutingMode = RoutingMode.LOCAL_ONLY,
    val selectedCharacterId: String = "kai",
    val connectionsBaseUrl: String = "",
    val connectionsSessionToken: String = "",
    val accountId: String = "",
    val accountDisplayName: String = "",
    val connectionGeneration: String = "",
    val memoryEnabled: Boolean = true,
    val reducedMotion: Boolean = false,
    val isDarkTheme: Boolean = true,
    val speechRate: Float = 1.0f,
    val composioApiKey: String = "",
    val braveSearchApiKey: String = "",
    val composioUserId: String = "",
    val memoryMode: MemoryMode = MemoryMode.AUTOMATIC,
    val useMemory: Boolean = true,
    val speechPacing: SpeechPacing = SpeechPacing.NATURAL,
    val speechLanguageTag: String = "",
    val localSpeechOnly: Boolean = false,
    val hapticsEnabled: Boolean = false,
    val desktopVoiceDefaultApplied: Boolean = true,
    val conversationVoiceDefaultApplied: Boolean = true,
    val playfulVoiceDefaultApplied: Boolean = true,
    val kikiVoiceDefaultApplied: Boolean = true,
    val visionInputEnabled: Boolean = false,
) {
    fun activeProfile(): ProviderProfile = when (selectedProvider) {
        Provider.KOINOS -> koinos
        Provider.OPENAI -> openai
    }
    override fun toString(): String = "AppSettings(provider=$selectedProvider, routing=$routing, character=$selectedCharacterId, credentials=<redacted>)"
}

data class CharacterProfile(
    val id: String,
    val name: String,
    val description: String,
    val systemPrompt: String,
    val voiceId: String = KikiVoiceDefaults.VOICE_ID,
    val accentColor: Long = 0xFF67DAC1,
    val memoryScopes: List<String> = listOf("personal"),
    val builtIn: Boolean = false,
    val deviceVoiceBaseId: String = "system",
)

fun defaultCharacters(): List<CharacterProfile> = listOf(
    CharacterProfile("kai", "KAI", "Warm, capable and a little playful", "Be warm, practical and concise. Help the user think aloud and finish tasks. Never claim a task succeeded without evidence.", voiceId = KikiVoiceDefaults.VOICE_ID, accentColor = 0xFF67DAC1, builtIn = true),
    CharacterProfile("nova", "Nova", "Curious, creative and energetic", "Be curious, imaginative and encouraging. Offer concrete creative ideas. Never invent personal memories or claim unverified actions succeeded.", accentColor = 0xFFC4A0FF, builtIn = true),
    CharacterProfile("sage", "Sage", "Calm, patient and clear", "Be calm and patient. Explain clearly and let the user finish their thought. Keep facts and task results accurate.", accentColor = 0xFF8FBDF3, builtIn = true),
    CharacterProfile("spark", "Spark", "Quick, witty and direct", "Be concise, witty when appropriate and useful. Keep humor subordinate to accurate memories and verified task outcomes.", accentColor = 0xFFFFB77A, builtIn = true),
)

data class Conversation(
    val id: String = newId(),
    val title: String = "New conversation",
    val characterId: String = "kai",
    val createdAt: Long = now(),
    val updatedAt: Long = createdAt,
    val messages: List<ChatMessage> = emptyList(),
    val temporary: Boolean = false,
    val projectId: String = "",
    val contextAfter: Long = 0,
)

data class ChatMessage(
    val id: String = newId(),
    val role: String,
    val text: String,
    val createdAt: Long = now(),
    val interrupted: Boolean = false,
    val provider: String = "",
    val model: String = "",
    val spokenCharacters: Int? = null,
    val documentIds: List<String> = emptyList(),
    val includeImages: Boolean = false,
)

data class MemorySource(
    val kind: String = "user",
    val ref: String = "",
    val conversationId: String = "",
    val snippet: String = "",
)

enum class MemoryOrigin { EXPLICIT_USER, INFERRED, IMPORTED }

data class MemoryFact(
    val id: String = newId(),
    val text: String,
    val scope: String = "personal",
    val source: MemorySource = MemorySource(),
    val origin: MemoryOrigin = MemoryOrigin.EXPLICIT_USER,
    val revision: Long = 1,
    val createdAt: Long = now(),
    val updatedAt: Long = createdAt,
    val pinned: Boolean = false,
    val confidence: Double = 1.0,
    val lineageIds: List<String> = emptyList(),
)

/** Contains digests only: forgotten plaintext is not kept in the tombstone. */
data class MemoryTombstone(
    val factId: String,
    val normalizedTextHash: String,
    val sourceHash: String,
    val deletedAt: Long = now(),
    val revision: Long = 1,
    val lineageIds: List<String> = emptyList(),
    val reason: String = "forgotten",
)

data class MemoryEvidence(val fact: MemoryFact, val score: Double)

enum class TaskStatus {
    DRAFT, AWAITING_APPROVAL, READY, RUNNING, ACCEPTED, SUCCEEDED, UNKNOWN, FAILED, CANCELLED
}

data class ActionIntent(
    val id: String = newId(),
    val toolName: String,
    val accountId: String,
    val argumentsJson: String = "{}",
    val connectionGeneration: String = "",
    val idempotencyKey: String = newId(),
    val createdAt: Long = now(),
    val ownerId: String = "",
    val toolVersion: String = "",
    val toolSchemaJson: String = "{}",
)

data class ActionReceipt(
    val operationId: String,
    val providerId: String = "",
    val status: String,
    val summary: String = "",
    val url: String = "",
    val observedAt: Long = now(),
)

data class TaskEvent(
    val id: String = newId(),
    val status: TaskStatus,
    val detail: String = "",
    val createdAt: Long = now(),
)

data class AgentTask(
    val id: String = newId(),
    val title: String,
    val instructions: String = "",
    val status: TaskStatus = TaskStatus.DRAFT,
    val createdAt: Long = now(),
    val updatedAt: Long = createdAt,
    val intent: ActionIntent? = null,
    val approvedFingerprint: String = "",
    val approvedAt: Long? = null,
    val submittedAt: Long? = null,
    val receipts: List<ActionReceipt> = emptyList(),
    val events: List<TaskEvent> = emptyList(),
    val lastError: String = "",
    val conversationId: String = "",
    val runId: String = "",
    val resultContext: String = "",
    val contextArchive: String = "",
)

internal fun newId(): String = UUID.randomUUID().toString()
internal fun now(): Long = System.currentTimeMillis()
