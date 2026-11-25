package com.kai.agent.ui

import com.kai.agent.data.*

enum class KaiProvider { KOINOS, OPENAI }
enum class KaiRouting { LOCAL_ONLY, LOCAL_FIRST, NETWORK_ONLY }
enum class KaiVoiceState { IDLE, LISTENING, THINKING, SPEAKING }
enum class KaiSpeechPacing { NATURAL, PATIENT, QUICK }
enum class KaiRole { USER, ASSISTANT, SYSTEM }
enum class KaiMemoryMode { AUTOMATIC, ASK_FIRST, OFF }
enum class KaiTaskStatus { QUEUED, ACCEPTED, RUNNING, WAITING, UNKNOWN, NEEDS_APPROVAL, COMPLETED, FAILED, CANCELLED }
enum class KaiConnectionStatus { AVAILABLE, CONNECTED, NEEDS_SETUP, COMING_SOON }

data class KaiProviderConfig(
    val provider: KaiProvider = KaiProvider.KOINOS,
    val endpoint: String = "",
    val apiKey: String = "",
    val modelId: String = "",
    val routing: KaiRouting = KaiRouting.LOCAL_ONLY,
    val creditAccount: com.kai.agent.account.CreditAccount? = null,
) {
    override fun toString(): String = "KaiProviderConfig(provider=$provider, endpoint=$endpoint, apiKey=[redacted], modelId=$modelId, routing=$routing)"
}

data class KaiCharacter(
    val id: String,
    val name: String,
    val subtitle: String,
    val description: String,
    val color: Long,
    val voiceId: String = "system",
)

val builtInKaiCharacters = listOf(
    KaiCharacter("kai", "KAI", "Your everyday sidekick", "Warm, curious, and ready to help. A little personality, a lot of follow-through.", 0xFFBEA5FF),
    KaiCharacter("nova", "Nova", "The creative spark", "Playful ideas, fresh perspectives, and room to think out loud.", 0xFFFFBEA5),
    KaiCharacter("sage", "Sage", "A little more clarity", "Thoughtful, grounded, and patient. Makes the complicated feel manageable.", 0xFF9EDAC4),
    KaiCharacter("spark", "Spark", "Let's make it happen", "Bright, upbeat, and practical. Turns your next idea into a next step.", 0xFFF2D582),
)

data class KaiMessage(val id: String, val role: KaiRole, val text: String)
data class KaiConversation(val id: String, val title: String, val detail: String = "", val temporary: Boolean = false)
data class KaiTask(val id: String, val title: String, val detail: String = "", val status: KaiTaskStatus = KaiTaskStatus.QUEUED, val resultUrl: String = "", val receiptId: String = "")
data class KaiMemory(
    val id: String,
    val text: String,
    val category: String = "Personal",
    val source: String = "Saved by you",
    val sourceDetail: String = "",
    val relatedPeople: List<String> = emptyList(),
    val relatedFacts: List<String> = emptyList(),
    val editable: Boolean = true,
)
data class KaiMemorySuggestion(
    val id: String,
    val text: String,
    val source: String = "From this conversation",
    val relatedPeople: List<String> = emptyList(),
    val reason: String = "",
    val updating: Boolean = false,
    val needsSpelling: Boolean = false,
    val entityChoices: List<KaiMemoryEntityChoice> = emptyList(),
)
data class KaiMemoryEntityChoice(val id: String, val label: String)
data class KaiMemoryNotice(val id: String, val text: String)
data class KaiConnection(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val status: KaiConnectionStatus = KaiConnectionStatus.NEEDS_SETUP,
    val actionLabel: String = "Set up",
    val accountLabel: String = "",
)
data class KaiApproval(
    val id: String,
    val title: String,
    val description: String,
    val actionLabel: String = "Approve",
    val account: String = "",
    val tool: String = "",
    val arguments: String = "",
    val runId: String = "",
    val reviewKey: String = "",
)
data class KaiVoice(val id: String, val label: String, val detail: String = "", val locale: String = "", val local: Boolean = true)

