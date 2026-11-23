package com.kai.agent.upgrade

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kai.agent.data.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Invoke with the archived 0.2.1 APK installed. The store implementation is the old APK's. */
@RunWith(AndroidJUnit4::class)
class SeedVoiceCharacterUpdate021DeviceTest {
    @Test fun seedReal021EncryptedProfile() = runBlocking {
        UpgradeFixture.requireEmulator()
        VoiceCharacterUpdateFixture.requireVersion("0.2.1-alpha")
        VoiceCharacterUpdateFixture.backupProfile()
        val store = KaiStore(UpgradeFixture.context)
        try {
            store.load()
            // Resolve the codec from the installed 0.2.1 APK. Never call AppSettings.copy
            // across this upgrade: adding fields changes Kotlin's copy$default ABI.
            val seed = JSONObject(KaiJson.encode(store.state.value))
            seed.getJSONObject("settings").apply {
                put("selectedProvider", "OPENAI")
                put("openai", JSONObject().put("endpoint", "http://127.0.0.1:18765/v1")
                    .put("apiKey", VoiceCharacterUpdateFixture.AI_KEY).put("model", "upgrade-voice-character-fixture"))
                put("koinos", JSONObject().put("endpoint", "http://127.0.0.1:18766/v1")
                    .put("apiKey", VoiceCharacterUpdateFixture.KOINOS_KEY).put("model", "local-kai-fixture"))
                put("routing", "LOCAL_FIRST_NETWORK")
                put("composioApiKey", VoiceCharacterUpdateFixture.COMPOSIO_KEY)
                put("composioUserId", VoiceCharacterUpdateFixture.USER_ID)
                put("memoryEnabled", true); put("memoryMode", "ASK_FIRST"); put("useMemory", false)
                put("selectedCharacterId", "nova"); put("speechRate", 1.25); put("reducedMotion", true)
            }
            val characters = seed.getJSONArray("characters")
            for (index in 0 until characters.length()) {
                characters.getJSONObject(index).put("voiceId", "fixture-voice-" + characters.getJSONObject(index).getString("id"))
            }
            val oldCodecState = KaiJson.decode(seed.toString())
            store.updateSettings { oldCodecState.settings }
            oldCodecState.characters.forEach { character -> store.updateCharacter(character.id) { character } }
            store.remember(VoiceCharacterUpdateFixture.NOTE, pinned = true)
            val conversation = store.createConversation(title = "Voice and character update fixture")
            val daughterMessage = store.appendMessage(conversation.id, "user", VoiceCharacterUpdateFixture.DAUGHTER_TEXT)
            val birthdayMessage = store.appendMessage(conversation.id, "user", VoiceCharacterUpdateFixture.BIRTHDAY_TEXT)
            val self = MemoryEntity(id = "self:personal", displayName = "You")
            val daughter = MemoryEntity(id = VoiceCharacterUpdateFixture.DAUGHTER_ID, displayName = "Aurora")
            val relationship = StructuredFact(id = "voice-character-update-daughter", subjectId = self.id,
                predicate = "has_daughter", objectEntityId = daughter.id)
            val birthday = StructuredFact(id = "voice-character-update-birthday", subjectId = daughter.id,
                predicate = "birthday", value = MemoryValue(type = "DATE", month = 10, precision = "month"))
            store.mutateMemoryGraph { graph -> graph.copy(
                entities = (graph.entities + self + daughter).distinctBy { it.id },
                facts = graph.facts + listOf(relationship, birthday),
                evidence = graph.evidence + listOf(
                    FactEvidence(factId = relationship.id, sourceMessageId = daughterMessage.id,
                        conversationId = conversation.id, quote = VoiceCharacterUpdateFixture.DAUGHTER_TEXT),
                    FactEvidence(factId = birthday.id, sourceMessageId = birthdayMessage.id,
                        conversationId = conversation.id, quote = VoiceCharacterUpdateFixture.BIRTHDAY_TEXT),
                ),
            ) }
            VoiceCharacterUpdateFixture.verifyState(store.state.value)
        } finally { store.close() }
        val reopened = KaiStore(UpgradeFixture.context)
        try { reopened.load(); VoiceCharacterUpdateFixture.verifyState(reopened.state.value) }
        finally { reopened.close() }
        VoiceCharacterUpdateFixture.marker().writeText(JSONObject()
            .put("version", "0.2.1-alpha").put("uid", UpgradeFixture.context.applicationInfo.uid)
            .put("fixture_only", true).toString())
    }
}

