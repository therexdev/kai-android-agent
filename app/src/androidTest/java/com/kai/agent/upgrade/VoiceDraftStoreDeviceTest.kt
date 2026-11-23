package com.kai.agent.upgrade

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kai.agent.data.KaiStore
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** A crash/reopen may never upgrade partially heard speech into a completed assistant reply. */
@RunWith(AndroidJUnit4::class)
class VoiceDraftStoreDeviceTest {
    @Test fun emptyAndPartiallyHeardDraftsReopenConservativelyAndDeletionWins() = runBlocking {
        UpgradeFixture.requireEmulator()
        val app = UpgradeFixture.context
        val identity = app.packageName + ".voice-test." + UUID.randomUUID()
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
            val conversation = first.createConversation()
            val draft = first.beginVoiceReply(conversation.id, "OPENAI", "local-fixture")
            assertTrue(draft.interrupted)
            assertEquals("", draft.text)
            assertEquals(0, draft.spokenCharacters)
            first.close()
            val reopened = store().also { it.load() }
            val savedEmpty = reopened.state.value.conversations.single().messages.single()
            assertEquals(draft, savedEmpty)
            val fullText = "The opening was heard. The rest was not spoken."
            val heard = "The opening was heard.".length
            reopened.updateMessage(conversation.id, draft.id) { it.copy(text = fullText, interrupted = true, spokenCharacters = heard) }
            reopened.close()
            val final = store().also { it.load() }
            val savedPartial = final.state.value.conversations.single().messages.single()
            assertTrue(savedPartial.interrupted)
            assertEquals(heard, savedPartial.spokenCharacters)
            assertEquals(fullText, savedPartial.text)
            directory.walkTopDown().filter { it.isFile }.forEach {
                assertFalse(it.readBytes().toString(Charsets.ISO_8859_1).contains(fullText))
            }
            try {
                final.updateMessage(conversation.id, draft.id) { it.copy(spokenCharacters = fullText.length + 1) }
                fail("A playback offset beyond generated text must fail")
            } catch (_: IllegalArgumentException) { }
            final.deleteConversation(conversation.id)
            try {
                final.updateMessage(conversation.id, draft.id) { it.copy(interrupted = false) }
                fail("A late completion must never resurrect a deleted conversation")
            } catch (_: IllegalStateException) { }
            assertTrue(final.state.value.conversations.isEmpty())
        } finally {
            opened.forEach { it.close() }
            directory.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null); deleteEntry("$identity.kai.state.aes.v1"); deleteEntry("$identity.kai.db.wrap.v1")
            }
        }
    }
}
