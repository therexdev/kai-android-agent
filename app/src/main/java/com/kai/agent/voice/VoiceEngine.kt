package com.kai.agent.voice

import android.content.Context
import java.util.UUID

/** Phone voice boundary; production uses Android services and lifecycle tests control callbacks. */
interface VoiceEngine : AutoCloseable {
    fun startListening()
    fun stop()
    fun beginSpeech(utteranceId: String)
    fun appendSpeech(utteranceId: String, delta: String)
    fun endSpeech(utteranceId: String)
    fun cancelSpeech(utteranceId: String)
    fun speak(text: String, utteranceId: String = UUID.randomUUID().toString())
    fun stopSpeaking()
    fun setVoice(name: String?)
    fun setPresetBaseVoice(name: String?) {}
    fun setRate(rate: Float)
    fun setPatience(value: Patience)
    fun setLanguage(languageTag: String)
    fun setLocalSpeechOnly(enabled: Boolean)
    fun selectAudioRoute(id: String) {}
    fun startVoiceTest() {}
    fun clearVoiceTest() {}
    fun voiceTestReport(): String = "{}"
    override fun close()
}

fun interface VoiceEngineFactory {
    fun create(context: Context, callbacks: VoiceController.Callbacks): VoiceEngine
}