/** Invoke after install -r of 0.3.0; do not clear package data or reinstall the instrumentation. */
@RunWith(AndroidJUnit4::class)
class VerifyVoiceCharacterUpdate030DeviceTest {
    @Test fun upgradeRetainsCredentialsMemoryVoicesAndDefaults() = runBlocking {
        UpgradeFixture.requireEmulator()
        VoiceCharacterUpdateFixture.requireVersion("0.3.0-alpha")
        assertTrue("The seed must run on the old APK first", VoiceCharacterUpdateFixture.marker().isFile)
        val seed = JSONObject(VoiceCharacterUpdateFixture.marker().readText())
        assertEquals("0.2.1-alpha", seed.getString("version"))
        assertEquals(seed.getInt("uid"), UpgradeFixture.context.applicationInfo.uid)
        val store = KaiStore(UpgradeFixture.context)
        val state: KaiState
        try {
            store.load()
            state = store.state.value
            VoiceCharacterUpdateFixture.verifyState(state)
            assertEquals(SpeechPacing.NATURAL, state.settings.speechPacing)
            assertEquals("", state.settings.speechLanguageTag)
            assertFalse(state.settings.localSpeechOnly)
            assertFalse(state.settings.hapticsEnabled)
            val exported = store.exportJson()
            assertTrue(exported.contains("Aurora"))
            assertTrue(exported.contains(VoiceCharacterUpdateFixture.NOTE))
            assertFalse(exported.contains(VoiceCharacterUpdateFixture.AI_KEY))
            assertFalse(exported.contains(VoiceCharacterUpdateFixture.KOINOS_KEY))
            assertFalse(exported.contains(VoiceCharacterUpdateFixture.COMPOSIO_KEY))
            assertFalse(exported.contains(VoiceCharacterUpdateFixture.USER_ID))
        } finally { store.close() }
        val reopened = KaiStore(UpgradeFixture.context)
        try {
            reopened.load()
            VoiceCharacterUpdateFixture.verifyState(reopened.state.value)
            assertEquals(state.memoryGraph, reopened.state.value.memoryGraph)
        } finally { reopened.close() }
        VoiceCharacterUpdateFixture.profileDirectory().walkTopDown().filter { it.isFile }.forEach { file ->
            val bytesAsText = file.readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(bytesAsText.contains(VoiceCharacterUpdateFixture.AI_KEY))
            assertFalse(bytesAsText.contains(VoiceCharacterUpdateFixture.KOINOS_KEY))
            assertFalse(bytesAsText.contains(VoiceCharacterUpdateFixture.COMPOSIO_KEY))
            assertFalse(bytesAsText.contains(VoiceCharacterUpdateFixture.NOTE))
        }
        val output = File(UpgradeFixture.context.getExternalFilesDir(null), "qa-voice-character-030").apply { mkdirs() }
        File(output, "upgrade-evidence.json").writeText(JSONObject().put("fixture_only", true)
            .put("from", "0.2.1-alpha").put("to", "0.3.0-alpha")
            .put("uid_preserved", true).put("credentials_preserved", true)
            .put("exact_user_id_preserved", true).put("linked_daughter_month_preserved", true)
            .put("pinned_note_and_memory_settings_preserved", true)
            .put("all_character_voices_preserved", true).put("existing_speed_and_motion_preserved", true)
            .put("new_speech_defaults_verified", true).put("encrypted_reopen_and_export_checked", true).toString(2))
        VoiceCharacterUpdateFixture.restoreProfile()
    }
}

