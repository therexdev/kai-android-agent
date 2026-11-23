package com.kai.agent.voice

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.kai.agent.data.KikiVoiceDefaults
import java.util.Locale

enum class VoiceState { IDLE, LISTENING, THINKING, SPEAKING }

data class VoiceOption(
    val id: String,
    val name: String,
    val languageTag: String,
    val requiresNetwork: Boolean,
    val quality: Int,
)

/**
 * Foreground, user-initiated Android voice session. MainActivity owns RECORD_AUDIO permission
 * and must call stopListening/stopSpeaking on background. No microphone is opened by construction.
 * Platform recognizers vary: partial-based interruption is best effort, not guaranteed full duplex.
 */
class VoiceController private constructor(context: Context, private val callbacks: Callbacks, private val ttsPackage: String?, localFactory: (Context, Handler) -> BufferedLocalPlayer, private val recognizerComponent: android.content.ComponentName? = null) : VoiceEngine {
    constructor(context: Context, callbacks: Callbacks, ttsPackage: String? = null) : this(context, callbacks, ttsPackage, { app, handler -> BufferedLocalPlayer(app, handler) })
    internal constructor(context: Context, callbacks: Callbacks, ttsPackage: String?, localPlayer: BufferedLocalPlayer, recognizerComponent: android.content.ComponentName? = null) : this(context, callbacks, ttsPackage, { _, _ -> localPlayer }, recognizerComponent)
    interface Callbacks {
        fun onPartial(text: String) {}
        fun onUtterance(text: String) {}
        fun onState(state: VoiceState) {}
        fun onError(message: String) {}
        fun onBargeIn() {}
        fun onVoicesChanged(voices: List<VoiceOption>) {}
        fun onServiceChanged(onDeviceRecognition: Boolean) {}
        /** Whether the explicitly requested foreground microphone session remains enabled. */
        fun onListeningChanged(enabled: Boolean) {}
        fun onPlaybackProgress(utteranceId: String, spokenCharacters: Int) {}
        fun onSpeechFinished(utteranceId: String, interrupted: Boolean) {}
        /** Brief cues tied to TTS playback/range events, not an audio amplitude measurement. */
        fun onSpeechActivity(utteranceId: String, level: Float) {}
        fun onAudioRoutes(routes: List<VoiceAudioRoute>, selectedId: String, status: String) {}
        fun onUnsentTranscript(text: String) {}
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val diagnostics = VoiceDiagnostics(SystemClock::elapsedRealtime)
    private val audioRoute: ConversationAudioRoute = ConversationAudioRoute(appContext, main, callbacks::onAudioRoutes) {
        diagnostics.event("route_lost")
        preserveUnsentInput()
        stop()
        callbacks.onError("Conversation audio changed or could not connect. Check your headset, then tap Start talking to resume. Any captured words are available in Chat.")
    }
    private val speechAttributes get() = AudioAttributes.Builder().setUsage(
        if (listening && audioRoute.communicationActive) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private var hasAudioFocus = false
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(speechAttributes)
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change ->
            if (!closed && (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)) {
                diagnostics.event("audio_focus_loss", change.toLong())
                preserveUnsentInput()
                stopListening()
                stopSpeaking()
            }
        }, main).build()
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!closed && intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && (listening || currentUtterance != null)) {
                diagnostics.event("route_lost")
                preserveUnsentInput()
                stop()
                callbacks.onError("Audio output changed. Tap the microphone to resume when ready.")
            }
        }
    }
    private var receiverRegistered = false
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private val local = localFactory(appContext, main)
    private var ttsReady = false
    private var closed = false
    private var listening = false
        set(value) {
            if (field == value) return
            field = value
            callbacks.onListeningChanged(value)
        }
    private var recognizerRunning = false
    private var recognitionEpoch = 0
    private var selectedVoice: String? = KikiVoiceDefaults.VOICE_ID
    private val usesKikiVoice: Boolean get() = LocalVoiceCatalog.isLocalVoice(selectedVoice)
    private var localSpeechOnly = false
    private var forcePlatformRecognizer = false
    private var usingOnDeviceRecognizer = false
    private var speechRate = 1f
    private var patience = Patience.NATURAL
    private var locale = Locale.getDefault()
    private var speech: StreamingSpeechSession? = null
    private var speechPlaybackStarted = false
    private var speechChunkAudible = false
    private val currentUtterance: String? get() = speech?.id
    private var lastSpeechText = ""
    private var lastSpeechEndedAt = Long.MIN_VALUE
    private var acceptedUserSpeech = false
    private var sessionSpeechStarted = false
    private var retryCount = 0
    private val accumulator = TurnAccumulator()
    private val commitClock = TurnCommitClock()
    private val recognitionSegment = RecognitionSegment()
    private var commitRunnable: Runnable? = null
    private var restartRunnable: Runnable? = null
    private var watchdogRunnable: Runnable? = null
    private var speechFlushRunnable: Runnable? = null
    private var speechActivityRunnable: Runnable? = null
    private var ttsFailed = false

    init {
        onMain {
            local.setMetrics { name, first, second -> diagnostics.event(name, first, second) }
            publishVoices()
            val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            if (Build.VERSION.SDK_INT >= 33) appContext.registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else appContext.registerReceiver(noisyReceiver, filter)
            receiverRegistered = true
            tts = TextToSpeech(appContext, { status ->
                main.post {
                    if (closed) return@post
                    ttsReady = status == TextToSpeech.SUCCESS
                    if (ttsReady) {
                        configureTts()
                        publishVoices()
                        pumpSpeech()
                    } else {
                        ttsFailed = true
                        publishVoices()
                        if (!usesKikiVoice) {
                            stopSpeakingInternal()
                            callbacks.onError("Android speech output is unavailable. Check Android’s text-to-speech settings or continue by text.")
                        }
                    }
                }
            }, ttsPackage)
        }
    }

    override fun startListening() = onMain {
        if (closed) return@onMain
        if (listening) { if (usesKikiVoice) local.warm(selectedVoice.orEmpty()); return@onMain }
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            callbacks.onListeningChanged(false)
            callbacks.onError("Allow microphone access to start a voice conversation.")
            return@onMain
        }
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            callbacks.onListeningChanged(false)
            callbacks.onError("No speech recognition service is available. Use text or enable an Android speech service.")
            return@onMain
        }
        stopSpeakingInternal()
        listening = true
        diagnostics.event("listening_start")
        if (usesKikiVoice) local.warm(selectedVoice.orEmpty())
        forcePlatformRecognizer = false
        retryCount = 0
        accumulator.clear()
        commitClock.clear()
        recognitionSegment.clear()
        diagnostics.event("route_requested")
        audioRoute.start {
            if (listening && !closed) {
                diagnostics.event("route_ready", audioRoute.activeType.toLong())
                if (ttsReady) configureTts()
                ensureRecognizer(); startRecognition()
            }
        }
    }

    fun stopListening() = onMain {
        if (listening) diagnostics.event("listening_stop")
        listening = false
        cancelCommit()
        cancelRestart()
        accumulator.clear()
        commitClock.clear()
        recognitionSegment.clear()
        callbacks.onPartial("")
        destroyRecognizer()
        audioRoute.stop()
        if (currentUtterance == null) callbacks.onState(VoiceState.IDLE)
    }

    override fun stop() = onMain { stopListening(); stopSpeakingInternal(); local.release() }
    fun mute() = stopListening()

    /** Use a unique id per reply attempt. Only exact, append-only reply text belongs in this stream. */
    override fun beginSpeech(utteranceId: String) = onMain {
        if (closed) return@onMain
        stopSpeakingInternal()
        speech = StreamingSpeechSession(utteranceId)
        diagnostics.event("reply_begin")
        if (usesKikiVoice) local.begin(selectedVoice.orEmpty(), speechRate, speechAttributes)
        speechPlaybackStarted = false
        speechChunkAudible = false
        callbacks.onPlaybackProgress(utteranceId, 0)
        callbacks.onState(VoiceState.THINKING)
        if (ttsFailed && !usesKikiVoice) stopSpeakingInternal()
    }

    override fun appendSpeech(utteranceId: String, delta: String) = onMain {
        val session = speech?.takeIf { it.id == utteranceId } ?: return@onMain
        if (session.append(delta)) pumpSpeech()
    }

    override fun endSpeech(utteranceId: String) = onMain {
        val session = speech?.takeIf { it.id == utteranceId } ?: return@onMain
        session.end()
        pumpSpeech()
    }

    override fun cancelSpeech(utteranceId: String) = onMain {
        if (speech?.id == utteranceId) stopSpeakingInternal()
    }

    override fun speak(text: String, utteranceId: String) = onMain {
        if (closed || text.isBlank()) return@onMain
        beginSpeech(utteranceId)
        appendSpeech(utteranceId, text)
        endSpeech(utteranceId)
    }

    override fun stopSpeaking() = onMain { stopSpeakingInternal() }

    override fun setVoice(name: String?) = onMain {
        val next = name?.takeIf { it.isNotBlank() }?.let(KikiVoiceDefaults::normalize)
        if (selectedVoice == next) return@onMain
        stopSpeakingInternal()
        local.release()
        selectedVoice = next
        if (listening && usesKikiVoice) local.warm(selectedVoice.orEmpty())
        if (ttsReady) configureTts()
    }

    // Saved legacy base-voice metadata remains portable; removed pitch styles no longer use it.
    override fun setPresetBaseVoice(name: String?) = Unit

    override fun setRate(rate: Float) = onMain {
        val next = rate.takeIf { it.isFinite() }?.coerceIn(.5f, 2f) ?: 1f
        if (speechRate == next) return@onMain
        speechRate = next
        tts?.setSpeechRate(speechRate)
    }
    override fun setPatience(value: Patience) = onMain { patience = value }
    override fun setLanguage(languageTag: String) = onMain {
        val next = Locale.forLanguageTag(languageTag).takeIf { it.language.isNotBlank() } ?: Locale.getDefault()
        if (locale == next) return@onMain
        locale = next
        if (ttsReady) configureTts()
    }

    /** Local-only recognition fails closed if the platform has no on-device service. */
    override fun setLocalSpeechOnly(enabled: Boolean) = onMain {
        if (localSpeechOnly == enabled) return@onMain
        stopSpeakingInternal()
        localSpeechOnly = enabled
        forcePlatformRecognizer = false
        if (ttsReady) configureTts()
        if (listening) { destroyRecognizer(); ensureRecognizer(); startRecognition() }
    }

    fun voices(): List<VoiceOption> = voiceOptions()

    override fun selectAudioRoute(id: String) = onMain { audioRoute.select(id) }
    override fun startVoiceTest() = onMain { diagnostics.start() }
    override fun clearVoiceTest() = onMain { diagnostics.clear() }
    override fun voiceTestReport(): String = diagnostics.report(Build.VERSION.SDK_INT, com.kai.agent.BuildConfig.VERSION_NAME)

    private fun configureTts() {
        val engine = tts ?: return
        engine.setAudioAttributes(speechAttributes)
        engine.setSpeechRate(speechRate)
        // Pitch belongs only to these styles. Always reset it when returning to a normal voice.
        engine.setPitch(1f)
        engine.language = locale
        val voices = engine.voices.orEmpty().filter { (!localSpeechOnly || !it.isNetworkConnectionRequired) && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features }
        val requestedVoice = selectedVoice
        val wanted = voices.firstOrNull { it.name == requestedVoice }
            ?: voices.filter { it.locale.language == locale.language }
                .sortedWith(compareBy<android.speech.tts.Voice> { it.isNetworkConnectionRequired }.thenByDescending { it.quality })
                .firstOrNull()
        wanted?.let { engine.voice = it }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = postCurrent(utteranceId) { session ->
                speechPlaybackStarted = true
                speechChunkAudible = true
                diagnostics.event("playback_start")
                callbacks.onState(VoiceState.SPEAKING)
                speechCue(session, .35f)
            }
            override fun onDone(utteranceId: String?) = postCurrent(utteranceId) { session ->
                if (!session.done(utteranceId)) return@postCurrent
                speechChunkAudible = false
                callbacks.onPlaybackProgress(session.id, session.heardCharacters)
                clearSpeechCue(session.id)
                pumpSpeech()
            }
            @Deprecated("Android legacy callback")
            override fun onError(utteranceId: String?) = speechError(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = speechError(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = postCurrent(utteranceId) {
                stopSpeakingInternal()
            }
            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) = postCurrent(utteranceId) { session ->
                // onRangeStart means the range is about to play. Its END is not heard yet.
                if (session.rangeStarted(utteranceId, start)) {
                    callbacks.onPlaybackProgress(session.id, session.heardCharacters)
                    speechCue(session, (.45f + (end - start).coerceIn(0, 10) * .045f).coerceAtMost(.9f))
                }
            }
        })
    }

    private fun speechError(id: String?) = postCurrent(id) {
        stopSpeakingInternal()
        callbacks.onError("Speech playback failed. The full response is still available as text.")
    }

    private fun pumpSpeech(flushPartial: Boolean = false) {
        val session = speech ?: return
        if (usesKikiVoice) { pumpLocalSpeech(session, flushPartial); return }
        if (session.isComplete) { finishSpeech(false); return }
        if (session.inFlight != null || !ttsReady) return
        val engine = tts ?: return
        if (localSpeechOnly && (engine?.voice == null || engine.voice.isNetworkConnectionRequired)) {
            stopSpeakingInternal()
            callbacks.onError("No downloaded voice is available for this language. Select an offline voice or use text.")
            return
        }
        val chunk = session.next(TextToSpeech.getMaxSpeechInputLength(), flushPartial)
        if (chunk == null) {
            if (session.isComplete) { finishSpeech(false); return }
            if (session.hasPending && speechFlushRunnable == null && !flushPartial) {
                speechFlushRunnable = Runnable {
                    speechFlushRunnable = null
                    if (speech === session) pumpSpeech(flushPartial = true)
                }.also { main.postDelayed(it, 420) }
            }
            // Close the mouth during token gaps, while retaining the reply's speaking pose.
            if (session.inFlight == null) {
                clearSpeechCue(session.id)
                if (!speechPlaybackStarted) callbacks.onState(VoiceState.THINKING)
            }
            return
        }
        speechFlushRunnable?.let(main::removeCallbacks); speechFlushRunnable = null
        if (!hasAudioFocus) {
            hasAudioFocus = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (!hasAudioFocus) {
                stopSpeakingInternal()
                callbacks.onError("Audio is in use by another app. The response is available as text.")
                return
            }
        }
        if (engine.speak(chunk.text, TextToSpeech.QUEUE_ADD, Bundle(), chunk.platformId) == TextToSpeech.ERROR) {
            stopSpeakingInternal()
            callbacks.onError("Speech could not start. Continue using the transcript.")
        }
    }

    private fun pumpLocalSpeech(session: StreamingSpeechSession, flushPartial: Boolean) {
        if (session.isComplete) { local.seal(); finishSpeech(false); return }
        while (session.reservedCount < 2) {
            val chunk = session.next(160, flushPartial, capacity = 2) ?: break
            if (!hasAudioFocus) {
                hasAudioFocus = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                if (!hasAudioFocus) { stopSpeakingInternal(); callbacks.onError("Audio is in use by another app. The response is available as text."); return }
            }
            local.enqueue(chunk.text, selectedVoice.orEmpty(), object : LocalSpeech.Callback {
                override fun started() = postCurrent(chunk.platformId) { current ->
                    speechPlaybackStarted = true
                    speechChunkAudible = true
                    callbacks.onState(VoiceState.SPEAKING)
                    localSpeechCue(current)
                }
                override fun finished() = postCurrent(chunk.platformId) { current ->
                    if (current.done(chunk.platformId)) {
                        speechChunkAudible = false
                        callbacks.onPlaybackProgress(current.id, current.heardCharacters)
                        clearSpeechCue(current.id)
                        pumpSpeech()
                    }
                }
                override fun failed(message: String) = postCurrent(chunk.platformId) {
                    stopSpeakingInternal(); callbacks.onError(message)
                }
            })
            speechFlushRunnable?.let(main::removeCallbacks); speechFlushRunnable = null
        }
        if (session.isComplete) { local.seal(); finishSpeech(false); return }
        if (session.inputEnded && !session.hasPending) local.seal()
        if (session.hasPending && session.reservedCount < 2 && speechFlushRunnable == null && !flushPartial) {
            speechFlushRunnable = Runnable {
                speechFlushRunnable = null
                if (speech === session) pumpSpeech(flushPartial = true)
            }.also { main.postDelayed(it, 420) }
        }
    }

    private fun stopSpeakingInternal() = finishSpeech(interrupted = true)

    private fun finishSpeech(interrupted: Boolean) {
        val session = speech
        val echo = session?.echoReference(includeCurrent = speechChunkAudible).orEmpty()
        val completion = session?.finish(interrupted)
        if (session != null && completion == null) return
        speech = null
        speechPlaybackStarted = false
        speechChunkAudible = false
        speechFlushRunnable?.let(main::removeCallbacks); speechFlushRunnable = null
        if (echo.isNotBlank()) {
            lastSpeechText = echo
            lastSpeechEndedAt = SystemClock.elapsedRealtime()
        }
        clearSpeechCue(session?.id)
        if (interrupted && session != null) { tts?.stop(); local.stop() }
        releaseAudioFocus()
        completion?.let {
            diagnostics.event("reply_finish", if (it.interrupted) 1 else 0, it.heardCharacters.toLong())
            callbacks.onPlaybackProgress(it.id, it.heardCharacters)
            callbacks.onSpeechFinished(it.id, it.interrupted)
        }
        if (!closed) callbacks.onState(if (listening) VoiceState.LISTENING else VoiceState.IDLE)
        if (listening && !recognizerRunning) scheduleRestart(180)
    }

    private fun speechCue(session: StreamingSpeechSession, level: Float) {
        speechActivityRunnable?.let(main::removeCallbacks)
        callbacks.onSpeechActivity(session.id, level)
        speechActivityRunnable = Runnable {
            speechActivityRunnable = null
            if (speech === session) callbacks.onSpeechActivity(session.id, 0f)
        }.also { main.postDelayed(it, 180) }
    }

    /** Playback activity only; no claim of phoneme-level lip synchronization. */
    private fun localSpeechCue(session: StreamingSpeechSession) {
        speechActivityRunnable?.let(main::removeCallbacks)
        callbacks.onSpeechActivity(session.id, .55f)
        speechActivityRunnable = Runnable {
            speechActivityRunnable = null
            if (speech === session && session.inFlight != null) {
                callbacks.onSpeechActivity(session.id, .2f)
                speechActivityRunnable = Runnable { if (speech === session) localSpeechCue(session) }
                    .also { main.postDelayed(it, 100) }
            }
        }.also { main.postDelayed(it, 160) }
    }

    private fun clearSpeechCue(id: String?) {
        speechActivityRunnable?.let(main::removeCallbacks); speechActivityRunnable = null
        id?.let { callbacks.onSpeechActivity(it, 0f) }
    }

    private fun ensureRecognizer() {
        if (recognizer != null || !listening) return
        val localAvailable = !forcePlatformRecognizer && Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
        if (localSpeechOnly && !localAvailable) {
            preserveUnsentInput(); stop()
            callbacks.onError("On-device speech recognition is unavailable. Download/enable it or turn off Local speech only.")
            return
        }
        try {
            recognizer = if (recognizerComponent != null) SpeechRecognizer.createSpeechRecognizer(appContext, recognizerComponent)
                else if (localAvailable) SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
                else SpeechRecognizer.createSpeechRecognizer(appContext)
            usingOnDeviceRecognizer = localAvailable
            callbacks.onServiceChanged(localAvailable)
        } catch (_: Exception) {
            preserveUnsentInput(); stop()
            callbacks.onError("The Android speech service could not start. Text input remains available.")
            callbacks.onState(VoiceState.IDLE)
        }
    }

    private fun startRecognition() {
        if (!listening || closed || recognizerRunning) return
        ensureRecognizer()
        val service = recognizer ?: return
        if (!listening) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, localSpeechOnly)
            // Patience is enforced by our turn accumulator across endpoints. Android documents
            // silence-length overrides as provider-dependent; stacking both delays also feels slow.
        }
        try {
            sessionSpeechStarted = false
            acceptedUserSpeech = false
            recognitionEpoch++
            recognitionSegment.begin(recognitionEpoch)
            service.setRecognitionListener(listener(recognitionEpoch))
            recognizerRunning = true
            diagnostics.event("recognizer_start")
            service.startListening(intent)
            if (currentUtterance == null) callbacks.onState(VoiceState.LISTENING)
            // A broken provider must not hold a permanent microphone state with no callbacks.
            armRecognitionWatchdog(recognitionEpoch, 60_000)
        } catch (_: SecurityException) {
            preserveUnsentInput(); stop()
            callbacks.onError("Microphone access was revoked. Enable it to resume voice.")
            callbacks.onState(VoiceState.IDLE)
        } catch (_: Exception) { recognizerRunning = false; handleError(SpeechRecognizer.ERROR_CLIENT) }
    }

    private fun armRecognitionWatchdog(epoch: Int, delay: Long) {
        watchdogRunnable?.let(main::removeCallbacks)
        watchdogRunnable = Runnable {
            if (!closed && listening && recognizerRunning && epoch == recognitionEpoch) {
                diagnostics.event("recognizer_watchdog")
                preserveRecognitionPartial(epoch)
                destroyRecognizer(); scheduleRestart(500)
            }
        }.also { main.postDelayed(it, delay) }
    }

    private fun listener(epoch: Int) = object : RecognitionListener {
        private fun active() = !closed && listening && recognizerRunning && epoch == recognitionEpoch
        override fun onReadyForSpeech(params: Bundle?) { if (active()) { diagnostics.event("recognizer_ready"); if (currentUtterance == null) callbacks.onState(VoiceState.LISTENING) } }
        override fun onBeginningOfSpeech() { if (active()) { sessionSpeechStarted = true; cancelCommit(); armRecognitionWatchdog(epoch, 60_000) } }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            if (active()) {
                sessionSpeechStarted = false
                // Wait for the provider's final result before committing an older fragment.
                // If that result never arrives, retain the accepted partial and reopen cleanly.
                armRecognitionWatchdog(epoch, 2500)
            }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) {
            if (!active()) return
            val raw = recognized(partialResults)
            val partial = SpeechEchoGuard.userTranscript(raw, echoReference(), suppressPureEcho = !acceptedUserSpeech)
            if (partial.isBlank()) { if (raw.isNotBlank()) diagnostics.event("echo_rejected"); return }
            val firstUserPartial = !acceptedUserSpeech
            if (currentUtterance != null) {
                if (!SpeechEchoGuard.hasBargeInEvidence(partial, echoReference())) return
                // Capture before notifying VM/cancelling the reply. Never restart the recognizer
                // here: it is currently recording the new user utterance that caused interruption.
                recognitionSegment.acceptPartial(epoch, partial)
                acceptedUserSpeech = true
                diagnostics.event("barge_in")
                stopSpeakingInternal()
                callbacks.onBargeIn()
            } else if (firstUserPartial) callbacks.onBargeIn()
            acceptedUserSpeech = true
            recognitionSegment.acceptPartial(epoch, partial)
            commitClock.input(SystemClock.elapsedRealtime())
            armRecognitionWatchdog(epoch, 60_000)
            cancelCommit()
            callbacks.onPartial(accumulator.preview(partial))
        }
        override fun onResults(results: Bundle?) {
            if (!active()) return
            finishRecognition()
            retryCount = 0
            val raw = recognized(results)
            val clean = SpeechEchoGuard.userTranscript(raw, echoReference(), suppressPureEcho = !acceptedUserSpeech)
            val result = recognitionSegment.finish(epoch, clean)
            if (result.isNotBlank()) {
                if (currentUtterance != null) { stopSpeakingInternal(); callbacks.onBargeIn() }
                diagnostics.event("user_endpoint")
                if (accumulator.appendFinal(result, SystemClock.elapsedRealtime())) commitClock.input(SystemClock.elapsedRealtime())
                callbacks.onPartial(accumulator.text)
                scheduleCommit()
            } else if (!accumulator.isEmpty) scheduleCommit()
            scheduleRestart(180)
        }
        override fun onError(error: Int) {
            if (active()) {
                finishRecognition()
                // Some providers emit a useful partial, then NO_MATCH or TIMEOUT after a
                // barge-in. Preserve those words instead of silently erasing the user's turn.
                preserveRecognitionPartial(epoch)
                handleError(error)
            }
        }
    }

    private fun recognized(bundle: Bundle?): String = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
    private fun finishRecognition() { recognizerRunning = false; sessionSpeechStarted = false; watchdogRunnable?.let(main::removeCallbacks); watchdogRunnable = null }

    private fun preserveRecognitionPartial(epoch: Int) {
        val partial = recognitionSegment.finish(epoch)
        if (partial.isNotBlank()) {
            accumulator.appendFinal(partial, SystemClock.elapsedRealtime())
            commitClock.input(SystemClock.elapsedRealtime())
            callbacks.onPartial(accumulator.text)
            scheduleCommit()
        }
    }

    private fun handleError(code: Int) {
        if (!listening || closed) return
        diagnostics.event("recognizer_error", code.toLong())
        if (!accumulator.isEmpty) scheduleCommit()
        if (!localSpeechOnly && usingOnDeviceRecognizer && code in setOf(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)) {
            forcePlatformRecognizer = true
            destroyRecognizer()
            scheduleRestart(400)
            return
        }
        when (code) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> { retryCount = 0; scheduleRestart(450) }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                preserveUnsentInput(); stop()
                callbacks.onError("Allow microphone access to use voice.")
            }
            else -> {
                retryCount++
                if (retryCount > 3) {
                    preserveUnsentInput(); stop()
                    callbacks.onError("Speech recognition is unavailable right now. Tap the mic to retry, or use text.")
                } else {
                    destroyRecognizer()
                    scheduleRestart((500L * retryCount).coerceAtMost(2000))
                }
            }
        }
    }

    private fun scheduleCommit() {
        cancelCommit()
        if (accumulator.isEmpty) return
        commitRunnable = Runnable {
            commitRunnable = null
            if (!listening || closed || sessionSpeechStarted) return@Runnable
            val text = accumulator.take()
            if (text.isBlank()) return@Runnable
            commitClock.clear()
            diagnostics.event("turn_commit", text.length.toLong())
            callbacks.onPartial("")
            callbacks.onState(VoiceState.THINKING)
            callbacks.onUtterance(text)
        }.also { main.postDelayed(it, commitClock.remaining(SystemClock.elapsedRealtime(), accumulator.delayMillis(patience))) }
    }

    private fun scheduleRestart(delay: Long) {
        cancelRestart()
        if (!listening || closed) return
        restartRunnable = Runnable { startRecognition() }.also { main.postDelayed(it, delay) }
    }
    private fun cancelRestart() { restartRunnable?.let(main::removeCallbacks); restartRunnable = null }
    private fun cancelCommit() { commitRunnable?.let(main::removeCallbacks); commitRunnable = null }
    private fun destroyRecognizer() {
        recognitionEpoch++
        recognizerRunning = false
        sessionSpeechStarted = false
        recognitionSegment.clear()
        watchdogRunnable?.let(main::removeCallbacks); watchdogRunnable = null
        try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }
    private fun voiceOptions(): List<VoiceOption> {
        val ordinals = mutableMapOf<String, Int>()
        return LocalVoiceCatalog.VOICES.map { VoiceOption(it.id, it.name, "en-US", false, 500) } +
            tts?.voices.orEmpty()
            .filter { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features }
            .sortedWith(compareBy<android.speech.tts.Voice> { it.locale.language != locale.language }
                .thenBy { it.isNetworkConnectionRequired }.thenByDescending { it.quality }.thenBy { it.name })
            .map { voice ->
                val language = voice.locale.toLanguageTag()
                val ordinal = (ordinals[language] ?: 0) + 1
                ordinals[language] = ordinal
                VoiceOption(voice.name, "${voice.locale.displayName} · Voice $ordinal", language, voice.isNetworkConnectionRequired, voice.quality)
            }
    }
    private fun publishVoices() = callbacks.onVoicesChanged(voiceOptions())
    private fun echoReference(): String = if (currentUtterance != null) speech?.echoReference(includeCurrent = speechChunkAudible).orEmpty()
        else if (SystemClock.elapsedRealtime() - lastSpeechEndedAt in 0..1500) lastSpeechText else ""
    private fun preserveUnsentInput() {
        val partial = recognitionSegment.finish(recognitionEpoch)
        val text = accumulator.preview(partial)
        if (text.isNotBlank()) callbacks.onUnsentTranscript(text)
    }
    private fun releaseAudioFocus() {
        if (hasAudioFocus) audioManager.abandonAudioFocusRequest(focusRequest)
        hasAudioFocus = false
    }
    private fun postCurrent(id: String?, block: (StreamingSpeechSession) -> Unit) {
        main.post { speech?.takeIf { !closed && it.accepts(id) }?.let(block) }
    }
    private fun onMain(block: () -> Unit): Unit { if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block) }

    override fun close() = onMain {
        if (closed) return@onMain
        listening = false
        cancelCommit(); cancelRestart(); destroyRecognizer()
        stopSpeakingInternal()
        closed = true
        if (receiverRegistered) { appContext.unregisterReceiver(noisyReceiver); receiverRegistered = false }
        tts?.shutdown(); tts = null; ttsReady = false
        local.close()
        audioRoute.close()
        accumulator.clear()
    }
}
