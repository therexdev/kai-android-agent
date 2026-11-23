package com.kai.agent.data

/** Retires the 0.4.1 phone-voice preset. Existing explicit voice choices and preferences are kept. */
object ConversationVoiceDefaults {
    const val VOICE_ID = "kai:conversation:local"

    fun restore(character: CharacterProfile): CharacterProfile = if (character.voiceId == VOICE_ID)
        character.copy(voiceId = if (character.id == "kai") DesktopVoiceDefaults.VOICE_ID else "system") else character

    fun migrate(state: KaiState): KaiState {
        val characters = state.characters.map(::restore)
        if (characters == state.characters && state.settings.conversationVoiceDefaultApplied) return state
        return state.copy(settings = state.settings.copy(conversationVoiceDefaultApplied = true), characters = characters)
    }
}