private object VoiceCharacterUpdateFixture {
    const val AI_KEY = "voice-character-upgrade-local-ai-fixture-key"
    const val KOINOS_KEY = "voice-character-koinos-fixture-key"
    const val COMPOSIO_KEY = "voice-character-upgrade-local-composio-fixture-key"
    const val USER_ID = "kai:existing-upgrade-user-030"
    const val NOTE = "I prefer upgrade-safe memory."
    const val DAUGHTER_TEXT = "My daughter's name is Aurora."
    const val BIRTHDAY_TEXT = "Aurora's birthday is in October."
    const val DAUGHTER_ID = "voice-character-update-aurora-daughter"
    fun profileDirectory() = File(UpgradeFixture.context.noBackupFilesDir, "kai-state")
    private fun backupDirectory() = File(UpgradeFixture.context.noBackupFilesDir, "kai-voice-character-upgrade-030-backup")
    fun marker() = File(backupDirectory(), "seed-proof.json")
    fun requireVersion(expected: String) {
        val context = UpgradeFixture.context
        assertEquals(expected, context.packageManager.getPackageInfo(context.packageName, 0).versionName)
    }
    fun backupProfile() {
        check(!backupDirectory().exists()) { "A previous upgrade fixture needs recovery before reseeding." }
        check(backupDirectory().mkdirs())
        if (profileDirectory().exists()) profileDirectory().copyRecursively(File(backupDirectory(), "profile"))
        else File(backupDirectory(), "profile.absent").writeText("absent")
        check(!profileDirectory().exists() || profileDirectory().deleteRecursively())
    }
    fun restoreProfile() {
        check(backupDirectory().exists())
        check(!profileDirectory().exists() || profileDirectory().deleteRecursively())
        File(backupDirectory(), "profile").takeIf { it.exists() }?.copyRecursively(profileDirectory(), overwrite = true)
        check(backupDirectory().deleteRecursively())
    }
    fun verifyState(state: KaiState) {
        assertEquals(Provider.OPENAI, state.settings.selectedProvider)
        assertEquals(AI_KEY, state.settings.openai.apiKey)
        assertEquals("upgrade-voice-character-fixture", state.settings.openai.model)
        assertEquals("http://127.0.0.1:18765/v1", state.settings.openai.endpoint)
        assertEquals(KOINOS_KEY, state.settings.koinos.apiKey)
        assertEquals("http://127.0.0.1:18766/v1", state.settings.koinos.endpoint)
        assertEquals("local-kai-fixture", state.settings.koinos.model)
        assertEquals(RoutingMode.LOCAL_FIRST_NETWORK, state.settings.routing)
        assertEquals("nova", state.settings.selectedCharacterId)
        assertEquals(1.25f, state.settings.speechRate, 0.001f)
        assertTrue(state.settings.reducedMotion)
        assertEquals(setOf("kai", "nova", "sage", "spark"), state.characters.map { it.id }.toSet())
        state.characters.forEach { assertEquals("fixture-voice-" + it.id, it.voiceId) }
        assertEquals(COMPOSIO_KEY, state.settings.composioApiKey)
        assertEquals(USER_ID, state.settings.composioUserId)
        assertEquals(MemoryMode.ASK_FIRST, state.settings.memoryMode)
        assertTrue(state.settings.memoryEnabled)
        assertFalse(state.settings.useMemory)
        assertTrue(state.memories.single { it.text == NOTE }.pinned)
        val relationship = state.memoryGraph.facts.single { it.id == "voice-character-update-daughter" }
        val birthday = state.memoryGraph.facts.single { it.id == "voice-character-update-birthday" }
        assertEquals(DAUGHTER_ID, relationship.objectEntityId)
        assertEquals(relationship.objectEntityId, birthday.subjectId)
        assertEquals("has_daughter", relationship.predicate)
        assertEquals("birthday", birthday.predicate)
        assertEquals(10, birthday.value?.month)
        assertNull(birthday.value?.day)
        assertNull(birthday.value?.year)
        assertTrue(state.memoryGraph.evidence.any { it.factId == relationship.id && it.quote == DAUGHTER_TEXT })
        assertTrue(state.memoryGraph.evidence.any { it.factId == birthday.id && it.quote == BIRTHDAY_TEXT })
    }
}
