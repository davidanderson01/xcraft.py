package com.example.domain.telemetry

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.example.data.repository.SovereignWorkspaceRepository
import com.example.domain.security.SensitiveDataMasker
import com.example.domain.terminal.EncryptedEnvelopeCodec
import com.example.domain.terminal.TerminalSummonService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

data class ExternalSessionState(
    val sessionId: String = "xcraft-local",
    val launchedFromTerminal: Boolean = false,
    val sourceOrigin: String = "DIRECT",
    val targetPackage: String = "",
    val summonSummary: String = "",
    val callbackPort: Int? = null,
    val cipherAlgorithm: String = "AES-256-GCM",
    val envelopeSha256: String = "",
    val isActive: Boolean = false,
    val packetsTransmitted: Int = 0,
    val bytesTransferred: Long = 0L,
    val secretsMasked: Int = 0,
    val startedAtMillis: Long = 0L,
    val endedAtMillis: Long = 0L,
    val summonedScriptTitle: String? = null,
    val summonedScriptCode: String? = null
)

/**
 * Tracks continuous runtime telemetry (Room, EncryptedActivityLogger, Firestore, and loopback
 * HTTP/Logcat signaling back to the summoning OS terminal) and transmits a verified `SESSION_ENDED`
 * payload when the Xcraft window closes.
 */
