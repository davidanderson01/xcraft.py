package com.example.ui.library

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import com.example.data.model.ScriptEntity
import com.example.data.repository.FirestoreRepository
import com.example.ui.auth.performSignOut
import com.example.ui.theme.BorderStroke
import com.example.ui.theme.CodeBackground
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.DangerRose
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkCard
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.EmeraldTertiary
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.launch

@Composable
fun ScriptLibraryScreen(
    repository: FirestoreRepository,
    onOpenInPlayground: (String, String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(context) }
    val currentUser = Firebase.auth.currentUser

    val scripts by repository.observeScripts().collectAsState(initial = emptyList())
    var showCreateDialog by remember { mutableStateOf(false) }

    var newTitle by remember { mutableStateOf("") }
    var newCode by remember { mutableStateOf("") }
    var newDesc by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .testTag("script_library_screen")
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // User Header Profile & Sign-Out
            Surface(color = DarkSurface, tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(CyanPrimary.copy(alpha = 0.2f))
                                .border(1.dp, CyanPrimary, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Person, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(24.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = currentUser?.displayName ?: "Sovereign Developer",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = currentUser?.email ?: "Google Authenticated",
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudDone, contentDescription = null, tint = EmeraldTertiary, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                performSignOut(
                                    context = context,
                                    credentialManager = credentialManager,
                                    onSignOutComplete = {
                                        Toast.makeText(context, "Signed out successfully", Toast.LENGTH_SHORT).show()
                                    },
                                    scope = coroutineScope
                                )
                            }
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Sign Out", tint = DangerRose)
                        }
                    }
                }
            }

            // Script Count Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SAVED CLOUD SCRIPTS (${scripts.size})",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "Firestore Enterprise Synced",
                    fontSize = 11.sp,
                    color = EmeraldTertiary
                )
            }

            // Scripts List
            if (scripts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Code, contentDescription = null, tint = TextMuted, modifier = Modifier.size(56.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("No scripts saved yet", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Save scripts from the Playground or tap the '+' button below to create your first sovereign script.",
                            fontSize = 12.sp,
                            color = TextSecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(scripts, key = { it.id }) { script ->
                        ScriptCardItem(
                            script = script,
                            onOpen = { onOpenInPlayground(script.title, script.code) },
                            onDelete = {
                                coroutineScope.launch {
                                    try {
                                        repository.deleteScript(script.id)
                                        Toast.makeText(context, "Deleted ${script.title}", Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }

        // FAB to add new script
        FloatingActionButton(
            onClick = {
                newTitle = "New Sovereign Script"
                newCode = "# Xcraft Python Script\nprint('Hello from Sovereign Xcraft!')\n"
                newDesc = ""
                showCreateDialog = true
            },
            containerColor = CyanPrimary,
            contentColor = Color.Black,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .testTag("add_script_fab")
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add new script")
        }

        // Create Script Modal Dialog
        if (showCreateDialog) {
            AlertDialog(
                onDismissRequest = { showCreateDialog = false },
                title = { Text("New Sovereign Script", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = newTitle,
                            onValueChange = { newTitle = it },
                            label = { Text("Script Title") },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyanPrimary,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )
                        OutlinedTextField(
                            value = newDesc,
                            onValueChange = { newDesc = it },
                            label = { Text("Description (Optional)") },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyanPrimary,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )
                        OutlinedTextField(
                            value = newCode,
                            onValueChange = { newCode = it },
                            label = { Text("Python Source Code") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyanPrimary,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (newTitle.isNotBlank() && newCode.isNotBlank()) {
                                coroutineScope.launch {
                                    try {
                                        repository.saveScript(
                                            title = newTitle.trim(),
                                            code = newCode.trim(),
                                            description = newDesc.trim(),
                                            category = "tool"
                                        )
                                        showCreateDialog = false
                                        Toast.makeText(context, "Saved script to Firestore", Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary, contentColor = Color.Black)
                    ) {
                        Text("Save Script")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCreateDialog = false }) {
                        Text("Cancel", color = TextSecondary)
                    }
                },
                containerColor = DarkCard
            )
        }
    }
}

@Composable
private fun ScriptCardItem(
    script: ScriptEntity,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderStroke, RoundedCornerShape(12.dp))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Code, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = script.title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }

                Row {
                    IconButton(onClick = onOpen, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Run in Playground", tint = EmeraldTertiary)
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = DangerRose)
                    }
                }
            }

            if (script.description.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = script.description, fontSize = 11.sp, color = TextSecondary)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Code Preview Snippet
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(CodeBackground)
                    .padding(8.dp)
            ) {
                Text(
                    text = script.code.lines().take(4).joinToString("\n"),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = TextSecondary,
                    maxLines = 4
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = EmeraldTertiary, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Zero-Vulnerability Audited", fontSize = 10.sp, color = EmeraldTertiary)
                }
                Text("Category: ${script.category}", fontSize = 10.sp, color = TextMuted)
            }
        }
    }
}
