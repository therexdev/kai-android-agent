package com.kai.agent.voice

import android.content.*
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kai.agent.data.DesktopVoiceDefaults
import com.kai.agent.data.PlayfulVoiceDefaults
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationVoiceDeviceTest {
    @Test fun platformVoiceReceivesReadableMarkdownAndReportsOriginalTranscriptLength()=withController { controller,events,heard,finished,errors ->
        val raw="Read **bold words** and [the guide](https://example.com/help)."
        main {controller.setVoice("fixture-fast");controller.setLanguage("en-US");controller.speak(raw,"markdown-platform")}
        waitFor {finished.isNotEmpty()}
        assertEquals(listOf(false),finished.toList());assertTrue(errors.toString(),errors.isEmpty())
        assertEquals("Read bold words and the guide.",events.joinToString(""){it.getStringExtra("text").orEmpty()})
        assertEquals(raw.length,heard.get())
    }
    @Test fun platformVoiceUsesNormalPitchUserSpeedAndCompletesTranscript()=withController { controller,events,heard,finished,errors ->
        val text="First sentence. Second sentence. Third sentence."
        main {controller.setVoice("fixture-fast");controller.setLanguage("en-US");controller.speak(text,"reply")}
        waitFor{finished.isNotEmpty()}
        assertTrue(errors.toString(),errors.isEmpty())
        assertEquals(listOf(false),finished.toList());assertEquals(text.length,heard.get())
        assertEquals(text,events.joinToString(""){it.getStringExtra("text").orEmpty()})
        assertTrue(events.all{it.getIntExtra("pitch",0) == 100 && it.getIntExtra("rate",0) == 100})
        assertTrue(events.all{it.getStringExtra("voice")=="fixture-fast"})
    }
    @Test fun synthesisFailureStopsWithoutClaimingUnheardText()=withController {controller,events,heard,finished,errors ->
        val text="A first phrase. FAIL the next phrase. Never spoken."
        main {controller.setVoice("fixture-fast");controller.setLanguage("en-US");controller.speak(text,"reply")}
        waitFor{finished.isNotEmpty()}
        assertEquals(listOf(true),finished.toList());assertTrue(errors.toString(),errors.isNotEmpty())
        assertTrue(heard.get()<text.length)
        assertFalse(events.any{it.getStringExtra("text")?.contains("Never spoken")==true})
    }
    @Test fun explicitStopCancelsCurrentPhraseAndReplacementRejectsOldCallbacks()=withController {controller,events,heard,finished,errors ->
        main {controller.setVoice("fixture-fast");controller.setLanguage("en-US");controller.speak("First sentence. Queued next sentence. More never heard.","old")}
        waitFor{events.isNotEmpty()}
        main{controller.stopSpeaking()}
        waitFor{finished.isNotEmpty()};assertEquals(true,finished.first());assertTrue(heard.get()<54)
        main{controller.speak("Replacement.","new")}
        waitFor{finished.size==2}
        assertEquals(listOf(true,false),finished.toList());assertEquals("Replacement.".length,heard.get())
        assertTrue(errors.toString(),errors.isEmpty())
    }
    @Test fun longPreviewKeepsSentenceBoundariesAndSelectedSpeedIsNotMultiplied()=withController {controller,events,heard,finished,errors ->
        val firstSentence="A complete answer with enough context should keep its natural sentence together rather than being cut into a tiny first clip."
        val text=firstSentence+" Next sentence."
        main {controller.setVoice("fixture-fast");controller.setLanguage("en-US");controller.setRate(1.25f);controller.speak(text,"preview")}
        waitFor {finished.isNotEmpty()}
        assertEquals(listOf(false),finished.toList());assertTrue(errors.toString(),errors.isEmpty())
        assertEquals(2,events.size);assertEquals(firstSentence,events.first().getStringExtra("text"))
        assertEquals(text,events.joinToString(""){it.getStringExtra("text").orEmpty()})
        assertTrue(events.all {it.getIntExtra("rate",0)==125 && it.getIntExtra("pitch",0)==100})
        assertEquals(text.length,heard.get())
        assertFalse(controller.voices().any {it.id=="kai:conversation:local"})
        assertFalse(controller.voices().any {it.id==DesktopVoiceDefaults.VOICE_ID})
        assertTrue(controller.voices().any {it.id==LocalVoiceCatalog.KIKI})
    }
    private fun withController(body:(VoiceController,CopyOnWriteArrayList<Intent>,AtomicInteger,CopyOnWriteArrayList<Boolean>,CopyOnWriteArrayList<String>)->Unit) {
        assumeTrue(android.os.Build.HARDWARE in setOf("ranchu","goldfish") || android.os.Build.FINGERPRINT.contains("generic"))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val events=CopyOnWriteArrayList<Intent>();val finished=CopyOnWriteArrayList<Boolean>();val errors=CopyOnWriteArrayList<String>();val heard=AtomicInteger()
        val ready=CountDownLatch(1);val reference=AtomicReference<VoiceController>()
        val receiver=object:BroadcastReceiver(){override fun onReceive(context:Context?,intent:Intent?){if(intent!=null)events+=Intent(intent)}}
        ContextCompat.registerReceiver(context,receiver,IntentFilter(FixtureTtsService.ACTION),ContextCompat.RECEIVER_EXPORTED)
        try {
            main {reference.set(VoiceController(context,object:VoiceController.Callbacks {
                override fun onVoicesChanged(voices:List<VoiceOption>){if(voices.any{it.id=="fixture-fast"})ready.countDown()}
                override fun onError(message:String){errors+=message}
                override fun onPlaybackProgress(utteranceId:String,spokenCharacters:Int){heard.set(spokenCharacters)}
                override fun onSpeechFinished(utteranceId:String,interrupted:Boolean){finished+=interrupted}
            },InstrumentationRegistry.getInstrumentation().context.packageName))}
            assertTrue("Fixture TTS did not initialize: $errors",ready.await(45,TimeUnit.SECONDS))
            body(reference.get(),events,heard,finished,errors)
        } finally {main{reference.get()?.close()};context.unregisterReceiver(receiver)}
    }
    private fun main(block:()->Unit)=InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun waitFor(predicate:()->Boolean) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(40)
        while(!predicate() && System.nanoTime()<end)Thread.sleep(30)
        assertTrue("Timed out waiting for platform speech callback",predicate())
    }
}
