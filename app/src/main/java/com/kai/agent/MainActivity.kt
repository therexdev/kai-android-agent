package com.kai.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.kai.agent.ui.KaiApp
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val model: KaiViewModel by viewModels()
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { model.microphoneResult(it) }
    private var attachmentConversation = ""
    private var attachmentProject = ""
    private var cameraUri: Uri? = null
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val pickAttachment = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null)model.importAttachment(uri,attachmentConversation,attachmentProject,false)
    }
    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        val uri=cameraUri;cameraUri=null
        if(captured && uri!=null)model.importAttachment(uri,attachmentConversation,attachmentProject,true)
        else if(uri!=null)runCatching { contentResolver.delete(uri,null,null) }
    }
    private val exportDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val content = model.consumeExport()
        if(uri != null && content == null) model.reportError("The app restarted before the file was saved. Please export it again.")
        if (uri != null && content != null) lifecycleScope.launch {
            try { withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri, "wt")?.use { it.write(content.toByteArray(Charsets.UTF_8)) } ?: error("The file could not be opened.") }
                android.widget.Toast.makeText(this@MainActivity,"File saved",android.widget.Toast.LENGTH_SHORT).show()
            }
            catch (_: Exception) { model.reportError("The export could not be saved. Choose another location and try again.") }
        }
    }
    private val exportAllReports = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val pending = model.consumeAllChatReportExport()
        if (uri != null && !pending) model.reportError("The app restarted before the report was saved. Please export it again.")
        if (uri != null && pending) lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri, "wt")?.use { model.writeAllChatReports(it) }
                        ?: error("The file could not be opened.")
                }
                android.widget.Toast.makeText(this@MainActivity, "All chats report saved", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                // A partial batch is not a valid report. Do not leave it looking like a successful save.
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    runCatching { android.provider.DocumentsContract.deleteDocument(contentResolver, uri) }
                }
                if (e is kotlinx.coroutines.CancellationException) throw e
                model.reportError("The report could not be saved completely. Discard any partial file and export again.")
            }
        }
    }
    private val importDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                val content = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= 32_000_000) { "This file is too large." }
                            output.write(buffer, 0, count)
                        }
                        output.toString("UTF-8")
                    } ?: error("The file could not be opened.")
                }
                model.importJson(content)
            } catch (_: Exception) { model.reportError("This file could not be imported. Choose a KAI JSON export smaller than 32 MB.") }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        attachmentConversation=savedInstanceState?.getString("attachmentConversation").orEmpty()
        attachmentProject=savedInstanceState?.getString("attachmentProject").orEmpty()
        cameraUri=savedInstanceState?.getString("cameraUri")?.let(Uri::parse)
        enableEdgeToEdge()
        setContent { val state by model.ui.collectAsStateWithLifecycle(); KaiApp(state, model) }
        if (savedInstanceState == null) handleShare(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.events.collect { event -> when (event) {
                    KaiViewModel.AppEvent.RequestMicrophone -> {
                        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) model.microphoneResult(true)
                        else microphone.launch(Manifest.permission.RECORD_AUDIO)
                    }
                    is KaiViewModel.AppEvent.OpenUrl -> {
                        val uri = Uri.parse(event.url)
                        if (uri.scheme == "https") runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }.onFailure { model.reportError("No browser is available to open the sign-in page.") }
                    }
                    KaiViewModel.AppEvent.ExportAllChatReports -> {
                        model.stageAllChatReportExport()
                        exportAllReports.launch("kai-all-chats-report-${java.time.LocalDate.now()}.zip")
                    }
                    is KaiViewModel.AppEvent.ExportJson -> { model.stageExport(event.text); exportDocument.launch(event.fileName) }
                    KaiViewModel.AppEvent.RequestNotifications -> if(android.os.Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(this@MainActivity,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    is KaiViewModel.AppEvent.PickAttachment -> {
                        attachmentConversation=event.conversationId;attachmentProject=event.projectId
                        if(event.camera) {
                            try {
                                val directory=java.io.File(cacheDir,"camera").apply { mkdirs() }
                                directory.listFiles()?.filter { System.currentTimeMillis()-it.lastModified()>86400000 }?.forEach { it.delete() }
                                val file=java.io.File.createTempFile("kai-",".jpg",directory)
                                val uri=androidx.core.content.FileProvider.getUriForFile(this@MainActivity,"$packageName.files",file)
                                cameraUri=uri;takePhoto.launch(uri)
                            } catch(_: Exception) { model.reportError("No camera app is available. You can attach a photo with the file picker.") }
                        } else pickAttachment.launch(arrayOf("text/*","application/octet-stream","application/json","application/pdf","application/vnd.openxmlformats-officedocument.wordprocessingml.document","image/*"))
                    }
                    KaiViewModel.AppEvent.ImportJson -> importDocument.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                } }
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("attachmentConversation",attachmentConversation);outState.putString("attachmentProject",attachmentProject)
        outState.putString("cameraUri",cameraUri?.toString());super.onSaveInstanceState(outState)
    }
    override fun onStart() { super.onStart(); model.onForeground() }
    override fun onStop() { model.onBackground(); super.onStop() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleShare(intent) }
    private fun handleShare(intent: Intent?) {
        if(intent?.action=="com.kai.agent.TASK")intent.getStringExtra("runId")?.let(model::openTask)
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let(model::receiveSharedText)
        }
    }
}
