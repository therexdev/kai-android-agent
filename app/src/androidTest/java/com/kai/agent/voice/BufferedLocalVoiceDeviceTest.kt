package com.kai.agent.voice

import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android AudioTrack + recorded native Kiki PCM; synthesis is a controlled fixture. */
@RunWith(AndroidJUnit4::class)
class BufferedLocalVoiceDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val main = Handler(Looper.getMainLooper())
    private val voice = "kai:kitten:kiki"
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    private class Request(val id: Int, val text: String, val voice: String, val callback: PocketClient.Callback) {
        val at = SystemClock.elapsedRealtime()
    }
    private inner class Source(var automatic: Boolean = true, val failAt: Int = -1) : NativeSpeechSource {
        val requests = CopyOnWriteArrayList<Request>()
        val cancelled = CopyOnWriteArrayList<Int>()
        val warmups = CopyOnWriteArrayList<Request>()
        var released = 0
        var next = 0
        override fun warm(voice: String, callback: PocketClient.Callback): Int {
            val request = Request(++next, "", voice, callback); warmups += request
            if (automatic) main.postDelayed({ request.callback.ready(null, 0, 24000, 0) }, 35)
            return request.id
        }
        override fun synthesize(text: String, voice: String, callback: PocketClient.Callback): Int {
            val request = Request(++next, text, voice, callback); requests += request
            if (automatic) main.postDelayed({
                if (!PocketVoiceService.valid(text, "natural", 9)) callback.error("fixture service rejected an empty speech segment")
                else if (requests.indexOf(request) + 1 == failAt) callback.error("fixture generation failure")
                else complete(request)
            }, 90)
            return request.id
        }
        fun complete(request: Request): ParcelFileDescriptor {
            val bytes = instrumentation.context.assets.open("kiki-playback.pcm").use { it.readBytes() }
            val file = File.createTempFile("kiki-playback", ".pcm", context.cacheDir).apply { writeBytes(bytes) }
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY); file.delete()
            request.callback.ready(fd, bytes.size / 2, 24000, 90)
            return fd
        }
        override fun cancel(request: Int) { cancelled += request }
        override fun release() { released++ }
        override fun close() { release() }
    }
    private inner class Rig(val source: Source) : AutoCloseable {
        val opens = AtomicInteger()
        val errors = CopyOnWriteArrayList<String>()
        val finished = CopyOnWriteArrayList<Boolean>()
        val progress = CopyOnWriteArrayList<Pair<Int,Long>>()
        val heard = AtomicInteger()
        lateinit var player: BufferedLocalPlayer
        lateinit var controller: VoiceController
        init { onMain {
            player = BufferedLocalPlayer(main, source) { speed, attrs ->
                opens.incrementAndGet(); BufferedLocalPlayer.ANDROID_OUTPUT.open(speed, attrs)
            }
            controller = VoiceController(context, object : VoiceController.Callbacks {
                override fun onError(message: String) { errors += message }
                override fun onPlaybackProgress(utteranceId: String, spokenCharacters: Int) {
                    heard.set(spokenCharacters); progress += spokenCharacters to SystemClock.elapsedRealtime()
                }
                override fun onSpeechFinished(utteranceId: String, interrupted: Boolean) { finished += interrupted }
            }, instrumentation.context.packageName, player)
            controller.setVoice(voice); controller.setLocalSpeechOnly(true)
        } }
        override fun close() { onMain { controller.close() } }
    }

    @Test fun preparesNextSentenceDuringPlaybackAndReusesAudioOutputForWholeReply() {
        Rig(Source()).use { rig ->
            val text = "First sentence. Second sentence. Third sentence."
            onMain { rig.controller.speak(text, "reply") }
            waitFor { rig.finished.isNotEmpty() }
            assertEquals(listOf(false), rig.finished.toList()); assertTrue(rig.errors.toString(), rig.errors.isEmpty())
            assertEquals(text.length, rig.heard.get()); assertEquals(1, rig.opens.get())
            assertEquals(text, rig.source.requests.joinToString("") { it.text })
            val firstDone = rig.progress.first { it.first == "First sentence.".length }.second
            val secondDone = rig.progress.first { it.first == "First sentence. Second sentence.".length }.second
            assertTrue("Sentence two waited until playback completed", rig.source.requests[1].at < firstDone)
            assertTrue("Sentence three was not prepared during sentence two", rig.source.requests[2].at < secondDone)
        }
    }

    @Test fun markdownAddressesAndLinksReachKikiAsCleanSpeechWithOriginalSourceProgress() {
        Rig(Source()).use { rig ->
            val raw = "Confirm **reader_name@example.com**. Open [the guide](https://example.com/help_(start))."
            onMain { rig.controller.speak(raw, "markdown") }
            waitFor { rig.finished.isNotEmpty() }
            assertEquals(listOf(false), rig.finished.toList()); assertTrue(rig.errors.toString(), rig.errors.isEmpty())
            assertEquals("Confirm reader_name@example.com. Open the guide.", rig.source.requests.joinToString("") { it.text })
            assertEquals(raw.length, rig.heard.get()); assertEquals(1, rig.opens.get())
            assertTrue(rig.source.requests.none { it.text.contains("**") || it.text.contains("https") || it.text.contains('[') })
        }
    }

    @Test fun interruptionOfFormattedSpeechCannotCreditThePreparedLinkOrTail() {
        val source = Source(automatic = false)
        Rig(source).use { rig ->
            val raw = "**First phrase.** [Prepared second phrase](https://example.com/private). Never heard."
            onMain { rig.controller.speak(raw, "formatted-stop") }
            onMain { source.warmups.last().callback.ready(null, 0, 24000, 0) }
            waitFor { source.requests.size == 1 }
            onMain { source.complete(source.requests.first()) }
            val firstEnd = raw.indexOf(".**") + 1
            waitFor { rig.heard.get() == firstEnd && source.requests.size == 2 }
            onMain { rig.controller.stopSpeaking() }
            assertEquals(listOf(true), rig.finished.toList()); assertEquals(firstEnd, rig.heard.get())
            assertTrue(rig.heard.get() < raw.indexOf("Prepared"))
            val late = AtomicReference<ParcelFileDescriptor>()
            onMain { late.set(source.complete(source.requests[1])) }
            assertFalse(late.get().fileDescriptor.valid())
        }
    }

    @Test fun storyParagraphsAndBlankLinesContinueBeyondTheFirstFewSentences() {
        Rig(Source()).use { rig ->
            val text = "Every night, someone stole one shoe from outside the cobbler’s shop.\n\n" +
                "On the fourth night, Nell hid behind the counter with a lantern.\n\n" +
                "At midnight, a small visitor appeared.\r\n\r\n" +
                "The visitor returned the shoe.\n\nNell smiled. The story continued."
            onMain { rig.controller.speak(text, "story") }
            waitFor { rig.finished.isNotEmpty() }
            assertEquals(listOf(false), rig.finished.toList())
            assertTrue(rig.errors.toString(), rig.errors.isEmpty())
            assertEquals(text.length, rig.heard.get()); assertEquals(1, rig.opens.get())
            assertTrue(rig.source.requests.size >= 6)
            assertTrue(rig.source.requests.all { it.text.isNotBlank() })
            assertEquals(text, rig.source.requests.joinToString("") { it.text })
        }
    }

    @Test fun kikiIsTheDefaultAndOnlyBundledVoiceWhileRetiredChoicesResolveToKiki() {
        Rig(Source()).use { rig ->
            assertEquals(listOf(voice), rig.controller.voices().filter { it.id.startsWith("kai:") }.map { it.id })
            val old = listOf("kai:azelma:cute", "kai:device:cute", "kai:device:cartoon", "kai:device:squeaky", "kai:kitten:rosie", "kai:kokoro:heart")
            old.forEachIndexed { index, id ->
                onMain { rig.controller.setVoice(id); rig.controller.speak("Ready.", "legacy-$index") }
                waitFor { rig.finished.size == index + 1 }
                assertFalse(rig.finished.last()); assertEquals(voice, rig.source.requests.last().voice)
            }
            assertTrue(rig.errors.toString(), rig.errors.isEmpty())
        }
    }

    @Test fun prefetchedFailureWaitsForPlayingSentenceAndNeverCommitsFailedText() {
        Rig(Source(failAt = 2)).use { rig ->
            onMain { rig.controller.speak("First sentence. Failed second sentence. Never heard.", "reply") }
            waitFor { rig.finished.isNotEmpty() }
            assertEquals(listOf(true), rig.finished.toList())
            assertEquals("First sentence.".length, rig.heard.get())
            assertEquals(2, rig.source.requests.size); assertEquals(1, rig.errors.size)
        }
    }

    @Test fun newlyStreamedSentenceStartsPreparingBeforeCurrentPlaybackCompletes() {
        Rig(Source()).use { rig ->
            onMain {
                rig.controller.beginSpeech("stream")
                rig.controller.appendSpeech("stream", "First sentence. ")
            }
            waitFor { rig.source.requests.isNotEmpty() }
            onMain {
                rig.controller.appendSpeech("stream", "Second sentence.")
                rig.controller.endSpeech("stream")
            }
            waitFor { rig.finished.isNotEmpty() }
            assertEquals(listOf(false), rig.finished.toList()); assertEquals(1, rig.opens.get())
            assertEquals("First sentence. Second sentence.".length, rig.heard.get())
            val firstDone = rig.progress.first { it.first == "First sentence.".length }.second
            assertTrue(rig.source.requests[1].at < firstDone)
            assertTrue(rig.errors.toString(), rig.errors.isEmpty())
        }
    }

    @Test fun unsupportedPreparedLanguageFailsInOrderWithoutHangingOrClaimingItHeard() {
        Rig(Source()).use { rig ->
            onMain { rig.controller.speak("First sentence. 日本語. Never heard.", "reply") }
            waitFor { rig.finished.isNotEmpty() }
            assertEquals(listOf(true), rig.finished.toList())
            assertEquals("First sentence.".length, rig.heard.get())
            assertEquals(1, rig.source.requests.size); assertEquals(1, rig.errors.size)
        }
    }

    @Test fun interruptionCancelsPrefetchClosesLateAudioAndAllowsReplacement() {
        val source = Source(automatic = false)
        Rig(source).use { rig ->
            onMain { rig.controller.speak("First sentence. Prepared next sentence.", "old") }
            onMain { source.warmups.last().callback.ready(null, 0, 24000, 0) }
            waitFor { source.requests.size == 1 }
            onMain { source.complete(source.requests.first()) }
            waitFor { source.requests.size == 2 && rig.opens.get() == 1 }
            onMain { rig.controller.stopSpeaking() }
            assertEquals(listOf(true), rig.finished.toList()); assertEquals(0, rig.heard.get())
            assertTrue(source.cancelled.contains(source.requests[1].id))
            val late = AtomicReference<ParcelFileDescriptor>()
            onMain { late.set(source.complete(source.requests[1])) }
            assertFalse("Stale descriptor leaked", late.get().fileDescriptor.valid())
            source.automatic = true
            onMain { rig.controller.speak("Replacement.", "new") }
            waitFor { rig.finished.size == 2 }
            assertEquals(listOf(true, false), rig.finished.toList()); assertEquals("Replacement.".length, rig.heard.get())
            assertTrue(rig.errors.toString(), rig.errors.isEmpty())
        }
    }

    @Test fun voiceSwitchReleasesOldWorkerAndRejectsItsLateAudio() {
        val source = Source(automatic = false)
        Rig(source).use { rig ->
            onMain { rig.controller.speak("Old voice must stop.", "old") }
            onMain { source.warmups.last().callback.ready(null, 0, 24000, 0) }
            waitFor { source.requests.isNotEmpty() }
            val before = source.released
            onMain { rig.controller.setVoice("fixture-fast") }
            assertTrue(source.released > before); assertEquals(listOf(true), rig.finished.toList())
            val late = AtomicReference<ParcelFileDescriptor>()
            onMain { late.set(source.complete(source.requests.first())) }
            assertFalse(late.get().fileDescriptor.valid()); assertEquals(0, rig.heard.get())
            source.automatic = true
            onMain { rig.controller.setVoice(voice); rig.controller.speak("New voice.", "new") }
            waitFor { rig.finished.size == 2 }
            assertEquals(voice, source.requests.last().voice)
            assertEquals(listOf(true, false), rig.finished.toList())
        }
    }

    @Test fun warmupIsSilentSurvivesReplyStartAndFullStopReleasesIt() {
        val source = Source(automatic = false)
        val reference = AtomicReference<BufferedLocalPlayer>(); val opened = AtomicInteger()
        onMain {
            reference.set(BufferedLocalPlayer(main, source) { speed, attrs ->
                opened.incrementAndGet(); BufferedLocalPlayer.ANDROID_OUTPUT.open(speed, attrs)
            })
            reference.get().warm(voice)
            reference.get().begin(voice, 1f, attributes)
            reference.get().enqueue("Ready.", voice, object : LocalSpeech.Callback {
                override fun started() {}
                override fun finished() {}
                override fun failed(message: String) { fail(message) }
            })
        }
        try {
            assertEquals(1, source.warmups.size); assertEquals(0, source.requests.size); assertEquals(0, opened.get())
            onMain { source.warmups.first().callback.ready(null, 0, 24000, 0) }
            assertEquals(1, source.requests.size)
            onMain { reference.get().release() }
            assertEquals(1, source.released); assertTrue(source.cancelled.contains(source.requests.first().id))
            val late = AtomicReference<ParcelFileDescriptor>()
            onMain { late.set(source.complete(source.requests.first())) }
            assertFalse(late.get().fileDescriptor.valid()); assertEquals(0, opened.get())
        } finally { onMain { reference.get().close() } }
    }

    @Test fun blankStreamingReplyAndTrailingWhitespaceFinishWithoutStalling() {
        Rig(Source()).use { rig ->
            onMain {
                rig.controller.beginSpeech("blank")
                rig.controller.appendSpeech("blank", "   ")
                rig.controller.endSpeech("blank")
            }
            waitFor { rig.finished.size == 1 }
            assertEquals(0, rig.opens.get()); assertEquals(0, rig.heard.get())
            onMain { rig.controller.speak("One. Two.   ", "spaces") }
            waitFor { rig.finished.size == 2 }
            assertEquals(listOf(false, false), rig.finished.toList())
            assertEquals("One. Two.".length, rig.heard.get()); assertEquals(1, rig.opens.get())
            assertTrue(rig.errors.toString(), rig.errors.isEmpty())
        }
    }
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun waitFor(predicate: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(35)
        while (!predicate() && System.nanoTime() < end) Thread.sleep(20)
        assertTrue("Timed out waiting for buffered speech", predicate())
    }
}
