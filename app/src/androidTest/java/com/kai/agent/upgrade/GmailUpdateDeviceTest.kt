package com.kai.agent.upgrade

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kai.agent.data.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Invoke with the archived 0.2.0 APK installed. The store implementation is the old APK's. */
@RunWith(AndroidJUnit4::class)
class SeedGmailUpdate020DeviceTest {
    @Test fun seedReal020EncryptedProfile() = runBlocking {
        UpgradeFixture.requireEmulator()
        GmailUpdateFixture.requireVersion("0.2.0-alpha")
        GmailUpdateFixture.backupProfile()
        val store = KaiStore(UpgradeFixture.context)
        try {
            store.load()
            store.updateSettings { it.copy(
                selectedProvider = Provider.OPENAI,
                openai = ProviderProfile("http://127.0.0.1:18765/v1", GmailUpdateFixture.AI_KEY, "upgrade-gmail-fixture"),
                composioApiKey = GmailUpdateFixture.COMPOSIO_KEY,
                composioUserId = GmailUpdateFixture.USER_ID,
                memoryEnabled = true, memoryMode = MemoryMode.ASK_FIRST, useMemory = false,
            ) }
            store.remember(GmailUpdateFixture.NOTE, pinned = true)
            val conversation = store.createConversation(title = "Gmail update fixture")
            val daughterMessage = store.appendMessage(conversation.id, "user", GmailUpdateFixture.DAUGHTER_TEXT)
            val birthdayMessage = store.appendMessage(conversation.id, "user", GmailUpdateFixture.BIRTHDAY_TEXT)
            val self = MemoryEntity(id = "self:personal", displayName = "You")
            val daughter = MemoryEntity(id = GmailUpdateFixture.DAUGHTER_ID, displayName = "Aurora")
            val relationship = StructuredFact(id = "gmail-update-daughter", subjectId = self.id,
                predicate = "has_daughter", objectEntityId = daughter.id)
            val birthday = StructuredFact(id = "gmail-update-birthday", subjectId = daughter.id,
                predicate = "birthday", value = MemoryValue(type = "DATE", month = 10, precision = "month"))
            store.mutateMemoryGraph { graph -> graph.copy(
                entities = (graph.entities + self + daughter).distinctBy { it.id },
                facts = graph.facts + listOf(relationship, birthday),
                evidence = graph.evidence + listOf(
                    FactEvidence(factId = relationship.id, sourceMessageId = daughterMessage.id,
                        conversationId = conversation.id, quote = GmailUpdateFixture.DAUGHTER_TEXT),
                    FactEvidence(factId = birthday.id, sourceMessageId = birthdayMessage.id,
                        conversationId = conversation.id, quote = GmailUpdateFixture.BIRTHDAY_TEXT),
                ),
            ) }
            GmailUpdateFixture.verifyState(store.state.value)
        } finally { store.close() }
        val reopened = KaiStore(UpgradeFixture.context)
        try { reopened.load(); GmailUpdateFixture.verifyState(reopened.state.value) }
        finally { reopened.close() }
        GmailUpdateFixture.marker().writeText(JSONObject()
            .put("version", "0.2.0-alpha").put("uid", UpgradeFixture.context.applicationInfo.uid)
            .put("fixture_only", true).toString())
    }
}

/** Invoke after install -r of 0.2.1; do not clear package data or reinstall the instrumentation. */
@RunWith(AndroidJUnit4::class)
class VerifyGmailUpdate021DeviceTest {
    @Test fun upgradeRetainsCredentialsIdentityAndLinkedMemory() = runBlocking {
        UpgradeFixture.requireEmulator()
        GmailUpdateFixture.requireVersion("0.2.1-alpha")
        assertTrue("The seed must run on the old APK first", GmailUpdateFixture.marker().isFile)
        val seed = JSONObject(GmailUpdateFixture.marker().readText())
        assertEquals("0.2.0-alpha", seed.getString("version"))
        assertEquals(seed.getInt("uid"), UpgradeFixture.context.applicationInfo.uid)
        val store = KaiStore(UpgradeFixture.context)
        val state: KaiState
        try {
            store.load()
            state = store.state.value
            GmailUpdateFixture.verifyState(state)
            val exported = store.exportJson()
            assertTrue(exported.contains("Aurora"))
            assertTrue(exported.contains(GmailUpdateFixture.NOTE))
            assertFalse(exported.contains(GmailUpdateFixture.AI_KEY))
            assertFalse(exported.contains(GmailUpdateFixture.COMPOSIO_KEY))
            assertFalse(exported.contains(GmailUpdateFixture.USER_ID))
        } finally { store.close() }
        val reopened = KaiStore(UpgradeFixture.context)
        try {
            reopened.load()
            GmailUpdateFixture.verifyState(reopened.state.value)
            assertEquals(state.memoryGraph, reopened.state.value.memoryGraph)
        } finally { reopened.close() }
        GmailUpdateFixture.profileDirectory().walkTopDown().filter { it.isFile }.forEach { file ->
            val bytesAsText = file.readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(bytesAsText.contains(GmailUpdateFixture.AI_KEY))
            assertFalse(bytesAsText.contains(GmailUpdateFixture.COMPOSIO_KEY))
            assertFalse(bytesAsText.contains(GmailUpdateFixture.NOTE))
        }
        val output = File(UpgradeFixture.context.getExternalFilesDir(null), "qa-gmail-021").apply { mkdirs() }
        File(output, "upgrade-evidence.json").writeText(JSONObject().put("fixture_only", true)
            .put("from", "0.2.0-alpha").put("to", "0.2.1-alpha")
            .put("uid_preserved", true).put("credentials_preserved", true)
            .put("exact_user_id_preserved", true).put("linked_daughter_month_preserved", true)
            .put("pinned_note_and_memory_settings_preserved", true)
            .put("encrypted_reopen_and_export_checked", true).toString(2))
        GmailUpdateFixture.restoreProfile()
    }
}

