package com.kai.agent.memory.embedding

import java.security.MessageDigest
import kotlin.math.sqrt

/** These are learned dense vectors; hashing is used only to identify stale source text. */
object EmbeddingMath {
    fun textHash(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun cosine(a: FloatArray, b: FloatArray): Double {
        require(a.isNotEmpty() && a.size == b.size) { "Embedding dimensions must match" }
        var dot = 0.0
        var aNorm = 0.0
        var bNorm = 0.0
        for (i in a.indices) {
            require(a[i].isFinite() && b[i].isFinite()) { "Embeddings must be finite" }
            dot += a[i].toDouble() * b[i]
            aNorm += a[i].toDouble() * a[i]
            bNorm += b[i].toDouble() * b[i]
        }
        if (aNorm <= 0 || bNorm <= 0) return 0.0
        return (dot / sqrt(aNorm * bNorm)).coerceIn(-1.0, 1.0)
    }

    internal fun normalize(vector: FloatArray): FloatArray {
        val magnitude = sqrt(vector.sumOf { it.toDouble() * it })
        require(magnitude > 0 && magnitude.isFinite()) { "Invalid embedding norm" }
        return FloatArray(vector.size) { (vector[it] / magnitude).toFloat() }
    }
}

data class EmbeddingModelInfo(
    val id: String,
    val dimensions: Int,
    val maxWordPieces: Int,
    val modelBytes: Long,
    val modelSha256: String,
    val vocabularySha256: String,
    val sourceRevision: String,
    val license: String,
    val language: String,
)

object BundledEmbeddingModel {
    const val ASSET = "memory/minilm-l6-v2-qint8.onnx"
    const val VOCABULARY = "memory/vocab.txt"
    const val MODEL_ID = "all-MiniLM-L6-v2/5641a788/qint8-arm64/wordpiece-v1/mean-l2/256"
    val info = EmbeddingModelInfo(
        id = MODEL_ID,
        dimensions = 384,
        maxWordPieces = 256,
        modelBytes = 23_026_053,
        modelSha256 = "4278337fd0ff3c68bfb6291042cad8ab363e1d9fbc43dcb499fe91c871902474",
        vocabularySha256 = "07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3",
        sourceRevision = "5641a7880f40ebf4035d05e60c5f9b7a9c272c84",
        license = "Apache-2.0",
        language = "English",
    )
}
