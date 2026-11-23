package com.kai.agent.projects

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Xml
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.kai.agent.data.ProjectDocument
import kotlinx.coroutines.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.xmlpull.v1.XmlPullParser

/** User-selected content only. Original files are temporary; extracted content is encrypted by KaiStore. */
class DocumentImporter(private val context: Context) {
    suspend fun read(uri: Uri, projectId: String, conversationId: String, camera: Boolean=false): ProjectDocument = withContext(Dispatchers.IO) {
        require(uri.scheme=="content") { "Choose a file from the Android file picker." }
        val name=context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { c ->
            if(c.moveToFirst()) c.getString(0) else null }?.take(200) ?: if(camera) "Camera photo.jpg" else "Attachment"
        val bytes=context.contentResolver.openInputStream(uri)?.use { bounded(it,15_000_000) } ?: error("The file could not be opened.")
        val mime=context.contentResolver.getType(uri).orEmpty()
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        var image=""
        val text: String
        val kind: String
        when {
            bytes.take(5).toByteArray().toString(Charsets.US_ASCII)=="%PDF-" -> { text=pdf(bytes);kind="application/pdf" }
            mime.startsWith("image/") || camera || name.substringAfterLast('.').lowercase() in setOf("jpg","jpeg","png","webp","heic") -> {
                val bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder,info,_ ->
                    require(info.size.width>0 && info.size.height>0 && info.size.width.toLong()*info.size.height<=100_000_000) { "This image is too large." }
                    val ratio=minOf(1.0,1600.0/maxOf(info.size.width,info.size.height))
                    decoder.setTargetSize((info.size.width*ratio).toInt().coerceAtLeast(1),(info.size.height*ratio).toInt().coerceAtLeast(1))
                    decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
                }
                try {
                    text=ocr(bitmap).take(200000)
                    val output=ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG,75,output)
                    require(output.size()<=750000) { "This photo is too detailed. Crop it and try again." }
                    image=Base64.encodeToString(output.toByteArray(),Base64.NO_WRAP)
                } finally { bitmap.recycle() }
                kind="image/jpeg"
            }
            name.endsWith(".docx",true) -> { text=docx(bytes);kind="application/vnd.openxmlformats-officedocument.wordprocessingml.document" }
            mime.startsWith("text/") || mime in setOf("application/json","application/xml") || name.substringAfterLast('.').lowercase() in setOf("txt","md","csv","json","log","xml") -> {
                text=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                require('\u0000' !in text && text.length<=200000) { "Choose a text file with at most 200,000 characters." };kind=mime.ifBlank { "text/plain" }
            }
            else -> error("Choose a text, Markdown, CSV, JSON, DOCX, PDF or image file.")
        }
        require(text.isNotBlank() || image.isNotBlank()) { "No readable text was found. Try a clearer scan." }
        ProjectDocument(name=if(camera) "Camera photo ${java.time.LocalDateTime.now().withNano(0)}" else name,
            mimeType=kind,projectId=projectId,conversationId=conversationId,text=text,imageBase64=image,
            sourceKind=if(camera) "camera" else if(kind=="application/pdf" || kind.startsWith("image/")) "OCR" else "file",sha256=hash)
    }
    private suspend fun ocr(bitmap: Bitmap): String {
        val recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try { withTimeout(90_000) { suspendCancellableCoroutine { continuation ->
            recognizer.process(InputImage.fromBitmap(bitmap,0)).addOnSuccessListener { if(continuation.isActive) continuation.resume(it.text) }
                .addOnFailureListener { if(continuation.isActive) continuation.resumeWithException(it) }
        } } } finally { recognizer.close() }
    }
    private suspend fun pdf(bytes: ByteArray): String {
        val file=File.createTempFile("kai-import-",".pdf",context.cacheDir)
        try {
            file.writeBytes(bytes)
            return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { renderer ->
                require(renderer.pageCount in 1..12) { "Import a PDF of up to 12 pages. Split longer documents first." }
                buildString {
                    repeat(renderer.pageCount) { index ->
                        currentCoroutineContext().ensureActive()
                        renderer.openPage(index).use { page ->
                            val ratio=1600.0/maxOf(page.width,page.height)
                            val bitmap=Bitmap.createBitmap((page.width*ratio).toInt().coerceAtLeast(1),(page.height*ratio).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
                            try { bitmap.eraseColor(Color.WHITE);page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                append("\n[Page ${index+1}]\n");append(ocr(bitmap)) } finally { bitmap.recycle() }
                        }
                        require(length<=200000) { "This PDF contains too much text. Split it into smaller files." }
                    }
                }
            } }
        } finally { file.delete() }
    }
    private fun docx(bytes: ByteArray): String = ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        var count=0
        var skipped=0
        while(true) {
            val entry=zip.nextEntry ?: break
            require(++count<=2000) { "This document has too many entries." }
            if(entry.name!="word/document.xml") { skipped+=bounded(zip,10_000_000-skipped).size;require(skipped<=10_000_000) { "This document is too large when expanded." };continue }
            val xml=bounded(zip,2_000_000)
            val parser=Xml.newPullParser().apply { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,true);setInput(ByteArrayInputStream(xml),"UTF-8") }
            val result=StringBuilder();var inText=false
            while(parser.eventType!=XmlPullParser.END_DOCUMENT) {
                if(parser.eventType==XmlPullParser.DOCDECL) error("Document entities are not supported.")
                when(parser.eventType) {
                    XmlPullParser.START_TAG -> { inText=parser.name=="t";if(parser.name in setOf("br","tab")) result.append(' ') }
                    XmlPullParser.TEXT -> if(inText) result.append(parser.text)
                    XmlPullParser.END_TAG -> { if(parser.name=="p") result.append('\n');inText=false }
                }
                require(result.length<=200000) { "This document is too long. Split it into smaller files." };parser.nextToken()
            }
            return result.toString()
        }
        error("This DOCX file has no readable document.")
    }
    private fun bounded(input: InputStream, limit: Int): ByteArray {
        val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true) { val count=input.read(buffer);if(count<0)break;require(out.size()+count<=limit) { "Choose a file smaller than 15 MB." };out.write(buffer,0,count) }
        return out.toByteArray()
    }
}
