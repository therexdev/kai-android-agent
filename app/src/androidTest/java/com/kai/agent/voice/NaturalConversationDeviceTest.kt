package com.kai.agent.voice

import android.content.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real SpeechRecognizer IPC + controller + AudioTrack; scripted ASR/native PCM, not acoustic testing. */
@RunWith(AndroidJUnit4::class)
class NaturalConversationDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val main = Handler(Looper.getMainLooper())
    private inner class Rig : AutoCloseable {
        val sequence = AtomicInteger()
        val utterances = CopyOnWriteArrayList<String>()
        val errors = CopyOnWriteArrayList<String>()
        val unsent = CopyOnWriteArrayList<String>()
        val finished = CopyOnWriteArrayList<Pair<String, Boolean>>()
        val states = CopyOnWriteArrayList<VoiceState>()
        val bargeIns = AtomicInteger()
        @Volatile var listening = false
        lateinit var controller: VoiceController
        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { sequence.set(intent?.getIntExtra("sequence", 0) ?: 0) }
        }
        init {
            instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.RECORD_AUDIO").close()
            ContextCompat.registerReceiver(context, receiver, IntentFilter(FixtureRecognitionService.STARTED), ContextCompat.RECEIVER_EXPORTED)
            onMain {
                controller = VoiceController(context, object : VoiceController.Callbacks {
                    override fun onUtterance(text: String) { utterances += text }
                    override fun onError(message: String) { errors += message }
                    override fun onState(state: VoiceState) { states += state }
                    override fun onListeningChanged(enabled: Boolean) { listening = enabled }
                    override fun onUnsentTranscript(text: String) { unsent += text }
                    override fun onBargeIn() { bargeIns.incrementAndGet() }
                    override fun onSpeechFinished(utteranceId: String, interrupted: Boolean) { finished += utteranceId to interrupted }
                }, instrumentation.context.packageName, BufferedLocalPlayer(context, main),
                    ComponentName(instrumentation.context.packageName, FixtureRecognitionService::class.java.name))
                controller.setVoice("fixture-fast"); controller.setLanguage("en-US"); controller.startVoiceTest()
                controller.startListening()
            }
            try { waitFor { sequence.get() > 0 } }
            catch (failure: AssertionError) {
                close()
                throw AssertionError("Scripted recognition service did not start: $errors", failure)
            }
        }
        fun command(command: String, text: String = "", error: Int = 0) {
            context.sendBroadcast(Intent(FixtureRecognitionService.COMMAND).setPackage(instrumentation.context.packageName)
                .putExtra("sequence", sequence.get()).putExtra("command", command).putExtra("text", text).putExtra("error", error))
            instrumentation.waitForIdleSync()
        }
        fun endpoint(text: String) { val old = sequence.get(); command("final", text); waitFor { sequence.get() != old } }
        override fun close() { onMain { controller.close() }; context.unregisterReceiver(receiver) }
    }
    @Test fun idleEndpointsDoNotPostponeAnAcceptedTurn() {
        Rig().use { rig ->
            rig.endpoint("Could you explain that?")
            repeat(2) { rig.endpoint("") }
            waitFor { rig.utterances.isNotEmpty() }
            assertEquals(listOf("Could you explain that?"), rig.utterances.toList())
            assertTrue(rig.errors.toString(), rig.errors.isEmpty())
        }
    }
    @Test fun acceptedPartialSurvivesAnEndpointWithoutItsFinalResult() {
        Rig().use { rig ->
            rig.command("begin"); rig.command("partial", "Actually make that tomorrow"); rig.command("end")
            waitFor { rig.utterances.isNotEmpty() }
            assertEquals(listOf("Actually make that tomorrow"), rig.utterances.toList())
            assertTrue(rig.listening)
            var report = ""
            onMain { report = rig.controller.voiceTestReport() }
            assertTrue("Missing-result watchdog did not reopen recognition", JSONObject(report).getJSONObject("counts").getLong("recognizer_start") >= 2)
        }
    }
    @Test fun interruptionStopsLongPlaybackAndKeepsTheWholeCorrection() {
        Rig().use { rig ->
            onMain { rig.controller.speak("A long story continues. ".repeat(100), "long") }
            waitFor { rig.states.contains(VoiceState.SPEAKING) }
            rig.command("begin"); rig.command("partial", "No make that Monday")
            waitFor { rig.finished.any { it.first == "long" } }
            assertEquals(true, rig.finished.first { it.first == "long" }.second)
            rig.command("partial", "No make that Monday after three")
            rig.endpoint("No make that Monday after three")
            waitFor { rig.utterances.isNotEmpty() }
            assertEquals(listOf("No make that Monday after three"), rig.utterances.toList())
            assertEquals(1, rig.bargeIns.get()); assertTrue(rig.listening)
        }
    }
    @Test fun aPreparedButUnspokenSentenceDoesNotSuppressTheUsersWords() {
        Rig().use { rig ->
            onMain { rig.controller.beginSpeech("thinking"); rig.controller.appendSpeech("thinking", "Could you explain that") }
            rig.command("partial", "Could you explain that")
            rig.endpoint("Could you explain that")
            waitFor { rig.utterances.isNotEmpty() }
            assertEquals(listOf("Could you explain that"), rig.utterances.toList())
            assertTrue(rig.finished.contains("thinking" to true))
        }
    }
    @Test fun repeatedTurnsAndExplicitInterruptionKeepOneForegroundSessionRecoverable() {
        Rig().use { rig ->
            repeat(20) { turn ->
                rig.endpoint("This is turn number $turn.")
                waitFor { rig.utterances.size == turn + 1 }
                onMain { rig.controller.speak("A reply that can be stopped. More text follows.", "reply-$turn"); rig.controller.stopSpeaking() }
                waitFor { rig.finished.size == turn + 1 }
                assertTrue(rig.listening)
            }
            assertEquals(20, rig.utterances.distinct().size)
            assertTrue(rig.finished.all { it.second })
            assertTrue(rig.errors.toString(), rig.errors.isEmpty())
            var report = ""
            onMain { report = rig.controller.voiceTestReport(); rig.controller.stop() }
            val counts = JSONObject(report).getJSONObject("counts")
            assertEquals(20L, counts.getLong("turn_commit")); assertEquals(20L, counts.getLong("reply_finish"))
            assertFalse(rig.listening)
        }
    }
    @Test fun fatalRecognitionErrorsLeaveCapturedWordsAsAnUnsentDraft() {
        Rig().use { rig ->
            rig.command("partial", "Keep these captured words")
            rig.command("error", error = SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            waitFor { !rig.listening }
            // A usable partial is retained in the accumulator before the fatal error is handled.
            assertEquals(listOf("Keep these captured words"), rig.unsent.toList())
            assertTrue(rig.utterances.isEmpty())
        }
    }
    @Test fun ongoingDictationSurvivesTheOldSixtySecondStartupLimit() {
        Rig().use { rig ->
            val first = rig.sequence.get()
            rig.command("begin")
            repeat(7) { part ->
                Thread.sleep(10_000)
                rig.command("partial", "This long spoken thought has reached part $part")
                assertEquals("Recognizer was recycled during active dictation", first, rig.sequence.get())
                assertTrue(rig.utterances.isEmpty())
            }
            rig.endpoint("This long spoken thought is now complete.")
            waitFor { rig.utterances.isNotEmpty() }
            assertEquals(listOf("This long spoken thought is now complete."), rig.utterances.toList())
        }
    }
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun waitFor(predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + TimeUnit.SECONDS.toMillis(35)
        while (!predicate() && SystemClock.elapsedRealtime() < end) Thread.sleep(30)
        assertTrue("Timed out waiting for conversation fixture", predicate())
    }
}
