package com.kai.agent.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kai.agent.runtime.*
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExecutionRunDeviceTest {
    @Test fun preApprovalResearchIsEncryptedAndRestartDoesNotReplayEffects()=runBlocking {
        isolated { context ->
            val first=KaiStore(context);first.load()
            val chat=first.createConversation();val message=first.appendMessage(chat.id,"user","Research private-sentinel-topic")
            val session=RunController(ExecutionRunRepository(first)).open(ExecutionRun("run",chat.id,message.id,RunOrigin.CHAT,GoalContract(message.text),privacyRevision=ExecutionRunRepository.privacy(first.state.value,chat.id),createdAt=now()))
            session.checkpoint("private-sentinel-result");session.draft("private-sentinel-draft")
            first.close()
            val bytes=File(context.noBackupFilesDir,"kai-state/memory.db").readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(bytes.contains("private-sentinel"))
            val reopened=KaiStore(context)
            try {
                reopened.load();val restored=reopened.state.value.executions.single()
                assertEquals(ExecutionState.PAUSED,restored.state)
                assertEquals("private-sentinel-result",restored.archive)
                assertEquals("private-sentinel-draft",restored.artifacts.single().text)
                assertEquals("",restored.owner)
                assertTrue(reopened.state.value.tasks.isEmpty())
                reopened.updateSettings { it.copy(useMemory=false) }
                val invalid=reopened.state.value.executions.single()
                assertEquals(ExecutionState.CANCELLED,invalid.state);assertEquals("[]",invalid.archive);assertTrue(invalid.artifacts.isEmpty())
            } finally { reopened.close() }
        }
    }
    @Test fun schemaTwoMigrationPreservesPendingApprovalAndSeparateRows()=runBlocking {
        isolated { context ->
            val first=EncryptedKaiDatabase(context)
            val initial=first.openOrMigrate { null }
            val chat=Conversation(id="conversation",title="Migration",characterId="kai",createdAt=1,updatedAt=1)
            val intent=ActionIntent(id="intent",toolName="send",accountId="mail",argumentsJson="{\"body\":\"draft\"}")
            val action=AgentTask(id="action",title="Reviewed send",conversationId=chat.id,intent=intent,
                status=TaskStatus.AWAITING_APPROVAL,resultContext="retained research")
            val saved=AgentRun(id="saved",title="Saved",goal="Research",conversationId=chat.id)
            val execution=ExecutionRun("execution",chat.id,"message",RunOrigin.CHAT,GoalContract("Research"),privacyRevision=1,createdAt=1,archive="retained archive")
            val document=ProjectDocument(id="document",name="Imported",mimeType="text/plain",conversationId=chat.id,text="retained document")
            val expected=initial.copy(conversations=listOf(chat),tasks=listOf(action),runs=listOf(saved),executions=listOf(execution),documents=listOf(document))
            first.save(expected)
            val field=EncryptedKaiDatabase::class.java.getDeclaredField("database").apply { isAccessible=true }
            val db=field.get(first) as net.zetetic.database.sqlcipher.SQLiteDatabase
            // Reconstruct the exact v2 layout under the existing encrypted key.
            db.beginTransaction()
            try {
                val core=org.json.JSONObject(KaiJson.encode(expected.copy(conversations=emptyList(),memoryGraph=MemoryGraph())))
                db.execSQL("UPDATE app_state SET payload=? WHERE id=1",arrayOf(core.toString()))
                listOf("execution_runs","saved_runs","project_documents","actions").forEach { db.execSQL("DROP TABLE $it") }
                db.version=2;db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            first.close()
            val upgraded=EncryptedKaiDatabase(context)
            try {
                val restored=upgraded.openOrMigrate { error("Must not restore legacy data") }
                assertEquals(KaiJson.encode(expected),KaiJson.encode(restored))
                val active=field.get(upgraded) as net.zetetic.database.sqlcipher.SQLiteDatabase
                assertEquals(3,active.version)
                active.execSQL("CREATE TABLE runtime_write_audit (id INTEGER)")
                active.execSQL("CREATE TRIGGER runtime_write_watch AFTER INSERT ON execution_runs BEGIN INSERT INTO runtime_write_audit VALUES(1); END")
                val before=active.rawQuery("SELECT payload FROM execution_runs WHERE id='execution'",emptyArray<String>()).use { it.moveToFirst();it.getString(0) }
                upgraded.save(restored.copy(settings=restored.settings.copy(isDarkTheme=false)))
                val after=active.rawQuery("SELECT payload FROM execution_runs WHERE id='execution'",emptyArray<String>()).use { it.moveToFirst();it.getString(0) }
                assertEquals(before,after)
                active.rawQuery("SELECT count(*) FROM runtime_write_audit",emptyArray<String>()).use { it.moveToFirst();assertEquals(0,it.getInt(0)) }
                val core=active.rawQuery("SELECT payload FROM app_state",emptyArray<String>()).use { it.moveToFirst();org.json.JSONObject(it.getString(0)) }
                listOf("executions","runs","documents","tasks").forEach { assertEquals(0,core.getJSONArray(it).length()) }
                assertNull(restored.tasks.single().submittedAt)
            } finally { upgraded.close() }
        }
    }

    private suspend fun isolated(block: suspend (Context)->Unit) {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val suffix=UUID.randomUUID().toString();val directory=File(app.noBackupFilesDir,"runtime-$suffix").apply { mkdirs() }
        val identity=app.packageName+".runtime."+suffix
        val context=object: ContextWrapper(app) {
            override fun getApplicationContext(): Context=this
            override fun getNoBackupFilesDir()=directory
            override fun getPackageName()=identity
        }
        try { block(context) } finally {
            directory.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null);deleteEntry("$identity.kai.state.aes.v1");deleteEntry("$identity.kai.db.wrap.v1") }
        }
    }
}
