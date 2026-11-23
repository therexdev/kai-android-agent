package com.kai.agent.data

import org.json.JSONArray
import org.json.JSONObject

/** Versioned JSON payload, always encrypted by KaiStore before touching disk. */
object KaiJson {
    fun encode(state: KaiState, includeSecrets: Boolean = true): String = WorkspaceJson.write(JSONObject()
        .put("schemaVersion", state.schemaVersion).put("revision", state.revision)
        .put("settings", settings(state.settings, includeSecrets))
        .put("characters", array(state.characters.map(::character)))
        .put("conversations", array(state.conversations.filterNot { it.temporary }.map(::conversation)))
        .put("memories", array(state.memories.map(::memory)))
        .put("memoryTombstones", array(state.memoryTombstones.map(::tombstone)))
        .put("tasks", array(state.tasks.map(::task)))
        .put("memoryGraph", MemoryGraphJson.encode(state.memoryGraph)), state).toString()

    fun decode(json: String): KaiState {
        require(json.length <= 32 * 1024 * 1024) { "KAI data exceeds the supported size." }
        val root = JSONObject(json)
        require(root.getInt("schemaVersion") == 1) { "Unsupported KAI data version. Update the app before opening this data." }
        val base = KaiState(revision = root.optLong("revision"),
            settings = readSettings(root.getJSONObject("settings")),
            characters = objects(root, "characters").map(::readCharacter),
            conversations = objects(root, "conversations").map(::readConversation),
            memories = objects(root, "memories").map(::readMemory),
            memoryTombstones = objects(root, "memoryTombstones").map(::readTombstone),
            tasks = objects(root, "tasks").map(::readTask),
            memoryGraph = root.optJSONObject("memoryGraph")?.let(MemoryGraphJson::decode) ?: MemoryGraph())
        return WorkspaceJson.read(base, root)
    }

