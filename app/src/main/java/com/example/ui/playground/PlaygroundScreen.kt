package com.example.ui.playground

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.example.data.repository.FirestoreRepository
import com.example.domain.sandbox.ExecutionResult
import com.example.domain.sandbox.PythonSandboxEngine
import com.example.ui.theme.BorderStroke
import com.example.ui.theme.CodeBackground
import com.example.ui.theme.CyanPrimary
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
import kotlinx.coroutines.launch

val SCRIPT_TEMPLATES = listOf(
    "FastAPI Auth Endpoint" to """
from fastapi import FastAPI, Depends, HTTPException, status
from fastapi.security import OAuth2PasswordBearer
from pydantic import BaseModel

app = FastAPI(title="Xcraft Secure Auth API", version="2.0.0")
oauth2_scheme = OAuth2PasswordBearer(tokenUrl="token")

class User(BaseModel):
    username: str
    email: str
    is_active: bool = True

@app.post("/auth/register")
def register_user(user: User):
    # Sovereign verified user registration
    print(f"Registered secure user: {user.username}")
    return {"status": "success", "username": user.username}

@app.get("/auth/me")
def read_current_user(token: str = Depends(oauth2_scheme)):
    return {"token": token, "sovereign_verified": True}
""".trimIndent(),

    "Live DNS Validator" to """
# Xcraft DNS Resolution Plugin
from xcraftpy.plugins.tools.dns_validator import DNSValidator

validator = DNSValidator()
print("Starting DNS Validator for google.com...")
dns google.com
dns python.org
print("DNS verification completed.")
""".trimIndent(),

    "Live SSL Checker" to """
# Xcraft SSL/TLS Certificate Auditor
from xcraftpy.plugins.tools.ssl_checker import SSLChecker

checker = SSLChecker()
print("Auditing SSL certificate for python.org...")
ssl python.org
ssl google.com
print("SSL verification completed.")
""".trimIndent(),

    "Agent Router Multi-Model" to """
from xcraftpy.agents.router import AgentRouter
from xcraftpy.plugins.tools.dns_validator import DNSValidator
from xcraftpy.plugins.tools.ssl_checker import SSLChecker

router = AgentRouter({
    "dns": DNSValidator(),
    "ssl": SSLChecker()
})

print("Routing request through sovereign router:")
print(router.route("dns", "elevatecraft.org"))
print(router.route("ssl", "elevatecraft.org"))
""".trimIndent(),

    "Vulnerability CVE Test" to """
# Auditing script for insecure code execution
import json

def process_payload(raw_json: str):
    data = json.loads(raw_json)
    return {"count": len(data), "safe": True}

print("Code is clean and verified against PHP/Python CVEs.")
""".trimIndent()
)

