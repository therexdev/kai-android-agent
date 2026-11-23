package com.kai.agent.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConversationVoiceDefaultsTest {
    @Test fun retiredPresetReturnsToOriginalWithoutChangingExplicitVoicesOrPrivateState() {
        for (oldMarker in listOf(false, true)) {
            val original = KaiState(settings = AppSettings(conversationVoiceDefaultApplied = oldMarker,
                composioUserId = "exact-owner", composioApiKey = "synthetic-key", speechRate = 1.2f,
                speechPacing = SpeechPacing.PATIENT),
                characters = defaultCharacters().map { it.copy(voiceId = when (it.id) {
                    "kai", "spark" -> ConversationVoiceDefaults.VOICE_ID
                    "nova" -> "chosen-android-voice"
                    else -> "system"
                }) }, conversations = listOf(Conversation(title = "Keep chat")))
            val restored = ConversationVoiceDefaults.migrate(original)
            assertEquals(DesktopVoiceDefaults.VOICE_ID, restored.characters.first { it.id == "kai" }.voiceId)
            assertEquals("system", restored.characters.first { it.id == "spark" }.voiceId)
            assertEquals("chosen-android-voice", restored.characters.first { it.id == "nova" }.voiceId)
            assertEquals("system", restored.characters.first { it.id == "sage" }.voiceId)
            assertEquals(original.settings.copy(conversationVoiceDefaultApplied = true), restored.settings)
            assertEquals(original.conversations, restored.conversations)
            assertEquals(restored, ConversationVoiceDefaults.migrate(KaiJson.decode(KaiJson.encode(restored))))
        }
    }
    @Test fun newAndOlderInstallsUseOriginalAndExplicitChoicesStayChosen() {
        assertEquals(KikiVoiceDefaults.VOICE_ID, KaiState().characters.first { it.id == "kai" }.voiceId)
        for (choice in listOf("system", "selected-android-voice", DesktopVoiceDefaults.VOICE_ID)) {
            val chosen = KaiState(characters = defaultCharacters().map { if(it.id == "kai") it.copy(voiceId=choice) else it })
            assertEquals(chosen, ConversationVoiceDefaults.migrate(chosen))
        }
        val json = JSONObject(KaiJson.encode(KaiState())).apply { getJSONObject("settings").remove("conversationVoiceDefaultApplied") }
        val decoded = ConversationVoiceDefaults.migrate(DesktopVoiceDefaults.migrate(KaiJson.decode(json.toString())))
        assertEquals(KikiVoiceDefaults.VOICE_ID, decoded.characters.first { it.id == "kai" }.voiceId)
    }
    @Test fun importedCustomCharactersCannotReintroduceRetiredPreset() {
        val custom = CharacterProfile("custom", "Custom", "", "", voiceId = ConversationVoiceDefaults.VOICE_ID)
        assertEquals(custom.copy(voiceId="system"), ConversationVoiceDefaults.restore(custom))
        val explicit = custom.copy(voiceId="chosen-voice")
        assertEquals(explicit, ConversationVoiceDefaults.restore(explicit))
    }
}
