package com.kai.agent.diagnostics

/** Bounded trace history for a batch of phone tests; conversation history is not pruned here. */
object DiagnosticRetention {
    const val CHAT_CHARACTERS = 4_000_000L
    const val CHAT_EVENTS = 2_000
    const val ACTIVE_CHATS = 20
    const val description = "Newest 2,000 events / 4,000,000 characters per chat, for the 20 most recently traced chats (at most 80,000,000 characters). All saved regular chats are exported, including chats without retained traces. Large fields are marked truncated. Privacy changes clear traces. Earlier internal steps cannot be reconstructed. These limits apply from 0.5.11; previously discarded records cannot be restored."
}