data class KaiUiState(
    val characters: List<KaiCharacter> = builtInKaiCharacters,
    val selectedCharacterId: String = "kai",
    val selectedVoiceId: String = "system",
    val selectedVoiceBaseId: String = "system",
    val voices: List<KaiVoice> = listOf(KaiVoice("system", "Device voice", "Uses your Android text-to-speech voice")),
    val messages: List<KaiMessage> = emptyList(),
    val streamingText: String = "",
    val partialTranscript: String = "",
    val incomingDraftText: String? = null,
    val conversations: List<KaiConversation> = emptyList(),
    val currentConversationId: String? = null,
    val voiceState: KaiVoiceState = KaiVoiceState.IDLE,
    val isBusy: Boolean = false,
    val voiceEnabled: Boolean = false,
    val voiceAvailable: Boolean = true,
    val speechAudible: Boolean = false,
    val voiceAudioRoutes: List<com.kai.agent.voice.VoiceAudioRoute> = listOf(com.kai.agent.voice.VoiceAudioRoute("auto", "Automatic")),
    val selectedAudioRoute: String = "auto",
    val voiceAudioRouteStatus: String = "Follows Android audio routing.",
    val voiceTestRunning: Boolean = false,
    val speechPacing: KaiSpeechPacing = KaiSpeechPacing.NATURAL,
    val speechRate: Float = 1.0f,
    val speechLanguageTag: String = "",
    val localSpeechOnly: Boolean = false,
    val reducedMotion: Boolean = false,
    val hapticsEnabled: Boolean = false,
    val providerConfig: KaiProviderConfig = KaiProviderConfig(),
    val account: com.kai.agent.account.AccountUi = com.kai.agent.account.AccountUi(),
    val preferredOutput: Int = 4096,
    val runCreditLimit: Int = 0,
    val fallbackModels: List<String> = emptyList(),
    val providerStatus: String = "Choose your AI to get started",
    val providerConnected: Boolean = false,
    val availableModels: List<String> = emptyList(),
    val providerTesting: Boolean = false,
    val providerSupportsNetwork: Boolean = false,
    val tasks: List<KaiTask> = emptyList(),
    val memories: List<KaiMemory> = emptyList(),
    val memoryMode: KaiMemoryMode = KaiMemoryMode.AUTOMATIC,
    val useSavedMemory: Boolean = true,
    val memoryScope: String = "personal",
    val memoryScopes: List<KaiMemoryScope> = emptyList(),
    val temporaryConversation: Boolean = false,
    val pendingMemories: List<KaiMemorySuggestion> = emptyList(),
    val memoryNotice: KaiMemoryNotice? = null,
    val connections: List<KaiConnection> = emptyList(),
    val connectionsSignedIn: Boolean = false,
    val composioUserId: String = "",
    val composioKeySaved: Boolean = false,
    val braveSearchKeySaved: Boolean = false,
    val composioDiagnostics: String? = null,
    val connectionHasMore: Boolean = false,
    val connectionStatus: String = "Connect the services you use, on your terms.",
    val connectionBusy: Boolean = false,
    val pendingApproval: KaiApproval? = null,
    val error: String? = null,
    val isDarkTheme: Boolean = true,
    val runs: List<AgentRun> = emptyList(),
    val executions: List<com.kai.agent.runtime.ExecutionRun> = emptyList(),
    val templates: List<String> = emptyList(),
    val mcpServers: List<com.kai.agent.connections.mcp.McpServer> = emptyList(),
    val routines: List<AgentRoutine> = emptyList(),
    val projects: List<KaiProject> = emptyList(),
    val documents: List<ProjectDocument> = emptyList(),
    val projectId: String = "",
    val selectedDocuments: Set<String> = emptySet(),
    val attachmentBusy: Boolean = false,
    val visionInputEnabled: Boolean = false,
    val runApprovals: List<KaiApproval> = emptyList(),
    val runActions: Map<String,List<KaiTask>> = emptyMap(),
    val requestedRunId: String? = null,

)

