package com.kai.agent.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SpeechSettingsPersistenceTest {
    @Test fun `existing installation gets conservative defaults without losing account or memory state`() {
        val original = savedPhoneState()
        val legacy = JSONObject(KaiJson.encode(original))
        legacy.getJSONObject("settings").apply {
            remove("speechPacing")
            remove("speechLanguageTag")
            remove("localSpeechOnly")
            remove("hapticsEnabled")
        }

        val upgraded = KaiJson.decode(legacy.toString())

        assertEquals(original, upgraded)
        assertEquals(SpeechPacing.NATURAL, upgraded.settings.speechPacing)
        assertEquals("", upgraded.settings.speechLanguageTag)
        assertFalse(upgraded.settings.localSpeechOnly)
        assertFalse(upgraded.settings.hapticsEnabled)
        assertEquals(.8f, upgraded.settings.speechRate, .0001f)
        assertTrue(upgraded.settings.reducedMotion)
    }

    @Test fun `each pacing preference round trips alongside character voices and protected data`() {
        SpeechPacing.entries.forEach { pacing ->
            val original = savedPhoneState().let { state ->
                state.copy(settings = state.settings.copy(
                    speechPacing = pacing,
                    speechRate = 1.4f,
                    speechLanguageTag = "es-MX",
                    localSpeechOnly = true,
                    hapticsEnabled = true,
                ))
            }

            assertEquals(original, KaiJson.decode(KaiJson.encode(original)))
        }
    }

    @Test fun `new preferences are portable while exports still remove every configured credential`() {
        val original = savedPhoneState().let { state -> state.copy(settings = state.settings.copy(
            speechPacing = SpeechPacing.PATIENT, speechLanguageTag = "fr-CA", localSpeechOnly = true, hapticsEnabled = true)) }
        val exported = KaiJson.encode(original, includeSecrets = false)
        val restored = KaiJson.decode(exported)

        assertEquals(original.settings.speechPacing, restored.settings.speechPacing)
        assertEquals(original.settings.speechLanguageTag, restored.settings.speechLanguageTag)
        assertTrue(restored.settings.localSpeechOnly)
        assertTrue(restored.settings.hapticsEnabled)
        assertEquals(original.characters, restored.characters)
        assertEquals(original.memories, restored.memories)
        assertEquals("", restored.settings.koinos.apiKey)
        assertEquals("", restored.settings.openai.apiKey)
        assertEquals("", restored.settings.composioApiKey)
        assertEquals("", restored.settings.composioUserId)
        assertEquals("", restored.settings.connectionsSessionToken)
        listOf("test-koinos-secret", "test-openai-secret", "test-composio-secret", "test-session-secret").forEach {
            assertFalse(exported.contains(it))
        }
    }

    @Test fun `unsupported optional speech values fall back without resetting unrelated settings`() {
        val original = savedPhoneState()
        val payload = JSONObject(KaiJson.encode(original))
        payload.getJSONObject("settings").apply {
            put("speechPacing", "FUTURE_PACING")
            put("speechLanguageTag", "en--US")
            put("speechRate", "NaN")
        }

        val restored = KaiJson.decode(payload.toString())

        assertEquals(original.copy(settings = original.settings.copy(speechRate = 1f)), restored)
    }

    @Test fun `language tags accept device default and canonicalize valid input without accepting malformed tags`() {
        assertEquals("", normalizedSpeechLanguageTag("  "))
        assertEquals("en-US", normalizedSpeechLanguageTag(" en_us "))
        assertEquals("zh-Hant-TW", normalizedSpeechLanguageTag("zh-hant-tw"))
        assertEquals("es", normalizedSpeechLanguageTag("es"))
        assertNull(normalizedSpeechLanguageTag("en--US"))
        assertNull(normalizedSpeechLanguageTag("English (US)"))
        assertNull(normalizedSpeechLanguageTag("und"))
        assertNull(normalizedSpeechLanguageTag("x-private"))
        assertNull(normalizedSpeechLanguageTag("a".repeat(65)))
    }

    private fun savedPhoneState() = KaiState(
        revision = 19,
        settings = AppSettings(
            selectedProvider = Provider.OPENAI,
            koinos = ProviderProfile("https://example.test/v1", "test-koinos-secret", "local-model"),
            openai = ProviderProfile("https://api.openai.com/v1", "test-openai-secret", "chosen-model"),
            routing = RoutingMode.LOCAL_FIRST_NETWORK,
            selectedCharacterId = "nova",
            connectionsBaseUrl = "https://legacy.example.test",
            connectionsSessionToken = "test-session-secret",
            accountId = "existing-owner",
            accountDisplayName = "Existing account",
            connectionGeneration = "existing-generation",
            composioApiKey = "test-composio-secret",
            composioUserId = "exact:existing-user",
            memoryMode = MemoryMode.ASK_FIRST,
            useMemory = false,
            reducedMotion = true,
            isDarkTheme = false,
            speechRate = .8f,
        ),
        characters = defaultCharacters().map { it.copy(voiceId = "saved-${it.id}-voice") },
        conversations = listOf(Conversation(characterId = "nova", messages = listOf(
            ChatMessage(role = "user", text = "My daughter's name is Aurora."),
            ChatMessage(role = "assistant", text = "I will remember. More text.", interrupted = true, spokenCharacters = 16),
        ))),
        memories = listOf(MemoryFact(text = "The user's daughter is named Aurora", source = MemorySource(ref = "source-message"))),
        tasks = listOf(AgentTask(title = "Existing action", status = TaskStatus.UNKNOWN,
            intent = ActionIntent(toolName = "send_mail", accountId = "existing-connected-account", argumentsJson = "{}"))),
    )
}