private object GmailUpdateFixture {
    const val AI_KEY = "gmail-upgrade-local-ai-fixture-key"
    const val COMPOSIO_KEY = "gmail-upgrade-local-composio-fixture-key"
    const val USER_ID = "kai:existing-upgrade-user-021"
    const val NOTE = "I prefer upgrade-safe memory."
    const val DAUGHTER_TEXT = "My daughter's name is Aurora."
    const val BIRTHDAY_TEXT = "Aurora's birthday is in October."
    const val DAUGHTER_ID = "gmail-update-aurora-daughter"
    fun profileDirectory() = File(UpgradeFixture.context.noBackupFilesDir, "kai-state")
    private fun backupDirectory() = File(UpgradeFixture.context.noBackupFilesDir, "kai-gmail-upgrade-021-backup")
    fun marker() = File(backupDirectory(), "seed-proof.json")
    fun requireVersion(expected: String) {
        val context = UpgradeFixture.context
        assertEquals(expected, context.packageManager.getPackageInfo(context.packageName, 0).versionName)
    }
    fun backupProfile() {
        check(!backupDirectory().exists()) { "A previous upgrade fixture needs recovery before reseeding." }
        check(backupDirectory().mkdirs())
        if (profileDirectory().exists()) profileDirectory().copyRecursively(File(backupDirectory(), "profile"))
        else File(backupDirectory(), "profile.absent").writeText("absent")
        check(!profileDirectory().exists() || profileDirectory().deleteRecursively())
    }
    fun restoreProfile() {
        check(backupDirectory().exists())
        check(!profileDirectory().exists() || profileDirectory().deleteRecursively())
        File(backupDirectory(), "profile").takeIf { it.exists() }?.copyRecursively(profileDirectory(), overwrite = true)
        check(backupDirectory().deleteRecursively())
    }
    fun verifyState(state: KaiState) {
        assertEquals(Provider.OPENAI, state.settings.selectedProvider)
        assertEquals(AI_KEY, state.settings.openai.apiKey)
        assertEquals("upgrade-gmail-fixture", state.settings.openai.model)
        assertEquals("http://127.0.0.1:18765/v1", state.settings.openai.endpoint)
        assertEquals(COMPOSIO_KEY, state.settings.composioApiKey)
        assertEquals(USER_ID, state.settings.composioUserId)
        assertEquals(MemoryMode.ASK_FIRST, state.settings.memoryMode)
        assertTrue(state.settings.memoryEnabled)
        assertFalse(state.settings.useMemory)
        assertTrue(state.memories.single { it.text == NOTE }.pinned)
        val relationship = state.memoryGraph.facts.single { it.id == "gmail-update-daughter" }
        val birthday = state.memoryGraph.facts.single { it.id == "gmail-update-birthday" }
        assertEquals(DAUGHTER_ID, relationship.objectEntityId)
        assertEquals(relationship.objectEntityId, birthday.subjectId)
        assertEquals("has_daughter", relationship.predicate)
        assertEquals("birthday", birthday.predicate)
        assertEquals(10, birthday.value?.month)
        assertNull(birthday.value?.day)
        assertNull(birthday.value?.year)
        assertTrue(state.memoryGraph.evidence.any { it.factId == relationship.id && it.quote == DAUGHTER_TEXT })
        assertTrue(state.memoryGraph.evidence.any { it.factId == birthday.id && it.quote == BIRTHDAY_TEXT })
    }
}