class ExternalSessionTelemetryBridge(
    private val workspaceRepository: SovereignWorkspaceRepository,
    private val summonService: TerminalSummonService = TerminalSummonService(),
    private val codec: EncryptedEnvelopeCodec = EncryptedEnvelopeCodec(),
    private val httpClient: OkHttpClient = defaultTelemetryHttpClient()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _sessionState = MutableStateFlow(ExternalSessionState())
    val sessionState: StateFlow<ExternalSessionState> = _sessionState.asStateFlow()

    /**
     * Attaches to an incoming `xcraft://summon` or `xcraft://open` Intent (or starts a normal
     * direct session when opened from the launcher), fetches/verifies any requested GitHub
     * target cleanly inside encrypted memory, and informs any listening summoning terminal.
     */
    fun attachSession(intent: Intent?) {
        val data: Uri? = intent?.data
        val isTerminalLaunch = data != null && data.scheme.equals("xcraft", ignoreCase = true)

        val current = _sessionState.value
        val suppliedSessionId = data?.getQueryParameter("session_id")?.trim()?.takeIf { it.isNotEmpty() }
        if (current.isActive && !isTerminalLaunch) {
            return
        }
        if (current.isActive && suppliedSessionId != null && suppliedSessionId == current.sessionId) {
            return
        }

        val sessionId = suppliedSessionId ?: "xcraft-${UUID.randomUUID().toString().replace("-", "").take(8)}"
        val sourceOrigin = data?.getQueryParameter("source")?.trim()?.uppercase(Locale.US)?.ifBlank { "TERMINAL" }
            ?: if (isTerminalLaunch) "TERMINAL" else "DIRECT"
        val target = data?.getQueryParameter("target")?.trim().orEmpty()
        val parsedPort = data?.getQueryParameter("callback_port")?.toIntOrNull()?.takeIf { it in 1024..65535 }
        val expectedSha256 = data?.getQueryParameter("sha256")?.trim().orEmpty()
        val now = System.currentTimeMillis()

        val initialState = ExternalSessionState(
            sessionId = sessionId,
            launchedFromTerminal = isTerminalLaunch,
            sourceOrigin = sourceOrigin,
            targetPackage = target,
            summonSummary = if (isTerminalLaunch) {
                "Launched from terminal ($sourceOrigin${if (target.isNotBlank()) ": $target" else ""})"
            } else {
                "Interactive Sovereign Session"
            },
            callbackPort = parsedPort,
            cipherAlgorithm = "AES-256-GCM",
            envelopeSha256 = expectedSha256,
            isActive = true,
            packetsTransmitted = 1,
            bytesTransferred = 0L,
            secretsMasked = 0,
            startedAtMillis = now,
            endedAtMillis = 0L
        )
        _sessionState.value = initialState

        scope.launch {
            workspaceRepository.seedStarterWorkspaceIfEmpty()

            var resolvedTitle: String? = null
            var resolvedCode: String? = null
            var verifiedDigest = expectedSha256

            if (isTerminalLaunch && target.isNotBlank()) {
                try {
                    val pkg = when (sourceOrigin) {
                        "GITHUB" -> summonService.summonFromGitHub(target, sessionId)
                        else -> null
                    }
                    if (pkg != null) {
                        val unsealed = codec.unseal(pkg.envelope)
                        val saved = workspaceRepository.saveScriptAndSync(
                            title = pkg.title,
                            code = pkg.code,
                            description = pkg.description,
                            category = "agent",
                            hierarchyPath = pkg.hierarchyPath,
                            sourceOrigin = pkg.sourceOrigin
                        )
                        resolvedTitle = saved.title
                        resolvedCode = pkg.code
                        verifiedDigest = pkg.envelope.sha256Digest

                        _sessionState.update { state ->
                            state.copy(
                                envelopeSha256 = verifiedDigest,
                                summonedScriptTitle = resolvedTitle,
                                summonedScriptCode = resolvedCode,
                                secretsMasked = state.secretsMasked + unsealed.redactionsCount
                            )
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Deferred terminal package hydrate: ${SensitiveDataMasker.maskText(e.message ?: "offline")}")
                }
            }

            val logEntry = workspaceRepository.recordExecutionAndTelemetry(
                scriptId = null,
                scriptTitle = resolvedTitle ?: "Xcraft App Session",
                command = if (isTerminalLaunch) "TERMINAL_SUMMON $sourceOrigin $target" else "APP_LAUNCH",
                rawOutput = "Session $sessionId active | cipher=AES-256-GCM | terminal=$isTerminalLaunch",
                status = "SESSION_STARTED",
                executionSurface = if (isTerminalLaunch) "EXTERNAL_TERMINAL_BRIDGE" else "APP_LIFECYCLE",
                sessionId = sessionId,
                durationMs = 0.0,
                envelopeSha256 = verifiedDigest
            )

            _sessionState.update { state ->
                state.copy(
                    packetsTransmitted = state.packetsTransmitted + 1,
                    bytesTransferred = state.bytesTransferred + logEntry.bytesTransmitted,
                    secretsMasked = state.secretsMasked + logEntry.secretsMasked,
                    envelopeSha256 = logEntry.envelopeSha256
                )
            }

            Log.i(
                TAG,
                "[XCRAFT_SESSION_STARTED] session=$sessionId source=$sourceOrigin target=${SensitiveDataMasker.maskText(target)} sha256=${logEntry.envelopeSha256.take(16)}"
            )

            postTelemetryPacket(
                event = "SESSION_STARTED",
                message = "Xcraft session '$sessionId' active via $sourceOrigin",
                state = _sessionState.value,
                extra = mapOf(
                    "sourceOrigin" to sourceOrigin,
                    "target" to SensitiveDataMasker.maskText(target),
                    "sha256" to logEntry.envelopeSha256
                )
            )
        }
    }

    /**
     * Records any user/agent/script transmission telemetry continuously while the app is open,
     * keeping Room, EncryptedActivityLogger, Firestore, and the external terminal updated.
     */
    fun recordActivityTelemetry(
        surface: String,
        action: String,
        scriptTitle: String,
        command: String,
        rawOutput: String,
        status: String,
        durationMs: Double = 0.0,
        scriptId: String? = null
    ) {
        scope.launch {
            val active = _sessionState.value
            val sessionId = active.sessionId.ifBlank { "xcraft-local" }
            val entry = workspaceRepository.recordExecutionAndTelemetry(
                scriptId = scriptId,
                scriptTitle = scriptTitle,
                command = "[$action] $command",
                rawOutput = rawOutput,
                status = status,
                executionSurface = surface,
                sessionId = sessionId,
                durationMs = durationMs,
                envelopeSha256 = active.envelopeSha256
            )

            _sessionState.update { state ->
                state.copy(
                    isActive = true,
                    packetsTransmitted = state.packetsTransmitted + 1,
                    bytesTransferred = state.bytesTransferred + entry.bytesTransmitted,
                    secretsMasked = state.secretsMasked + entry.secretsMasked,
                    envelopeSha256 = entry.envelopeSha256
                )
            }

            postTelemetryPacket(
                event = "TELEMETRY_HEARTBEAT",
                message = "${action} ($status) on $surface",
                state = _sessionState.value,
                extra = mapOf(
                    "surface" to surface,
                    "action" to action,
                    "status" to status,
                    "bytes" to entry.bytesTransmitted.toString(),
                    "secretsMasked" to entry.secretsMasked.toString()
                )
            )
        }
    }

    /**
     * Marks the active Xcraft session as ended when the app window closes/backgrounds and
     * transmits a final masked telemetry packet to the summoning terminal window.
     */
    suspend fun endSessionAndTransmit(reason: String = "WINDOW_CLOSED"): ExternalSessionState = withContext(Dispatchers.IO) {
        val current = _sessionState.value
        if (!current.isActive) {
            return@withContext current
        }

        val now = System.currentTimeMillis()
        val startMs = if (current.startedAtMillis > 0L) current.startedAtMillis else now
        val durationMs = (now - startMs).coerceAtLeast(1L).toDouble()
        val durationSec = String.format(Locale.US, "%.1f", durationMs / 1000.0)
        val totalPackets = current.packetsTransmitted + 1
        val finalDigest = sha256Hex(
            "${current.sessionId}|ENDED|$durationMs|$totalPackets|${current.bytesTransferred}|${current.secretsMasked}"
        )

        val endSummary = "Xcraft session '${current.sessionId}' has ended ($reason) | " +
            "duration=${durationSec}s | packets=$totalPackets | bytes=${current.bytesTransferred}B | " +
            "masked=${current.secretsMasked} | sha256=${finalDigest.take(16)}..."

        runCatching {
            workspaceRepository.recordExecutionAndTelemetry(
                scriptId = null,
                scriptTitle = current.summonedScriptTitle ?: "Xcraft App Session",
                command = "SESSION_END_TRANSMIT ($reason)",
                rawOutput = endSummary,
                status = "SESSION_ENDED",
                executionSurface = "EXTERNAL_TERMINAL_BRIDGE",
                sessionId = current.sessionId,
                durationMs = durationMs,
                envelopeSha256 = finalDigest
            )
        }

        val finalState = current.copy(
            isActive = false,
            packetsTransmitted = totalPackets,
            endedAtMillis = now,
            envelopeSha256 = finalDigest
        )
        _sessionState.value = finalState

        // Emit structured logcat marker for external terminal logcat watchers
        Log.i(
            TAG,
            "[XCRAFT_SESSION_ENDED] session=${finalState.sessionId} reason=$reason duration=${durationSec}s packets=${finalState.packetsTransmitted} bytes=${finalState.bytesTransferred} masked=${finalState.secretsMasked} sha256=$finalDigest"
        )

        // Post direct loopback HTTP telemetry back to Terminal 1 (via adb reverse)
        postTelemetryPacket(
            event = "SESSION_ENDED",
            message = "Xcraft session '${finalState.sessionId}' has ended ($reason).",
            state = finalState,
            extra = mapOf(
                "reason" to reason,
                "durationSeconds" to durationSec,
                "finalSha256" to finalDigest
            )
        )

        finalState
    }

    /**
     * Non-suspending helper for Activity lifecycle callbacks (`onStop` / `onDestroy`).
     */
    fun notifyWindowClosedAsync(reason: String = "WINDOW_CLOSED") {
        scope.launch {
            endSessionAndTransmit(reason)
        }
    }

    fun buildExternalLaunchCommand(targetTitle: String? = null): String {
        val cleanTarget = targetTitle?.replace("\"", "")?.trim()
        return if (!cleanTarget.isNullOrBlank()) {
            "python cli/xcraft_summon.py --source local --target \"$cleanTarget\""
        } else {
            "python cli/xcraft_summon.py --source github --repo elevatecraft/xcraftpy"
        }
    }

    private fun postTelemetryPacket(
        event: String,
        message: String,
        state: ExternalSessionState,
        extra: Map<String, String> = emptyMap()
    ) {
        val port = state.callbackPort ?: return
        if (port !in 1024..65535) return

        try {
            val payload = JSONObject().apply {
                put("event", event)
                put("sessionId", state.sessionId)
                put("cipher", state.cipherAlgorithm)
                put("message", SensitiveDataMasker.maskText(message))
                put("packetsTransmitted", state.packetsTransmitted)
                put("bytesTransferred", state.bytesTransferred)
                put("secretsMasked", state.secretsMasked)
                put("sha256", state.envelopeSha256)
                put("timestampMillis", System.currentTimeMillis())
                extra.forEach { (k, v) -> put(k, SensitiveDataMasker.maskText(v)) }
            }
            val request = Request.Builder()
                .url("http://127.0.0.1:$port/telemetry")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            httpClient.newCall(request).execute().close()
        } catch (_: Exception) {
            // Terminal callback listener is optional; Room + EncryptedActivityLogger + Logcat already persisted event.
        }
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val TAG = "XcraftSessionTelemetry"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        @Volatile
        private var instance: ExternalSessionTelemetryBridge? = null

        fun getInstance(context: Context): ExternalSessionTelemetryBridge {
            return instance ?: synchronized(this) {
                instance ?: ExternalSessionTelemetryBridge(
                    workspaceRepository = SovereignWorkspaceRepository.getInstance(context.applicationContext)
                ).also { instance = it }
            }
        }

        private fun defaultTelemetryHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(1500, TimeUnit.MILLISECONDS)
            .readTimeout(1500, TimeUnit.MILLISECONDS)
            .writeTimeout(1500, TimeUnit.MILLISECONDS)
            .build()
    }
}
