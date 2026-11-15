package com.example.ui.hierarchy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.local.AgentScriptEntity
import com.example.data.local.HierarchyNodeEntity
import com.example.data.local.HierarchyNodeType
import com.example.data.repository.FirestoreRepository
import com.example.data.repository.SovereignWorkspaceRepository
import com.example.ui.theme.BorderStroke
import com.example.ui.theme.CodeBackground
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.DangerRose
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkCard
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.EmeraldTertiary
import com.example.ui.theme.PurpleSecondary
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningAmber

@Composable
fun HierarchyPlannerScreen(
    firestoreRepository: FirestoreRepository?,
    onOpenScriptInPlayground: (title: String, code: String) -> Unit = { _, _ -> },
    onCopyTerminalCommand: ((scriptTitle: String) -> Unit)? = null
) {
    val context = LocalContext.current
    val workspaceRepo = remember(context, firestoreRepository) {
        SovereignWorkspaceRepository.getInstance(context, firestoreRepository)
    }
    val vm: HierarchyPlannerViewModel = viewModel(
        factory = HierarchyPlannerViewModel.Factory(workspaceRepo)
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    val copyTerminalLauncher: (String) -> Unit = onCopyTerminalCommand ?: { title ->
        val safeTitle = title.replace("\"", "").trim()
        val cmd = "python cli/xcraft_summon.py --source local --target \"$safeTitle\""
        copyToClipboard(context, "Terminal Launch Command", cmd)
    }

    var showAddNodeDialog by remember { mutableStateOf(false) }
    var customGoalInput by remember { mutableStateOf("") }

    LaunchedEffect(state.statusMessage) {
        state.statusMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            vm.clearStatus()
        }
    }

    val agentManifestJson = remember(state.nodes, state.scripts, state.recentLogs) {
        vm.buildAgentManifest()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .testTag("hierarchy_planner_screen")
    ) {
        // Header + Dual Perspective Toggle
        Surface(color = DarkSurface, tonalElevation = 3.dp) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.AccountTree, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Hierarchy & File Structure Planner",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Room SQLite (${state.nodes.size} nodes, ${state.scripts.size} scripts, ${state.recentLogs.size} logs)",
                                fontSize = 10.sp,
                                color = EmeraldTertiary
                            )
                        }
                    }

                    Row {
                        IconButton(
                            onClick = { vm.syncWithCloud() },
                            enabled = !state.isSyncing,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("sync_hierarchy_button")
                        ) {
                            if (state.isSyncing) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = CyanPrimary, strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.CloudSync, contentDescription = "Sync Room with Firestore", tint = CyanPrimary)
                            }
                        }
                        IconButton(
                            onClick = { vm.toggleHelpPrompt(!state.showHelpPrompt) },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("toggle_hierarchy_help_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = "Toggle Guide Assistant", tint = PurpleSecondary)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Perspective Selector: Side 1 (Agent) vs Side 2 (Human) vs Dual
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HierarchyPerspective.entries.forEach { mode ->
                        FilterChip(
                            selected = state.perspective == mode,
                            onClick = { vm.setPerspective(mode) },
                            label = { Text(mode.label, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CyanPrimary.copy(alpha = 0.22f),
                                selectedLabelColor = CyanPrimary,
                                containerColor = DarkCard,
                                labelColor = TextSecondary
                            )
                        )
                    }
                }
            }
        }

        // Onboarding Help & Layout Suggestion Card
        AnimatedVisibility(visible = state.showHelpPrompt) {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .border(1.dp, PurpleSecondary.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = PurpleSecondary, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Want help organizing your hierarchy?",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = state.helpStepMessage,
                        fontSize = 11.sp,
                        color = TextSecondary,
                        lineHeight = 15.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Layout Presets
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        state.presets.forEach { preset ->
                            AssistChip(
                                onClick = { vm.applyPreset(preset) },
                                label = { Text("Apply: ${preset.name}", fontSize = 10.sp) },
                                colors = AssistChipDefaults.assistChipColors(containerColor = DarkSurfaceVariant)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Ask AI Architect for custom layout or further explanation
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = customGoalInput,
                            onValueChange = { customGoalInput = it },
                            placeholder = { Text("Describe what you're building for layout help...", fontSize = 11.sp, color = TextMuted) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PurpleSecondary,
                                unfocusedBorderColor = BorderStroke,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )
                        Button(
                            onClick = { vm.askAiLayoutRecommendation(customGoalInput) },
                            enabled = !state.isAiBusy && customGoalInput.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = PurpleSecondary, contentColor = Color.White),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(48.dp)
                        ) {
                            if (state.isAiBusy) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                            } else {
                                Text("Suggest", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // Main Dual-Sided Content Area
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            val isWide = maxWidth >= 680.dp && state.perspective == HierarchyPerspective.DUAL

            if (isWide) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HumanGuidePane(
                        state = state,
                        onSelectNode = vm::selectNode,
                        onAddNode = { showAddNodeDialog = true },
                        onDeleteNode = vm::deleteNode,
                        onExplainDeeper = { vm.explainSelectedDeeper(useAiIfConfigured = true) },
                        onOpenScriptInPlayground = onOpenScriptInPlayground,
                        onSummonInTerminal = copyTerminalLauncher,
                        modifier = Modifier
                            .weight(1.1f)
                            .fillMaxSize()
                    )
                    AgentManifestPane(
                        manifestJson = agentManifestJson,
                        logsCount = state.recentLogs.size,
                        onCopyManifest = {
                            copyToClipboard(context, "Xcraft Agent Manifest", agentManifestJson)
                        },
                        modifier = Modifier
                            .weight(0.9f)
                            .fillMaxSize()
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (state.perspective == HierarchyPerspective.DUAL || state.perspective == HierarchyPerspective.HUMAN_GUIDE) {
                        item {
                            HumanGuideHeaderCard(
                                onAddNode = { showAddNodeDialog = true },
                                selectedNode = state.nodes.firstOrNull { it.id == state.selectedNodeId },
                                deeperExplanation = state.deeperExplanation,
                                isAiBusy = state.isAiBusy,
                                onExplainDeeper = { vm.explainSelectedDeeper(useAiIfConfigured = true) }
                            )
                        }

                        items(state.nodes, key = { it.id }) { node ->
                            val depth = node.relativePath.trimEnd('/').count { it == '/' }
                            val linkedScripts = state.scripts.filter {
                                it.nodeId == node.id || it.hierarchyPath.startsWith(node.relativePath.trimEnd('/'))
                            }
                            HierarchyNodeRowCard(
                                node = node,
                                depth = depth,
                                isSelected = node.id == state.selectedNodeId,
                                linkedScripts = linkedScripts,
                                logsCountForNode = state.recentLogs.count { log ->
                                    linkedScripts.any { s -> s.id == log.scriptId }
                                },
                                onSelect = { vm.selectNode(node.id) },
                                onDelete = { vm.deleteNode(node.id) },
                                onOpenScript = onOpenScriptInPlayground,
                                onSummonScript = copyTerminalLauncher
                            )
                        }
                    }

                    if (state.perspective == HierarchyPerspective.DUAL || state.perspective == HierarchyPerspective.AGENT_MANIFEST) {
                        item {
                            AgentManifestPane(
                                manifestJson = agentManifestJson,
                                logsCount = state.recentLogs.size,
                                onCopyManifest = {
                                    copyToClipboard(context, "Xcraft Agent Manifest", agentManifestJson)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 260.dp, max = 380.dp)
                            )
                        }
                    }

                    item { Spacer(modifier = Modifier.height(12.dp)) }
                }
            }
        }
    }

    if (showAddNodeDialog) {
        AddHierarchyNodeDialog(
            parentNode = state.nodes.firstOrNull { it.id == state.selectedNodeId },
            onDismiss = { showAddNodeDialog = false },
            onConfirm = { name, type, explanation, role ->
                vm.addFolderOrModule(
                    parentId = state.selectedNodeId,
                    name = name,
                    nodeType = type,
                    humanExplanation = explanation,
                    agentSchemaRole = role
                )
                showAddNodeDialog = false
            }
        )
    }
}

@Composable
private fun HumanGuidePane(
    state: HierarchyPlannerUiState,
    onSelectNode: (String) -> Unit,
    onAddNode: () -> Unit,
    onDeleteNode: (String) -> Unit,
    onExplainDeeper: () -> Unit,
    onOpenScriptInPlayground: (String, String) -> Unit,
    onSummonInTerminal: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            HumanGuideHeaderCard(
                onAddNode = onAddNode,
                selectedNode = state.nodes.firstOrNull { it.id == state.selectedNodeId },
                deeperExplanation = state.deeperExplanation,
                isAiBusy = state.isAiBusy,
                onExplainDeeper = onExplainDeeper
            )
        }
        items(state.nodes, key = { it.id }) { node ->
            val depth = node.relativePath.trimEnd('/').count { it == '/' }
            val linkedScripts = state.scripts.filter {
                it.nodeId == node.id || it.hierarchyPath.startsWith(node.relativePath.trimEnd('/'))
            }
            HierarchyNodeRowCard(
                node = node,
                depth = depth,
                isSelected = node.id == state.selectedNodeId,
                linkedScripts = linkedScripts,
                logsCountForNode = state.recentLogs.count { log -> linkedScripts.any { it.id == log.scriptId } },
                onSelect = { onSelectNode(node.id) },
                onDelete = { onDeleteNode(node.id) },
                onOpenScript = onOpenScriptInPlayground,
                onSummonScript = onSummonInTerminal
            )
        }
    }
}

