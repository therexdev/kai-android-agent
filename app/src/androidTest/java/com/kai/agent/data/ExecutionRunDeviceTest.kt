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
    @Test fun modelAdmissionSurvivesEncryptedRestartWithoutRefundOrReplay()=runBlocking {
        isolated { context ->
            val first=EncryptedKaiDatabase(context);val initial=first.openOrMigrate { null }
            val chat=Conversation(id="budget-chat",title="Budget",characterId="kai",createdAt=1,updatedAt=1)
            val config=com.kai.agent.inference.ProviderConfig(com.kai.agent.inference.ProviderId.KOINOS,"https://example.com","private-credential","model",maxOutputTokens=128)
            val attempt=InferenceLedger.request(config,listOf(com.kai.agent.inference.InferenceMessage("user","private-prompt-sentinel")),InferencePurpose.SUMMARY,1000,100)
            val ledger=InferenceLedger(maxAttempts=1).reserve(attempt,128)
            val execution=ExecutionRun("budget-run",chat.id,"message",RunOrigin.CHAT,GoalContract("Research"),privacyRevision=1,createdAt=1,
                state=ExecutionState.RUNNING,owner="old-owner",budget=RunBudget(inference=ledger))
            first.save(initial.copy(conversations=listOf(chat),executions=listOf(execution)));first.close()
            val reopened=EncryptedKaiDatabase(context)
            try {
                val restored=reopened.openOrMigrate { error("Existing data must be preserved") }
                val recovered=ExecutionRunRepository.recover(restored.executions.single())
                assertEquals(ExecutionState.PAUSED,recovered.state);assertEquals("",recovered.owner)
                assertEquals(ledger.input,recovered.budget.inference.input);assertEquals(100L,recovered.budget.inference.millis)
                assertEquals(AttemptState.INTERRUPTED,recovered.budget.inference.history.single().state)
                assertTrue(recovered.budget.exhausted);assertTrue(restored.tasks.isEmpty())
                val exported=KaiJson.encode(restored,false)
                assertFalse(exported.contains("private-credential"));assertFalse(exported.contains("private-prompt-sentinel"))
            } finally { reopened.close() }
        }
    }
    @Test fun preApprovalResearchIsEncryptedAndRestartDoesNotReplayEffects()=runBlocking {
        isolated { context ->
            val first=KaiStore(context);first.load()
            val chat=first.createConversation();val message=first.appendMessage(chat.id,"user","Research private-sentinel-topic")
            val session=RunController(ExecutionRunRepository(first)).open(ExecutionRun("run",chat.id,message.id,RunOrigin.CHAT,GoalContract(message.text),privacyRevision=ExecutionRunRepository.privacy(first.state.value,chat.id),createdAt=now()))
            session.checkpoint("private-sentinel-result");session.draft("private-sentinel-draft")
            val quotation="private-sentinel-source publishes an open toolkit."
            session.recordResearch(org.json.JSONObject().put("kind","page").put("status","ok").put("networkAttempt",true)
                .put("url","https://example.test/source").put("requestedUrl","https://example.test/source")
                .put("requestKey","page:https://example.test/source").put("text",quotation).put("truncated",false))
            session.recordFindings(listOf(com.kai.agent.web.FindingProposal("private-sentinel-claim","s1_0",quotation)))
            first.close()
            val bytes=File(context.noBackupFilesDir,"kai-state/memory.db").readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(bytes.contains("private-sentinel"))
            val reopened=KaiStore(context)
            try {
                reopened.load();val restored=reopened.state.value.executions.single()
                assertEquals(ExecutionState.PAUSED,restored.state)
                assertEquals("private-sentinel-result",restored.archive)
                assertEquals("private-sentinel-draft",restored.artifacts.single().text)
                assertEquals(quotation,restored.research.findings.single().quote)
                assertEquals("s1_0",restored.research.sources.single().id)
                assertEquals("",restored.owner)
                assertTrue(reopened.state.value.tasks.isEmpty())
                reopened.updateSettings { it.copy(useMemory=false) }
                val invalid=reopened.state.value.executions.single()
                assertEquals(ExecutionState.CANCELLED,invalid.state);assertEquals("[]",invalid.archive);assertTrue(invalid.artifacts.isEmpty())
                assertTrue(invalid.research.receipts.isEmpty());assertTrue(invalid.research.findings.isEmpty())
            } finally { reopened.close() }
        }
    }
    @Test fun nativeContinuationIsEncryptedSurvivesRestartAndIsRemovedFromExportAndForget()=runBlocking {
        isolated { context ->
            val token="kr1."+"private_device_fixture".repeat(12)
            val first=KaiStore(context);first.load()
            val chat=first.createConversation();val message=first.appendMessage(chat.id,"user","Research a test topic")
            val turn=com.kai.agent.agent.AgentTurnContext(emptyList(),managed=true)
            val replay=com.kai.agent.inference.ProviderReplay.read(org.json.JSONObject().put("kai_replay",token).put("kai_replay_route","fixture-route"))
            turn.awaiting(com.kai.agent.inference.Completion("","tool_calls",toolCalls=listOf(com.kai.agent.inference.ToolCall("call_1","web_search","{}")),providerReplay=replay))
            val run=ExecutionRun("native",chat.id,message.id,RunOrigin.CHAT,GoalContract(message.text),privacyRevision=ExecutionRunRepository.privacy(first.state.value,chat.id),createdAt=now())
            RunController(ExecutionRunRepository(first)).open(run).checkpoint(turn.archive())
            assertFalse(first.exportJson().contains(token));first.close()
            assertFalse(File(context.noBackupFilesDir,"kai-state/memory.db").readBytes().toString(Charsets.ISO_8859_1).contains(token))
            val reopened=KaiStore(context)
            try {
                reopened.load();val restored=reopened.state.value.executions.single()
                val resumed=com.kai.agent.agent.AgentTurnContext(emptyList(),checkpointSeed=restored.archive,managed=true)
                resumed.appendOutcome("ACCEPTED real receipt")
                assertEquals(replay,resumed.fullMessages().first().providerReplay)
                assertTrue(reopened.state.value.tasks.isEmpty());assertFalse(reopened.exportChatReport(chat.id).contains(token))
                reopened.updateSettings { it.copy(useMemory=false) }
                assertEquals("[]",reopened.state.value.executions.single().archive)
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
