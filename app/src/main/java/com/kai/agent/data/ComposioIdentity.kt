package com.kai.agent.data

/**
 * Direct Composio account identity belongs to this phone profile, not to an inference provider.
 * Call only during load/migration (and persist the result), never from rendering or a property getter.
 */
object ComposioIdentity {
    fun migrate(settings: AppSettings, generateId: () -> String = ::newId): AppSettings {
        if (settings.composioUserId.isNotBlank()) return settings
        val userId = if (settings.accountId.isNotBlank()) "kai:${settings.accountId}"
            else "kai-mobile:${generateId()}"
        // A legacy session token is deliberately never promoted into a Composio API key.
        return settings.copy(composioUserId = userId)
    }
}