@Composable
private fun HumanGuideHeaderCard(
    onAddNode: () -> Unit,
    selectedNode: HierarchyNodeEntity?,
    deeperExplanation: String?,
    isAiBusy: Boolean,
    onExplainDeeper: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, CyanPrimary.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("SIDE 2 • HUMAN VISUAL GUIDE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = CyanPrimary)
                    Text("Plain-English File Structure & Roles", fontSize = 12.sp, color = TextSecondary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = onExplainDeeper,
                        enabled = selectedNode != null && !isAiBusy,
                        modifier = Modifier.height(48.dp)
                    ) {
                        Icon(Icons.Default.Psychology, contentDescription = null, tint = PurpleSecondary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Explain", fontSize = 11.sp, color = PurpleSecondary)
                    }
                    Button(
                        onClick = onAddNode,
                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary, contentColor = Color.Black),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("add_hierarchy_node_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (deeperExplanation != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(CodeBackground)
                        .padding(10.dp)
                ) {
                    Text(
                        text = deeperExplanation,
                        fontSize = 11.sp,
                        color = TextPrimary,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun HierarchyNodeRowCard(
    node: HierarchyNodeEntity,
    depth: Int,
    isSelected: Boolean,
    linkedScripts: List<AgentScriptEntity>,
    logsCountForNode: Int,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onOpenScript: (String, String) -> Unit,
    onSummonScript: (String) -> Unit
) {
    val startIndent = (depth.coerceAtMost(3) * 12).dp
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) DarkSurfaceVariant else DarkCard
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = startIndent)
            .border(
                1.dp,
                if (isSelected) CyanPrimary else BorderStroke,
                RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onSelect)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = if (node.nodeType == HierarchyNodeType.MODULE) Icons.Default.Code else Icons.Default.Folder,
                        contentDescription = null,
                        tint = if (node.nodeType == HierarchyNodeType.ROOT) EmeraldTertiary else CyanPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = node.relativePath,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${node.nodeType} • ${linkedScripts.size} script(s) • $logsCountForNode log(s)",
                            fontSize = 10.sp,
                            color = TextMuted
                        )
                    }
                }

                if (node.parentId != null) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete folder node", tint = DangerRose, modifier = Modifier.size(16.dp))
                    }
                }
            }

            if (node.humanExplanation.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = node.humanExplanation,
                    fontSize = 11.sp,
                    color = TextSecondary,
                    lineHeight = 15.sp
                )
            }

            if (linkedScripts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                linkedScripts.take(3).forEach { script ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(CodeBackground)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(script.title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Sync: ${script.syncStatus} • ${script.sourceOrigin}", fontSize = 9.sp, color = EmeraldTertiary)
                        }
                        Row {
                            IconButton(
                                onClick = { onSummonScript(script.title) },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Default.Terminal, contentDescription = "Copy Terminal Launch Command", tint = CyanPrimary, modifier = Modifier.size(16.dp))
                            }
                            IconButton(
                                onClick = { onOpenScript(script.title, script.code) },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Open in Playground", tint = EmeraldTertiary, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentManifestPane(
    manifestJson: String,
    logsCount: Int,
    onCopyManifest: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CodeBackground),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.border(1.dp, EmeraldTertiary.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("SIDE 1 • AGENT & TERMINAL SCHEMA", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = EmeraldTertiary)
                    Text("Machine-readable Room relational blueprint ($logsCount logs tracked)", fontSize = 10.sp, color = TextSecondary)
                }
                IconButton(
                    onClick = onCopyManifest,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy Agent Manifest JSON", tint = CyanPrimary, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
            ) {
                Text(
                    text = manifestJson,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = TextPrimary,
                    lineHeight = 14.sp
                )
            }
        }
    }
}

@Composable
private fun AddHierarchyNodeDialog(
    parentNode: HierarchyNodeEntity?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, type: String, explanation: String, role: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var explanation by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("MODULAR_AGENT_COMPONENT") }
    var selectedType by remember { mutableStateOf(HierarchyNodeType.FOLDER) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkCard,
        title = {
            Text(
                text = "Add Node under ${parentNode?.relativePath ?: "xcraftpy/"}",
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(HierarchyNodeType.FOLDER, HierarchyNodeType.MODULE).forEach { t ->
                        FilterChip(
                            selected = selectedType == t,
                            onClick = { selectedType = t },
                            label = { Text(t, fontSize = 10.sp) }
                        )
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Folder / Module Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = explanation,
                    onValueChange = { explanation = it },
                    label = { Text("Plain-English Human Guide") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = role,
                    onValueChange = { role = it },
                    label = { Text("Agent Schema Role Token") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, selectedType, explanation, role) },
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary, contentColor = Color.Black)
            ) {
                Text("Save to Room")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "Copied $label to clipboard", Toast.LENGTH_SHORT).show()
}
