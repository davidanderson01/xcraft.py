package com.example.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ChatMessageEntity
import com.example.data.remote.GeminiService
import com.example.data.repository.FirestoreRepository
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

data class UIMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: String, // "user" or "model"
    val content: String,
    val model: String = "",
    val thinking: String? = null,
    val imageBitmap: Bitmap? = null,
    val videoBase64: String? = null,
    val searchSources: List<String> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)

val SYSTEM_ROLES = listOf(
    "CraftX Architect" to "You are the Lead Architect for Xcraft.py (sovereign Python AI scripting & agent framework). Help the user build modular tools, router configurations, and sovereign automation.",
    "Python Security Auditor" to "You are the Sovereign Python Security Auditor for Xcraft.py. Audit code strictly against recent Python and PHP CVEs, prevent arbitrary code execution, verify zero-vulnerabilities, and check command whitelists.",
    "CodeGen Master" to "You are the Python CodeGen Master for Xcraft.py. Generate clean, type-hinted, idiomatic Python code, FastAPI endpoints, CLI tools, and test suites.",
    "Network Diagnostics Specialist" to "You are the Network Diagnostics Specialist for Xcraft.py. Guide users through DNS resolution, SSL certificate validation, socket verification, and sovereign server telemetry."
)

@Composable
fun ChatScreen(
    repository: FirestoreRepository
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var selectedRoleIndex by remember { mutableStateOf(0) }
    var selectedModel by remember { mutableStateOf("gemini-3.8-flash") }
    var enableSearchGrounding by remember { mutableStateOf(false) }
    var enableHighThinking by remember { mutableStateOf(false) }

    // Media mode toggles
    var isImageGenMode by remember { mutableStateOf(false) }
    var isVideoGenMode by remember { mutableStateOf(false) }
    var selectedAspectRatio by remember { mutableStateOf("1:1") }
    var selectedResolution by remember { mutableStateOf("1K") }

    var inputText by remember { mutableStateOf("") }
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var selectedImageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isGenerating by remember { mutableStateOf(false) }
    var statusStatusText by remember { mutableStateOf("") }

    val messages = remember { mutableStateListOf<UIMessage>() }

    // Initial greeting from selected role
    LaunchedEffect(Unit) {
        if (messages.isEmpty()) {
            messages.add(
                UIMessage(
                    role = "model",
                    content = "👋 Welcome to **Xcraft Studio**! I am your **${SYSTEM_ROLES[selectedRoleIndex].first}**.\n\n" +
                            "I can route across **Gemini 4 Argon** (with High Thinking), **Gemini 3.8 Flash** (with Google Search grounding), **3.1 Flash-Lite** (low-latency), " +
                            "generate studio images (1K/2K/4K with custom aspect ratios), generate videos with **Gemini Omni Flash**, or analyze uploaded photos.",
                    model = selectedModel
                )
            )
        }
    }

    // Photo picker launcher
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        selectedImageUri = uri
        if (uri != null) {
            try {
                val input = context.contentResolver.openInputStream(uri)
                selectedImageBitmap = BitmapFactory.decodeStream(input)
            } catch (e: Exception) {
                selectedImageBitmap = null
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .testTag("chat_screen")
    ) {
        // Top Control Strip: Role & Model Selector
        Surface(
            color = DarkSurface,
            tonalElevation = 4.dp
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // Role Picker Chips
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SYSTEM_ROLES.forEachIndexed { index, (roleName, _) ->
                        FilterChip(
                            selected = selectedRoleIndex == index,
                            onClick = { selectedRoleIndex = index },
                            label = { Text(roleName, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CyanPrimary.copy(alpha = 0.2f),
                                selectedLabelColor = CyanPrimary,
                                containerColor = DarkCard,
                                labelColor = TextSecondary
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Model & Feature Toggles Strip
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Models
                    AssistChip(
                        onClick = {
                            selectedModel = "gemini-4-argon"
                            enableHighThinking = true
                            isImageGenMode = false
                            isVideoGenMode = false
                        },
                        label = { Text("Gemini 4 Argon (Thinking)", fontSize = 11.sp) },
                        leadingIcon = { Icon(Icons.Default.Psychology, contentDescription = null, tint = PurpleSecondary, modifier = Modifier.size(16.dp)) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (selectedModel == "gemini-4-argon" && !isImageGenMode && !isVideoGenMode) PurpleSecondary.copy(alpha = 0.25f) else DarkCard
                        )
                    )

                    AssistChip(
                        onClick = {
                            selectedModel = "gemini-3.8-flash"
                            enableHighThinking = false
                            isImageGenMode = false
                            isVideoGenMode = false
                        },
                        label = { Text("Gemini 3.8 Flash (General)", fontSize = 11.sp) },
                        leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(16.dp)) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (selectedModel == "gemini-3.8-flash" && !isImageGenMode && !isVideoGenMode) CyanPrimary.copy(alpha = 0.25f) else DarkCard
                        )
                    )

                    AssistChip(
                        onClick = {
                            selectedModel = "gemini-3.1-flash-lite"
                            enableHighThinking = false
                            isImageGenMode = false
                            isVideoGenMode = false
                        },
                        label = { Text("3.1 Flash-Lite (Fast)", fontSize = 11.sp) },
                        leadingIcon = { Icon(Icons.Default.Bolt, contentDescription = null, tint = EmeraldTertiary, modifier = Modifier.size(16.dp)) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (selectedModel == "gemini-3.1-flash-lite" && !isImageGenMode && !isVideoGenMode) EmeraldTertiary.copy(alpha = 0.25f) else DarkCard
                        )
                    )

                    AssistChip(
                        onClick = {
                            isImageGenMode = !isImageGenMode
                            isVideoGenMode = false
                        },
                        label = { Text("Image Gen (1K-4K)", fontSize = 11.sp) },
                        leadingIcon = { Icon(Icons.Default.Image, contentDescription = null, tint = WarningAmber, modifier = Modifier.size(16.dp)) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (isImageGenMode) WarningAmber.copy(alpha = 0.25f) else DarkCard
                        )
                    )

                    AssistChip(
                        onClick = {
                            isVideoGenMode = !isVideoGenMode
                            isImageGenMode = false
                        },
                        label = { Text("Video Gen (Omni)", fontSize = 11.sp) },
                        leadingIcon = { Icon(Icons.Default.Videocam, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(16.dp)) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (isVideoGenMode) CyanPrimary.copy(alpha = 0.25f) else DarkCard
                        )
                    )
                }

                // Second row: Search Grounding switch and aspect ratios if in image/video mode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Language, contentDescription = null, tint = if (enableSearchGrounding) CyanPrimary else TextMuted, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Google Search Grounding", fontSize = 12.sp, color = if (enableSearchGrounding) CyanPrimary else TextSecondary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = enableSearchGrounding,
                            onCheckedChange = { enableSearchGrounding = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = CyanPrimary, checkedTrackColor = CyanPrimary.copy(alpha = 0.3f)),
                            modifier = Modifier.size(width = 38.dp, height = 24.dp)
                        )
                    }

                    if (isImageGenMode || isVideoGenMode) {
                        Text(
                            text = if (isImageGenMode) "Size: $selectedResolution | Ratio: $selectedAspectRatio" else "Ratio: $selectedAspectRatio",
                            fontSize = 11.sp,
                            color = WarningAmber
                        )
                    }
                }

                // AI Sovereign Armor Status Indicator
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(EmeraldTertiary)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "AI Sovereign Armor: Active (Cryptographic Nonce & Injection Shield)",
                        fontSize = 10.sp,
                        color = EmeraldTertiary,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Image Aspect Ratio & Size Controls
                if (isImageGenMode) {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("1:1", "16:9", "9:16", "4:3", "3:4", "2:3", "3:2", "21:9").forEach { ratio ->
                            AssistChip(
                                onClick = { selectedAspectRatio = ratio },
                                label = { Text(ratio, fontSize = 10.sp) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = if (selectedAspectRatio == ratio) WarningAmber.copy(alpha = 0.3f) else DarkCard
                                )
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        listOf("1K", "2K", "4K").forEach { res ->
                            AssistChip(
                                onClick = { selectedResolution = res },
                                label = { Text(res, fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = if (selectedResolution == res) CyanPrimary.copy(alpha = 0.3f) else DarkCard
                                )
                            )
                        }
                    }
                }

                // Video Aspect Ratio Controls (16:9 or 9:16 per specification)
                if (isVideoGenMode) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("16:9", "9:16").forEach { ratio ->
                            FilterChip(
                                selected = selectedAspectRatio == ratio,
                                onClick = { selectedAspectRatio = ratio },
                                label = { Text("Video $ratio", fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = CyanPrimary.copy(alpha = 0.25f),
                                    selectedLabelColor = CyanPrimary
                                )
                            )
                        }
                    }
                }
            }
        }

        // Messages Thread
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                ChatMessageItem(message = msg)
            }

            if (isGenerating) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = DarkCard),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(0.9f)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = CyanPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = statusStatusText.ifEmpty { "Generating response from ${if (isImageGenMode) "gemini-3-pro-image-preview" else if (isVideoGenMode) "gemini-omni-1.1-flash" else selectedModel}..." },
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }
        }

        // Selected Image Preview Thumbnail before sending
        if (selectedImageBitmap != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkCard)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    bitmap = selectedImageBitmap!!.asImageBitmap(),
                    contentDescription = "Selected photo",
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Photo attached for analysis", fontSize = 12.sp, color = TextPrimary)
                    Text("Will analyze with Gemini 4 Argon", fontSize = 10.sp, color = CyanPrimary)
                }
                IconButton(onClick = {
                    selectedImageBitmap = null
                    selectedImageUri = null
                }) {
                    Icon(Icons.Default.Close, contentDescription = "Remove photo", tint = TextMuted)
                }
            }
        }

        // Quick Suggestion Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                "Create a FastAPI endpoint for user auth",
                "Audit Python code for recent CVEs",
                "Check DNS for google.com",
                "Verify SSL certificate for python.org",
                "Show AgentRouter architecture"
            ).forEach { prompt ->
                AssistChip(
                    onClick = { inputText = prompt },
                    label = { Text(prompt, fontSize = 10.sp, maxLines = 1) },
                    colors = AssistChipDefaults.assistChipColors(containerColor = DarkSurfaceVariant)
                )
            }
        }

        // Input Field Strip
        Surface(
            color = DarkSurface,
            tonalElevation = 6.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Attach Photo button
                IconButton(
                    onClick = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier.size(42.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AddPhotoAlternate,
                        contentDescription = "Attach photo to analyze",
                        tint = if (selectedImageBitmap != null) CyanPrimary else TextMuted
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = {
                        Text(
                            when {
                                isVideoGenMode -> "Prompt video generation..."
                                isImageGenMode -> "Prompt image generation ($selectedAspectRatio)..."
                                selectedImageBitmap != null -> "Ask about this photo..."
                                else -> "Ask ${SYSTEM_ROLES[selectedRoleIndex].first}..."
                            },
                            fontSize = 13.sp,
                            color = TextMuted
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("chat_input_field"),
                    shape = RoundedCornerShape(20.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = DarkBackground,
                        unfocusedContainerColor = DarkBackground,
                        focusedBorderColor = CyanPrimary,
                        unfocusedBorderColor = BorderStroke,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    maxLines = 4
                )

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = {
                        if (inputText.isNotBlank() || selectedImageBitmap != null) {
                            val userText = inputText.trim()
                            val uploadedBmp = selectedImageBitmap
                            val activeRole = SYSTEM_ROLES[selectedRoleIndex].second
                            val targetModel = when {
                                uploadedBmp != null -> "gemini-4-argon"
                                isImageGenMode -> "gemini-3-pro-image-preview"
                                isVideoGenMode -> "gemini-omni-1.1-flash"
                                else -> selectedModel
                            }

                            // Add user message to UI
                            messages.add(
                                UIMessage(
                                    role = "user",
                                    content = userText.ifEmpty { "[Analyzing attached image]" },
                                    imageBitmap = uploadedBmp
                                )
                            )

                            inputText = ""
                            selectedImageBitmap = null
                            selectedImageUri = null
                            isGenerating = true
                            statusStatusText = "Connecting to $targetModel..."

                            coroutineScope.launch {
                                listState.animateScrollToItem(messages.size)

                                if (isVideoGenMode) {
                                    val videoResult = GeminiService.generateVideo(
                                        prompt = userText,
                                        aspectRatio = selectedAspectRatio,
                                        onProgress = { statusStatusText = it }
                                    )

                                    isGenerating = false
                                    messages.add(
                                        UIMessage(
                                            role = "model",
                                            content = if (videoResult.error != null) "❌ ${videoResult.error}" else videoResult.text,
                                            model = "gemini-omni-1.1-flash",
                                            videoBase64 = videoResult.videoBase64
                                        )
                                    )
                                } else {
                                    val historyPairs = messages.map { it.role to it.content }
                                    val result = GeminiService.generateContent(
                                        model = targetModel,
                                        prompt = userText,
                                        systemInstruction = activeRole,
                                        history = historyPairs.takeLast(10),
                                        enableSearch = enableSearchGrounding,
                                        enableHighThinking = enableHighThinking,
                                        imageBitmap = uploadedBmp,
                                        generateImage = isImageGenMode,
                                        imageAspectRatio = selectedAspectRatio,
                                        imageResolution = selectedResolution
                                    )

                                    var genBmp: Bitmap? = null
                                    if (result.imageBase64 != null) {
                                        try {
                                            val bytes = Base64.decode(result.imageBase64, Base64.DEFAULT)
                                            genBmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                        } catch (e: Exception) {
                                            // Handle decoding error
                                        }
                                    }

                                    isGenerating = false
                                    messages.add(
                                        UIMessage(
                                            role = "model",
                                            content = if (result.error != null) "❌ ${result.error}" else result.text,
                                            model = targetModel,
                                            thinking = result.thinking,
                                            imageBitmap = genBmp,
                                            searchSources = result.sources.map { "${it.title}: ${it.uri}" }
                                        )
                                    )
                                }
                                listState.animateScrollToItem(messages.size)
                            }
                        }
                    },
                    enabled = !isGenerating && (inputText.isNotBlank() || selectedImageBitmap != null),
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (!isGenerating && (inputText.isNotBlank() || selectedImageBitmap != null)) CyanPrimary else DarkCard)
                        .testTag("chat_send_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send message",
                        tint = if (!isGenerating && (inputText.isNotBlank() || selectedImageBitmap != null)) Color.Black else TextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatMessageItem(message: UIMessage) {
    val isUser = message.role == "user"
    var isThinkingExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        // Speaker Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 4.dp)
        ) {
            if (!isUser) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(CyanPrimary),
                    contentAlignment = Alignment.Center
                ) {
                    Text("X", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (message.model.isNotEmpty()) "Xcraft (${message.model})" else "Xcraft Assistant",
                    fontSize = 11.sp,
                    color = CyanPrimary,
                    fontWeight = FontWeight.Medium
                )
            } else {
                Text(
                    text = "You",
                    fontSize = 11.sp,
                    color = TextSecondary,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Thinking Card (if model generated thoughts)
        if (!isUser && !message.thinking.isNullOrBlank()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkCard.copy(alpha = 0.8f)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .padding(bottom = 6.dp)
                    .border(1.dp, PurpleSecondary.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isThinkingExpanded = !isThinkingExpanded },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Psychology, contentDescription = null, tint = PurpleSecondary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Thought Process (Thinking Mode HIGH)", fontSize = 11.sp, color = PurpleSecondary, fontWeight = FontWeight.SemiBold)
                        }
                        Icon(
                            imageVector = if (isThinkingExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = PurpleSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    if (isThinkingExpanded) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = message.thinking,
                            fontSize = 11.sp,
                            color = TextSecondary,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        }

        // Main Message Card
        Card(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) CyanPrimaryVariant else DarkCard
            ),
            modifier = Modifier.fillMaxWidth(if (isUser) 0.85f else 0.95f)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // Attached or Generated Image
                if (message.imageBitmap != null) {
                    Image(
                        bitmap = message.imageBitmap.asImageBitmap(),
                        contentDescription = "Message image",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Video Indicator
                if (message.videoBase64 != null) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Videocam, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text("Rendered Video Output", fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
                                Text("Ready for playback / export", fontSize = 10.sp, color = TextSecondary)
                            }
                        }
                    }
                }

                // Text Content
                Text(
                    text = message.content,
                    color = if (isUser) Color.White else TextPrimary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )

                // Search Grounding Sources
                if (message.searchSources.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = BorderStroke)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("🔍 Grounding Sources:", fontSize = 10.sp, color = CyanPrimary, fontWeight = FontWeight.Bold)
                    message.searchSources.take(3).forEach { src ->
                        Text(text = "• $src", fontSize = 9.sp, color = TextMuted, maxLines = 1)
                    }
                }
            }
        }
    }
}

val BorderStroke = com.example.ui.theme.BorderStroke
val CyanPrimaryVariant = com.example.ui.theme.CyanPrimaryVariant
