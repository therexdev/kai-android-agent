package com.kai.agent.voice

import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real native Kiki, private Messenger IPC, lookahead and AudioTrack, including paragraph gaps. */
@RunWith(AndroidJUnit4::class)
class KikiNativeStoryDeviceTest {
    @Test fun actualKikiReadsThreeParagraphsWithoutBlankRequestsOrLosingHeardProgress() {
        val i = InstrumentationRegistry.getInstrumentation(); val context = i.targetContext
        val main = Handler(Looper.getMainLooper()); val done = CountDownLatch(1)
        val errors = CopyOnWriteArrayList<String>(); val requests = CopyOnWriteArrayList<String>()
        val heard = AtomicInteger(); val opens = AtomicInteger(); val interrupted = AtomicReference<Boolean>()
        val reference = AtomicReference<VoiceController>()
        val text = "Hi.\n\nHi.\n\nHi."
        i.runOnMainSync {
            // Only the fixture's operation budget is longer for slow software CPU emulation.
            val client = PocketClient(context, main, 600000, 300000)
            val source = object : NativeSpeechSource {
                override fun warm(voice: String, callback: PocketClient.Callback) = client.warm(voice, callback)
                override fun synthesize(text: String, voice: String, callback: PocketClient.Callback): Int {
                    assertTrue(text.isNotBlank()); requests += text
                    return client.request(text, false, "natural", 9, voice, callback)
                }
                override fun cancel(request: Int) = client.cancel(request)
                override fun release() = client.close()
                override fun close() = client.close()
            }
            val player = BufferedLocalPlayer(main, source) { speed, attrs ->
                opens.incrementAndGet(); BufferedLocalPlayer.ANDROID_OUTPUT.open(speed, attrs)
            }
            val controller = VoiceController(context, object : VoiceController.Callbacks {
                override fun onError(message: String) { errors += message; done.countDown() }
                override fun onPlaybackProgress(utteranceId: String, spokenCharacters: Int) { heard.set(spokenCharacters) }
                override fun onSpeechFinished(utteranceId: String, wasInterrupted: Boolean) {
                    interrupted.set(wasInterrupted); done.countDown()
                }
            }, "com.kai.fixture.missing.tts", player)
            reference.set(controller)
            controller.setLocalSpeechOnly(true)
            controller.speak(text, "real-native-paragraphs") // Default must already be Kiki.
        }
        try {
            assertTrue("Native Kiki story timed out; software emulator is not a phone benchmark", done.await(650, TimeUnit.SECONDS))
            assertTrue(errors.toString(), errors.isEmpty()); assertEquals(false, interrupted.get())
            assertEquals(text.length, heard.get()); assertEquals(text, requests.joinToString(""))
            assertEquals(3, requests.size); assertEquals(1, opens.get())
        } finally { i.runOnMainSync { reference.get()?.close() } }
    }
}
