package com.kai.agent.data

/** Pitch styles for the existing Android TTS path. They never invoke the desktop model. */
object PlayfulVoiceDefaults {
    const val CUTE = "kai:device:cute"
    const val CARTOON = "kai:device:cartoon"
    const val SQUEAKY = "kai:device:squeaky"
    data class Style(val id: String, val name: String, val detail: String, val pitch: Float)
    val styles = listOf(
        Style(CUTE, "KAI · Cute", "Gentle, cute high pitch", 1.35f),
        Style(CARTOON, "KAI · Cartoon", "Brighter cartoon pitch", 1.55f),
        Style(SQUEAKY, "KAI · Squeaky", "Extra high, squeaky pitch", 1.8f),
    )
    fun style(id: String?): Style? = styles.firstOrNull { it.id == id }
    fun baseVoice(id: String?): String = id?.takeIf {
        it.isNotBlank() && it.length <= 512 && !it.startsWith("kai:kitten:") && !it.startsWith("kai:kokoro:") && style(it) == null &&
            it != DesktopVoiceDefaults.VOICE_ID && it != ConversationVoiceDefaults.VOICE_ID
    } ?: "system"

    /** Upgrade only the old KAI model/default; deliberate device choices remain untouched. */
    fun migrate(state: KaiState): KaiState {
        if (state.settings.playfulVoiceDefaultApplied) return state
        return state.copy(settings = state.settings.copy(playfulVoiceDefaultApplied = true),
            characters = state.characters.map {
                if (it.id == "kai" && it.voiceId in setOf(DesktopVoiceDefaults.VOICE_ID, ConversationVoiceDefaults.VOICE_ID))
                    it.copy(voiceId = CUTE) else it
            })
    }
}
