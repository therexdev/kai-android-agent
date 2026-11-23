package com.kai.agent.voice

import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real bundled native inference on a disposable emulator, not a mocked speech service. */
@RunWith(AndroidJUnit4::class)
class LocalVoicesDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun emulatorOnly() = assumeTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    @Test fun shortKittenNativeProbe() = shortProbe("kai:kitten:kiki")
    private fun shortProbe(id: String) {
        emulatorOnly()
        val done=CountDownLatch(1);val failure=AtomicReference<String?>()
        val handler=Handler(Looper.getMainLooper());lateinit var client:PocketClient
        main {
            client=PocketClient(context,handler,600000)
            client.request("Hi.",false,"natural",9,id,object:PocketClient.Callback {
                override fun ready(audio:ParcelFileDescriptor?,frames:Int,rate:Int,elapsed:Long) {
                    try {
                        assertEquals(24000,rate);assertNotNull(audio)
                        val pcm=ParcelFileDescriptor.AutoCloseInputStream(audio!!).use{it.readBytes()}
                        assertEquals(frames*2,pcm.size);assertTrue(pcm.any{it!=0.toByte()})
                        File(context.cacheDir,"probe-${LocalVoiceCatalog.find(id)!!.family}.pcm").writeBytes(pcm)
                        println("Native $id: frames=$frames, synthesisMs=$elapsed")
                    }catch(error:Throwable){failure.set(error.toString())}finally{done.countDown()}
                }
                override fun error(message:String){failure.set(message);done.countDown()}
            })
        }
        try {
            assertTrue("Native probe timed out: $id",done.await(650,TimeUnit.SECONDS))
            assertNull(failure.get(),failure.get())
        }finally{handler.post{client.close()}}
    }

    @Test fun bundledKikiProducesNonSilentAudio() {
        emulatorOnly()
        val main = Handler(Looper.getMainLooper())
        lateinit var client: PocketClient
        main { client = PocketClient(context, main, 600000) }
        val hashes = mutableSetOf<String>()
        val metrics = StringBuilder("Synthetic English sample; software emulator timings are not phone benchmarks.\n")
        try {
            for (voice in LocalVoiceCatalog.VOICES) {
                val done = CountDownLatch(1)
                val failure = AtomicReference<String?>()
                main { client.request("Hello there.", false, "natural", 9, voice.id, object : PocketClient.Callback {
                    override fun ready(audio: ParcelFileDescriptor?, frames: Int, rate: Int, elapsed: Long) {
                        try {
                            assertNotNull(audio); assertEquals(24000, rate)
                            val pcm = ParcelFileDescriptor.AutoCloseInputStream(audio!!).use { it.readBytes() }
                            assertEquals(frames * 2, pcm.size); assertTrue(pcm.size > 480)
                            assertTrue(pcm.any { it != 0.toByte() })
                            val hash = MessageDigest.getInstance("SHA-256").digest(pcm).joinToString("") { "%02x".format(it) }
                            assertTrue("Duplicate audio for ${voice.name}", hashes.add(hash))
                            File(context.cacheDir, "test-${voice.family}-${voice.speaker}.pcm").writeBytes(pcm)
                            metrics.append("${voice.name}: frames=$frames, synthesisMs=$elapsed, sha256=$hash\n")
                        } catch (error: Throwable) { failure.set(error.toString()) }
                        finally { done.countDown() }
                    }
                    override fun error(message: String) { failure.set(message); done.countDown() }
                }) }
                assertTrue("${voice.name} timed out", done.await(650, TimeUnit.SECONDS))
                assertNull(failure.get(), failure.get())
            }
            assertEquals(1, hashes.size)
        } finally {
            File(context.cacheDir, "local-voice-metrics.txt").writeText(metrics.toString())
            main { client.close() }
        }
    }

    @Test fun kikiPlaysWithoutAndroidTtsAndCompletesOnlyHeardText() {
        emulatorOnly()
        for (id in listOf("kai:kitten:kiki")) {
            val done = CountDownLatch(1); val failure = AtomicReference<String?>()
            val heard = AtomicInteger(); val started = AtomicBoolean(); val active = AtomicBoolean()
            lateinit var controller: VoiceController
            main {
                controller = VoiceController(context, object : VoiceController.Callbacks {
                    override fun onError(message: String) { failure.set(message); done.countDown() }
                    override fun onState(state: VoiceState) { if (state == VoiceState.SPEAKING) started.set(true) }
                    override fun onPlaybackProgress(utteranceId: String, spokenCharacters: Int) { heard.set(spokenCharacters) }
                    override fun onSpeechFinished(utteranceId: String, interrupted: Boolean) {
                        if (!active.get()) return
                        if (interrupted) failure.set("Interrupted $id")
                        done.countDown()
                    }
                }, "com.kai.fixture.missing.tts")
                controller.setVoice(id); controller.setLocalSpeechOnly(true)
                active.set(true); controller.speak("Hello.", "native-playback-$id")
            }
            try {
                assertTrue("$id playback timed out", done.await(150, TimeUnit.SECONDS))
                assertNull(failure.get(), failure.get()); assertTrue("No playback start for $id", started.get())
                assertEquals("Hello.".length, heard.get())
            } finally { main { controller.close() } }
        }
    }

    @Test fun stoppingNativePreparationCannotMarkUnheardTextAsPlayed() {
        emulatorOnly()
        val heard = AtomicInteger(); val completions = mutableListOf<Boolean>()
        lateinit var controller: VoiceController
        main {
            controller = VoiceController(context, object : VoiceController.Callbacks {
                override fun onPlaybackProgress(utteranceId: String, spokenCharacters: Int) { heard.set(spokenCharacters) }
                override fun onSpeechFinished(utteranceId: String, interrupted: Boolean) { completions += interrupted }
            })
            controller.setVoice("kai:kitten:kiki")
            controller.speak("This must be cancelled before any audio plays.", "cancel-native")
            controller.stop()
        }
        try {
            Thread.sleep(1200)
            main { assertEquals(listOf(true), completions); assertEquals(0, heard.get()) }
        } finally { main { controller.close() } }
    }
}
