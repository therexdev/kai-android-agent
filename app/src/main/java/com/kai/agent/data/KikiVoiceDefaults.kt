package com.kai.agent.data

/** Kiki is the bundled default; retired app voices cannot reappear through import or reload. */
object KikiVoiceDefaults {
    const val VOICE_ID = "kai:kitten:kiki"
    fun retired(id: String): Boolean = id == ConversationVoiceDefaults.VOICE_ID ||
        id.startsWith("kai:azelma:") || id.startsWith("kai:device:") || id.startsWith("kai:kokoro:") ||
        id.startsWith("kai:kitten:") && id != VOICE_ID
    fun normalize(id: String): String = if (id.isBlank() || retired(id)) VOICE_ID else id
    fun restore(character: CharacterProfile): CharacterProfile {
        val voice = normalize(character.voiceId)
        return if (voice == character.voiceId) character else character.copy(voiceId = voice)
    }
    fun migrate(state: KaiState): KaiState {
        val firstUpgrade = !state.settings.kikiVoiceDefaultApplied
        val characters = state.characters.map {
            if (firstUpgrade && it.voiceId == "system") it.copy(voiceId = VOICE_ID) else restore(it)
        }
        if (!firstUpgrade && characters == state.characters && state.settings.desktopVoiceDefaultApplied &&
            state.settings.conversationVoiceDefaultApplied && state.settings.playfulVoiceDefaultApplied) return state
        return state.copy(settings = state.settings.copy(kikiVoiceDefaultApplied = true, desktopVoiceDefaultApplied = true,
            conversationVoiceDefaultApplied = true, playfulVoiceDefaultApplied = true), characters = characters)
    }
}
