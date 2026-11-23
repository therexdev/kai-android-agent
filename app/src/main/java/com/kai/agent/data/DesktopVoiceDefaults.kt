package com.kai.agent.data

/** Stable identity for the same Azelma model and Cute KAI treatment as the desktop. */
object DesktopVoiceDefaults {
    const val VOICE_ID = "kai:azelma:cute"

    fun migrate(state: KaiState): KaiState {
        if (state.settings.desktopVoiceDefaultApplied) return state
        return state.copy(
            settings = state.settings.copy(desktopVoiceDefaultApplied = true),
            characters = state.characters.map {
                if (it.id == "kai" && (it.voiceId.isBlank() || it.voiceId == "system"))
                    it.copy(voiceId = VOICE_ID) else it
            },
        )
    }
}