    private fun array(values: List<Any>): JSONArray = JSONArray().also { a -> values.forEach(a::put) }
    private fun strings(values: List<String>): JSONArray = array(values)
    private fun readStrings(o: JSONObject, key: String): List<String> = o.optJSONArray(key)?.let { a ->
        (0 until a.length()).map { a.getString(it) }
    } ?: emptyList()
    private fun objects(o: JSONObject, key: String): List<JSONObject> = o.getJSONArray(key).let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }
    }
    private fun profile(p: ProviderProfile, secrets: Boolean) = JSONObject()
        .put("endpoint", p.endpoint).put("model", p.model).put("apiKey", if (secrets) p.apiKey else "")
    private fun readProfile(o: JSONObject) = ProviderProfile(o.optString("endpoint"), o.optString("apiKey"), o.optString("model"))
    private fun settings(s: AppSettings, secrets: Boolean) = JSONObject()
        .put("creditAccount",s.creditAccount.json(secrets))
        .put("selectedProvider", s.selectedProvider.name).put("koinos", profile(s.koinos, secrets))
        .put("openai", profile(s.openai, secrets)).put("routing", s.routing.name)
        .put("selectedCharacterId", s.selectedCharacterId).put("connectionsBaseUrl", s.connectionsBaseUrl)
        .put("connectionsSessionToken", if (secrets) s.connectionsSessionToken else "")
        .put("accountId", if (secrets) s.accountId else "").put("accountDisplayName", if (secrets) s.accountDisplayName else "")
        .put("connectionGeneration", if (secrets) s.connectionGeneration else "")
        .put("memoryEnabled", s.memoryEnabled).put("reducedMotion", s.reducedMotion)
        .put("isDarkTheme", s.isDarkTheme).put("speechRate", s.speechRate.toDouble())
        .put("speechPacing", s.speechPacing.name).put("speechLanguageTag", s.speechLanguageTag)
        .put("localSpeechOnly", s.localSpeechOnly).put("hapticsEnabled", s.hapticsEnabled)
        .put("conversationVoiceDefaultApplied", s.conversationVoiceDefaultApplied)
        .put("playfulVoiceDefaultApplied", s.playfulVoiceDefaultApplied)
        .put("kikiVoiceDefaultApplied", s.kikiVoiceDefaultApplied)
        .put("desktopVoiceDefaultApplied", s.desktopVoiceDefaultApplied).put("visionInputEnabled", s.visionInputEnabled)
        .put("composioApiKey", if (secrets) s.composioApiKey else "")
        .put("braveSearchApiKey", if (secrets) s.braveSearchApiKey else "")
        .put("composioUserId", if (secrets) s.composioUserId else "")
        .put("memoryMode", s.memoryMode.name).put("useMemory", s.useMemory)
    private fun readSettings(o: JSONObject) = AppSettings(
        creditAccount = com.kai.agent.account.CreditAccount.read(o.optJSONObject("creditAccount")),
        selectedProvider = enumValueOf(o.getString("selectedProvider")),
        koinos = readProfile(o.getJSONObject("koinos")), openai = readProfile(o.getJSONObject("openai")),
        routing = enumValueOf(o.getString("routing")), selectedCharacterId = o.optString("selectedCharacterId", "kai"),
        connectionsBaseUrl = o.optString("connectionsBaseUrl"), connectionsSessionToken = o.optString("connectionsSessionToken"),
        accountId = o.optString("accountId"), accountDisplayName = o.optString("accountDisplayName"),
        connectionGeneration = o.optString("connectionGeneration"), memoryEnabled = o.optBoolean("memoryEnabled", true),
        reducedMotion = o.optBoolean("reducedMotion"), isDarkTheme = o.optBoolean("isDarkTheme", true),
        speechRate = o.optDouble("speechRate", 1.0).toFloat().takeIf { it.isFinite() }?.coerceIn(0.5f, 2f) ?: 1f,
        braveSearchApiKey = o.optString("braveSearchApiKey"),
        composioApiKey = o.optString("composioApiKey"), composioUserId = o.optString("composioUserId"),
        memoryMode = o.optString("memoryMode").takeIf { it.isNotBlank() }?.let { enumValueOf<MemoryMode>(it) }
            ?: if (o.optBoolean("memoryEnabled", true)) MemoryMode.AUTOMATIC else MemoryMode.OFF,
        useMemory = o.optBoolean("useMemory", o.optBoolean("memoryEnabled", true)),
        speechPacing = SpeechPacing.entries.firstOrNull { it.name == o.optString("speechPacing") } ?: SpeechPacing.NATURAL,
        speechLanguageTag = normalizedSpeechLanguageTag(o.optString("speechLanguageTag")) ?: "",
        localSpeechOnly = o.optBoolean("localSpeechOnly", false),
        hapticsEnabled = o.optBoolean("hapticsEnabled", false),
        desktopVoiceDefaultApplied = o.optBoolean("desktopVoiceDefaultApplied", false),
        conversationVoiceDefaultApplied = o.optBoolean("conversationVoiceDefaultApplied", false),
        playfulVoiceDefaultApplied = o.optBoolean("playfulVoiceDefaultApplied", false),
        kikiVoiceDefaultApplied = o.optBoolean("kikiVoiceDefaultApplied", false),
        visionInputEnabled = o.optBoolean("visionInputEnabled", false))
    private fun character(c: CharacterProfile) = JSONObject().put("id", c.id).put("name", c.name)
        .put("description", c.description).put("systemPrompt", c.systemPrompt).put("voiceId", c.voiceId)
        .put("accentColor", c.accentColor).put("memoryScopes", strings(c.memoryScopes)).put("builtIn", c.builtIn).put("deviceVoiceBaseId", c.deviceVoiceBaseId)
    private fun readCharacter(o: JSONObject) = CharacterProfile(o.getString("id"), o.getString("name"),
        o.optString("description"), o.optString("systemPrompt"), o.optString("voiceId"),
        o.optLong("accentColor", 0xFF67DAC1), readStrings(o, "memoryScopes"), o.optBoolean("builtIn"), PlayfulVoiceDefaults.baseVoice(o.optString("deviceVoiceBaseId")))
    private fun message(m: ChatMessage) = JSONObject().put("id", m.id).put("role", m.role).put("text", m.text)
        .put("createdAt", m.createdAt).put("interrupted", m.interrupted).put("provider", m.provider).put("model", m.model).put("spokenCharacters", m.spokenCharacters ?: JSONObject.NULL).put("documentIds", strings(m.documentIds)).put("includeImages", m.includeImages)
    private fun readMessage(o: JSONObject) = ChatMessage(o.getString("id"), o.getString("role"), o.getString("text"),
        o.getLong("createdAt"), o.optBoolean("interrupted"), o.optString("provider"), o.optString("model"),
        if (o.isNull("spokenCharacters")) null else o.getInt("spokenCharacters"), readStrings(o,"documentIds"), o.optBoolean("includeImages"))
    internal fun conversation(c: Conversation) = JSONObject().put("id", c.id).put("title", c.title)
        .put("characterId", c.characterId).put("createdAt", c.createdAt).put("updatedAt", c.updatedAt)
        .put("messages", array(c.messages.map(::message))).put("temporary", c.temporary).put("projectId",c.projectId).put("contextAfter",c.contextAfter)
    private fun readConversation(o: JSONObject) = Conversation(o.getString("id"), o.getString("title"),
        o.getString("characterId"), o.getLong("createdAt"), o.getLong("updatedAt"),
        objects(o, "messages").map(::readMessage), o.optBoolean("temporary"), o.optString("projectId"),o.optLong("contextAfter"))
    private fun source(s: MemorySource) = JSONObject().put("kind", s.kind).put("ref", s.ref)
        .put("conversationId", s.conversationId).put("snippet", s.snippet)
    private fun readSource(o: JSONObject) = MemorySource(o.optString("kind", "user"), o.optString("ref"),
        o.optString("conversationId"), o.optString("snippet"))
    private fun memory(m: MemoryFact) = JSONObject().put("id", m.id).put("text", m.text).put("scope", m.scope)
        .put("source", source(m.source)).put("origin", m.origin.name).put("revision", m.revision)
        .put("createdAt", m.createdAt).put("updatedAt", m.updatedAt).put("pinned", m.pinned)
        .put("confidence", m.confidence).put("lineageIds", strings(m.lineageIds))
    private fun readMemory(o: JSONObject) = MemoryFact(o.getString("id"), o.getString("text"), o.getString("scope"),
        readSource(o.getJSONObject("source")), enumValueOf(o.getString("origin")), o.getLong("revision"),
        o.getLong("createdAt"), o.getLong("updatedAt"), o.optBoolean("pinned"),
        o.optDouble("confidence", 1.0).coerceIn(0.0, 1.0), readStrings(o, "lineageIds"))
    private fun tombstone(t: MemoryTombstone) = JSONObject().put("factId", t.factId)
        .put("normalizedTextHash", t.normalizedTextHash).put("sourceHash", t.sourceHash)
        .put("deletedAt", t.deletedAt).put("revision", t.revision).put("lineageIds", strings(t.lineageIds))
        .put("reason", t.reason)
    private fun readTombstone(o: JSONObject) = MemoryTombstone(o.getString("factId"), o.getString("normalizedTextHash"),
        o.optString("sourceHash"), o.getLong("deletedAt"), o.getLong("revision"), readStrings(o, "lineageIds"), o.optString("reason", "forgotten"))
    private fun intent(i: ActionIntent) = JSONObject().put("id", i.id).put("toolName", i.toolName)
        .put("accountId", i.accountId).put("argumentsJson", i.argumentsJson).put("connectionGeneration", i.connectionGeneration)
        .put("idempotencyKey", i.idempotencyKey).put("createdAt", i.createdAt)
        .put("ownerId", i.ownerId).put("toolVersion", i.toolVersion).put("toolSchemaJson", i.toolSchemaJson)
    private fun readIntent(o: JSONObject) = ActionIntent(o.getString("id"), o.getString("toolName"), o.getString("accountId"),
        o.getString("argumentsJson"), o.getString("connectionGeneration"), o.getString("idempotencyKey"), o.getLong("createdAt"),
        o.optString("ownerId"), o.optString("toolVersion"), o.optString("toolSchemaJson", "{}"))
    private fun receipt(r: ActionReceipt) = JSONObject().put("operationId", r.operationId).put("providerId", r.providerId)
        .put("status", r.status).put("summary", r.summary).put("url", r.url).put("observedAt", r.observedAt)
    private fun readReceipt(o: JSONObject) = ActionReceipt(o.getString("operationId"), o.optString("providerId"),
        o.getString("status"), o.optString("summary"), o.optString("url"), o.getLong("observedAt"))
    private fun event(e: TaskEvent) = JSONObject().put("id", e.id).put("status", e.status.name)
        .put("detail", e.detail).put("createdAt", e.createdAt)
    private fun readEvent(o: JSONObject) = TaskEvent(o.getString("id"), enumValueOf(o.getString("status")),
        o.optString("detail"), o.getLong("createdAt"))
    internal fun task(t: AgentTask) = JSONObject().put("id", t.id).put("title", t.title).put("instructions", t.instructions)
        .put("status", t.status.name).put("createdAt", t.createdAt).put("updatedAt", t.updatedAt)
        .put("intent", t.intent?.let(::intent) ?: JSONObject.NULL).put("approvedFingerprint", t.approvedFingerprint)
        .put("approvedAt", t.approvedAt ?: JSONObject.NULL).put("submittedAt", t.submittedAt ?: JSONObject.NULL)
        .put("receipts", array(t.receipts.map(::receipt))).put("events", array(t.events.map(::event))).put("lastError", t.lastError).put("conversationId", t.conversationId).put("runId",t.runId).put("resultContext",t.resultContext)
    private fun readTask(o: JSONObject) = AgentTask(o.getString("id"), o.getString("title"), o.optString("instructions"),
        enumValueOf(o.getString("status")), o.getLong("createdAt"), o.getLong("updatedAt"),
        o.optJSONObject("intent")?.let(::readIntent), o.optString("approvedFingerprint"),
        if (o.isNull("approvedAt")) null else o.getLong("approvedAt"),
        if (o.isNull("submittedAt")) null else o.getLong("submittedAt"),
        objects(o, "receipts").map(::readReceipt), objects(o, "events").map(::readEvent), o.optString("lastError"), o.optString("conversationId"), o.optString("runId"), o.optString("resultContext"))
}
