package com.example.domain.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class ActivityCategory(val label: String) {
    AGENT_EXECUTION("Agent Execution"),
    SCRIPT_ACTIVITY("Script Activity"),
    SECURITY_EVENT("Security Event")
}

data class DecryptedAuditEntry(
    val id: String,
    val sequence: Long,
    val timestampMillis: Long,
    val category: ActivityCategory,
    val action: String,
    val target: String,
    val status: String,
    val durationMs: Double,
    val maskedSummary: String,
    val maskedDetails: String,
    val redactionsCount: Int,
    val redactedCategories: List<String>,
    val prevHash: String,
    val entryHash: String,
    val chainValid: Boolean,
    val cipherPreview: String
)

data class AuditChainVerification(
    val totalRecords: Int,
    val validRecords: Int,
    val tamperedCount: Int,
    val isChainIntact: Boolean,
    val keyProtectionMode: String
)

interface AuditKeyProvider {
    val protectionMode: String
    fun getOrCreateKey(): SecretKey
}

/**
 * Hardware-backed Android Keystore AES-256-GCM key provider.
 * Falls back cleanly to a process-scoped AES-256 key only when running in
 * non-Keystore JVM/Robolectric test environments.
 */
class AndroidKeystoreAuditKeyProvider(
    private val alias: String = DEFAULT_KEY_ALIAS
) : AuditKeyProvider {

    private var testFallbackKey: SecretKey? = null

    override val protectionMode: String
        get() = if (testFallbackKey != null) {
            "Test/JVM AES-256-GCM"
        } else {
            "Hardware Keystore AES-256-GCM"
        }

    @Synchronized
    override fun getOrCreateKey(): SecretKey {
        testFallbackKey?.let { return it }

        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = keyStore.getKey(alias, null) as? SecretKey
            if (existing != null) {
                existing
            } else {
                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE
                )
                val spec = KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
                keyGenerator.init(spec)
                keyGenerator.generateKey()
            }
        } catch (_: Exception) {
            // Only reached in headless JVM/Robolectric test harnesses lacking AndroidKeyStore
            val keyGen = KeyGenerator.getInstance("AES")
            keyGen.init(256)
            keyGen.generateKey().also { testFallbackKey = it }
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val DEFAULT_KEY_ALIAS = "xcraft_sovereign_audit_aes256_v1"
    }
}

class InMemoryAesKeyProvider : AuditKeyProvider {
    private val secretKey: SecretKey by lazy {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        keyGen.generateKey()
    }

    override val protectionMode: String = "In-Memory AES-256-GCM"

    override fun getOrCreateKey(): SecretKey = secretKey
}

/**
 * Tamper-evident, AES-256-GCM encrypted audit logger for AI Agent executions,
 * sandboxed script activity, and firewall security intercepts.
 *
 * All sensitive tokens, keys, credentials, and PII are masked by [SensitiveDataMasker]
 * prior to JSON serialization and authenticated encryption.
 */
