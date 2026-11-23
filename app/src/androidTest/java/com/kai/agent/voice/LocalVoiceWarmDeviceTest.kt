package com.kai.agent.voice

import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual model loading and Messenger warm protocol; no synthetic speech or phone latency claim. */
@RunWith(AndroidJUnit4::class)
class LocalVoiceWarmDeviceTest {
    @Test fun kittenWarmsSilentlyAndCanBeReusedWithoutRebinding() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val service = context.packageManager.getServiceInfo(ComponentName(context, PocketVoiceService::class.java), 0)
        assertFalse(service.exported); assertTrue(service.processName.endsWith(":desktop_voice"))
        val main = Handler(Looper.getMainLooper())
        val reference = AtomicReference<PocketClient>()
        instrumentation.runOnMainSync { reference.set(PocketClient(context, main, 600000, 300000)) }
        val client = reference.get()
        try {
            repeat(2) {
                val done = CountDownLatch(1); val error = AtomicReference<String?>()
                instrumentation.runOnMainSync {
                    client.warm("kai:kitten:kiki", object : PocketClient.Callback {
                        override fun ready(audio: ParcelFileDescriptor?, frames: Int, rate: Int, elapsed: Long) {
                            if (audio != null || frames != 0 || rate != 24000) error.set("Warmup returned spoken audio")
                            audio?.close(); done.countDown()
                        }
                        override fun error(message: String) { error.set(message); done.countDown() }
                    })
                }
                assertTrue("Native warmup timed out", done.await(620, TimeUnit.SECONDS))
                assertNull(error.get(), error.get())
                instrumentation.runOnMainSync {
                    assertTrue(client.bound); assertNotNull(client.remote); assertEquals(0, client.active)
                    assertEquals(300000L, client.idleTimeoutMs)
                }
            }
        } finally { instrumentation.runOnMainSync { client.close() } }
    }
}
