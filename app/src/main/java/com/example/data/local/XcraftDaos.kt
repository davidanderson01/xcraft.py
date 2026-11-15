package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface HierarchyNodeDao {

    @Query("SELECT * FROM hierarchy_nodes ORDER BY sort_order ASC, name ASC")
    fun observeAllNodes(): Flow<List<HierarchyNodeEntity>>

    @Query("SELECT * FROM hierarchy_nodes ORDER BY sort_order ASC, name ASC")
    suspend fun getAllNodesOnce(): List<HierarchyNodeEntity>

    @Query("SELECT * FROM hierarchy_nodes WHERE parent_id = :parentId ORDER BY sort_order ASC, name ASC")
    fun observeChildren(parentId: String): Flow<List<HierarchyNodeEntity>>

    @Query("SELECT COUNT(*) FROM hierarchy_nodes")
    suspend fun countNodes(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNode(node: HierarchyNodeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNodes(nodes: List<HierarchyNodeEntity>)

    @Query("DELETE FROM hierarchy_nodes WHERE id = :id OR parent_id = :id")
    suspend fun deleteNodeSubtree(id: String)

    @Query("DELETE FROM hierarchy_nodes")
    suspend fun clearAll()
}

@Dao
interface AgentScriptDao {

    @Query("SELECT * FROM agent_scripts ORDER BY updated_at DESC")
    fun observeAllScripts(): Flow<List<AgentScriptEntity>>

    @Transaction
    @Query("SELECT * FROM agent_scripts ORDER BY updated_at DESC")
    fun observeScriptsWithLogs(): Flow<List<ScriptWithExecutionLogs>>

    @Query("SELECT * FROM agent_scripts ORDER BY updated_at DESC")
    suspend fun getAllScriptsOnce(): List<AgentScriptEntity>

    @Query("SELECT * FROM agent_scripts WHERE id = :id LIMIT 1")
    suspend fun getScriptById(id: String): AgentScriptEntity?

    @Query("SELECT * FROM agent_scripts WHERE LOWER(title) = LOWER(:title) LIMIT 1")
    suspend fun getScriptByTitle(title: String): AgentScriptEntity?

    @Query("SELECT * FROM agent_scripts WHERE cloud_id = :cloudId LIMIT 1")
    suspend fun getScriptByCloudId(cloudId: String): AgentScriptEntity?

    @Query("SELECT * FROM agent_scripts WHERE sync_status != 'SYNCED'")
    suspend fun getUnsyncedScripts(): List<AgentScriptEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertScript(script: AgentScriptEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertScripts(scripts: List<AgentScriptEntity>)

    @Query("UPDATE agent_scripts SET cloud_id = :cloudId, sync_status = :syncStatus, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateSyncState(id: String, cloudId: String?, syncStatus: String, updatedAt: Long)

    @Query("DELETE FROM agent_scripts WHERE id = :id")
    suspend fun deleteScriptById(id: String)

    @Query("SELECT COUNT(*) FROM agent_scripts")
    suspend fun countScripts(): Int
}

@Dao
interface AgentExecutionLogDao {

    @Query("SELECT * FROM agent_execution_logs ORDER BY executed_at DESC LIMIT 200")
    fun observeAllLogs(): Flow<List<AgentExecutionLogEntity>>

    @Query("SELECT * FROM agent_execution_logs WHERE script_id = :scriptId ORDER BY executed_at DESC")
    fun observeLogsForScript(scriptId: String): Flow<List<AgentExecutionLogEntity>>

    @Query("SELECT * FROM agent_execution_logs WHERE sync_status != 'SYNCED' ORDER BY executed_at ASC")
    suspend fun getUnsyncedLogs(): List<AgentExecutionLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: AgentExecutionLogEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLogs(logs: List<AgentExecutionLogEntity>)

    @Query("UPDATE agent_execution_logs SET sync_status = :syncStatus WHERE id = :id")
    suspend fun updateSyncState(id: String, syncStatus: String)

    @Query("DELETE FROM agent_execution_logs WHERE script_id = :scriptId")
    suspend fun deleteLogsForScript(scriptId: String)

    @Query("DELETE FROM agent_execution_logs")
    suspend fun clearAllLogs()
}
