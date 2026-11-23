package com.kai.agent.data

import com.kai.agent.voice.LocalVoiceCatalog
import org.junit.Assert.*
import org.junit.Test

class LocalVoicePersistenceTest {
    @Test fun `new local voices survive save reload and all default migrations`() {
        for (voice in LocalVoiceCatalog.VOICES) {
            val original = KaiState(settings = AppSettings(composioApiKey = "fixture-secret", composioUserId = "exact:user"),
                characters = defaultCharacters().map { it.copy(voiceId = voice.id, deviceVoiceBaseId = "saved-device-voice") })
            val restored = PlayfulVoiceDefaults.migrate(ConversationVoiceDefaults.migrate(DesktopVoiceDefaults.migrate(KaiJson.decode(KaiJson.encode(original)))))
            assertEquals(original.characters, restored.characters)
            assertEquals("fixture-secret", restored.settings.composioApiKey)
            assertEquals("exact:user", restored.settings.composioUserId)
            val exported = KaiJson.decode(KaiJson.encode(original, includeSecrets = false))
            assertEquals(original.characters, exported.characters)
            assertEquals("", exported.settings.composioApiKey)
        }
    }
    @Test fun `native model identities cannot be used as Android style base voices`() {
        for (voice in LocalVoiceCatalog.VOICES) assertEquals("system", PlayfulVoiceDefaults.baseVoice(voice.id))
        assertEquals("system", PlayfulVoiceDefaults.baseVoice("kai:kitten:future"))
        assertEquals("system", PlayfulVoiceDefaults.baseVoice("kai:kokoro:future"))
        assertEquals("real-installed-device-voice", PlayfulVoiceDefaults.baseVoice("real-installed-device-voice"))
        assertFalse(LocalVoiceCatalog.isLocalVoice(PlayfulVoiceDefaults.CUTE))
        assertNull(LocalVoiceCatalog.find("kai:kitten:missing"))
    }
}
