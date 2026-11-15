package com.example.ui.hierarchy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.AgentExecutionLogEntity
import com.example.data.local.AgentScriptEntity
import com.example.data.local.HierarchyNodeEntity
import com.example.data.local.HierarchyNodeType
import com.example.data.local.ScriptWithExecutionLogs
import com.example.data.remote.GeminiService
import com.example.data.repository.HierarchyPreset
import com.example.data.repository.SovereignWorkspaceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

enum class HierarchyPerspective(val label: String) {
    DUAL("Dual (Both Sides)"),
    HUMAN_GUIDE("Human Guide"),
    AGENT_MANIFEST("Agent Schema")
}

data class HierarchyPlannerUiState(
    val nodes: List<HierarchyNodeEntity> = emptyList(),
    val scripts: List<AgentScriptEntity> = emptyList(),
    val scriptsWithLogs: List<ScriptWithExecutionLogs> = emptyList(),
    val recentLogs: List<AgentExecutionLogEntity> = emptyList(),
    val presets: List<HierarchyPreset> = SovereignWorkspaceRepository.builtInPresets(),
    val selectedNodeId: String? = null,
    val perspective: HierarchyPerspective = HierarchyPerspective.DUAL,
    val showHelpPrompt: Boolean = true,
    val helpStepMessage: String = "Need help managing your project hierarchy? Choose a layout suggestion below, inspect any folder for a plain-language guide, or ask AI Architect to explain deeper.",
    val deeperExplanation: String? = null,
    val isAiBusy: Boolean = false,
    val isSyncing: Boolean = false,
    val statusMessage: String? = null
)