@Composable
fun PlaygroundScreen(
    repository: FirestoreRepository
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var scriptTitle by remember { mutableStateOf("My Sovereign Tool") }
    var scriptCategory by remember { mutableStateOf("tool") }
    var codeText by remember { mutableStateOf(SCRIPT_TEMPLATES[0].second) }
    var isExecuting by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var executionResult by remember { mutableStateOf<ExecutionResult?>(null) }
    var auditWarnings by remember { mutableStateOf<List<String>>(emptyList()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .testTag("playground_screen")
    ) {
        // Top Toolbar: Template Chips
        Surface(color = DarkSurface, tonalElevation = 3.dp) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Python Script Playground", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text("Zero-CVE Sandbox", fontSize = 11.sp, color = EmeraldTertiary, fontWeight = FontWeight.Medium)
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SCRIPT_TEMPLATES.forEach { (name, template) ->
                        AssistChip(
                            onClick = {
                                scriptTitle = name
                                codeText = template
                                executionResult = null
                                auditWarnings = emptyList()
                            },
                            label = { Text(name, fontSize = 11.sp) },
                            colors = AssistChipDefaults.assistChipColors(containerColor = DarkCard)
                        )
                    }
                }
            }
        }

        // Script Details & Actions
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = scriptTitle,
                onValueChange = { scriptTitle = it },
                label = { Text("Script Title", fontSize = 10.sp) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyanPrimary,
                    unfocusedBorderColor = BorderStroke,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )

            // Audit Button
            Button(
                onClick = {
                    auditWarnings = PythonSandboxEngine.auditCode(codeText)
                    if (auditWarnings.isEmpty()) {
                        Toast.makeText(context, "✅ Clean: 0 vulnerabilities found!", Toast.LENGTH_SHORT).show()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = DarkCard, contentColor = WarningAmber),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(48.dp)
            ) {
                Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Audit", fontSize = 11.sp)
            }

            // Run Button
            Button(
                onClick = {
                    isExecuting = true
                    coroutineScope.launch {
                        val result = PythonSandboxEngine.executeScript(codeText)
                        executionResult = result
                        isExecuting = false

                        // Automatically log execution to Firestore
                        try {
                            repository.logExecution(
                                command = scriptTitle,
                                output = result.output,
                                status = result.status,
                                durationMs = result.durationMs
                            )
                        } catch (e: Exception) {
                            // Non-fatal logging error
                        }
                    }
                },
                enabled = !isExecuting,
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary, contentColor = Color.Black),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .height(48.dp)
                    .testTag("run_script_button")
            ) {
                if (isExecuting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Black, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Run", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }

            // Save to Firestore Button
            Button(
                onClick = {
                    isSaving = true
                    coroutineScope.launch {
                        try {
                            repository.saveScript(
                                title = scriptTitle,
                                code = codeText,
                                description = "Saved from Xcraft Playground",
                                category = scriptCategory
                            )
                            Toast.makeText(context, "Saved '$scriptTitle' to Firestore!", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, "Save error: ${e.message}", Toast.LENGTH_LONG).show()
                        } finally {
                            isSaving = false
                        }
                    }
                },
                enabled = !isSaving,
                colors = ButtonDefaults.buttonColors(containerColor = PurpleSecondary, contentColor = Color.White),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(48.dp)
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
        }

        // Audit Warning Banner if issues found
        if (auditWarnings.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .border(1.dp, WarningAmber, RoundedCornerShape(8.dp))
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text("⚠️ Security Auditor Findings (${auditWarnings.size}):", fontSize = 11.sp, color = WarningAmber, fontWeight = FontWeight.Bold)
                    auditWarnings.forEach { w ->
                        Text("• $w", fontSize = 10.sp, color = TextSecondary)
                    }
                }
            }
        }

        // Code Editor
        Card(
            colors = CardDefaults.cardColors(containerColor = CodeBackground),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .border(1.dp, BorderStroke, RoundedCornerShape(12.dp))
        ) {
            OutlinedTextField(
                value = codeText,
                onValueChange = { codeText = it },
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("code_editor_field"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = CodeBackground,
                    unfocusedContainerColor = CodeBackground,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            )
        }

        // Live Output Console
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .border(1.dp, BorderStroke, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("TERMINAL OUTPUT", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextMuted, letterSpacing = 1.sp)
                        if (executionResult != null) {
                            Spacer(modifier = Modifier.width(8.dp))
                            val color = if (executionResult!!.status == "SUCCESS") EmeraldTertiary else DangerRose
                            Text(
                                text = "${executionResult!!.status} (${executionResult!!.durationMs.toInt()}ms)",
                                fontSize = 10.sp,
                                color = color,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Text("Python 3.12 / Sovereign v2.0", fontSize = 10.sp, color = TextMuted)
                }

                Spacer(modifier = Modifier.height(6.dp))

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp))
                        .background(CodeBackground)
                        .padding(8.dp)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                ) {
                    Text(
                        text = executionResult?.output ?: "⚡ Ready. Tap 'Run' to execute in the sovereign sandbox.",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = when (executionResult?.status) {
                            "BLOCKED" -> DangerRose
                            "ERROR" -> WarningAmber
                            "SUCCESS" -> TextPrimary
                            else -> TextMuted
                        },
                        lineHeight = 15.sp
                    )
                }
            }
        }
    }
}

val DangerRose = com.example.ui.theme.DangerRose
