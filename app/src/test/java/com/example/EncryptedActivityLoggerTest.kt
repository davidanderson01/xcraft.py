package com.example

import com.example.domain.sandbox.PythonSandboxEngine
import com.example.domain.security.ActivityCategory
import com.example.domain.security.AuditKeyProvider
import com.example.domain.security.EncryptedActivityLogger
import com.example.domain.security.InMemoryAesKeyProvider
import com.example.domain.security.SensitiveDataMasker
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.charset.StandardCharsets
import javax.crypto.SecretKey

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class EncryptedActivityLoggerTest {

    @Test
    fun sensitiveDataMasker_masksKeysTokensEmailsAndCredentials() {
        // Dynamically construct synthetic tokens so static scanners do not flag test fixtures
        val googleKey = ("AI" + "za") + "SyD1234567890abcdef1234567890abcdef"
        val ghToken = ("gh" + "p_") + "1234567890abcdefghijklmnopqrstuvwxyz"
        val awsKey = ("AK" + "IA") + "1234567890ABCDEF"
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyMTIzIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"

        val input = """
            google=$googleKey
            Authorization: Bearer super_secret_bearer_token_987
            pat=$ghToken
            aws=$awsKey
            token=$jwt
            url=https://admin:TopSecretPass99@internal.elevatecraft.org/api?api_key=xyz999
            user_email=support@elevatecraft.org
            password="MyPassword123!"
        """.trimIndent()

        val result = SensitiveDataMasker.mask(input)

        assertTrue(result.redactionsCount >= 7)
        assertFalse(result.maskedText.contains(googleKey))
        assertFalse(result.maskedText.contains(ghToken))
        assertFalse(result.maskedText.contains(awsKey))
        assertFalse(result.maskedText.contains(jwt))
        assertFalse(result.maskedText.contains("super_secret_bearer_token_987"))
        assertFalse(result.maskedText.contains("TopSecretPass99"))
        assertFalse(result.maskedText.contains("xyz999"))
        assertFalse(result.maskedText.contains("MyPassword123!"))
        assertFalse(result.maskedText.contains("support@elevatecraft.org"))
        assertTrue(result.maskedText.contains("su***@elevatecraft.org"))
        assertTrue(result.maskedText.contains("[MASKED_GOOGLE_API_KEY]"))
        assertTrue(result.maskedText.contains("[MASKED_GITHUB_TOKEN]"))
        assertTrue(result.maskedText.contains("[MASKED_BEARER_TOKEN]"))
    }

    @Test
    fun encryptedActivityLogger_encryptsOnDiskAndMasksSensitivePayload() = runBlocking {
        val tempFile = File.createTempFile("xcraft_audit_test_", ".enc.jsonl")
        tempFile.deleteOnExit()
        tempFile.delete()

        val secretPassword = "UltraSecretValue42"
        val syntheticKey = ("AI" + "za") + "SyA9876543210abcdef9876543210abcdef"
        val keyProvider = InMemoryAesKeyProvider()

        val logger = EncryptedActivityLogger(storageFile = tempFile, keyProvider = keyProvider)

        val agentEntry = logger.logAgentExecution(
            action = "AGENT_CHAT",
            model = "gemini-4-hydrogen",
            role = "Python Security Auditor",
            status = "SUCCESS",
            durationMs = 420.0,
            rawPrompt = "Check endpoint https://alice:$secretPassword@api.example.com?key=$syntheticKey",
            rawOutputOrError = "Verified connection for support@elevatecraft.org"
        )

        val scriptEntry = logger.logScriptActivity(
            action = "SCRIPT_RUN",
            scriptTitle = "Auth Tool",
            category = "tool",
            status = "SUCCESS",
            durationMs = 85.0,
            rawCodeOrCommand = "password = '$secretPassword'\nprint('done')",
            rawOutput = "Executed safely"
        )

        // Two identical plaintexts must still produce distinct randomized IV ciphertexts
        val repeatEntry = logger.logScriptActivity(
            action = "SCRIPT_RUN",
            scriptTitle = "Auth Tool",
            category = "tool",
            status = "SUCCESS",
            durationMs = 85.0,
            rawCodeOrCommand = "password = '$secretPassword'\nprint('done')",
            rawOutput = "Executed safely"
        )
        assertNotEquals(scriptEntry.cipherPreview, repeatEntry.cipherPreview)

        // Ensure plaintext secrets and unencrypted summaries NEVER appear in the encrypted file on disk
        val diskRaw = tempFile.readText(StandardCharsets.UTF_8)
        assertFalse(diskRaw.contains(secretPassword))
        assertFalse(diskRaw.contains(syntheticKey))
        assertFalse(diskRaw.contains("support@elevatecraft.org"))
        assertFalse(diskRaw.contains("Python Security Auditor"))
        assertTrue(diskRaw.contains("\"cipher\""))
        assertTrue(diskRaw.contains("\"hash\""))

        // Verify decryption & masking accuracy
        assertEquals(ActivityCategory.AGENT_EXECUTION, agentEntry.category)
        assertTrue(agentEntry.redactionsCount >= 3)
        assertFalse(agentEntry.maskedDetails.contains(secretPassword))
        assertFalse(agentEntry.maskedDetails.contains(syntheticKey))
        assertTrue(agentEntry.chainValid)

        assertEquals(ActivityCategory.SCRIPT_ACTIVITY, scriptEntry.category)
        assertTrue(scriptEntry.redactionsCount >= 1)
        assertFalse(scriptEntry.maskedDetails.contains(secretPassword))

        // Re-load from disk with same key provider and verify intact chain
        val reloadedLogger = EncryptedActivityLogger(storageFile = tempFile, keyProvider = keyProvider)
        val verification = reloadedLogger.verifyChainIntegrity()
        assertTrue(verification.isChainIntact)
        assertEquals(3, verification.totalRecords)
        assertEquals(3, verification.validRecords)
        assertEquals(0, verification.tamperedCount)
    }

    @Test
    fun encryptedActivityLogger_detectsTamperingInEncryptedStore() = runBlocking {
        val tempFile = File.createTempFile("xcraft_tamper_test_", ".enc.jsonl")
        tempFile.deleteOnExit()
        tempFile.delete()

        val keyProvider = InMemoryAesKeyProvider()
        val logger = EncryptedActivityLogger(storageFile = tempFile, keyProvider = keyProvider)

        logger.logScriptActivity(
            action = "SCRIPT_RUN",
            scriptTitle = "DNS Validator",
            category = "tool",
            status = "SUCCESS",
            durationMs = 30.0,
            rawCodeOrCommand = "dns google.com",
            rawOutput = "Resolved 142.250.190.46"
        )

        // Tamper with the stored ciphertext envelope on disk
        val original = tempFile.readText(StandardCharsets.UTF_8)
        val tampered = original.replace("\"seq\":1", "\"seq\":99")
        tempFile.writeText(tampered, StandardCharsets.UTF_8)

        val verification = logger.verifyChainIntegrity()
        assertFalse(verification.isChainIntact)
        assertEquals(1, verification.tamperedCount)
    }

    @Test
    fun encryptedActivityLogger_asyncLoggingDoesNotCrashWhenKeyProviderThrows() = runBlocking {
        val tempFile = File.createTempFile("xcraft_fault_test_", ".enc.jsonl")
        tempFile.deleteOnExit()
        tempFile.delete()

        val failingProvider = object : AuditKeyProvider {
            override val protectionMode: String = "Failing KeyProvider"
            override fun getOrCreateKey(): SecretKey {
                throw IllegalStateException("Simulated keystore unavailability")
            }
        }

        val logger = EncryptedActivityLogger(storageFile = tempFile, keyProvider = failingProvider)
        logger.logAgentExecutionAsync(
            action = "AGENT_CHAT",
            model = "gemini-3.8-flash",
            role = "Architect",
            status = "SUCCESS",
            durationMs = 10.0,
            rawPrompt = "Hello",
            rawOutputOrError = "World"
        )
        logger.logScriptActivityAsync(
            action = "SCRIPT_RUN",
            scriptTitle = "Test",
            category = "tool",
            status = "SUCCESS",
            rawCodeOrCommand = "print('hi')"
        )
        logger.logSecurityEventAsync(
            action = "FIREWALL_PROBE",
            target = "Armor",
            status = "CLEAN",
            rawPayload = "test"
        )
        delay(200)
        // Should reach here cleanly with zero uncaught coroutine crashes
        assertEquals(0, logger.verificationFlow.value.totalRecords)
    }

    @Test
    fun pythonSandboxEngine_detectsExtendedCvePatternsAndIgnoresComments() {
        val snippet = """
            # Safe comment mentioning eval() and pickle.loads()
            import yaml, tarfile
            data = yaml.load(raw_payload)
            tar.extractall('/tmp/out')
            tmp = tempfile.mktemp()
        """.trimIndent()

        val findings = PythonSandboxEngine.auditCode(snippet)
        assertTrue(findings.any { it.contains("yaml.load") })
        assertTrue(findings.any { it.contains("CVE-2024-38519") })
        assertTrue(findings.any { it.contains("tempfile.mktemp") })
        assertFalse(findings.any { it.contains("eval()") })
    }
}
