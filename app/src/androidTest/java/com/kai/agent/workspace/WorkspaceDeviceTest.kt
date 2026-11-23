package com.kai.agent.workspace

import android.content.*
import android.graphics.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.content.FileProvider
import com.kai.agent.data.*
import com.kai.agent.inference.*
import com.kai.agent.projects.DocumentImporter
import com.kai.agent.tasks.TaskAgentEngine
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WorkspaceDeviceTest {
    @Test fun resumedTaskSeesFilesAddedToItsPersonalConversation()=runBlocking<Unit> {
        fixture { context -> val handle=KaiStores.acquire(context);val store=handle.store
            try {
                store.load();configure(store);val run=store.createRun("Read file","Read my attachment",steps=listOf("Read attachment"))
                TaskAgentEngine(store,compactContext={false},complete={_,_ -> Completion("{\"type\":\"needs_input\",\"text\":\"Please attach the file\"}","stop") }).run(run.id,"one")
                store.addDocument(ProjectDocument(name="new.txt",mimeType="text/plain",conversationId=run.conversationId,text="Newly attached fixture detail"))
                store.setRunStatus(run.id,RunStatus.QUEUED)
                val result=TaskAgentEngine(store,compactContext={false},complete={_,messages ->
                    assertTrue(messages.first().content.contains("Newly attached fixture detail"))
                    Completion("{\"type\":\"reply\",\"text\":\"Read the new attachment\"}","stop")
                }).run(run.id,"two")
                assertEquals(RunStatus.COMPLETED,result?.status)
            } finally { handle.close() }
        }
    }
    @Test fun compressedNativeLibrariesLoadWithoutOnnxSymbolCollision() {
        System.loadLibrary("kai_pocket0")
        System.loadLibrary("sherpa-onnx-jni")
        assertNotNull(ai.onnxruntime.OrtEnvironment.getEnvironment())
    }
    @Test fun sharedStoreLeasesSerializeUiAndWorkerAndReopenEncryptedState()= runBlocking<Unit> {
        fixture { context ->
            val a=KaiStores.acquire(context);val b=KaiStores.acquire(context)
            assertSame(a.store,b.store);a.store.load();b.store.load()
            coroutineScope { (1..8).map { index -> launch(Dispatchers.IO) { b.store.saveProject(null,"Project $index","Secret $index") } }.joinAll() }
            assertEquals(8,a.store.state.value.projects.size);a.close()
            val c=b.store.createConversation();b.store.addDocument(ProjectDocument(name="fixture.txt",mimeType="text/plain",conversationId=c.id,text="fixture private content"));b.close()
            val reopened=KaiStores.acquire(context)
            try { reopened.store.load();assertEquals(8,reopened.store.state.value.projects.size);assertEquals("fixture private content",reopened.store.state.value.documents.single().text) } finally { reopened.close() }
            assertFalse(File(context.noBackupFilesDir,"kai-state/memory.db").readBytes().toString(Charsets.ISO_8859_1).contains("fixture private content"))
        }
    }
    @Test fun taskPauseResumePreservesFinishedStepAndRejectsStaleWorker()= runBlocking<Unit> {
        fixture { context ->
            val handle=KaiStores.acquire(context);val store=handle.store
            try {
                store.load();configure(store)
                val run=store.createRun("Two steps","Read then summarize",steps=listOf("Read fixture","Summarize fixture"))
                val entered=CompletableDeferred<Unit>();var calls=0
                val job=launch { TaskAgentEngine(store,compactContext={false},complete={_,_ -> calls++;if(calls==2) { entered.complete(Unit);awaitCancellation() };Completion("""{"type":"reply","text":"First saved output"}""","stop") }).run(run.id,"first") }
                withTimeout(30000) { entered.await() };store.setRunStatus(run.id,RunStatus.PAUSED);job.cancelAndJoin()
                assertEquals(StepStatus.COMPLETED,store.state.value.runs.single().steps.first().status)
                assertEquals(RunStatus.PAUSED,store.state.value.runs.single().status)
                store.setRunStatus(run.id,RunStatus.QUEUED)
                var resumed=0
                val result=TaskAgentEngine(store,compactContext={false},complete={_,messages -> resumed++;assertTrue(messages.any { "First saved output" in it.content });Completion("""{"type":"reply","text":"Final result"}""","stop") }).run(run.id,"second")
                assertEquals(RunStatus.COMPLETED,result?.status);assertEquals(1,resumed)
                assertNotNull(runCatching { store.editRun(run.id,"first") { it } }.exceptionOrNull())
                assertEquals(3,store.state.value.conversations.first { it.id==run.conversationId }.messages.size)
            } finally { handle.close() }
        }
    }
    @Test fun ambiguousSubmittedActionIsRecoveredAndNeverReplayed()= runBlocking<Unit> {
        fixture { context ->
            var handle=KaiStores.acquire(context);var store=handle.store
            store.load();configure(store);store.updateSettings { it.copy(composioApiKey="fixture",composioUserId="owner",connectionGeneration="g") }
            val run=store.createRun("Send once","Send the reviewed fixture",steps=listOf("Send"));store.claimRun(run.id,"old");store.editRun(run.id,"old") { it.copy(steps=it.steps.map { step -> step.copy(contextRevision=WorkspaceRules.contextRevision(store.state.value,run.projectId,run.conversationId)) }) }
            val action=store.prepareRunAction(run.id,"old",ActionIntent(toolName="send",accountId="account",ownerId="owner",connectionGeneration="g"),"Send fixture","{}",false)
            store.approveTask(action.id);store.beginRunAction(run.id,"old",action.id)
            handle.close();handle=KaiStores.acquire(context);store=handle.store
            try {
                store.load();assertEquals(TaskStatus.UNKNOWN,store.state.value.tasks.single().status)
                store.updateSettings { it.copy(composioApiKey="") }
                var calls=0
                val result=TaskAgentEngine(store,compactContext={false},complete={_,_ -> calls++;error("Must not call inference") }).run(run.id,"new")
                assertEquals(RunStatus.BLOCKED,result?.status);assertEquals(0,calls)
                val skipped=store.skipRunStep(run.id);assertEquals(RunStatus.COMPLETED,skipped.status)
                assertEquals(TaskStatus.UNKNOWN,store.state.value.tasks.single().status)
            } finally { handle.close() }
        }
    }
    @Test fun routinesAreIdempotentSkipBacklogAndDoNotOverlap()= runBlocking<Unit> {
        fixture { context -> val handle=KaiStores.acquire(context);val store=handle.store
            try { store.load();val routine=store.saveRoutine(AgentRoutine(title="Briefing",goal="Summarize",nextRunAt=0))
                val first=store.claimRoutine(routine.id,routine.revision,routine.nextRunAt,routine.nextRunAt+86400000)!!
                val again=store.claimRoutine(routine.id,routine.revision,routine.nextRunAt,routine.nextRunAt+86400000)!!
                assertEquals(first.id,again.id);assertEquals(1,store.state.value.runs.size)
                val next=store.state.value.routines.single();assertTrue(next.nextRunAt>routine.nextRunAt+86400000)
                assertNull(store.claimRoutine(next.id,next.revision,next.nextRunAt,next.nextRunAt));assertEquals(1,store.state.value.runs.size)
                val changed=store.saveRoutine(next.copy(enabled=false));assertNull(store.claimRoutine(next.id,next.revision,next.nextRunAt,next.nextRunAt))
                assertFalse(changed.enabled)
            } finally { handle.close() }
        }
    }
    @Test fun projectForgetAndExportImportKeepDeletedFilesAndMemoryOut()= runBlocking<Unit> {
        fixture { context -> val handle=KaiStores.acquire(context);val store=handle.store
            try { store.load();val project=store.saveProject(null,"Project","Private note")
                val chat=store.createConversation(projectId=project.id)
                val doc=store.addDocument(ProjectDocument(name="secret.txt",mimeType="text/plain",projectId=project.id,text="Secret file"))
                store.appendMessage(chat.id,"user","Remember blue",documentIds=listOf(doc.id));store.remember("Blue belongs here",scope=WorkspaceRules.scope(project.id))
                val draft=store.createTask("Unsubmitted action","Private draft",conversationId=chat.id)
                store.updateSettings { it.copy(composioApiKey="fixture",composioUserId="owner",connectionGeneration="g") }
                val run=store.createRun("Project action","Private instructions",projectId=project.id,steps=listOf("Send"))
                store.claimRun(run.id,"lease")
                store.editRun(run.id,"lease") { it.copy(steps=it.steps.map { step -> step.copy(contextRevision=WorkspaceRules.contextRevision(store.state.value,run.projectId,run.conversationId)) }) }
                val intent=ActionIntent(toolName="send",accountId="account",ownerId="owner",connectionGeneration="g")
                val sent=store.prepareRunAction(run.id,"lease",intent,"Submitted action","{}",false)
                store.approveTask(sent.id);store.beginRunAction(run.id,"lease",sent.id)
                store.recordRunAction(sent.id,ActionReceipt(intent.idempotencyKey,status="accepted"),TaskStatus.ACCEPTED,"Private result")
                val backup=store.exportJson();store.deleteDocument(doc.id);store.importJson(backup)
                assertTrue(store.state.value.documents.isEmpty())
                store.deleteProject(project.id)
                assertFalse(store.state.value.tasks.any { it.id==draft.id })
                assertTrue(store.state.value.tasks.single().instructions.isBlank());assertTrue(store.state.value.tasks.single().resultContext.isBlank())
                store.recordRunAction(sent.id,ActionReceipt(intent.idempotencyKey,status="succeeded"),TaskStatus.SUCCEEDED,"Late private result")
                store.importJson(backup)
                assertTrue(store.state.value.projects.isEmpty());assertTrue(store.state.value.documents.isEmpty())
                assertFalse(store.state.value.memories.any { it.scope==WorkspaceRules.scope(project.id) })
                assertFalse(store.state.value.tasks.any { it.id==draft.id })
                assertEquals(TaskStatus.SUCCEEDED,store.state.value.tasks.single().status)
                assertEquals(2,store.state.value.tasks.single().receipts.size)
                assertTrue(store.state.value.tasks.single().resultContext.isBlank());assertTrue(store.state.value.tasks.single().instructions.isBlank())
            } finally { handle.close() }
        }
    }
    @Test fun taskAsksForInputAndContinuesWithUserDirection()= runBlocking<Unit> {
        fixture { context -> val handle=KaiStores.acquire(context);val store=handle.store
            try { store.load();configure(store);val run=store.createRun("Question","Make a plan",steps=listOf("Plan"))
                val blocked=TaskAgentEngine(store,compactContext={false},complete={_,_ -> Completion("""{"type":"needs_input","text":"Which day?"}""","stop") }).run(run.id,"one")
                assertEquals(RunStatus.BLOCKED,blocked?.status)
                store.directRun(run.id,"Monday")
                val completed=TaskAgentEngine(store,compactContext={false},complete={_,messages -> assertTrue(messages.last().content.contains("Monday"));Completion("""{"type":"reply","text":"Monday plan"}""","stop") }).run(run.id,"two")
                assertEquals(RunStatus.COMPLETED,completed?.status)
            } finally { handle.close() }
        }
    }
    @Test fun cameraPhotoAndPdfUseBundledTextRecognition()= runBlocking<Unit> {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(context.cacheDir,"camera").apply { mkdirs() }
        val file=File(directory,"ocr-fixture.jpg")
        val bitmap=Bitmap.createBitmap(1000,400,Bitmap.Config.ARGB_8888)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK;textSize=64f;typeface=Typeface.DEFAULT_BOLD }
        Canvas(bitmap).apply { drawColor(Color.WHITE);drawText("KAI PROJECT ALPHA",40f,150f,paint);drawText("Meeting at 10 AM",40f,250f,paint) }
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,95,it) }
            val photo=DocumentImporter(context).read(FileProvider.getUriForFile(context,"${context.packageName}.files",file),"","fixture",true)
            assertTrue(photo.text.contains("ALPHA"));assertTrue(photo.imageBase64.isNotBlank())
            val pdf=File(directory,"ocr-fixture.pdf")
            val document=android.graphics.pdf.PdfDocument();val page=document.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(1000,400,1).create())
            page.canvas.drawBitmap(bitmap,0f,0f,null);document.finishPage(page);pdf.outputStream().use(document::writeTo);document.close()
            try { val read=DocumentImporter(context).read(FileProvider.getUriForFile(context,"${context.packageName}.files",pdf),"","fixture");assertTrue(read.text.contains("ALPHA"));assertEquals("application/pdf",read.mimeType) } finally { pdf.delete() }
        } finally { bitmap.recycle();file.delete() }
    }
    private suspend fun configure(store: KaiStore) { store.updateSettings { it.copy(selectedProvider=Provider.OPENAI,openai=ProviderProfile("https://example.com/v1","fixture-secret","fixture-model"),memoryMode=MemoryMode.OFF) } }
    private suspend fun fixture(block: suspend (Context)->Unit) {
        val app=InstrumentationRegistry.getInstrumentation().targetContext;val id=UUID.randomUUID().toString()
        val directory=File(app.noBackupFilesDir,"workspace-test-$id").apply { mkdirs() };val identity=app.packageName+".fixture."+id
        val context=object: ContextWrapper(app) { override fun getApplicationContext(): Context=this;override fun getNoBackupFilesDir()=directory;override fun getPackageName()=identity }
        try { block(context) } finally { directory.deleteRecursively();KeyStore.getInstance("AndroidKeyStore").apply { load(null);deleteEntry("$identity.kai.db.wrap.v1");deleteEntry("$identity.kai.state.aes.v1") } }
    }
}
