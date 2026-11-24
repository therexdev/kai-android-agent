package com.kai.agent.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Requires Android Keystore on an emulator/device. Pure JVM tests cannot establish encryption. */
@RunWith(AndroidJUnit4::class)
class KaiStoreDeviceTest {
    @Test fun encryptedRoundTripRestoresMemoryAndRecoversSubmittedTaskWithoutReplay() = runBlocking {
        withIsolatedContext { context ->
            val first = openStore(context)
            first.load()
            first.updateSettings { it.copy(openai = ProviderProfile(apiKey = "do-not-write-plaintext"),
                composioUserId = "owner", composioApiKey = "composio-secret", connectionGeneration = "g1") }
            val conversation = first.createConversation()
            val message = first.appendMessage(conversation.id, "user", "Remember meeting after ten")
            first.remember("Meeting after ten", MemorySource(ref = message.id, conversationId = conversation.id))
            val task = first.createTask("Mail")
            first.prepareTaskIntent(task.id, ActionIntent(toolName = "send", accountId = "mailbox", ownerId = "owner",
                connectionGeneration = "g1", argumentsJson = "{\"body\":\"Friday\"}"))
            first.approveTask(task.id)
            first.transitionTask(task.id, TaskStatus.RUNNING)

            val encrypted = File(context.noBackupFilesDir, "kai-state/memory.db").readBytes()
            val raw = encrypted.toString(Charsets.ISO_8859_1)
            assertFalse(raw.contains("do-not-write-plaintext"))
            assertFalse(raw.contains("Meeting after ten"))
            assertFalse(raw.contains("composio-secret"))
            val reopened = openStore(context)
            reopened.load()
            assertEquals("do-not-write-plaintext", reopened.state.value.settings.openai.apiKey)
            assertEquals("composio-secret", reopened.state.value.settings.composioApiKey)
            assertEquals("owner", reopened.state.value.settings.composioUserId)
            assertEquals("Meeting after ten", reopened.state.value.memories.single().text)
            assertEquals(TaskStatus.UNKNOWN, reopened.state.value.tasks.single().status)
            try { reopened.transitionTask(task.id, TaskStatus.RUNNING); fail("A recovered request must never be retried") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun tamperFailsClosedAndDoesNotOverwriteExistingData() = runBlocking {
        withIsolatedContext { context ->
            val first = openStore(context)
            first.load()
            first.remember("Preserve this fact")
            val file = File(context.noBackupFilesDir, "kai-state/memory.db")
            first.close()
            val corrupted = file.readBytes().also { it[24] = (it[24].toInt() xor 1).toByte() }
            file.writeBytes(corrupted)
            val reopened = openStore(context)
            try { reopened.load(); fail("Tampered encrypted state must fail authentication") }
            catch (_: KaiStoreException) { }
            try { reopened.remember("Replacement"); fail("A write must not reset corrupt data") }
            catch (_: KaiStoreException) { }
            assertArrayEquals(corrupted, file.readBytes())
        }
    }

    @Test fun importCannotReintroduceForgottenMemoryOrConnectionCredentialsAndIdentity() = runBlocking {
        withIsolatedContext { context ->
            val store = openStore(context)
            store.load()
            store.updateSettings { it.copy(openai = ProviderProfile(apiKey = "keep-current-secret"),
                composioApiKey = "keep-current-composio-key", composioUserId = "keep-current-composio-user") }
            val fact = store.remember("Prefer Friday meetings")
            val backup = store.exportJson()
            assertFalse(backup.contains("keep-current-secret"))
            assertFalse(backup.contains("keep-current-composio-key"))
            assertFalse(backup.contains("keep-current-composio-user"))
            val incoming = JSONObject(backup)
            incoming.getJSONObject("state").getJSONObject("settings")
                .put("composioApiKey", "never-adopt-incoming-key")
                .put("composioUserId", "never-adopt-incoming-user")
                .put("accountId", "never-adopt-legacy-account")
                .put("connectionsSessionToken", "never-adopt-session")
            store.forgetMemory(fact.id)
            store.importJson(incoming.toString())
            assertTrue(store.state.value.memories.isEmpty())
            assertEquals("keep-current-secret", store.state.value.settings.openai.apiKey)
            assertEquals("keep-current-composio-key", store.state.value.settings.composioApiKey)
            assertEquals("keep-current-composio-user", store.state.value.settings.composioUserId)
            assertEquals("", store.state.value.settings.accountId)
            assertEquals("", store.state.value.settings.connectionsSessionToken)
            val reopened = openStore(context)
            reopened.load()
            assertEquals(store.state.value.settings, reopened.state.value.settings)
        }
    }

    @Test fun firstLoadPersistsGeneratedIdentityWithoutRequiringAnotherChange() = runBlocking {
        withIsolatedContext { context ->
            val first = openStore(context)
            first.load()
            val firstState = first.state.value
            assertTrue(firstState.settings.composioUserId.startsWith("kai-mobile:"))
            UUID.fromString(firstState.settings.composioUserId.removePrefix("kai-mobile:"))
            assertEquals("", firstState.settings.composioApiKey)
            assertTrue(File(context.noBackupFilesDir, "kai-state/memory.db").isFile)
            val reopened = openStore(context)
            reopened.load()
            assertEquals(firstState.settings.composioUserId, reopened.state.value.settings.composioUserId)
            assertEquals(firstState.revision, reopened.state.value.revision)
            reopened.updateSettings { it.copy(isDarkTheme = false) }
            assertEquals(firstState.settings.composioUserId, reopened.state.value.settings.composioUserId)
        }
    }

    @Test fun changingDirectKeyOrIdentityInvalidatesUnsubmittedApproval() = runBlocking {
        withIsolatedContext { context ->
            val store = openStore(context)
            store.load()
            store.updateSettings { it.copy(composioApiKey = "first-key", composioUserId = "first-user", connectionGeneration = "g1") }
            val task = store.createTask("Send a reviewed message")
            val intent = ActionIntent(toolName = "send", accountId = "mailbox", ownerId = "first-user", connectionGeneration = "g1")
            store.prepareTaskIntent(task.id, intent)
            store.approveTask(task.id)
            store.updateSettings { it.copy(composioApiKey = "second-key") }
            assertEquals(TaskStatus.DRAFT, store.state.value.tasks.single().status)
            assertEquals("", store.state.value.tasks.single().approvedFingerprint)
            store.prepareTaskIntent(task.id, intent)
            store.approveTask(task.id)
            store.updateSettings { it.copy(composioUserId = "second-user") }
            assertEquals(TaskStatus.DRAFT, store.state.value.tasks.single().status)
            assertEquals("", store.state.value.tasks.single().approvedFingerprint)
            assertNull(store.state.value.tasks.single().submittedAt)
        }
    }


    @Test fun approvalCheckpointSurvivesEncryptedRestartAndRejectsChangedContext() = runBlocking {
        withIsolatedContext { context ->
            val first=openStore(context);first.load()
            first.updateSettings { it.copy(composioUserId="fixture-owner",composioApiKey="fixture-only-key",connectionGeneration="fixture-generation") }
            val conversation=first.createConversation()
            val revision=WorkspaceRules.contextRevision(first.state.value,"",conversation.id)
            val task=first.createTask("Document","Research projects",conversation.id)
            first.prepareTaskIntent(task.id,ActionIntent(toolName="GOOGLEDOCS_CREATE_DOCUMENT",accountId="fixture-docs",ownerId="fixture-owner",connectionGeneration="fixture-generation"))
            val checkpoint="""[{"proposal":"search","result":"Retrieved source https://project.example/"}]"""
            val archive=org.json.JSONObject().put("version",2).put("entries",org.json.JSONArray(checkpoint)).toString()
            first.saveActionCheckpoint(task.id,checkpoint,revision,archive)
            first.close()
            val reopened=openStore(context);reopened.load()
            assertTrue(reopened.state.value.tasks.single().resultContext.contains("project.example"))
            assertEquals(archive,reopened.state.value.tasks.single().contextArchive)
            assertEquals(TaskStatus.AWAITING_APPROVAL,reopened.state.value.tasks.single().status)
            assertNull(reopened.state.value.tasks.single().submittedAt)
            reopened.updateSettings { it.copy(useMemory=!it.useMemory) }
            assertTrue(runCatching { reopened.saveActionCheckpoint(task.id,checkpoint,revision) }.isFailure)
        }
    }

    private val opened = mutableListOf<KaiStore>()
    private fun openStore(context: Context) = KaiStore(context).also(opened::add)

    private suspend fun withIsolatedContext(block: suspend (Context) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val directory = File(app.noBackupFilesDir, "kai-test-$suffix").apply { mkdirs() }
        val identity = app.packageName + ".test." + suffix
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
            override fun getPackageName(): String = identity
        }
        try { block(context) }
        finally {
            opened.forEach { it.close() }; opened.clear()
            directory.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("$identity.kai.state.aes.v1"); deleteEntry("$identity.kai.db.wrap.v1") }
        }
    }
}
