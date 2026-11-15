package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.local.AgentExecutionLogDao
import com.example.data.local.AgentExecutionLogEntity
import com.example.data.local.AgentScriptDao
import com.example.data.local.AgentScriptEntity
import com.example.data.local.HierarchyNodeDao
import com.example.data.local.HierarchyNodeEntity
import com.example.data.local.HierarchyNodeType
import com.example.data.local.ScriptWithExecutionLogs
import com.example.data.local.SyncStatus
import com.example.data.local.XcraftDatabase
import com.example.domain.codebase.XcraftSourceCodebase
import com.example.domain.sandbox.PythonSandboxEngine
import com.example.domain.security.EncryptedActivityLogger
import com.example.domain.security.SensitiveDataMasker
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

data class HierarchyPreset(
    val id: String,
    val name: String,
    val tagline: String,
    val humanSummary: String,
    val nodes: List<HierarchyNodeEntity>
)

/**
 * Single local source of truth backed by Room (`XcraftDatabase`), keeping scripts,
 * execution telemetry logs, and hierarchy plans synchronized with Firestore when signed in.
 */
class SovereignWorkspaceRepository(
    private val nodeDao: HierarchyNodeDao,
    private val scriptDao: AgentScriptDao,
    private val logDao: AgentExecutionLogDao,
    private val firestoreRepository: FirestoreRepository?,
    private val auditLogger: EncryptedActivityLogger?
) {
    val allNodes: Flow<List<HierarchyNodeEntity>> = nodeDao.observeAllNodes()
    val allScripts: Flow<List<AgentScriptEntity>> = scriptDao.observeAllScripts()
    val scriptsWithLogs: Flow<List<ScriptWithExecutionLogs>> = scriptDao.observeScriptsWithLogs()
    val allExecutionLogs: Flow<List<AgentExecutionLogEntity>> = logDao.observeAllLogs()

    suspend fun seedStarterWorkspaceIfEmpty() = withContext(Dispatchers.IO) {
        if (nodeDao.countNodes() == 0) {
            nodeDao.upsertNodes(buildDefaultHierarchyNodes())
        }
        if (scriptDao.countScripts() == 0) {
            val now = System.currentTimeMillis()
            val starterScripts = listOf(
                AgentScriptEntity(
                    id = "script_router_core",
                    nodeId = "node_agents",
                    hierarchyPath = "xcraftpy/agents/router.py",
                    title = "Sovereign AgentRouter",
                    description = "Multi-model agent routing engine with input boundary sanitization.",
                    code = XcraftSourceCodebase.files.find { it.path.endsWith("router.py") }?.content
                        ?: "print('Xcraft Router Ready')",
                    category = "agent",
                    agentRole = "CraftX Architect",
                    sourceOrigin = "LOCAL",
                    isAuditedClean = true,
                    syncStatus = SyncStatus.LOCAL_ONLY,
                    createdAt = now,
                    updatedAt = now
                ),
                AgentScriptEntity(
                    id = "script_dns_validator",
                    nodeId = "node_tools",
                    hierarchyPath = "xcraftpy/plugins/tools/dns_validator.py",
                    title = "DNS & SSL Diagnostics",
                    description = "Resolves target hostnames and inspects TLS certificate chains.",
                    code = "dns google.com\nssl python.org\nprint('Network telemetry OK')",
                    category = "tool",
                    agentRole = "Network Diagnostics Specialist",
                    sourceOrigin = "LOCAL",
                    isAuditedClean = true,
                    syncStatus = SyncStatus.LOCAL_ONLY,
                    createdAt = now,
                    updatedAt = now
                )
            )
            scriptDao.upsertScripts(starterScripts)
        }
    }

    suspend fun saveScriptAndSync(
        title: String,
        code: String,
        description: String,
        category: String = "tool",
        hierarchyPath: String = "xcraftpy/scripts",
        nodeId: String? = null,
        agentRole: String = "CraftX Architect",
        sourceOrigin: String = "LOCAL",
        existingId: String? = null
    ): AgentScriptEntity = withContext(Dispatchers.IO) {
        val cleanTitle = title.trim().ifBlank { "Untitled Agent Script" }
        val existing = if (existingId != null) {
            scriptDao.getScriptById(existingId)
        } else {
            scriptDao.getScriptByTitle(cleanTitle)
        }
        val scriptId = existing?.id ?: UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val isClean = PythonSandboxEngine.auditCode(code).isEmpty()

        val initialEntity = AgentScriptEntity(
            id = scriptId,
            cloudId = existing?.cloudId,
            nodeId = nodeId ?: existing?.nodeId,
            hierarchyPath = hierarchyPath,
            title = cleanTitle,
            description = description.trim(),
            code = code,
            category = category,
            agentRole = agentRole,
            sourceOrigin = sourceOrigin,
            isAuditedClean = isClean,
            syncStatus = if (isSignedIn()) SyncStatus.PENDING_SYNC else SyncStatus.LOCAL_ONLY,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )
        scriptDao.upsertScript(initialEntity)

        auditLogger?.logScriptActivityAsync(
            action = "ROOM_SCRIPT_SAVE",
            scriptTitle = cleanTitle,
            category = category,
            status = initialEntity.syncStatus,
            rawCodeOrCommand = code,
            rawOutput = "Stored in Room at $hierarchyPath"
        )

        if (isSignedIn() && firestoreRepository != null) {
            try {
                val remoteId = if (initialEntity.cloudId != null) {
                    firestoreRepository.updateScript(
                        id = initialEntity.cloudId,
                        title = cleanTitle,
                        code = code,
                        description = description.trim(),
                        category = category
                    )
                    initialEntity.cloudId
                } else {
                    firestoreRepository.saveScript(
                        title = cleanTitle,
                        code = code,
                        description = description.trim(),
                        category = category
                    )
                }
                scriptDao.updateSyncState(
                    id = scriptId,
                    cloudId = remoteId,
                    syncStatus = SyncStatus.SYNCED,
                    updatedAt = System.currentTimeMillis()
                )
                return@withContext initialEntity.copy(cloudId = remoteId, syncStatus = SyncStatus.SYNCED)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Deferred Firestore script sync: ${SensitiveDataMasker.maskText(e.message ?: "offline")}")
            }
        }
        initialEntity
    }

    suspend fun deleteScript(script: AgentScriptEntity) = withContext(Dispatchers.IO) {
        scriptDao.deleteScriptById(script.id)
        if (isSignedIn() && firestoreRepository != null && !script.cloudId.isNullOrBlank()) {
            try {
                firestoreRepository.deleteScript(script.cloudId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed remote script delete: ${SensitiveDataMasker.maskText(e.message ?: "unknown")}")
            }
        }
    }

    /**
     * Records execution & transmission telemetry into Room, AES-256-GCM EncryptedActivityLogger,
     * and Firestore (when signed in), ensuring all secrets/PII are masked beforehand.
     */
    suspend fun recordExecutionAndTelemetry(
        scriptId: String?,
        scriptTitle: String,
        command: String,
        rawOutput: String,
        status: String,
        executionSurface: String,
        sessionId: String,
        durationMs: Double,
        envelopeSha256: String = "",
        extraRedactionsCount: Int = 0
    ): AgentExecutionLogEntity = withContext(Dispatchers.IO) {
        val maskedCommand = SensitiveDataMasker.mask(command)
        val maskedOutput = SensitiveDataMasker.mask(rawOutput)
        val totalMasked = extraRedactionsCount + maskedCommand.redactionsCount + maskedOutput.redactionsCount
        val bytesCount = maskedOutput.maskedText.toByteArray(StandardCharsets.UTF_8).size +
            maskedCommand.maskedText.toByteArray(StandardCharsets.UTF_8).size
        val digest = envelopeSha256.ifBlank {
            sha256Hex("$sessionId|$scriptTitle|${maskedCommand.maskedText}|${maskedOutput.maskedText}")
        }

        val logId = UUID.randomUUID().toString()
        val pendingStatus = if (isSignedIn()) SyncStatus.PENDING_SYNC else SyncStatus.LOCAL_ONLY
        val logEntity = AgentExecutionLogEntity(
            id = logId,
            scriptId = scriptId,
            scriptTitle = SensitiveDataMasker.maskText(scriptTitle),
            command = maskedCommand.maskedText,
            maskedOutput = maskedOutput.maskedText,
            status = status,
            executionSurface = executionSurface,
            sessionId = sessionId,
            durationMs = durationMs,
            bytesTransmitted = bytesCount,
            secretsMasked = totalMasked,
            envelopeSha256 = digest,
            syncStatus = pendingStatus,
            executedAt = System.currentTimeMillis()
        )
        logDao.insertLog(logEntity)

        auditLogger?.logScriptActivityAsync(
            action = "${executionSurface}_$status",
            scriptTitle = scriptTitle,
            category = "telemetry",
            status = status,
            durationMs = durationMs,
            rawCodeOrCommand = command,
            rawOutput = rawOutput
        )

        if (isSignedIn() && firestoreRepository != null) {
            try {
                firestoreRepository.logExecution(
                    command = "[${executionSurface}] ${maskedCommand.maskedText}",
                    output = maskedOutput.maskedText,
                    status = status,
                    durationMs = durationMs
                )
                logDao.updateSyncState(logId, SyncStatus.SYNCED)
                return@withContext logEntity.copy(syncStatus = SyncStatus.SYNCED)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Deferred Firestore telemetry sync: ${SensitiveDataMasker.maskText(e.message ?: "offline")}")
            }
        }
        logEntity
    }

    /**
     * Reconciles unsynced local Room scripts/logs with Firestore and imports any remote cloud scripts.
     */
    suspend fun syncWithFirestoreIfAuthenticated(): Pair<Int, Int> = withContext(Dispatchers.IO) {
        if (!isSignedIn() || firestoreRepository == null) return@withContext 0 to 0
        var uploaded = 0
        var downloaded = 0

        try {
            for (local in scriptDao.getUnsyncedScripts()) {
                val remoteId = if (local.cloudId != null) {
                    firestoreRepository.updateScript(
                        id = local.cloudId,
                        title = local.title,
                        code = local.code,
                        description = local.description,
                        category = local.category
                    )
                    local.cloudId
                } else {
                    firestoreRepository.saveScript(
                        title = local.title,
                        code = local.code,
                        description = local.description,
                        category = local.category
                    )
                }
                scriptDao.updateSyncState(local.id, remoteId, SyncStatus.SYNCED, System.currentTimeMillis())
                uploaded++
            }

            for (log in logDao.getUnsyncedLogs()) {
                firestoreRepository.logExecution(
                    command = "[${log.executionSurface}] ${log.command}",
                    output = log.maskedOutput,
                    status = log.status,
                    durationMs = log.durationMs
                )
                logDao.updateSyncState(log.id, SyncStatus.SYNCED)
                uploaded++
            }

            val remoteScripts = firestoreRepository.observeScripts().first()
            for (remote in remoteScripts) {
                if (remote.id.isBlank()) continue
                val existing = scriptDao.getScriptByCloudId(remote.id)
                    ?: scriptDao.getScriptByTitle(remote.title)
                if (existing == null) {
                    val entity = AgentScriptEntity(
                        id = UUID.randomUUID().toString(),
                        cloudId = remote.id,
                        hierarchyPath = "xcraftpy/cloud/${remote.category}",
                        title = remote.title,
                        description = remote.description,
                        code = remote.code,
                        category = remote.category,
                        sourceOrigin = "FIRESTORE",
                        isAuditedClean = remote.isVulnerabilityFixed,
                        syncStatus = SyncStatus.SYNCED
                    )
                    scriptDao.upsertScript(entity)
                    downloaded++
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Workspace sync error: ${SensitiveDataMasker.maskText(e.message ?: "unknown")}")
        }
        uploaded to downloaded
    }

    suspend fun upsertHierarchyNode(node: HierarchyNodeEntity) = withContext(Dispatchers.IO) {
        nodeDao.upsertNode(node)
    }

    suspend fun applyHierarchyPreset(preset: HierarchyPreset) = withContext(Dispatchers.IO) {
        nodeDao.clearAll()
        nodeDao.upsertNodes(preset.nodes)
    }

    suspend fun deleteHierarchySubtree(nodeId: String) = withContext(Dispatchers.IO) {
        nodeDao.deleteNodeSubtree(nodeId)
    }

    /**
     * Side 1 (Machine / AI Agent Schema): Produces a deterministic JSON representation
     * of the entire Room hierarchy, script bindings, and telemetry summaries for agent consumption.
     */
    fun buildAgentSchemaManifest(
        nodes: List<HierarchyNodeEntity>,
        scripts: List<AgentScriptEntity>,
        logs: List<AgentExecutionLogEntity>
    ): String {
        val root = JSONObject()
        root.put("schemaVersion", "xcraft.hierarchy.v2")
        root.put("storageEngine", "Room(SQLite) + FirestoreSync")
        root.put("totalNodes", nodes.size)
        root.put("totalScripts", scripts.size)
        root.put("totalExecutionLogs", logs.size)

        val nodesArr = JSONArray()
        for (n in nodes) {
            nodesArr.put(
                JSONObject().apply {
                    put("id", n.id)
                    put("parentId", n.parentId ?: JSONObject.NULL)
                    put("name", n.name)
                    put("type", n.nodeType)
                    put("path", n.relativePath)
                    put("agentRole", n.agentSchemaRole)
                }
            )
        }
        root.put("hierarchyNodes", nodesArr)

        val scriptsArr = JSONArray()
        for (s in scripts) {
            val scriptLogs = logs.count { it.scriptId == s.id }
            scriptsArr.put(
                JSONObject().apply {
                    put("id", s.id)
                    put("title", s.title)
                    put("path", s.hierarchyPath)
                    put("category", s.category)
                    put("syncStatus", s.syncStatus)
                    put("executionLogCount", scriptLogs)
                }
            )
        }
        root.put("agentScripts", scriptsArr)
        return root.toString(2)
    }

    fun buildHumanNodeExplanation(
        node: HierarchyNodeEntity,
        childCount: Int,
        linkedScriptsCount: Int
    ): String {
        val base = node.humanExplanation.ifBlank {
            when (node.nodeType) {
                HierarchyNodeType.ROOT -> "Top-level workspace container anchoring every folder, script, and telemetry log."
                HierarchyNodeType.MODULE -> "Dedicated Python module package encapsulating reusable logic and clean imports."
                HierarchyNodeType.SCRIPT -> "Executable AI agent script tracked in Room with linked execution audit logs."
                else -> "Organized folder grouping related components so both you and AI agents stay aligned."
            }
        }
        return "$base\n• Path: ${node.relativePath}\n• Contains: $childCount sub-item(s), $linkedScriptsCount linked script(s)\n• Agent contract: ${node.agentSchemaRole.ifBlank { "General workspace artifact" }}"
    }

    private fun isSignedIn(): Boolean = try {
        Firebase.auth.currentUser != null
    } catch (_: Exception) {
        false
    }

    private fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "SovereignWorkspaceRepo"

        @Volatile
        private var instance: SovereignWorkspaceRepository? = null

        fun getInstance(
            context: Context,
            firestoreRepository: FirestoreRepository? = null
        ): SovereignWorkspaceRepository {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val appCtx = context.applicationContext
                    val db = XcraftDatabase.getInstance(appCtx)
                    val fs = firestoreRepository ?: runCatching { FirestoreRepository.create(appCtx) }.getOrNull()
                    val logger = runCatching { EncryptedActivityLogger.getInstance(appCtx) }.getOrNull()
                    SovereignWorkspaceRepository(
                        nodeDao = db.hierarchyNodeDao(),
                        scriptDao = db.agentScriptDao(),
                        logDao = db.agentExecutionLogDao(),
                        firestoreRepository = fs,
                        auditLogger = logger
                    ).also { instance = it }
                }
            }
        }

        fun buildDefaultHierarchyNodes(): List<HierarchyNodeEntity> = listOf(
            HierarchyNodeEntity(
                id = "node_root",
                parentId = null,
                name = "xcraftpy",
                nodeType = HierarchyNodeType.ROOT,
                relativePath = "xcraftpy/",
                humanExplanation = "Your root workspace folder. Think of this as the main project briefcase holding agents, tools, security checks, and run history.",
                agentSchemaRole = "ROOT_NAMESPACE_PACKAGE",
                sortOrder = 0
            ),
            HierarchyNodeEntity(
                id = "node_agents",
                parentId = "node_root",
                name = "agents",
                nodeType = HierarchyNodeType.MODULE,
                relativePath = "xcraftpy/agents/",
                humanExplanation = "Holds AI reasoning scripts and multi-model routers that decide which AI model or tool handles each task.",
                agentSchemaRole = "AGENT_ORCHESTRATION_LAYER",
                sortOrder = 1
            ),
            HierarchyNodeEntity(
                id = "node_tools",
                parentId = "node_root",
                name = "plugins/tools",
                nodeType = HierarchyNodeType.MODULE,
                relativePath = "xcraftpy/plugins/tools/",
                humanExplanation = "Contains standalone utilities like DNS lookups, SSL checks, and FastAPI helpers that agents can safely call.",
                agentSchemaRole = "SANDBOXED_TOOL_PLUGINS",
                sortOrder = 2
            ),
            HierarchyNodeEntity(
                id = "node_security",
                parentId = "node_root",
                name = "security",
                nodeType = HierarchyNodeType.FOLDER,
                relativePath = "xcraftpy/security/",
                humanExplanation = "Houses prompt-injection guards, secret maskers, and AES-256-GCM encrypted envelope rules.",
                agentSchemaRole = "ZERO_TRUST_SECURITY_BOUNDARY",
                sortOrder = 3
            ),
            HierarchyNodeEntity(
                id = "node_summoned",
                parentId = "node_root",
                name = "summoned",
                nodeType = HierarchyNodeType.FOLDER,
                relativePath = "xcraftpy/summoned/",
                humanExplanation = "Staging area for scripts downloaded or streamed safely from GitHub or Terminal sessions.",
                agentSchemaRole = "ENCRYPTED_STREAM_INGEST",
                sortOrder = 4
            )
        )

        fun builtInPresets(): List<HierarchyPreset> = listOf(
            HierarchyPreset(
                id = "sovereign_modular",
                name = "Sovereign Modular",
                tagline = "Balanced layout for agents, plugins, security, and summoned streams",
                humanSummary = "Separates orchestration brains (`agents/`), reusable utilities (`plugins/tools/`), security guards (`security/`), and downloaded streams (`summoned/`).",
                nodes = buildDefaultHierarchyNodes()
            ),
            HierarchyPreset(
                id = "fastapi_microservice",
                name = "FastAPI Service",
                tagline = "Production web API structure with routes, schemas, and tests",
                humanSummary = "Great when building REST APIs: groups web endpoints (`app/api/`), data models (`app/schemas/`), AI workers (`app/workers/`), and automated tests (`tests/`).",
                nodes = listOf(
                    HierarchyNodeEntity("fa_root", null, "xcraft_api", HierarchyNodeType.ROOT, "xcraft_api/", "Top-level FastAPI service workspace.", "SERVICE_ROOT", 0),
                    HierarchyNodeEntity("fa_api", "fa_root", "api/routes", HierarchyNodeType.MODULE, "xcraft_api/api/routes/", "HTTP endpoints and dependency injection handlers.", "HTTP_TRANSPORT_LAYER", 1),
                    HierarchyNodeEntity("fa_schemas", "fa_root", "schemas", HierarchyNodeType.MODULE, "xcraft_api/schemas/", "Pydantic request/response contracts with strict validation.", "CONTRACT_SERIALIZATION", 2),
                    HierarchyNodeEntity("fa_workers", "fa_root", "agent_workers", HierarchyNodeType.FOLDER, "xcraft_api/agent_workers/", "Background AI script runners and execution telemetry hooks.", "ASYNC_AGENT_DISPATCH", 3),
                    HierarchyNodeEntity("fa_tests", "fa_root", "tests", HierarchyNodeType.FOLDER, "xcraft_api/tests/", "Security regression & CVE validation suite.", "VERIFICATION_HARNESS", 4)
                )
            ),
            HierarchyPreset(
                id = "cli_toolkit",
                name = "CLI Security Toolkit",
                tagline = "Streamlined layout for terminal-summoned diagnostic scripts",
                humanSummary = "Best for power users working in the Terminal Summoner: keeps CLI entrypoints (`bin/`), diagnostics (`probes/`), and audit exports (`vault/`) neatly separated.",
                nodes = listOf(
                    HierarchyNodeEntity("cli_root", null, "xcraft_cli", HierarchyNodeType.ROOT, "xcraft_cli/", "Root CLI toolkit directory.", "CLI_NAMESPACE", 0),
                    HierarchyNodeEntity("cli_bin", "cli_root", "bin", HierarchyNodeType.MODULE, "xcraft_cli/bin/", "Summonable command entrypoints.", "COMMAND_ENTRYPOINTS", 1),
                    HierarchyNodeEntity("cli_probes", "cli_root", "probes", HierarchyNodeType.MODULE, "xcraft_cli/probes/", "DNS, SSL, and sandbox audit scripts.", "DIAGNOSTIC_PROBES", 2),
                    HierarchyNodeEntity("cli_vault", "cli_root", "vault", HierarchyNodeType.FOLDER, "xcraft_cli/vault/", "AES-256-GCM encrypted session logs and exports.", "ENCRYPTED_TELEMETRY_STORE", 3)
                )
            )
        )
    }
}
