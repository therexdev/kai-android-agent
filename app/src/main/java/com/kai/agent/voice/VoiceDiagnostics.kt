package com.kai.agent.voice

import org.json.JSONArray
import org.json.JSONObject

/** Opt-in, bounded numeric events. Never accepts audio, transcripts, account IDs or device names. */
internal class VoiceDiagnostics(private val clock: () -> Long) {
    private data class Event(val at: Long, val name: String, val first: Long, val second: Long)
    private val events = ArrayDeque<Event>()
    private val counts = linkedMapOf<String, Long>()
    private var started = 0L
    var enabled = false
        private set
    fun start() { clear(); started = clock(); enabled = true }
    fun clear() { enabled = false; started = 0; events.clear(); counts.clear() }
    fun event(name: String, first: Long = 0, second: Long = 0) {
        if (!enabled) return
        require(name in names)
        counts[name] = (counts[name] ?: 0) + 1
        if (events.size == 1200) events.removeFirst()
        events.addLast(Event((clock() - started).coerceAtLeast(0), name, first, second))
    }
    fun report(sdk: Int, version: String): String = JSONObject()
        .put("format", "kai-voice-test-1").put("app_version", version).put("android_sdk", sdk)
        .put("elapsed_ms", if (enabled) (clock() - started).coerceAtLeast(0) else 0)
        .put("recording_audio", false).put("includes_transcripts", false)
        .put("counts", JSONObject(counts as Map<*, *>))
        .put("events", JSONArray().apply { events.forEach {
            put(JSONObject().put("at_ms", it.at).put("event", it.name).put("first", it.first).put("second", it.second))
        } }).toString(2)
    companion object {
        private val names = setOf("listening_start", "listening_stop", "recognizer_start", "recognizer_error",
            "recognizer_watchdog", "recognizer_ready", "user_endpoint", "turn_commit", "echo_rejected",
            "reply_begin", "reply_finish", "barge_in", "synthesis", "playback_start", "playback_gap",
            "output_stop", "route_requested", "route_ready", "route_lost", "audio_focus_loss")
    }
}
