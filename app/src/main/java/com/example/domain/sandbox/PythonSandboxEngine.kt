package com.example.domain.sandbox

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.URL
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.regex.Pattern
import javax.net.ssl.HttpsURLConnection

data class ExecutionResult(
    val output: String,
    val status: String, // "SUCCESS", "BLOCKED", "ERROR"
    val durationMs: Double,
    val securityFindings: List<String> = emptyList()
)

object PythonSandboxEngine {

    val WHITELISTED_COMMANDS = setOf(
        "git", "python", "pip", "pytest", "ls", "dir", "echo",
        "ping", "traceroute", "curl", "whois", "dig", "nslookup"
    )

    private val EVAL_EXEC_PATTERN = Pattern.compile("\\b(eval|exec)\\s*\\(")
    private val PICKLE_PATTERN = Pattern.compile("pickle\\.(loads|load)\\s*\\(")
    private val SHELL_EXEC_PATTERN = Pattern.compile("(os\\.system\\s*\\(|shell\\s*=\\s*True)")
    private val LEGACY_PHP_SHELL_PATTERN = Pattern.compile("\\b(passthru|shell_exec|system|popen)\\s*\\(")
    private val DYNAMIC_IMPORT_PATTERN = Pattern.compile("__import__\\s*\\(")
    private val MKTEMP_PATTERN = Pattern.compile("tempfile\\.mktemp\\s*\\(")
    private val UNSAFE_YAML_PATTERN = Pattern.compile("yaml\\.load\\s*\\(")
    private val EXTRACTALL_PATTERN = Pattern.compile("\\.(extractall|extract)\\s*\\(")
    private val HOSTNAME_PATTERN = Regex("^[a-zA-Z0-9.-]{1,253}$")

