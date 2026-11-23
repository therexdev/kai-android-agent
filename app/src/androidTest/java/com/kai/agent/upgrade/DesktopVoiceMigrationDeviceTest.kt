package com.kai.agent.upgrade

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kai.agent.data.*
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DesktopVoiceMigrationDeviceTest {
    @Test fun upgradeChangesDefaultOnceAndRetainsEncryptedState() = runBlocking {
        UpgradeFixture.requireEmulator()
        val app = UpgradeFixture.context
        val identity = app.packageName + ".desktop-voice-test." + UUID.randomUUID()
        val directory = File(app.noBackupFilesDir, identity).apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
            override fun getPackageName(): String = identity
        }
        val opened = mutableListOf<KaiStore>()
        fun store() = KaiStore(context).also { opened += it }
        try {
            val first = store().also { it.load() }
            first.updateSettings { it.copy(desktopVoiceDefaultApplied = false,
                koinos = ProviderProfile(apiKey = "synthetic-provider-key"),
                composioApiKey = "synthetic-connection-key", composioUserId = "exact-synthetic-owner") }
            first.updateCharacter("kai") { it.copy(voiceId = "") }
            first.updateCharacter("nova") { it.copy(voiceId = "custom-voice-nova") }
            val chat = first.createConversation(title = "Preserved voice upgrade fixture")
            first.appendMessage(chat.id, "user", "Keep my conversation.")
            val before = first.state.value
            first.close()
            val second = store().also { it.load() }
            val migrated = second.state.value
            assertEquals(DesktopVoiceDefaults.VOICE_ID, migrated.characters.first { it.id == "kai" }.voiceId)
            assertEquals("custom-voice-nova", migrated.characters.first { it.id == "nova" }.voiceId)
            assertEquals(before.conversations, migrated.conversations)
            assertEquals(before.settings.copy(desktopVoiceDefaultApplied = true), migrated.settings)
            second.updateCharacter("kai") { it.copy(voiceId = "system") }
            second.close()
            val third = store().also { it.load() }
            assertEquals("system", third.state.value.characters.first { it.id == "kai" }.voiceId)
        } finally {
            opened.forEach { it.close() }
            directory.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null); deleteEntry("$identity.kai.state.aes.v1"); deleteEntry("$identity.kai.db.wrap.v1")
            }
        }
    }
}
