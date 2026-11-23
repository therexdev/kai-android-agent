package com.kai.agent.upgrade

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kai.agent.data.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Seed uses the old test APK on actual 0.4.5; verification uses the updated app and test APK. */
@RunWith(AndroidJUnit4::class)
class KikiVoiceUpdateDeviceTest {
    @Test fun voiceCleanupUpgradePreservesPrivateStateAndSelectsKikiDefaults() = runBlocking {
        UpgradeFixture.requireEmulator()
        val app = UpgradeFixture.context
        val directory = File(app.noBackupFilesDir, "native-voice-upgrade-fixture")
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
            override fun getPackageName(): String = app.packageName + ".native-voice-upgrade-fixture"
        }
        val old = KaiJson.decode(File(directory, "expected.json").readText())
        assertFalse(old.settings.kikiVoiceDefaultApplied)
        val expected = KikiVoiceDefaults.migrate(old)
        val store = KaiStore(context)
        try {
            store.load()
            assertEquals(expected.settings, store.state.value.settings)
            assertEquals(expected.characters, store.state.value.characters)
            assertEquals(old.conversations, store.state.value.conversations)
            assertEquals("kai:kitten:kiki", store.state.value.characters.first { it.id == "kai" }.voiceId)
            assertEquals("kai:kitten:kiki", store.state.value.characters.first { it.id == "spark" }.voiceId)
            assertEquals("explicit-nova-device-voice", store.state.value.characters.first { it.id == "nova" }.voiceId)
            assertEquals("saved-device-base", store.state.value.characters.first { it.id == "kai" }.deviceVoiceBaseId)
            store.close(); store.load()
            assertEquals(expected.characters, store.state.value.characters)
            assertEquals(old.conversations, store.state.value.conversations)
        } finally { store.close() }
    }
}
