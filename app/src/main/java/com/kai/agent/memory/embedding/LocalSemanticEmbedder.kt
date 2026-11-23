package com.kai.agent.memory.embedding

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.LongBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Offline learned sentence embeddings. Neither construction nor inference uses a network client.
 * Load and inference run off the UI thread; one session serializes native calls. A cancelled call
 * may finish inside ORT, but its output is discarded before it can become a persisted vector.
 * The owner must check current fact revision, hash, scope and deletion state before saving output.
 */
class LocalSemanticEmbedder(context: Context) : AutoCloseable {
    val modelId: String get() = BundledEmbeddingModel.MODEL_ID
    val dimensions: Int get() = BundledEmbeddingModel.info.dimensions
    private val assets = context.applicationContext.assets
    private val lock = Any()
    private var closed = false
    private var session: OrtSession? = null
    private var environment: OrtEnvironment? = null
    private var tokenizer: WordPieceTokenizer? = null
    private var modelMapping: ByteBuffer? = null

    suspend fun embed(text: String): FloatArray = withContext(Dispatchers.Default) {
        require(text.isNotBlank()) { "Cannot embed blank text" }
        require(text.length <= 32_000) { "Memory text exceeds the embedding input limit" }
        ensureActive()
        val jobContext = coroutineContext
        val result = synchronized(lock) {
            check(!closed) { "Embedder is closed" }
            jobContext.ensureActive()
            val current = session ?: loadSession()
            jobContext.ensureActive()
            val encoded = requireNotNull(tokenizer).encode(text)
            val env = requireNotNull(environment)
            val shape = longArrayOf(1L, encoded.inputIds.size.toLong())
            OnnxTensor.createTensor(env, LongBuffer.wrap(encoded.inputIds), shape).use { ids ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(encoded.attentionMask), shape).use { mask ->
                    OnnxTensor.createTensor(env, LongBuffer.wrap(encoded.tokenTypeIds), shape).use { types ->
                        val inputs = mapOf("input_ids" to ids, "attention_mask" to mask, "token_type_ids" to types)
                        current.run(inputs).use { output ->
                            val tensor = output[0] as OnnxTensor
                            val outputShape = tensor.info.shape
                            require(outputShape.contentEquals(longArrayOf(1, encoded.inputIds.size.toLong(), dimensions.toLong()))) {
                                "Unexpected sentence model output" }
                            val buffer = requireNotNull(tensor.floatBuffer) { "Missing model output" }
                            val mean = FloatArray(dimensions)
                            for (token in encoded.inputIds.indices) {
                                for (dimension in mean.indices) mean[dimension] += buffer.get(token * dimensions + dimension)
                            }
                            for (dimension in mean.indices) mean[dimension] /= encoded.inputIds.size.toFloat()
                            EmbeddingMath.normalize(mean)
                        }
                    }
                }
            }
        }
        ensureActive()
        result
    }

    private fun loadSession(): OrtSession {
        val metadata = BundledEmbeddingModel.info
        val vocabularyBytes = assets.open(BundledEmbeddingModel.VOCABULARY).use { it.readBytes() }
        require(sha256(vocabularyBytes) == metadata.vocabularySha256) { "Bundled vocabulary checksum mismatch" }
        tokenizer = WordPieceTokenizer(vocabularyBytes.toString(Charsets.UTF_8).lineSequence().filter { it.isNotEmpty() }.toList(), metadata.maxWordPieces)
        // Gradle noCompress=onnx allows a read-only mapping directly from the APK: no second 23 MB
        // private-file copy and no heap ByteArray holding the full model.
        val mapped = assets.openFd(BundledEmbeddingModel.ASSET).use { descriptor ->
            require(descriptor.declaredLength == metadata.modelBytes) { "Bundled model size mismatch" }
            FileInputStream(descriptor.fileDescriptor).use { file ->
                file.channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.declaredLength)
            }
        }
        require(sha256(mapped.duplicate()) == metadata.modelSha256) { "Bundled model checksum mismatch" }
        val env = OrtEnvironment.getEnvironment()
        val loaded = OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            env.createSession(mapped, options)
        }
        require(loaded.inputNames == setOf("input_ids", "attention_mask", "token_type_ids")) { "Unexpected model inputs" }
        modelMapping = mapped
        environment = env
        session = loaded
        return loaded
    }

    /** Call off the UI thread if inference may currently be running. */
    override fun close() = synchronized(lock) {
        closed = true
        session?.close()
        session = null
        tokenizer = null
        modelMapping = null
        // OrtEnvironment is a process-wide singleton; another embedder may still be using it.
        environment = null
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun sha256(buffer: ByteBuffer): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(buffer)
        return digest.digest().hex()
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