class EncryptedActivityLogger(
    private val storageFile: File,
    private val keyProvider: AuditKeyProvider = AndroidKeystoreAuditKeyProvider()
) {
    private val lock = Any()
    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        val safeMsg = SensitiveDataMasker.maskText(throwable.localizedMessage ?: "unknown")
        Log.e(TAG, "Unhandled audit logger coroutine failure: $safeMsg")
    }
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + coroutineExceptionHandler)

    private val _recordsFlow = MutableStateFlow<List<DecryptedAuditEntry>>(emptyList())
    val recordsFlow: StateFlow<List<DecryptedAuditEntry>> = _recordsFlow.asStateFlow()

    private val _verificationFlow = MutableStateFlow(
        AuditChainVerification(
            totalRecords = 0,
            validRecords = 0,
            tamperedCount = 0,
            isChainIntact = true,
            keyProtectionMode = keyProvider.protectionMode
        )
    )
    val verificationFlow: StateFlow<AuditChainVerification> = _verificationFlow.asStateFlow()

    init {
        ioScope.launch {
            runCatching { refreshFromDisk() }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Failed to load initial audit vault: ${SensitiveDataMasker.maskText(e.localizedMessage ?: "unknown")}")
                }
        }
    }

    /**
     * Logs an AI agent execution (prompt screening, model routing, response/fallback/block)
     * asynchronously so callers never block UI threads.
     */
    fun logAgentExecutionAsync(
        action: String,
        model: String,
        role: String,
        status: String,
        durationMs: Double,
        rawPrompt: String,
        rawOutputOrError: String,
        preRedactionsCount: Int = 0
    ) {
        ioScope.launch {
            try {
                logAgentExecution(
                    action = action,
                    model = model,
                    role = role,
                    status = status,
                    durationMs = durationMs,
                    rawPrompt = rawPrompt,
                    rawOutputOrError = rawOutputOrError,
                    preRedactionsCount = preRedactionsCount
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Async agent audit logging failed: ${SensitiveDataMasker.maskText(e.localizedMessage ?: "unknown")}")
            }
        }
    }

    /**
     * Logs a script activity event (audit, run, block, save, delete) asynchronously.
     */
    fun logScriptActivityAsync(
        action: String,
        scriptTitle: String,
        category: String,
        status: String,
        durationMs: Double = 0.0,
        rawCodeOrCommand: String,
        rawOutput: String = ""
    ) {
        ioScope.launch {
            try {
                logScriptActivity(
                    action = action,
                    scriptTitle = scriptTitle,
                    category = category,
                    status = status,
                    durationMs = durationMs,
                    rawCodeOrCommand = rawCodeOrCommand,
                    rawOutput = rawOutput
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Async script audit logging failed: ${SensitiveDataMasker.maskText(e.localizedMessage ?: "unknown")}")
            }
        }
    }

    /**
     * Logs a security/firewall event asynchronously.
     */
    fun logSecurityEventAsync(
        action: String,
        target: String,
        status: String,
        rawPayload: String,
        findings: List<String> = emptyList()
    ) {
        ioScope.launch {
            try {
                logSecurityEvent(
                    action = action,
                    target = target,
                    status = status,
                    rawPayload = rawPayload,
                    findings = findings
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Async security audit logging failed: ${SensitiveDataMasker.maskText(e.localizedMessage ?: "unknown")}")
            }
        }
    }

    suspend fun logAgentExecution(
        action: String,
        model: String,
        role: String,
        status: String,
        durationMs: Double,
        rawPrompt: String,
        rawOutputOrError: String,
        preRedactionsCount: Int = 0
    ): DecryptedAuditEntry = withContext(Dispatchers.IO) {
        val maskedModel = SensitiveDataMasker.mask(model)
        val maskedRole = SensitiveDataMasker.mask(role)
        val maskedPrompt = SensitiveDataMasker.mask(rawPrompt.take(800))
        val maskedOutput = SensitiveDataMasker.mask(rawOutputOrError.take(1500))

        val totalRedactions = preRedactionsCount +
            maskedModel.redactionsCount +
            maskedRole.redactionsCount +
            maskedPrompt.redactionsCount +
            maskedOutput.redactionsCount

        val categories = (maskedModel.redactedCategories +
            maskedRole.redactedCategories +
            maskedPrompt.redactedCategories +
            maskedOutput.redactedCategories).distinct()

        val summary = "Role: ${maskedRole.maskedText} | Prompt: ${maskedPrompt.maskedText.replace('\n', ' ').take(180)}"
        val details = buildString {
            appendLine("Model: ${maskedModel.maskedText}")
            appendLine("Role: ${maskedRole.maskedText}")
            appendLine("Prompt: ${maskedPrompt.maskedText}")
            if (maskedOutput.maskedText.isNotBlank()) {
                append("Output: ${maskedOutput.maskedText}")
            }
        }.trim()

        appendEncryptedRecord(
            category = ActivityCategory.AGENT_EXECUTION,
            action = action,
            target = maskedModel.maskedText,
            status = status,
            durationMs = durationMs,
            maskedSummary = summary,
            maskedDetails = details,
            redactionsCount = totalRedactions,
            redactedCategories = categories
        )
    }

    suspend fun logScriptActivity(
        action: String,
        scriptTitle: String,
        category: String,
        status: String,
        durationMs: Double = 0.0,
        rawCodeOrCommand: String,
        rawOutput: String = ""
    ): DecryptedAuditEntry = withContext(Dispatchers.IO) {
        val maskedTitle = SensitiveDataMasker.mask(scriptTitle.ifBlank { "Untitled Script" })
        val maskedCode = SensitiveDataMasker.mask(rawCodeOrCommand.take(800))
        val maskedOut = SensitiveDataMasker.mask(rawOutput.take(1500))

        val totalRedactions = maskedTitle.redactionsCount +
            maskedCode.redactionsCount +
            maskedOut.redactionsCount

        val categories = (maskedTitle.redactedCategories +
            maskedCode.redactedCategories +
            maskedOut.redactedCategories).distinct()

        val summary = buildString {
            append("Category: $category")
            if (maskedOut.maskedText.isNotBlank()) {
                append(" | ${maskedOut.maskedText.replace('\n', ' ').take(180)}")
            } else if (maskedCode.maskedText.isNotBlank()) {
                append(" | ${maskedCode.maskedText.replace('\n', ' ').take(180)}")
            }
        }
        val details = buildString {
            appendLine("Script: ${maskedTitle.maskedText} ($category)")
            if (maskedCode.maskedText.isNotBlank()) {
                appendLine("Input/Snippet: ${maskedCode.maskedText}")
            }
            if (maskedOut.maskedText.isNotBlank()) {
                append("Result: ${maskedOut.maskedText}")
            }
        }.trim()

        appendEncryptedRecord(
            category = ActivityCategory.SCRIPT_ACTIVITY,
            action = action,
            target = maskedTitle.maskedText,
            status = status,
            durationMs = durationMs,
            maskedSummary = summary,
            maskedDetails = details,
            redactionsCount = totalRedactions,
            redactedCategories = categories
        )
    }

    suspend fun logSecurityEvent(
        action: String,
        target: String,
        status: String,
        rawPayload: String,
        findings: List<String> = emptyList()
    ): DecryptedAuditEntry = withContext(Dispatchers.IO) {
        val maskedTarget = SensitiveDataMasker.mask(target)
        val maskedPayload = SensitiveDataMasker.mask(rawPayload.take(800))
        val maskedFindings = findings.map { SensitiveDataMasker.mask(it) }

        val totalRedactions = maskedTarget.redactionsCount +
            maskedPayload.redactionsCount +
            maskedFindings.sumOf { it.redactionsCount }

        val categories = (maskedTarget.redactedCategories +
            maskedPayload.redactedCategories +
            maskedFindings.flatMap { it.redactedCategories }).distinct()

        val summary = if (maskedFindings.isNotEmpty()) {
            maskedFindings.joinToString("; ") { it.maskedText }.take(220)
        } else {
            maskedPayload.maskedText.replace('\n', ' ').take(220)
        }

        val details = buildString {
            appendLine("Target: ${maskedTarget.maskedText}")
            appendLine("Payload: ${maskedPayload.maskedText}")
            if (maskedFindings.isNotEmpty()) {
                append("Findings: ${maskedFindings.joinToString(" | ") { it.maskedText }}")
            }
        }.trim()

        appendEncryptedRecord(
            category = ActivityCategory.SECURITY_EVENT,
            action = action,
            target = maskedTarget.maskedText,
            status = status,
            durationMs = 0.0,
            maskedSummary = summary,
            maskedDetails = details,
            redactionsCount = totalRedactions,
            redactedCategories = categories
        )
    }

    fun verifyChainIntegrity(): AuditChainVerification = synchronized(lock) {
        val decoded = readAndDecryptAllLocked()
        _recordsFlow.value = decoded.entries.asReversed()
        _verificationFlow.value = decoded.verification
        decoded.verification
    }

    fun clearVault() = synchronized(lock) {
        if (storageFile.exists()) {
            storageFile.delete()
        }
        _recordsFlow.value = emptyList()
        _verificationFlow.value = AuditChainVerification(
            totalRecords = 0,
            validRecords = 0,
            tamperedCount = 0,
            isChainIntact = true,
            keyProtectionMode = keyProvider.protectionMode
        )
    }

    fun refreshFromDisk() = synchronized(lock) {
        val decoded = readAndDecryptAllLocked()
        _recordsFlow.value = decoded.entries.asReversed()
        _verificationFlow.value = decoded.verification
    }

    private fun appendEncryptedRecord(
        category: ActivityCategory,
        action: String,
        target: String,
        status: String,
        durationMs: Double,
        maskedSummary: String,
        maskedDetails: String,
        redactionsCount: Int,
        redactedCategories: List<String>
    ): DecryptedAuditEntry = synchronized(lock) {
        storageFile.parentFile?.mkdirs()

        val existingLines = if (storageFile.exists()) {
            storageFile.readLines(StandardCharsets.UTF_8).filter { it.isNotBlank() }
        } else {
            emptyList()
        }

        val lastEnvelope = existingLines.lastOrNull()?.let { runCatching { JSONObject(it) }.getOrNull() }
        val nextSequence = (lastEnvelope?.optLong("seq", 0L) ?: 0L) + 1L
        val prevHash = lastEnvelope?.optString("hash", GENESIS_HASH) ?: GENESIS_HASH

        val id = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()

        val payloadJson = JSONObject().apply {
            put("action", action)
            put("target", target)
            put("status", status)
            put("durationMs", durationMs)
            put("summary", maskedSummary)
            put("details", maskedDetails)
            put("redactionsCount", redactionsCount)
            put("redactedCategories", JSONArray(redactedCategories))
        }

        val aad = buildAad(id, timestamp, category, nextSequence)
        val cipherBase64 = encryptAesGcm(payloadJson.toString().toByteArray(StandardCharsets.UTF_8), aad)
        val entryHash = computeRecordHash(prevHash, id, timestamp, category, nextSequence, cipherBase64)

        val envelope = JSONObject().apply {
            put("id", id)
            put("seq", nextSequence)
            put("ts", timestamp)
            put("cat", category.name)
            put("prev", prevHash)
            put("hash", entryHash)
            put("cipher", cipherBase64)
        }

        val updatedLines = (existingLines + envelope.toString()).takeLast(MAX_STORED_RECORDS)
        storageFile.writeText(updatedLines.joinToString("\n") + "\n", StandardCharsets.UTF_8)

        val decoded = readAndDecryptAllLocked()
        _recordsFlow.value = decoded.entries.asReversed()
        _verificationFlow.value = decoded.verification

        DecryptedAuditEntry(
            id = id,
            sequence = nextSequence,
            timestampMillis = timestamp,
            category = category,
            action = action,
            target = target,
            status = status,
            durationMs = durationMs,
            maskedSummary = maskedSummary,
            maskedDetails = maskedDetails,
            redactionsCount = redactionsCount,
            redactedCategories = redactedCategories,
            prevHash = prevHash,
            entryHash = entryHash,
            chainValid = true,
            cipherPreview = cipherBase64.take(44) + "..."
        )
    }

    private data class ReadResult(
        val entries: List<DecryptedAuditEntry>,
        val verification: AuditChainVerification
    )

    private fun readAndDecryptAllLocked(): ReadResult {
        if (!storageFile.exists()) {
            return ReadResult(
                entries = emptyList(),
                verification = AuditChainVerification(
                    totalRecords = 0,
                    validRecords = 0,
                    tamperedCount = 0,
                    isChainIntact = true,
                    keyProtectionMode = keyProvider.protectionMode
                )
            )
        }

        val lines = storageFile.readLines(StandardCharsets.UTF_8).filter { it.isNotBlank() }
        val entries = mutableListOf<DecryptedAuditEntry>()
        var expectedPrevHash: String? = null
        var validCount = 0
        var tamperedCount = 0

        for (line in lines) {
            try {
                val env = JSONObject(line)
                val id = env.getString("id")
                val seq = env.getLong("seq")
                val ts = env.getLong("ts")
                val cat = ActivityCategory.valueOf(env.getString("cat"))
                val prev = env.getString("prev")
                val recordedHash = env.getString("hash")
                val cipherBase64 = env.getString("cipher")

                val expectedHash = computeRecordHash(prev, id, ts, cat, seq, cipherBase64)
                val linkMatches = (expectedPrevHash == null || prev == expectedPrevHash)
                val hashMatches = (recordedHash == expectedHash)

                val aad = buildAad(id, ts, cat, seq)
                val decryptedBytes = decryptAesGcm(cipherBase64, aad)
                val payload = JSONObject(String(decryptedBytes, StandardCharsets.UTF_8))

                val catArray = payload.optJSONArray("redactedCategories")
                val categories = buildList {
                    if (catArray != null) {
                        for (i in 0 until catArray.length()) {
                            add(catArray.getString(i))
                        }
                    }
                }

                val isEntryValid = linkMatches && hashMatches
                if (isEntryValid) validCount++ else tamperedCount++

                entries.add(
                    DecryptedAuditEntry(
                        id = id,
                        sequence = seq,
                        timestampMillis = ts,
                        category = cat,
                        action = payload.optString("action", "ACTIVITY"),
                        target = payload.optString("target", "Unknown"),
                        status = payload.optString("status", "RECORDED"),
                        durationMs = payload.optDouble("durationMs", 0.0),
                        maskedSummary = payload.optString("summary", ""),
                        maskedDetails = payload.optString("details", ""),
                        redactionsCount = payload.optInt("redactionsCount", 0),
                        redactedCategories = categories,
                        prevHash = prev,
                        entryHash = recordedHash,
                        chainValid = isEntryValid,
                        cipherPreview = cipherBase64.take(44) + "..."
                    )
                )
                expectedPrevHash = recordedHash
            } catch (_: Exception) {
                tamperedCount++
                entries.add(
                    DecryptedAuditEntry(
                        id = "corrupted-${entries.size + 1}",
                        sequence = -1L,
                        timestampMillis = 0L,
                        category = ActivityCategory.SECURITY_EVENT,
                        action = "TAMPER_OR_KEY_MISMATCH",
                        target = "Audit Vault",
                        status = "TAMPERED",
                        durationMs = 0.0,
                        maskedSummary = "Encrypted log record failed GCM authentication or hash chain verification.",
                        maskedDetails = "Record integrity failure detected during AES-256-GCM decryption.",
                        redactionsCount = 0,
                        redactedCategories = emptyList(),
                        prevHash = "",
                        entryHash = "",
                        chainValid = false,
                        cipherPreview = line.take(44) + "..."
                    )
                )
                expectedPrevHash = null
            }
        }

        return ReadResult(
            entries = entries,
            verification = AuditChainVerification(
                totalRecords = lines.size,
                validRecords = validCount,
                tamperedCount = tamperedCount,
                isChainIntact = tamperedCount == 0,
                keyProtectionMode = keyProvider.protectionMode
            )
        )
    }

    private fun encryptAesGcm(plaintext: ByteArray, aad: ByteArray): String {
        val key = keyProvider.getOrCreateKey()
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        // AndroidKeyStore keys with setRandomizedEncryptionRequired(true) require Keystore-generated IVs.
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv ?: throw IllegalStateException("Cipher failed to generate a randomized IV")
        require(iv.size in 1..255) { "Unexpected GCM IV size: ${iv.size}" }
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintext)

        val combined = ByteArray(2 + iv.size + ciphertext.size)
        combined[0] = CIPHER_FORMAT_V2
        combined[1] = iv.size.toByte()
        System.arraycopy(iv, 0, combined, 2, iv.size)
        System.arraycopy(ciphertext, 0, combined, 2 + iv.size, ciphertext.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decryptAesGcm(cipherBase64: String, aad: ByteArray): ByteArray {
        val combined = Base64.decode(cipherBase64, Base64.NO_WRAP)
        val key = keyProvider.getOrCreateKey()

        if (combined.size > 3 && combined[0] == CIPHER_FORMAT_V2) {
            val ivLen = combined[1].toInt() and 0xFF
            if (ivLen in 1..32 && combined.size > 2 + ivLen) {
                val iv = combined.copyOfRange(2, 2 + ivLen)
                val ciphertext = combined.copyOfRange(2 + ivLen, combined.size)
                val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
                cipher.updateAAD(aad)
                return cipher.doFinal(ciphertext)
            }
        }

        // Legacy fallback: raw 12-byte IV prefix
        require(combined.size > GCM_IV_LENGTH_BYTES) { "Malformed encrypted payload" }
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH_BYTES)
        val ciphertext = combined.copyOfRange(GCM_IV_LENGTH_BYTES, combined.size)

        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    private fun buildAad(
        id: String,
        timestampMillis: Long,
        category: ActivityCategory,
        sequence: Long
    ): ByteArray {
        return "$id|$timestampMillis|${category.name}|$sequence".toByteArray(StandardCharsets.UTF_8)
    }

    private fun computeRecordHash(
        prevHash: String,
        id: String,
        timestampMillis: Long,
        category: ActivityCategory,
        sequence: Long,
        cipherBase64: String
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = "$prevHash|$id|$timestampMillis|${category.name}|$sequence|$cipherBase64"
        val hashBytes = digest.digest(input.toByteArray(StandardCharsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "EncryptedActivityLogger"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val CIPHER_FORMAT_V2: Byte = 0x02
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val GENESIS_HASH = "GENESIS_00000000000000000000000000000000"
        private const val MAX_STORED_RECORDS = 300
        private const val LOG_FILE_NAME = "xcraft_secure_audit.enc.jsonl"

        @Volatile
        private var instance: EncryptedActivityLogger? = null

        fun getInstance(context: Context): EncryptedActivityLogger {
            return instance ?: synchronized(this) {
                instance ?: EncryptedActivityLogger(
                    storageFile = File(context.applicationContext.filesDir, LOG_FILE_NAME)
                ).also { instance = it }
            }
        }
    }
}
