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
class ConversationVoiceMigrationDeviceTest {
    @Test fun retiredPresetRestoresOriginalAndKeepsEncryptedStateAndExplicitChoices()=runBlocking {
        UpgradeFixture.requireEmulator()
        val app=UpgradeFixture.context
        val identity=app.packageName+".conversation-voice-test."+UUID.randomUUID()
        val directory=File(app.noBackupFilesDir,identity).apply{mkdirs()}
        val context=object:ContextWrapper(app) {
            override fun getApplicationContext():Context=this
            override fun getNoBackupFilesDir():File=directory
            override fun getPackageName():String=identity
        }
        val stores=mutableListOf<KaiStore>()
        fun open()=KaiStore(context).also{stores+=it}
        try {
            val first=open().also{it.load()}
            first.updateSettings{it.copy(conversationVoiceDefaultApplied=true,
                koinos=ProviderProfile(apiKey="synthetic-provider-key"),composioApiKey="synthetic-connection-key",
                composioUserId="exact-owner",speechRate=1.1f)}
            first.updateCharacter("kai"){it.copy(voiceId=ConversationVoiceDefaults.VOICE_ID)}
            first.updateCharacter("nova"){it.copy(voiceId="chosen-nova")}
            val chat=first.createConversation(title="Keep conversation");first.appendMessage(chat.id,"user","Keep my data.")
            val before=first.state.value;first.close()
            val second=open().also{it.load()};val after=second.state.value
            assertEquals(DesktopVoiceDefaults.VOICE_ID,after.characters.first{it.id=="kai"}.voiceId)
            assertEquals(before.conversations,after.conversations)
            assertEquals(before.settings.copy(conversationVoiceDefaultApplied=true),after.settings)
            assertEquals("chosen-nova",after.characters.first{it.id=="nova"}.voiceId)
            second.updateCharacter("kai"){it.copy(voiceId="fixture-selected-voice")};second.close()
            val third=open().also{it.load()}
            assertEquals("fixture-selected-voice",third.state.value.characters.first{it.id=="kai"}.voiceId)
        } finally {
            stores.forEach{it.close()};directory.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply{load(null);deleteEntry("$identity.kai.state.aes.v1");deleteEntry("$identity.kai.db.wrap.v1")}
        }
    }
}
