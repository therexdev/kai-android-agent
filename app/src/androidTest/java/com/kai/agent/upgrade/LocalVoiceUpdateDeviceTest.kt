package com.kai.agent.upgrade

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kai.agent.data.*
import com.kai.agent.voice.LocalVoiceCatalog
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Seed runs against the actual 0.4.4 APK, verify after installing 0.4.5 in place. */
@RunWith(AndroidJUnit4::class)
class LocalVoiceUpdateDeviceTest {
    private fun fixture(): Context {
        UpgradeFixture.requireEmulator()
        val app = UpgradeFixture.context
        val directory = File(app.noBackupFilesDir, "native-voice-upgrade-fixture").apply { mkdirs() }
        return object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
            override fun getPackageName(): String = app.packageName + ".native-voice-upgrade-fixture"
        }
    }
    @Test fun seedOldProfile() = runBlocking {
        val context = fixture()
        KaiStore(context).let { store ->
            try {
                store.load()
                store.updateSettings { it.copy(composioApiKey = "synthetic-upgrade-connection", composioUserId = "exact:voice-test-owner",
                    koinos = ProviderProfile(apiKey = "synthetic-upgrade-model"), speechRate = 1.15f) }
                store.updateCharacter("kai") { it.copy(voiceId = "kai:kitten:kiki", deviceVoiceBaseId = "saved-device-base") }
                store.updateCharacter("nova") { it.copy(voiceId = "explicit-nova-device-voice") }
                val chat = store.createConversation(title = "Keep voice update conversation")
                store.appendMessage(chat.id, "user", "Preserve this conversation through the voice update.")
                File(context.noBackupFilesDir, "expected.json").writeText(KaiJson.encode(store.state.value))
            } finally { store.close() }
        }
    }
    @Test fun verifyNewProfile() = runBlocking {
        val context = fixture()
        val expected = KaiJson.decode(File(context.noBackupFilesDir, "expected.json").readText())
        val store = KaiStore(context)
        try {
            store.load()
            assertEquals(expected.settings, store.state.value.settings)
            assertEquals(expected.characters, store.state.value.characters)
            assertEquals(expected.conversations, store.state.value.conversations)
            for (voice in LocalVoiceCatalog.VOICES) {
                store.updateCharacter("kai") { it.copy(voiceId = voice.id) }
                store.close(); store.load()
                assertEquals(voice.id, store.state.value.characters.first { it.id == "kai" }.voiceId)
                assertEquals("saved-device-base", store.state.value.characters.first { it.id == "kai" }.deviceVoiceBaseId)
                assertEquals(expected.conversations, store.state.value.conversations)
                assertEquals(expected.settings.composioApiKey, store.state.value.settings.composioApiKey)
            }
        } finally { store.close() }
    }
}