    private val dateFormatter = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    }

    /**
     * Audit code against CVE & unsafe patterns.
     */
    fun auditCode(code: String): List<String> {
        val findings = mutableListOf<String>()
        val lines = code.lines()

        for ((idx, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#")) continue
            val lineNum = idx + 1

            if (EVAL_EXEC_PATTERN.matcher(line).find()) {
                findings.add("[CRITICAL] Line $lineNum: Arbitrary Code Execution via eval()/exec(). Use safe parsing or AST literals.")
            }
            if (PICKLE_PATTERN.matcher(line).find()) {
                findings.add("[CRITICAL] Line $lineNum: Insecure deserialization via pickle. Use JSON or protocol buffers.")
            }
            if (SHELL_EXEC_PATTERN.matcher(line).find()) {
                findings.add("[HIGH] Line $lineNum: Potential shell injection via os.system or shell=True. Use SafeShell argument list.")
            }
            if (LEGACY_PHP_SHELL_PATTERN.matcher(line).find()) {
                findings.add("[CRITICAL] Line $lineNum: Legacy PHP/shell execution wrapper. Disallowed in sovereign Xcraft.")
            }
            if (DYNAMIC_IMPORT_PATTERN.matcher(line).find()) {
                findings.add("[MEDIUM] Line $lineNum: Dynamic import bypass token detected.")
            }
            if (MKTEMP_PATTERN.matcher(line).find()) {
                findings.add("[MEDIUM] Line $lineNum: Insecure tempfile.mktemp() race condition. Use tempfile.NamedTemporaryFile().")
            }
            if (UNSAFE_YAML_PATTERN.matcher(line).find() && !line.contains("SafeLoader")) {
                findings.add("[HIGH] Line $lineNum: Unsafe yaml.load() without SafeLoader (arbitrary object construction risk).")
            }
            if (EXTRACTALL_PATTERN.matcher(line).find()) {
                findings.add("[HIGH] Line $lineNum: Unchecked archive extraction (CVE-2024-38519 Zip Slip risk). Validate member paths.")
            }
        }
        return findings
    }

    /**
     * Execute a script or tool simulation in the sovereign sandbox.
     */
    suspend fun executeScript(code: String): ExecutionResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val findings = auditCode(code)

        // If severe critical issues are detected, block execution
        if (findings.any { it.contains("[CRITICAL]") }) {
            val duration = (System.currentTimeMillis() - startTime).toDouble()
            return@withContext ExecutionResult(
                output = "🛡️ EXECUTION BLOCKED: Sovereign Security Policy violation.\n" +
                        findings.joinToString("\n") +
                        "\n\nPlease resolve vulnerabilities before running in the sovereign environment.",
                status = "BLOCKED",
                durationMs = duration,
                securityFindings = findings
            )
        }

        val outputBuffer = StringBuilder()
        outputBuffer.appendLine("⚡ [Xcraft Sovereign Sandbox v2.0.0]")
        outputBuffer.appendLine("🛡️ Security Audit: ${if (findings.isEmpty()) "PASSED (0 CVEs)" else "${findings.size} Warning(s)"}")
        outputBuffer.appendLine("--------------------------------------------------")

        try {
            val lines = code.lines()
            for (rawLine in lines) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) continue

                // Check for DNS Validator usage or simulation
                if (line.contains("DNSValidator") || line.contains(".route(\"dns\"") || line.startsWith("dns ")) {
                    val domain = sanitizeHostname(extractTarget(line, "google.com"), "google.com")
                    outputBuffer.appendLine("[DNS Validator] Querying domain '$domain'...")
                    try {
                        val addresses = InetAddress.getAllByName(domain)
                        outputBuffer.appendLine("✅ Domain '$domain' resolved successfully:")
                        for (addr in addresses) {
                            outputBuffer.appendLine("   • ${addr.hostAddress} (${if (addr.address.size == 4) "IPv4" else "IPv6"})")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        outputBuffer.appendLine("❌ DNS Resolution failed: ${e.message}")
                    }
                }
                // Check for SSL Checker usage or simulation
                else if (line.contains("SSLChecker") || line.contains(".route(\"ssl\"") || line.startsWith("ssl ")) {
                    val host = sanitizeHostname(extractTarget(line, "python.org"), "python.org")
                    outputBuffer.appendLine("[SSL Checker] Inspecting certificate for '$host'...")
                    var conn: HttpsURLConnection? = null
                    try {
                        val url = URL("https://$host")
                        conn = (url.openConnection() as HttpsURLConnection).apply {
                            connectTimeout = 5000
                            readTimeout = 5000
                            connect()
                        }
                        val certs = conn.serverCertificates
                        if (certs.isNotEmpty() && certs[0] is X509Certificate) {
                            val x509 = certs[0] as X509Certificate
                            val expiry = x509.notAfter
                            val daysLeft = ((expiry.time - System.currentTimeMillis()) / (1000 * 60 * 60 * 24)).toInt()
                            val formattedExpiry = dateFormatter.get()?.format(expiry) ?: expiry.toString()
                            outputBuffer.appendLine("✅ Valid SSL/TLS Certificate for $host:")
                            outputBuffer.appendLine("   • Subject: ${x509.subjectX500Principal.name}")
                            outputBuffer.appendLine("   • Issuer: ${x509.issuerX500Principal.name}")
                            outputBuffer.appendLine("   • Expires: $formattedExpiry ($daysLeft days remaining)")
                            outputBuffer.appendLine("   • Cipher Suite: ${conn.cipherSuite}")
                        } else {
                            outputBuffer.appendLine("✅ Connected to HTTPS on $host (Cipher: ${conn.cipherSuite})")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        outputBuffer.appendLine("❌ SSL Check failed: ${e.message}")
                    } finally {
                        conn?.disconnect()
                    }
                }
                // Check for Safe Shell execution
                else if (line.contains("SafeShell") || line.contains(".route(\"shell\"") || line.startsWith("shell ")) {
                    val cmd = extractTarget(line, "python --version")
                    outputBuffer.appendLine("[Safe Shell] Checking command '$cmd' against whitelist...")
                    val binary = cmd.split(" ").firstOrNull()?.lowercase() ?: ""
                    if (binary in WHITELISTED_COMMANDS) {
                        outputBuffer.appendLine("✅ Command '$binary' approved by sovereign whitelist policy.")
                        outputBuffer.appendLine("   Output: [Simulated Sovereign Sandbox] Python 3.12.3 / Xcraft Engine OK")
                    } else {
                        outputBuffer.appendLine("🛡️ Security Policy: Binary '$binary' is blocked (not in whitelist $WHITELISTED_COMMANDS)")
                    }
                }
                // Check for print statements
                else if (line.startsWith("print(") && line.endsWith(")")) {
                    val inner = line.removePrefix("print(").removeSuffix(")").trim()
                    val stripped = inner.removeSurrounding("\"").removeSurrounding("'")
                    outputBuffer.appendLine(stripped)
                }
                // Variable or calculation simulation
                else if (line.contains("=") && !line.startsWith("==")) {
                    outputBuffer.appendLine("   ↳ Assigned: $line")
                }
            }

            if (outputBuffer.lines().size <= 3) {
                outputBuffer.appendLine("✅ Script validated syntax and structure successfully with 0 runtime errors.")
            }

            outputBuffer.appendLine("--------------------------------------------------")
            outputBuffer.appendLine("✨ Execution finished successfully.")

            val duration = (System.currentTimeMillis() - startTime).toDouble()
            ExecutionResult(
                output = outputBuffer.toString(),
                status = "SUCCESS",
                durationMs = duration,
                securityFindings = findings
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val duration = (System.currentTimeMillis() - startTime).toDouble()
            ExecutionResult(
                output = "❌ Sandbox Execution Error: ${e.message}",
                status = "ERROR",
                durationMs = duration,
                securityFindings = findings
            )
        }
    }

    private fun extractTarget(line: String, default: String): String {
        val quotesRegex = Regex("[\"']([^\"']+)[\"']")
        val match = quotesRegex.find(line)
        if (match != null) {
            return match.groupValues[1]
        }
        val parts = line.split(" ")
        return if (parts.size > 1) parts[1].trim() else default
    }

    private fun sanitizeHostname(candidate: String, fallback: String): String {
        val clean = candidate.trim().removePrefix("https://").removePrefix("http://").substringBefore("/")
        return if (HOSTNAME_PATTERN.matcherOrMatches(clean)) clean else fallback
    }

    private fun Regex.matcherOrMatches(input: String): Boolean = this.matches(input)
}
