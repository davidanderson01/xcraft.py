package com.example.domain.terminal

import android.util.Base64
import com.example.domain.security.AndroidKeystoreAuditKeyProvider
import com.example.domain.security.AuditKeyProvider
import com.example.domain.security.SensitiveDataMasker
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

data class EncryptedEnvelope(
    val version: Int = 2,
    val sessionId: String,
    val sourceOrigin: String,
    val title: String,
    val algorithm: String = "AES-256-GCM",
    val ivBase64: String,
    val ciphertextBase64: String,
    val aad: String,
    val sha256Digest: String,
    val redactionsCount: Int,
    val redactedCategories: List<String>,
    val payloadBytes: Int,
    val timestampMillis: Long
) {
    fun toJsonString(pretty: Boolean = false): String {
        val obj = JSONObject().apply {
            put("version", version)
            put("sessionId", sessionId)
            put("sourceOrigin", sourceOrigin)
            put("title", title)
            put("algorithm", algorithm)
            put("iv", ivBase64)
            put("aad", aad)
            put("sha256", sha256Digest)
            put("redactionsCount", redactionsCount)
            put("redactedCategories", JSONArray(redactedCategories))
            put("payloadBytes", payloadBytes)
            put("timestamp", timestampMillis)
            put("ciphertext", ciphertextBase64)
        }
        return if (pretty) obj.toString(2) else obj.toString()
    }
}

data class UnsealedEnvelope(
    val title: String,
    val sourceOrigin: String,
    val sessionId: String,
    val maskedPayload: String,
    val redactionsCount: Int,
    val sha256Verified: Boolean,
    val gcmAuthenticated: Boolean
)

/**
 * Seals and unseals streamed or downloaded terminal payloads using pre-flight
 * [SensitiveDataMasker] scrubbing + AES-256-GCM authenticated encryption + SHA-256 verification.
 */
class EncryptedEnvelopeCodec(
    private val keyProvider: AuditKeyProvider = AndroidKeystoreAuditKeyProvider(ENVELOPE_KEY_ALIAS)
) {
    fun seal(
        sessionId: String,
        sourceOrigin: String,
        title: String,
        rawPayload: String
    ): EncryptedEnvelope {
        val maskedTitle = SensitiveDataMasker.mask(title)
        val maskedPayload = SensitiveDataMasker.mask(rawPayload)
        val totalRedactions = maskedTitle.redactionsCount + maskedPayload.redactionsCount
        val categories = (maskedTitle.redactedCategories + maskedPayload.redactedCategories).distinct()

        val timestamp = System.currentTimeMillis()
        val aadString = "$sessionId|$sourceOrigin|$timestamp"
        val aadBytes = aadString.toByteArray(StandardCharsets.UTF_8)

        val innerJson = JSONObject().apply {
            put("title", maskedTitle.maskedText)
            put("sourceOrigin", sourceOrigin)
            put("payload", maskedPayload.maskedText)
            put("redactionsCount", totalRedactions)
        }.toString()

        val plaintextBytes = innerJson.toByteArray(StandardCharsets.UTF_8)
        val key = keyProvider.getOrCreateKey()
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        // AndroidKeyStore keys with setRandomizedEncryptionRequired(true) require provider-generated IVs.
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv ?: throw IllegalStateException("Cipher failed to generate GCM IV")
        cipher.updateAAD(aadBytes)
        val ciphertext = cipher.doFinal(plaintextBytes)

        val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        val cipherBase64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        val sha256 = computeSha256("$aadString|$ivBase64|$cipherBase64")

        return EncryptedEnvelope(
            sessionId = sessionId,
            sourceOrigin = sourceOrigin,
            title = maskedTitle.maskedText,
            ivBase64 = ivBase64,
            ciphertextBase64 = cipherBase64,
            aad = aadString,
            sha256Digest = sha256,
            redactionsCount = totalRedactions,
            redactedCategories = categories,
            payloadBytes = plaintextBytes.size,
            timestampMillis = timestamp
        )
    }

    fun unseal(envelope: EncryptedEnvelope): UnsealedEnvelope {
        val expectedDigest = computeSha256("${envelope.aad}|${envelope.ivBase64}|${envelope.ciphertextBase64}")
        val digestMatches = expectedDigest == envelope.sha256Digest
        require(digestMatches) { "Encrypted stream integrity check failed (SHA-256 mismatch)" }

        val iv = Base64.decode(envelope.ivBase64, Base64.NO_WRAP)
        val ciphertext = Base64.decode(envelope.ciphertextBase64, Base64.NO_WRAP)
        val aadBytes = envelope.aad.toByteArray(StandardCharsets.UTF_8)

        val key = keyProvider.getOrCreateKey()
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        cipher.updateAAD(aadBytes)
        val plaintext = cipher.doFinal(ciphertext)

        val json = JSONObject(String(plaintext, StandardCharsets.UTF_8))
        return UnsealedEnvelope(
            title = json.optString("title", envelope.title),
            sourceOrigin = json.optString("sourceOrigin", envelope.sourceOrigin),
            sessionId = envelope.sessionId,
            maskedPayload = json.optString("payload", ""),
            redactionsCount = json.optInt("redactionsCount", envelope.redactionsCount),
            sha256Verified = digestMatches,
            gcmAuthenticated = true
        )
    }

    fun chunkEnvelope(envelope: EncryptedEnvelope, chunkSize: Int = 96): List<String> {
        val encoded = envelope.ciphertextBase64
        if (encoded.isEmpty()) return emptyList()
        return encoded.chunked(chunkSize.coerceAtLeast(16))
    }

    fun buildWindowsPowerShellCommand(
        source: String,
        target: String,
        envelope: EncryptedEnvelope
    ): String {
        val safeTarget = target.replace("\"", "").replace(";", "").trim()
        return """
            |# Windows PowerShell Sovereign Summon & Integrity Check
            |${'$'}session = "${envelope.sessionId}"
            |${'$'}expectedSha256 = "${envelope.sha256Digest}"
            |# Pull sanitized package via Python Xcraft CLI or inspect envelope:
            |python -m xcraftpy.summon --source $source --target "$safeTarget" --cipher AES-256-GCM --verify-sha256 ${'$'}expectedSha256
        """.trimMargin()
    }

    private fun computeSha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val ENVELOPE_KEY_ALIAS = "xcraft_terminal_stream_aes256_v1"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
    }
}
