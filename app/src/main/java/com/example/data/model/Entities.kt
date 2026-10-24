package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@IgnoreExtraProperties
data class ScriptEntity(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val code: String = "",
    val description: String = "",
    val category: String = "tool",
    val isVulnerabilityFixed: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class ChatSessionEntity(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val modelName: String = "gemini-3.8-flash",
    val role: String = "CraftX Architect",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class ChatMessageEntity(
    @DocumentId val id: String = "",
    val userId: String = "",
    val role: String = "user",
    val content: String = "",
    val model: String = "",
    val thinking: String = "",
    val mediaUrl: String = "",
    val mediaType: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class ExecutionLogEntity(
    @DocumentId val id: String = "",
    val userId: String = "",
    val command: String = "",
    val output: String = "",
    val status: String = "SUCCESS",
    val durationMs: Double = 0.0,
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class CustomToolEntity(
    @DocumentId val id: String = "",
    val userId: String = "",
    val name: String = "",
    val description: String = "",
    val pythonCode: String = "",
    val enabled: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)
