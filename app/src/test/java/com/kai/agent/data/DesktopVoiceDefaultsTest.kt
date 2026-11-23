package com.kai.agent.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DesktopVoiceDefaultsTest {
    @Test fun `fresh KAI uses playful voice while other characters remain unchanged`() {
        val state = KaiState()
        assertEquals(KikiVoiceDefaults.VOICE_ID, state.characters.first { it.id == "kai" }.voiceId)
        assertTrue(state.characters.all { it.voiceId == KikiVoiceDefaults.VOICE_ID })
        assertEquals(state, DesktopVoiceDefaults.migrate(state))
    }

    @Test fun `upgrade changes only KAI default and records migration alongside all private data`() {
        for (oldVoice in listOf("", "system")) {
            val original = KaiState(
                settings = AppSettings(koinos = ProviderProfile(apiKey = "synthetic-key"), composioUserId = "exact-user", selectedCharacterId = "nova"),
                characters = defaultCharacters().map { it.copy(voiceId = if (it.id == "kai") oldVoice else "saved-${it.id}") },
                conversations = listOf(Conversation(title = "Retain this conversation")),
            )
            val oldJson = JSONObject(KaiJson.encode(original)).apply { getJSONObject("settings").remove("desktopVoiceDefaultApplied") }
            val migrated = DesktopVoiceDefaults.migrate(KaiJson.decode(oldJson.toString()))
            assertEquals(original.copy(characters = original.characters.map {
                if (it.id == "kai") it.copy(voiceId = DesktopVoiceDefaults.VOICE_ID) else it
            }), migrated)
            assertEquals(migrated, KaiJson.decode(KaiJson.encode(migrated)))
        }
    }

    @Test fun `explicit custom voice survives upgrade and device default can be chosen afterward`() {
        val original = KaiState(settings = AppSettings(desktopVoiceDefaultApplied = false),
            characters = defaultCharacters().map { it.copy(voiceId = "chosen-android-voice") })
        val migrated = DesktopVoiceDefaults.migrate(original)
        assertEquals(original.characters, migrated.characters)
        val selectedSystem = migrated.copy(characters = migrated.characters.map { it.copy(voiceId = "system") })
        assertEquals(selectedSystem, DesktopVoiceDefaults.migrate(KaiJson.decode(KaiJson.encode(selectedSystem))))
    }
}