/** UI events are explicit; the UI never represents a requested action as completed. */
interface KaiUiActions {
    fun accountSignIn() {}
    fun accountCancelSignIn() {}
    fun accountRefresh() {}
    fun accountSignOut() {}
    fun setAgentOutput(tokens: Int) {}
    fun setRunCreditLimit(credits: Int) {}
    fun setFallbackModels(ids: List<String>) {}
    fun accountSelectModel(id: String) {}
    fun accountSelectWallet(address: String) {}
    fun accountFreeOnly(enabled: Boolean) {}
    fun accountWebsite(section: String) {}
    fun selectMemoryScope(scope: String) {}
    fun sendMessage(text: String)
    fun stopGeneration()
    fun toggleVoice()
    fun interruptVoice() {}
    fun selectAudioRoute(id: String) {}
    fun startVoiceTest() {}
    fun clearVoiceTest() {}
    fun exportVoiceTest() {}
    fun selectCharacter(characterId: String)
    fun selectVoice(voiceId: String)
    fun selectVoiceBase(voiceId: String)
    fun previewVoice(voiceId: String)
    fun setSpeechPacing(pacing: KaiSpeechPacing) {}
    fun setSpeechRate(rate: Float) {}
    fun setSpeechLanguageTag(languageTag: String) {}
    fun setLocalSpeechOnly(enabled: Boolean) {}
    fun setReducedMotion(enabled: Boolean) {}
    fun saveSearchKey(key: String) {}
    fun setHapticsEnabled(enabled: Boolean) {}
    fun saveProvider(config: KaiProviderConfig)
    fun selectProvider(provider: KaiProvider)
    fun testProvider(config: KaiProviderConfig)
    fun testResponse(config: KaiProviderConfig) {}
    fun loadModels(config: KaiProviderConfig)
    fun saveMemory(id: String?, text: String)
    fun deleteMemory(id: String)
    fun setMemoryMode(mode: KaiMemoryMode)
    fun setUseSavedMemory(enabled: Boolean)
    fun acceptMemorySuggestion(id: String, entityChoiceId: String? = null)
    fun dismissMemorySuggestion(id: String)
    fun undoMemorySave(noticeId: String)
    fun dismissMemoryNotice()
    fun openConnection(id: String)
    fun saveComposio(apiKey: String, userId: String)
    fun checkComposioAccess()
    fun refreshConnections()
    fun loadMoreConnections()
    fun disconnectConnection(id: String)
    fun searchConnections(query: String)
    fun signOutConnections()
    fun approveAction(id: String)
    fun denyAction(id: String)
    fun confirmTaskOutcome(id: String, happened: Boolean) {}
    fun cancelTask(id: String)
    fun clearError()
    fun setDarkTheme(enabled: Boolean)
    fun newConversation()
    fun newTemporaryConversation()
    fun selectConversation(id: String)
    fun deleteConversation(id: String)
    fun exportChatReport(id: String) {}
    fun exportAllChatReports() {}
    fun exportData()
    fun importData()
    fun createRun(title: String, goal: String, steps: List<String>) {}
    fun pauseRun(id: String) {}
    fun resumeRun(id: String) {}
    fun saveMcpServer(id: String, name: String, endpoint: String, token: String, protocol: String) {}
    fun toggleMcpServer(id: String, enabled: Boolean) {}
    fun removeMcpServer(id: String) {}
    fun checkMcpServer(id: String) {}
    fun saveTemplate(json: String) {}
    fun removeTemplate(id: String) {}
    fun exportTemplate(id: String) {}
    fun runTemplate(id: String, input: String) {}
    fun continueExecution(id: String, answerOnly: Boolean = false) {}
    fun stopSpeakingOnly() {}
    fun pauseExecution(id: String) {}
    fun cancelRun(id: String) {}
    fun directRun(id: String, text: String) {}
    fun skipRunStep(id: String) {}
    fun deleteRun(id: String) {}
    fun openRunChat(id: String) {}
    fun consumeRunRequest() {}
    fun saveRoutine(routine: AgentRoutine) {}
    fun toggleRoutine(id: String, enabled: Boolean) {}
    fun deleteRoutine(id: String) {}
    fun saveProject(id: String?, name: String, memory: String) {}
    fun openProject(id: String) {}
    fun deleteProject(id: String) {}
    fun pickFile(camera: Boolean = false) {}
    fun selectDocument(id: String, selected: Boolean) {}
    fun deleteDocument(id: String) {}
    fun setVisionInputEnabled(enabled: Boolean) {}
    fun consumeSharedText()
}

data class KaiMemoryScope(val id: String, val name: String, val count: Int)
