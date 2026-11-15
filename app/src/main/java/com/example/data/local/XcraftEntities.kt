package com.example.data.local

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

object HierarchyNodeType {
    const val ROOT = "ROOT"
    const val FOLDER = "FOLDER"
    const val MODULE = "MODULE"
    const val SCRIPT = "SCRIPT"
}

object SyncStatus {
    const val LOCAL_ONLY = "LOCAL_ONLY"
    const val PENDING_SYNC = "PENDING_SYNC"
    const val SYNCED = "SYNCED"
}

/**
 * Models project file structures and hierarchical plans of AI modules, folders, and scripts.
 * Supports both human-readable explanations and machine/agent schema roles.
 */
@Entity(
    tableName = "hierarchy_nodes",
    indices = [
        Index(value = ["parent_id"]),
        Index(value = ["sort_order"])
    ]
)
data class HierarchyNodeEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "parent_id")
    val parentId: String? = null,
    val name: String,
    @ColumnInfo(name = "node_type")
    val nodeType: String = HierarchyNodeType.FOLDER,
    @ColumnInfo(name = "relative_path")
    val relativePath: String,
    @ColumnInfo(name = "human_explanation")
    val humanExplanation: String = "",
    @ColumnInfo(name = "agent_schema_role")
    val agentSchemaRole: String = "",
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Room entity representing an AI agent script persisted locally and synchronized with Firestore.
 */
@Entity(
    tableName = "agent_scripts",
    foreignKeys = [
        ForeignKey(
            entity = HierarchyNodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["node_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["node_id"]),
        Index(value = ["title"]),
        Index(value = ["sync_status"])
    ]
)
data class AgentScriptEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "cloud_id")
    val cloudId: String? = null,
    @ColumnInfo(name = "node_id")
    val nodeId: String? = null,
    @ColumnInfo(name = "hierarchy_path")
    val hierarchyPath: String = "xcraftpy/scripts",
    val title: String,
    val description: String = "",
    val code: String,
    val category: String = "tool",
    @ColumnInfo(name = "agent_role")
    val agentRole: String = "CraftX Architect",
    @ColumnInfo(name = "source_origin")
    val sourceOrigin: String = "LOCAL",
    @ColumnInfo(name = "is_audited_clean")
    val isAuditedClean: Boolean = true,
    @ColumnInfo(name = "sync_status")
    val syncStatus: String = SyncStatus.LOCAL_ONLY,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Room entity storing execution logs, terminal stream telemetry, and security transmission digests
 * associated with AI agent scripts.
 */
@Entity(
    tableName = "agent_execution_logs",
    foreignKeys = [
        ForeignKey(
            entity = AgentScriptEntity::class,
            parentColumns = ["id"],
            childColumns = ["script_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["script_id"]),
        Index(value = ["executed_at"]),
        Index(value = ["sync_status"])
    ]
)
data class AgentExecutionLogEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "script_id")
    val scriptId: String? = null,
    @ColumnInfo(name = "script_title")
    val scriptTitle: String,
    val command: String,
    @ColumnInfo(name = "masked_output")
    val maskedOutput: String,
    val status: String,
    @ColumnInfo(name = "execution_surface")
    val executionSurface: String = "PLAYGROUND",
    @ColumnInfo(name = "session_id")
    val sessionId: String = "",
    @ColumnInfo(name = "duration_ms")
    val durationMs: Double = 0.0,
    @ColumnInfo(name = "bytes_transmitted")
    val bytesTransmitted: Int = 0,
    @ColumnInfo(name = "secrets_masked")
    val secretsMasked: Int = 0,
    @ColumnInfo(name = "envelope_sha256")
    val envelopeSha256: String = "",
    @ColumnInfo(name = "sync_status")
    val syncStatus: String = SyncStatus.LOCAL_ONLY,
    @ColumnInfo(name = "executed_at")
    val executedAt: Long = System.currentTimeMillis()
)

/**
 * Relational projection pairing each AI agent script with its chronological execution logs.
 */
data class ScriptWithExecutionLogs(
    @Embedded val script: AgentScriptEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "script_id"
    )
    val logs: List<AgentExecutionLogEntity>
)
