package com.kai.agent.data

import com.kai.agent.voice.LocalVoiceCatalog
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class KikiVoiceDefaultsTest {
    @Test fun freshCharactersAndOnlyBundledCatalogEntryUseKiki() {
        assertTrue(KaiState().characters.all { it.voiceId == KikiVoiceDefaults.VOICE_ID })
        assertEquals(KikiVoiceDefaults.VOICE_ID, CharacterProfile("custom", "Custom", "", "").voiceId)
        assertEquals(listOf(KikiVoiceDefaults.VOICE_ID), LocalVoiceCatalog.VOICES.map { it.id })
        assertEquals(7, LocalVoiceCatalog.find(KikiVoiceDefaults.VOICE_ID)!!.speaker)
        assertEquals(KaiState(), KikiVoiceDefaults.migrate(KaiState()))
    }
    @Test fun removedVoicesMigrateForEveryCharacterWithoutLosingPrivateState() {
        val retired = listOf("", "system", DesktopVoiceDefaults.VOICE_ID, ConversationVoiceDefaults.VOICE_ID,
            PlayfulVoiceDefaults.CUTE, PlayfulVoiceDefaults.CARTOON, PlayfulVoiceDefaults.SQUEAKY,
            "kai:kitten:rosie", "kai:kitten:bella", "kai:kitten:luna", "kai:kokoro:heart", "kai:kokoro:bella", "kai:kokoro:sky")
        for (id in retired) {
            val original = KaiState(settings = AppSettings(composioUserId = "exact:owner", composioApiKey = "fixture-key",
                selectedCharacterId = "spark", speechRate = 1.2f, speechLanguageTag = "en-GB", memoryMode = MemoryMode.ASK_FIRST),
                characters = defaultCharacters().map { it.copy(voiceId = id, deviceVoiceBaseId = "saved-base") },
                conversations = listOf(Conversation(title = "Preserve story", messages = listOf(ChatMessage(role = "user", text = "Tell me a story.")))),
                memories = listOf(MemoryFact(text = "Preserved memory")))
            val oldJson = JSONObject(KaiJson.encode(original)).apply { getJSONObject("settings").remove("kikiVoiceDefaultApplied") }
            val migrated = KikiVoiceDefaults.migrate(KaiJson.decode(oldJson.toString()))
            assertEquals(original.settings, migrated.settings)
            assertEquals(original.conversations, migrated.conversations); assertEquals(original.memories, migrated.memories)
            assertEquals(original.characters.map { it.copy(voiceId = KikiVoiceDefaults.VOICE_ID) }, migrated.characters)
            assertEquals(migrated, KikiVoiceDefaults.migrate(KaiJson.decode(KaiJson.encode(migrated))))
        }
    }
    @Test fun explicitDeviceVoicesAndLaterSystemChoiceStayChosen() {
        val old = KaiState(settings = AppSettings(kikiVoiceDefaultApplied = false),
            characters = defaultCharacters().map { it.copy(voiceId = "explicit-device-voice") })
        val migrated = KikiVoiceDefaults.migrate(old)
        assertEquals(old.characters, migrated.characters)
        val system = migrated.copy(characters = migrated.characters.map { it.copy(voiceId = "system") })
        assertEquals(system, KikiVoiceDefaults.migrate(KaiJson.decode(KaiJson.encode(system))))
    }
    @Test fun importsCannotReintroduceRemovedVoiceChoicesEvenAfterMigration() {
        for (id in listOf("kai:kokoro:future", "kai:kitten:rosie", "kai:device:cute", "kai:azelma:cute")) {
            val imported = CharacterProfile("custom", "Custom", "", "", voiceId = id)
            assertEquals(imported.copy(voiceId = KikiVoiceDefaults.VOICE_ID), KikiVoiceDefaults.restore(imported))
            assertNull(LocalVoiceCatalog.find(id))
        }
        val explicit = CharacterProfile("custom", "Custom", "", "", voiceId = "system")
        assertEquals(explicit, KikiVoiceDefaults.restore(explicit))
    }
}
