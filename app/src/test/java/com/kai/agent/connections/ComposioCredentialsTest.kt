package com.kai.agent.connections

import org.junit.Assert.*
import org.junit.Test

class ComposioCredentialsTest {
    @Test fun normalizesOnlyCommonOuterPasteWrappersAndPreservesExactUser() {
        val key = "ak_fixture-complete-key_123"
        listOf(key, " \t$key\r\n", "\uFEFF\u200B$key\u200B", "\"$key\"", "'$key'", "“$key”", "‘$key’",
            "Bearer $key", "bearer\t\"$key\"", "x-api-key: $key", "'x-api-key: $key'").forEach { input ->
            val credentials = ComposioCredentials(input, "kai:acc_exact-user")
            assertEquals(key, credentials.apiKey)
            assertEquals("kai:acc_exact-user", credentials.userId)
            assertFalse(credentials.toString().contains(key))
        }
    }

    @Test fun refusesToRepairEmbeddedWhitespaceOrExtractSecretsFromConfiguration() {
        listOf("ak_fixture secret", "ak_fixture\nsecret", "ak_fixture\u200Bsecret", "\"ak_fixture", "ak_fixture\"",
            "{\"api_key\":\"ak_fixture_secret\"}", "curl -H 'x-api-key: ak_fixture_secret'", "a".repeat(8193)).forEach { input ->
            val error = rejected(input)
            assertFalse(error.message!!.contains("ak_fixture_secret"))
        }
    }

    @Test fun rejectsUserKeyAndExplicitWrongCredentialKindsWithoutEchoingSecrets() {
        listOf("uak_private-fixture", "Bearer uak_private-fixture", "x-user-api-key: uak_private-fixture",
            "x-org-api-key: private-fixture", "https://connect.composio.dev/link/private-fixture").forEach { input ->
            val error = rejected(input)
            assertTrue(error.message!!.contains("project API key"))
            assertFalse(error.message!!.contains("private-fixture"))
        }
    }

    private fun rejected(value: String): IllegalArgumentException {
        try { ComposioCredentials(value, "my-user") }
        catch (error: IllegalArgumentException) { return error }
        throw AssertionError("Unsupported credential accepted")
    }
}
