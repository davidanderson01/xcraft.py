package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.R
import com.example.data.model.ChatMessageEntity
import com.example.data.model.ChatSessionEntity
import com.example.data.model.CustomToolEntity
import com.example.data.model.ExecutionLogEntity
import com.example.data.model.ScriptEntity
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import com.google.firebase.firestore.toObjects
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class FirestoreRepository(
    val db: FirebaseFirestore
) {
    companion object {
        private const val TAG = "FirestoreRepo"

        fun create(context: Context): FirestoreRepository {
            val dbId = context.getString(R.string.firestore_database_id)
            val app = if (com.google.firebase.FirebaseApp.getApps(context).isEmpty()) {
                com.google.firebase.FirebaseApp.initializeApp(context.applicationContext) ?: com.google.firebase.FirebaseApp.getInstance()
            } else {
                com.google.firebase.FirebaseApp.getInstance()
            }
            val firestore = FirebaseFirestore.getInstance(app, dbId)
            return FirestoreRepository(firestore)
        }
    }

    private val auth = Firebase.auth

    fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    // --- Scripts ---
    fun observeScripts(): Flow<List<ScriptEntity>> = flow {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            emit(emptyList())
            return@flow
        }
        emitAll(
            db.collection("users").document(uid).collection("scripts")
                .snapshots()
                .map { snapshot -> snapshot.toObjects<ScriptEntity>() }
                .catch { e ->
                    Log.e(TAG, "Error observing scripts: ${e.message}", e)
                    emit(emptyList())
                }
        )
    }

    suspend fun saveScript(title: String, code: String, description: String, category: String): String {
        val uid = requireUserId()
        val scriptsCol = db.collection("users").document(uid).collection("scripts")
        val docRef = scriptsCol.document()
        val data = mapOf(
            "userId" to uid,
            "title" to title,
            "code" to code,
            "description" to description,
            "category" to category,
            "isVulnerabilityFixed" to true,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        docRef.set(data).await()
        return docRef.id
    }

    suspend fun updateScript(id: String, title: String, code: String, description: String, category: String) {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid).collection("scripts").document(id)
        val data = mapOf(
            "userId" to uid,
            "title" to title,
            "code" to code,
            "description" to description,
            "category" to category,
            "isVulnerabilityFixed" to true,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        docRef.update(data).await()
    }

    suspend fun deleteScript(id: String) {
        val uid = requireUserId()
        db.collection("users").document(uid).collection("scripts").document(id).delete().await()
    }

    // --- Chat Sessions ---
    fun observeChatSessions(): Flow<List<ChatSessionEntity>> = flow {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            emit(emptyList())
            return@flow
        }
        emitAll(
            db.collection("users").document(uid).collection("chats")
                .snapshots()
                .map { snapshot -> snapshot.toObjects<ChatSessionEntity>() }
                .catch { e ->
                    Log.e(TAG, "Error observing chats: ${e.message}", e)
                    emit(emptyList())
                }
        )
    }

    suspend fun createChatSession(title: String, modelName: String, role: String): String {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid).collection("chats").document()
        val data = mapOf(
            "userId" to uid,
            "title" to title,
            "modelName" to modelName,
            "role" to role,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        docRef.set(data).await()
        return docRef.id
    }

    fun observeMessages(chatId: String): Flow<List<ChatMessageEntity>> = flow {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            emit(emptyList())
            return@flow
        }
        emitAll(
            db.collection("users").document(uid).collection("chats").document(chatId).collection("messages")
                .snapshots()
                .map { snapshot -> snapshot.toObjects<ChatMessageEntity>() }
                .catch { e ->
                    Log.e(TAG, "Error observing messages: ${e.message}", e)
                    emit(emptyList())
                }
        )
    }

    suspend fun addMessage(
        chatId: String,
        role: String,
        content: String,
        model: String = "",
        thinking: String = "",
        mediaUrl: String = "",
        mediaType: String = ""
    ): String {
        val uid = requireUserId()
        val msgCol = db.collection("users").document(uid).collection("chats").document(chatId).collection("messages")
        val docRef = msgCol.document()
        val data = mutableMapOf<String, Any>(
            "userId" to uid,
            "role" to role,
            "content" to content,
            "createdAt" to FieldValue.serverTimestamp()
        )
        if (model.isNotEmpty()) data["model"] = model
        if (thinking.isNotEmpty()) data["thinking"] = thinking
        if (mediaUrl.isNotEmpty()) data["mediaUrl"] = mediaUrl
        if (mediaType.isNotEmpty()) data["mediaType"] = mediaType

        docRef.set(data).await()
        return docRef.id
    }

    // --- Execution Logs ---
    fun observeExecutionLogs(): Flow<List<ExecutionLogEntity>> = flow {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            emit(emptyList())
            return@flow
        }
        emitAll(
            db.collection("users").document(uid).collection("executions")
                .snapshots()
                .map { snapshot -> snapshot.toObjects<ExecutionLogEntity>() }
                .catch { e ->
                    Log.e(TAG, "Error observing execution logs: ${e.message}", e)
                    emit(emptyList())
                }
        )
    }

    suspend fun logExecution(command: String, output: String, status: String, durationMs: Double): String {
        val uid = auth.currentUser?.uid ?: return "local"
        return try {
            val docRef = db.collection("users").document(uid).collection("executions").document()
            val data = mapOf(
                "userId" to uid,
                "command" to command,
                "output" to output.take(45000),
                "status" to status,
                "durationMs" to durationMs,
                "createdAt" to FieldValue.serverTimestamp()
            )
            docRef.set(data).await()
            docRef.id
        } catch (e: Exception) {
            Log.e(TAG, "Error logging execution: ${e.message}", e)
            "local"
        }
    }

    // --- Custom Tools ---
    fun observeCustomTools(): Flow<List<CustomToolEntity>> = flow {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            emit(emptyList())
            return@flow
        }
        emitAll(
            db.collection("users").document(uid).collection("tools")
                .snapshots()
                .map { snapshot -> snapshot.toObjects<CustomToolEntity>() }
                .catch { e ->
                    Log.e(TAG, "Error observing custom tools: ${e.message}", e)
                    emit(emptyList())
                }
        )
    }

    suspend fun saveCustomTool(name: String, description: String, pythonCode: String): String {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid).collection("tools").document()
        val data = mapOf(
            "userId" to uid,
            "name" to name,
            "description" to description,
            "pythonCode" to pythonCode,
            "enabled" to true,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        docRef.set(data).await()
        return docRef.id
    }

    suspend fun deleteCustomTool(id: String) {
        val uid = requireUserId()
        db.collection("users").document(uid).collection("tools").document(id).delete().await()
    }
}
