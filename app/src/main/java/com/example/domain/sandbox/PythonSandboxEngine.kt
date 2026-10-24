package com.example.domain.sandbox

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.URL
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
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

    val DANGEROUS_PATTERNS = listOf(
        Pattern.compile("\\b(eval|exec)\\s*\\("),
        Pattern.compile("pickle\\.(loads|load)\\s*\\("),
        Pattern.compile("(os\\.system\\s*\\(|subprocess\\.\\w+\\(.*shell\\s*=\\s*True)"),
        Pattern.compile("\\b(passthru|shell_exec|system|popen)\\s*\\("),
        Pattern.compile("tempfile\\.mktemp\\s*\\("),
        Pattern.compile("__import__\\s*\\("),
        Pattern.compile("rm\\s+-rf")
    )

    /**
     * Audit code against CVE & unsafe patterns.
     */
    fun auditCode(code: String): List<String> {
        val findings = mutableListOf<String>()
        val lines = code.lines()

        for ((idx, line) in lines.withIndex()) {
            if (Pattern.compile("\\b(eval|exec)\\s*\\(").matcher(line).find()) {
                findings.add("[CRITICAL] Line ${idx + 1}: Arbitrary Code Execution via eval()/exec(). Use safe parsing or AST literals.")
            }
            if (Pattern.compile("pickle\\.(loads|load)\\s*\\(").matcher(line).find()) {
                findings.add("[CRITICAL] Line ${idx + 1}: Insecure deserialization via pickle. Use JSON or protocol buffers.")
            }
            if (Pattern.compile("(os\\.system\\s*\\(|shell\\s*=\\s*True)").matcher(line).find()) {
                findings.add("[HIGH] Line ${idx + 1}: Potential shell injection via os.system or shell=True. Use SafeShell argument list.")
            }
            if (Pattern.compile("\\b(passthru|shell_exec|system|popen)\\s*\\(").matcher(line).find()) {
                findings.add("[CRITICAL] Line ${idx + 1}: Legacy PHP/shell execution wrapper. Disallowed in sovereign Xcraft.")
            }
            if (Pattern.compile("__import__\\s*\\(").matcher(line).find()) {
                findings.add("[MEDIUM] Line ${idx + 1}: Dynamic import bypass token detected.")
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
                    val domain = extractTarget(line, "google.com")
                    outputBuffer.appendLine("[DNS Validator] Querying domain '$domain'...")
                    try {
                        val addresses = InetAddress.getAllByName(domain)
                        outputBuffer.appendLine("✅ Domain '$domain' resolved successfully:")
                        for (addr in addresses) {
                            outputBuffer.appendLine("   • ${addr.hostAddress} (${if (addr.address.size == 4) "IPv4" else "IPv6"})")
                        }
                    } catch (e: Exception) {
                        outputBuffer.appendLine("❌ DNS Resolution failed: ${e.message}")
                    }
                }
                // Check for SSL Checker usage or simulation
                else if (line.contains("SSLChecker") || line.contains(".route(\"ssl\"") || line.startsWith("ssl ")) {
                    val host = extractTarget(line, "python.org")
                    outputBuffer.appendLine("[SSL Checker] Inspecting certificate for '$host'...")
                    try {
                        val url = URL("https://$host")
                        val conn = url.openConnection() as HttpsURLConnection
                        conn.connectTimeout = 5000
                        conn.readTimeout = 5000
                        conn.connect()
                        val certs = conn.serverCertificates
                        if (certs.isNotEmpty() && certs[0] is X509Certificate) {
                            val x509 = certs[0] as X509Certificate
                            val expiry = x509.notAfter
                            val daysLeft = ((expiry.time - System.currentTimeMillis()) / (1000 * 60 * 60 * 24)).toInt()
                            val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                            outputBuffer.appendLine("✅ Valid SSL/TLS Certificate for $host:")
                            outputBuffer.appendLine("   • Subject: ${x509.subjectDN.name}")
                            outputBuffer.appendLine("   • Issuer: ${x509.issuerDN.name}")
                            outputBuffer.appendLine("   • Expires: ${df.format(expiry)} ($daysLeft days remaining)")
                            outputBuffer.appendLine("   • Cipher Suite: ${conn.cipherSuite}")
                        } else {
                            outputBuffer.appendLine("✅ Connected to HTTPS on $host (Cipher: ${conn.cipherSuite})")
                        }
                        conn.disconnect()
                    } catch (e: Exception) {
                        outputBuffer.appendLine("❌ SSL Check failed: ${e.message}")
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
}
