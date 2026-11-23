package com.kai.agent.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ComposioIdentityTest {
    @Test fun `old JSON reuses legacy connected identity without adopting managed token`() {
        val legacy = JSONObject(KaiJson.encode(KaiState(settings = AppSettings(
            accountId = "existing-owner", connectionsSessionToken = "legacy-session-secret"))))
        legacy.getJSONObject("settings").remove("composioApiKey")
        legacy.getJSONObject("settings").remove("composioUserId")
        val decoded = KaiJson.decode(legacy.toString())
        val migrated = ComposioIdentity.migrate(decoded.settings) { error("Legacy identity must be reused") }
        assertEquals("kai:existing-owner", migrated.composioUserId)
        assertEquals("", migrated.composioApiKey)
        assertEquals("legacy-session-secret", migrated.connectionsSessionToken)
    }

    @Test fun `new phone identity is generated once and survives codec and copies`() {
        var generations = 0
        val migrated = ComposioIdentity.migrate(AppSettings()) { generations++; "test-uuid" }
        assertEquals("kai-mobile:test-uuid", migrated.composioUserId)
        val reloaded = KaiJson.decode(KaiJson.encode(KaiState(settings = migrated))).settings
        val copied = reloaded.copy(isDarkTheme = false)
        assertEquals(copied, ComposioIdentity.migrate(copied) { generations++; "should-not-be-used" })
        assertEquals(1, generations)
    }

    @Test fun `explicit direct identity takes precedence over old account`() {
        val current = AppSettings(composioApiKey = "direct-key", composioUserId = "custom-existing-user", accountId = "old-owner")
        assertEquals(current, ComposioIdentity.migrate(current) { error("Existing direct identity must be reused") })
    }

    @Test fun `settings debug representation excludes direct secrets and identity`() {
        val settings = AppSettings(composioApiKey = "secret-never-log", composioUserId = "private-never-log")
        assertFalse(settings.toString().contains("secret-never-log"))
        assertFalse(settings.toString().contains("private-never-log"))
    }
}
