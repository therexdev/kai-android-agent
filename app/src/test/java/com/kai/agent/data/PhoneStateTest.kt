package com.kai.agent.data

import org.junit.Assert.*
import org.junit.Test

class PhoneStateTest {
    @Test fun `export omits configured credentials and ephemeral conversation`() {
        val settings = AppSettings(koinos = ProviderProfile("http://192.168.1.8:8080", "koin-secret", "local-model"),
            openai = ProviderProfile("https://api.openai.com/v1", "openai-secret", "chosen-model"),
            connectionsSessionToken = "session-secret", accountId = "owner", connectionGeneration = "opaque-generation",
            braveSearchApiKey = "brave-search-secret", composioApiKey = "composio-secret", composioUserId = "kai-mobile:private-user")
        val state = KaiState(settings = settings, conversations = listOf(
            Conversation(title = "Private", temporary = true, messages = listOf(ChatMessage(role = "user", text = "temporary-secret"))),
            Conversation(title = "Saved", messages = listOf(ChatMessage(role = "user", text = "keep this")))))
        val exported = KaiJson.encode(state, includeSecrets = false)
        listOf("koin-secret", "openai-secret", "session-secret", "temporary-secret", "opaque-generation", "composio-secret", "brave-search-secret", "kai-mobile:private-user").forEach {
            assertFalse("Export leaked a secret: $it", exported.contains(it))
        }
        val decoded = KaiJson.decode(exported)
        assertEquals("chosen-model", decoded.settings.openai.model)
        assertEquals("", decoded.settings.openai.apiKey)
        assertEquals("", decoded.settings.braveSearchApiKey)
        assertEquals("", decoded.settings.composioApiKey)
        assertEquals("", decoded.settings.composioUserId)
        assertEquals(1, decoded.conversations.size)
        assertEquals("keep this", decoded.conversations.single().messages.single().text)
    }

    @Test fun `encrypted payload codec preserves exact approval and playback cursor`() {
        val intent = ActionIntent(toolName = "send_mail", accountId = "connected-account", argumentsJson = "{\"to\":\"sam\"}",
            ownerId = "owner", connectionGeneration = "generation", toolVersion = "v2", toolSchemaJson = "{\"type\":\"object\"}")
        val task = AgentTask(title = "Email", intent = intent, status = TaskStatus.UNKNOWN,
            submittedAt = 123, approvedFingerprint = TaskRules.fingerprint(intent))
        val state = KaiState(settings = AppSettings(openai = ProviderProfile(apiKey = "private"),
            braveSearchApiKey = "brave-private", composioApiKey = "composio-private", composioUserId = "kai-mobile:owner"),
            conversations = listOf(Conversation(messages = listOf(ChatMessage(role = "assistant", text = "Heard. Unheard.", interrupted = true, spokenCharacters = 6)))),
            tasks = listOf(task))
        assertEquals(state, KaiJson.decode(KaiJson.encode(state)))
    }

    @Test fun `forget prevents automatic re-extraction from same source and old fact`() {
        val fact = MemoryFact(id = "m1", text = "Meetings after ten", source = MemorySource(ref = "message-1", conversationId = "chat-1"), createdAt = 100, updatedAt = 100)
        val tombstone = MemoryRules.tombstone(fact, at = 200)
        assertTrue(MemoryRules.isSuppressed(fact, listOf(tombstone)))
        val reextracted = fact.copy(id = "new-id", text = "Prefers meetings later in the morning", origin = MemoryOrigin.INFERRED, createdAt = 300)
        assertTrue(MemoryRules.isSuppressed(reextracted, listOf(tombstone)))
        val sameTextNewSource = fact.copy(id = "new2", source = MemorySource(ref = "other"), origin = MemoryOrigin.INFERRED, createdAt = 300)
        assertTrue(MemoryRules.isSuppressed(sameTextNewSource, listOf(tombstone)))
    }

    @Test fun `explicit correction survives while old source cannot recreate old value`() {
        val old = MemoryFact(id = "m1", text = "Meetings after nine", revision = 1, source = MemorySource(ref = "source"), createdAt = 100)
        val tombstone = MemoryRules.tombstone(old, "corrected", 200)
        val corrected = old.copy(text = "Meetings after ten", revision = 2, updatedAt = 201)
        assertFalse(MemoryRules.isSuppressed(corrected, listOf(tombstone)))
        assertTrue(MemoryRules.isSuppressed(old.copy(id = "relearned", origin = MemoryOrigin.INFERRED), listOf(tombstone)))
    }

    @Test fun `search returns relevant evidence only within granted scopes`() {
        val facts = listOf(
            MemoryFact(text = "Meeting time is after ten", scope = "personal", source = MemorySource(ref = "message-1")),
            MemoryFact(text = "Meeting time is confidential", scope = "work"),
            MemoryFact(text = "Favorite food is pizza", scope = "personal", pinned = true))
        val matches = MemoryRules.search(facts, emptyList(), "meeting time", setOf("personal"))
        assertEquals(1, matches.size)
        assertEquals("message-1", matches.single().fact.source.ref)
        assertEquals("Meeting time is after ten", matches.single().fact.text)
    }

    @Test fun `unknown schema cannot silently reset saved state`() {
        try {
            KaiJson.decode("{\"schemaVersion\":42}")
            fail("A newer schema must fail closed")
        } catch (_: IllegalArgumentException) { }
    }
}
