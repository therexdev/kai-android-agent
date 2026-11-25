package com.kai.agent.diagnostics

import android.app.Application
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kai.agent.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ChatDiagnosticsDeviceTest {
    private class App(base: Context) : Application() {
        val directory=File(base.noBackupFilesDir,"diagnostics-${UUID.randomUUID()}").apply { mkdirs() }
        init { attachBaseContext(base) }
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = directory
    }
    @Test fun pendingExportSurvivesActivityRecreationAndIsConsumedOnce() {
        androidx.test.core.app.ActivityScenario.launch(com.kai.agent.MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                androidx.lifecycle.ViewModelProvider(activity)[com.kai.agent.KaiViewModel::class.java].stageExport("private-report-fixture")
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val model=androidx.lifecycle.ViewModelProvider(activity)[com.kai.agent.KaiViewModel::class.java]
                assertEquals("private-report-fixture",model.consumeExport())
                assertNull(model.consumeExport())
            }
        }
    }
    @Test fun encryptedReportSurvivesRestartAndCannotBeImportedAsBackup() = runBlocking {
        val app=App(InstrumentationRegistry.getInstrumentation().targetContext); var store=KaiStore(app)
        try {
            store.load(); store.updateSettings { it.copy(composioApiKey="device-fixture-secret") }
            val chat=store.createConversation(); store.appendMessage(chat.id,"user","diagnostics-private-marker")
            val trace=requireNotNull(store.beginChatTrace(chat.id,"fixture"))
            trace.record("fixture",JSONObject().put("response","device-fixture-secret").put("evidence","diagnostics-private-marker"))
            store.close(); store=KaiStore(app); store.load()
            val report=store.exportChatReport(chat.id); assertTrue(report.contains("diagnostics-private-marker")); assertFalse(report.contains("device-fixture-secret"))
            assertEquals(1,JSONObject(report).getJSONArray("trace").length())
            assertTrue(runCatching { store.importJson(report) }.isFailure)
            assertFalse(File(app.directory,"kai-state/memory.db").readBytes().toString(Charsets.ISO_8859_1).contains("diagnostics-private-marker"))
        } finally { store.close(); app.directory.deleteRecursively() }
    }
    @Test fun temporaryDeletionForgetAndLateCallbackCannotRetainDiagnostics() = runBlocking {
        val app=App(InstrumentationRegistry.getInstrumentation().targetContext); val store=KaiStore(app)
        try {
            store.load(); val temporary=store.createConversation(temporary=true)
            assertNull(store.beginChatTrace(temporary.id,"fixture")); assertTrue(runCatching { store.exportChatReport(temporary.id) }.isFailure)
            val chat=store.createConversation(); val trace=requireNotNull(store.beginChatTrace(chat.id,"fixture"))
            trace.record("fixture",JSONObject().put("text","forgotten-marker"))
            val fact=store.remember("forgotten-marker")
            store.forgetMemory(fact.id)
            trace.record("late",JSONObject().put("text","forgotten-marker"))
            assertFalse(store.exportChatReport(chat.id).contains("forgotten-marker"))
            val next=requireNotNull(store.beginChatTrace(chat.id,"new")); next.record("new")
            store.deleteConversation(chat.id); next.record("late")
            assertTrue(runCatching { store.exportChatReport(chat.id) }.isFailure)
        } finally { store.close(); app.directory.deleteRecursively() }
    }
    @Test fun retentionIsBoundedAndOmissionsAreCounted() = runBlocking {
        val app=App(InstrumentationRegistry.getInstrumentation().targetContext); val store=KaiStore(app)
        try {
            store.load(); val chat=store.createConversation(); val trace=requireNotNull(store.beginChatTrace(chat.id,"fixture"))
            repeat(44) { trace.record("large",JSONObject().put("index",it).put("text","x ".repeat(50000))) }
            val report=JSONObject(store.exportChatReport(chat.id)); val events=report.getJSONArray("trace")
            assertTrue(events.length() in 1..39); assertEquals(44-events.length(),report.getJSONObject("coverage").getInt("droppedEvents"))
            assertEquals(43,events.getJSONObject(events.length()-1).getJSONObject("data").getInt("index"))
        } finally { store.close(); app.directory.deleteRecursively() }
    }
    @Test fun twentyChatsSurviveRestartAndBatchExportHonorsDeletionAndTemporaryPrivacy() = runBlocking {
        val app=App(InstrumentationRegistry.getInstrumentation().targetContext); var store=KaiStore(app)
        try {
            store.load(); store.updateSettings { it.copy(composioApiKey="batch-secret") }
            val chats=(0 until 20).map { index ->
                val chat=store.createConversation(); store.appendMessage(chat.id,"user","attempt-$index")
                val trace=requireNotNull(store.beginChatTrace(chat.id,"fixture"))
                repeat(14) { trace.record("fixture",JSONObject().put("index",it).put("text","x ".repeat(50000)).put("api_key","batch-secret")) }
                chat
            }
            val temporary=store.createConversation(temporary=true); store.appendMessage(temporary.id,"user","temporary-marker")
            store.close(); store=KaiStore(app); store.load()
            val output=java.io.StringWriter(); store.writeAllChatReports(output)
            val batch=JSONObject(output.toString())
            assertEquals(20,batch.getInt("chatCount")); assertEquals(280,batch.getJSONObject("summary").getInt("eventCount"))
            assertEquals(0,batch.getJSONObject("summary").getInt("droppedEvents"))
            assertFalse(output.toString().contains("batch-secret")); assertFalse(output.toString().contains("temporary-marker"))
            assertTrue(runCatching { store.importJson(output.toString()) }.isFailure)
            val next=store.createConversation(); requireNotNull(store.beginChatTrace(next.id,"fixture")).record("new")
            val overflow=java.io.StringWriter(); store.writeAllChatReports(overflow)
            assertEquals(14,JSONObject(overflow.toString()).getJSONObject("summary").getInt("droppedEvents"))
            store.deleteConversation(chats.first().id)
            val after=java.io.StringWriter(); store.writeAllChatReports(after)
            assertFalse(after.toString().contains(chats.first().id))
            assertEquals(0,JSONObject(after.toString()).getJSONObject("summary").getInt("eventCount"))
        } finally { store.close(); app.directory.deleteRecursively() }
    }
    @Test fun pendingBatchExportSurvivesRecreationAndIsConsumedOnce() {
        androidx.test.core.app.ActivityScenario.launch(com.kai.agent.MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                androidx.lifecycle.ViewModelProvider(activity)[com.kai.agent.KaiViewModel::class.java].stageAllChatReportExport()
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val model=androidx.lifecycle.ViewModelProvider(activity)[com.kai.agent.KaiViewModel::class.java]
                assertTrue(model.consumeAllChatReportExport()); assertFalse(model.consumeAllChatReportExport())
            }
        }
    }

}