class HierarchyPlannerViewModel(
    private val repository: SovereignWorkspaceRepository
) : ViewModel() {

    private val _selectedNodeId = MutableStateFlow<String?>(null)
    private val _perspective = MutableStateFlow(HierarchyPerspective.DUAL)
    private val _showHelpPrompt = MutableStateFlow(true)
    private val _helpStepMessage = MutableStateFlow(
        "Want help organizing your scripts, logs, and project folders? Pick a suggested layout, tap any item for explanations, or view the AI Agent schema side."
    )
    private val _deeperExplanation = MutableStateFlow<String?>(null)
    private val _isAiBusy = MutableStateFlow(false)
    private val _isSyncing = MutableStateFlow(false)
    private val _statusMessage = MutableStateFlow<String?>(null)

    private data class UiFlags(
        val selectedNodeId: String?,
        val perspective: HierarchyPerspective,
        val showHelpPrompt: Boolean,
        val helpStepMessage: String,
        val deeperExplanation: String?,
        val isAiBusy: Boolean,
        val isSyncing: Boolean,
        val statusMessage: String?
    )

    private data class CoreUiFlags(
        val selectedNodeId: String?,
        val perspective: HierarchyPerspective,
        val showHelpPrompt: Boolean,
        val helpStepMessage: String
    )

    private val coreFlagsFlow = combine(
        _selectedNodeId,
        _perspective,
        _showHelpPrompt,
        _helpStepMessage
    ) { selectedNodeId, perspective, showHelpPrompt, helpStepMessage ->
        CoreUiFlags(selectedNodeId, perspective, showHelpPrompt, helpStepMessage)
    }

    private val flagsFlow = combine(
        coreFlagsFlow,
        _deeperExplanation,
        _isAiBusy,
        _isSyncing,
        _statusMessage
    ) { core, deeperExplanation, isAiBusy, isSyncing, statusMessage ->
        UiFlags(
            selectedNodeId = core.selectedNodeId,
            perspective = core.perspective,
            showHelpPrompt = core.showHelpPrompt,
            helpStepMessage = core.helpStepMessage,
            deeperExplanation = deeperExplanation,
            isAiBusy = isAiBusy,
            isSyncing = isSyncing,
            statusMessage = statusMessage
        )
    }

    val uiState: StateFlow<HierarchyPlannerUiState> = combine(
        repository.allNodes,
        repository.allScripts,
        repository.scriptsWithLogs,
        repository.allExecutionLogs,
        flagsFlow
    ) { nodes, scripts, scriptsWithLogs, logs, flags ->
        val resolvedSelected = flags.selectedNodeId?.takeIf { id -> nodes.any { it.id == id } }
            ?: nodes.firstOrNull()?.id
        HierarchyPlannerUiState(
            nodes = nodes,
            scripts = scripts,
            scriptsWithLogs = scriptsWithLogs,
            recentLogs = logs,
            selectedNodeId = resolvedSelected,
            perspective = flags.perspective,
            showHelpPrompt = flags.showHelpPrompt,
            helpStepMessage = flags.helpStepMessage,
            deeperExplanation = flags.deeperExplanation,
            isAiBusy = flags.isAiBusy,
            isSyncing = flags.isSyncing,
            statusMessage = flags.statusMessage
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = HierarchyPlannerUiState()
    )

    init {
        viewModelScope.launch {
            repository.seedStarterWorkspaceIfEmpty()
            repository.syncWithFirestoreIfAuthenticated()
        }
    }

    fun selectNode(nodeId: String) {
        _selectedNodeId.value = nodeId
        _deeperExplanation.value = null
    }

    fun setPerspective(perspective: HierarchyPerspective) {
        _perspective.value = perspective
    }

    fun toggleHelpPrompt(show: Boolean) {
        _showHelpPrompt.value = show
    }

    fun clearStatus() {
        _statusMessage.value = null
    }

    fun applyPreset(preset: HierarchyPreset) {
        viewModelScope.launch {
            repository.applyHierarchyPreset(preset)
            _helpStepMessage.value = "Applied '${preset.name}': ${preset.humanSummary}"
            _statusMessage.value = "Updated hierarchy to '${preset.name}'."
            _deeperExplanation.value = null
        }
    }

    fun addFolderOrModule(
        parentId: String?,
        name: String,
        nodeType: String,
        humanExplanation: String,
        agentSchemaRole: String
    ) {
        val cleanName = name.trim()
        if (cleanName.isBlank()) return

        viewModelScope.launch {
            val currentNodes = uiState.value.nodes
            val parentNode = currentNodes.firstOrNull { it.id == parentId }
            val basePath = parentNode?.relativePath?.trimEnd('/') ?: "xcraftpy"
            val relativePath = "$basePath/$cleanName/"
            val newNode = HierarchyNodeEntity(
                id = "node_${UUID.randomUUID().toString().take(8)}",
                parentId = parentNode?.id ?: currentNodes.firstOrNull()?.id,
                name = cleanName,
                nodeType = nodeType.ifBlank { HierarchyNodeType.FOLDER },
                relativePath = relativePath,
                humanExplanation = humanExplanation.ifBlank {
                    "Custom workspace container organizing scripts and execution records under $relativePath."
                },
                agentSchemaRole = agentSchemaRole.ifBlank { "CUSTOM_WORKSPACE_NODE" },
                sortOrder = currentNodes.size + 1
            )
            repository.upsertHierarchyNode(newNode)
            _selectedNodeId.value = newNode.id
            _statusMessage.value = "Added '$cleanName' to hierarchy."
        }
    }

    fun deleteNode(nodeId: String) {
        viewModelScope.launch {
            repository.deleteHierarchySubtree(nodeId)
            _statusMessage.value = "Removed node from Room hierarchy."
        }
    }

    fun explainSelectedDeeper(useAiIfConfigured: Boolean = true) {
        val state = uiState.value
        val node = state.nodes.firstOrNull { it.id == state.selectedNodeId } ?: return
        val childCount = state.nodes.count { it.parentId == node.id }
        val scriptCount = state.scripts.count {
            it.nodeId == node.id || it.hierarchyPath.startsWith(node.relativePath.trimEnd('/'))
        }
        val localSummary = repository.buildHumanNodeExplanation(node, childCount, scriptCount)

        if (!useAiIfConfigured) {
            _deeperExplanation.value = localSummary
            return
        }

        _isAiBusy.value = true
        _deeperExplanation.value = localSummary
        viewModelScope.launch {
            try {
                val manifest = buildAgentManifest()
                val prompt = """
                    Explain the folder/module '${node.name}' (${node.relativePath}, role=${node.agentSchemaRole}) in two concise sections:
                    1. Human Plain-English Guide: What belongs here, how it fits the big picture, and practical tips for the user.
                    2. Agent & Terminal Tips: How AI agents and CLI scripts should reference this path.
                    Current workspace manifest:
                    $manifest
                """.trimIndent()
                val res = GeminiService.generateContent(
                    model = "gemini-3.8-flash",
                    prompt = prompt,
                    systemInstruction = "You are Xcraft's friendly Hierarchy Guide helping developers organize Python AI projects cleanly."
                )
                if (res.error == null && res.text.isNotBlank()) {
                    _deeperExplanation.value = res.text
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Local plain-English explanation already displayed cleanly
            } finally {
                _isAiBusy.value = false
            }
        }
    }

    fun askAiLayoutRecommendation(goalDescription: String) {
        val cleanGoal = goalDescription.trim()
        if (cleanGoal.isBlank()) return
        _isAiBusy.value = true
        viewModelScope.launch {
            try {
                val manifest = buildAgentManifest()
                val prompt = """
                    The user wants help structuring their Xcraft workspace for: "$cleanGoal".
                    Offer 1 clear recommended hierarchy plan with:
                    - Plain-English explanation of why each folder exists (for the user)
                    - Exact path & agent role mapping (for AI agents and terminal tools)
                    Keep it concise and actionable. Current structure:
                    $manifest
                """.trimIndent()
                val res = GeminiService.generateContent(
                    model = "gemini-3.8-flash",
                    prompt = prompt,
                    systemInstruction = "You are Xcraft Hierarchy Architect."
                )
                if (res.error == null && res.text.isNotBlank()) {
                    _deeperExplanation.value = res.text
                    _helpStepMessage.value = "AI Architect prepared a tailored hierarchy recommendation below."
                } else {
                    _deeperExplanation.value = buildOfflineGoalSuggestion(cleanGoal)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _deeperExplanation.value = buildOfflineGoalSuggestion(cleanGoal)
            } finally {
                _isAiBusy.value = false
            }
        }
    }

    fun syncWithCloud() {
        _isSyncing.value = true
        viewModelScope.launch {
            try {
                val (up, down) = repository.syncWithFirestoreIfAuthenticated()
                _statusMessage.value = "Sync complete: ↑$up uploaded, ↓$down pulled from Firestore."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _statusMessage.value = "Local Room saved (cloud sync deferred: ${e.localizedMessage ?: "offline"})."
            } finally {
                _isSyncing.value = false
            }
        }
    }

    fun buildAgentManifest(): String {
        val s = uiState.value
        return repository.buildAgentSchemaManifest(s.nodes, s.scripts, s.recentLogs)
    }

    private fun buildOfflineGoalSuggestion(goal: String): String {
        return """
            Suggested Hierarchy Plan for "$goal":
            • Human Guide:
              - core/ -> Main business logic and reusable AI prompts.
              - adapters/ -> Safe network, GitHub, and Room storage bridges.
              - guards/ -> Secret masking and CVE firewall checks before running scripts.
              - logs/ -> Masked execution history linked to each script in Room.
            • Agent Schema Role:
              - Use modular folders so scripts under adapters/ never leak raw tokens into core/ or logs/.
        """.trimIndent()
    }

    class Factory(private val repository: SovereignWorkspaceRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HierarchyPlannerViewModel(repository) as T
        }
    }
}
