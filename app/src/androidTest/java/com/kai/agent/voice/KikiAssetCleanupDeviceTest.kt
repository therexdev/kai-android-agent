package com.kai.agent.voice

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KikiAssetCleanupDeviceTest {
    @Test fun cleanupRemovesRetiredModelCopiesAndKeepsActiveModelsAndPrivateState() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(app.cacheDir.toPath(), "kiki-cleanup-").toFile()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        try {
            val current = File(root, "test-voices/" + "b".repeat(64)).apply { mkdirs() }
            File(current, "kiki").writeText("active model")
            val old = File(root, "test-voices/" + "a".repeat(64)).apply { mkdirs() }
            File(old, "retired").writeText("old models")
            val desktop = File(root, "desktop-voice/old").apply { mkdirs() }
            File(desktop, "model").writeText("retired desktop")
            val privateState = File(root, "kai-state").apply { mkdirs() }
            File(privateState, "state.enc").writeText("preserve encrypted data")
            Files.createSymbolicLink(File(old, "link").toPath(), privateState.toPath())
            val unknown = File(root, "test-voices/unrecognized").apply { mkdirs() }
            LocalVoicePack.removeRetiredPacks(context, current)
            assertFalse(old.exists()); assertFalse(File(root, "desktop-voice").exists())
            assertEquals("active model", File(current, "kiki").readText())
            assertEquals("preserve encrypted data", File(privateState, "state.enc").readText())
            assertTrue(unknown.exists())
        } finally { root.deleteRecursively() }
    }
}
