package com.example.data.remote

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import com.example.domain.security.AIAgentFirewall
import com.example.domain.security.ThreatLevel

data class GeminiGenerationResult(
    val text: String,
    val thinking: String? = null,
    val imageBase64: String? = null,
    val videoBase64: String? = null,
    val sources: List<SearchSource> = emptyList(),
    val firewallSummary: String? = null,
    val error: String? = null
)

data class SearchSource(
    val title: String,
    val uri: String
)

object GeminiService {
    private const val TAG = "GeminiService"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/"

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun getApiKey(): String {
        return BuildConfig.GEMINI_API_KEY
    }

    /**
     * Standard text / multimodal generation with optional thinking, search grounding, or image output.
     */
    suspend fun generateContent(
        model: String,
        prompt: String,
        systemInstruction: String? = null,
        history: List<Pair<String, String>> = emptyList(), // Pair(role, text)
        enableSearch: Boolean = false,
        enableHighThinking: Boolean = false,
        imageBitmap: Bitmap? = null,
        generateImage: Boolean = false,
        imageAspectRatio: String = "1:1",
        imageResolution: String = "1K"
    ): GeminiGenerationResult = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext GeminiGenerationResult(
                text = "",
                error = "Gemini API key is not configured. Please add GEMINI_API_KEY in the Secrets panel in AI Studio."
            )
        }

        val firewallResult = AIAgentFirewall.screenInput(prompt)

        try {
            val root = JSONObject()

            // Hardened System instruction with immutable non-override mandate
            val hardenedSystemInstruction = AIAgentFirewall.buildHardenedSystemPrompt(systemInstruction)
            val sysObj = JSONObject()
            val sysParts = JSONArray()
            sysParts.put(JSONObject().put("text", hardenedSystemInstruction))
            sysObj.put("parts", sysParts)
            root.put("systemInstruction", sysObj)

            // Tools (Search grounding)
            if (enableSearch) {
                val toolsArr = JSONArray()
                val searchTool = JSONObject().put("googleSearch", JSONObject())
                toolsArr.put(searchTool)
                root.put("tools", toolsArr)
            }

            // Contents array
            val contentsArr = JSONArray()
            for ((role, text) in history) {
                val item = JSONObject()
                item.put("role", if (role == "model" || role == "assistant") "model" else "user")
                val parts = JSONArray()
                parts.put(JSONObject().put("text", text))
                item.put("parts", parts)
                contentsArr.put(item)
            }

            // Current prompt enclosed in cryptographic boundary
            val currentItem = JSONObject()
            currentItem.put("role", "user")
            val currentParts = JSONArray()
            currentParts.put(JSONObject().put("text", firewallResult.framedPrompt))

            // Multimodal image upload
            if (imageBitmap != null) {
                val stream = ByteArrayOutputStream()
                imageBitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                val base64Data = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                val inlineData = JSONObject()
                inlineData.put("mimeType", "image/jpeg")
                inlineData.put("data", base64Data)
                currentParts.put(JSONObject().put("inlineData", inlineData))
            }

            currentItem.put("parts", currentParts)
            contentsArr.put(currentItem)
            root.put("contents", contentsArr)

            // Generation config
            val genConfig = JSONObject()
            if (enableHighThinking) {
                val thinkingObj = JSONObject().put("thinkingLevel", "HIGH")
                genConfig.put("thinkingConfig", thinkingObj)
            }

            if (generateImage) {
                val imgConfig = JSONObject()
                    .put("aspectRatio", imageAspectRatio)
                    .put("imageSize", imageResolution)
                genConfig.put("imageConfig", imgConfig)

                val modalities = JSONArray()
                modalities.put("TEXT")
                modalities.put("IMAGE")
                genConfig.put("responseModalities", modalities)
            }

            if (genConfig.length() > 0) {
                root.put("generationConfig", genConfig)
            }

            val targetModel = when {
                generateImage -> "gemini-3-pro-image-preview"
                enableHighThinking -> "gemini-4-argon"
                model.isNotBlank() -> model
                else -> "gemini-3.8-flash"
            }

            val endpoint = "${BASE_URL}v1beta/models/$targetModel:generateContent"
            val body = root.toString().toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("x-goog-api-key", apiKey)
                .post(body)
                .build()

            val response = okHttpClient.newCall(request).execute()
            val respString = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                val sanitizedError = AIAgentFirewall.scanOutput(respString).sanitizedOutput
                Log.e(TAG, "Gemini API error ($targetModel): ${response.code}")
                return@withContext GeminiGenerationResult(
                    text = "",
                    error = "Gemini API call failed (${response.code}): $sanitizedError"
                )
            }

            val jsonResp = JSONObject(respString)
            val candidates = jsonResp.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                return@withContext GeminiGenerationResult(text = "No response generated.")
            }

            val candidate = candidates.getJSONObject(0)
            val content = candidate.optJSONObject("content")
            val parts = content?.optJSONArray("parts")

            var responseText = ""
            var thinkingText: String? = null
            var imageBase64: String? = null

            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val p = parts.getJSONObject(i)
                    if (p.has("thought") && p.getBoolean("thought")) {
                        thinkingText = (thinkingText ?: "") + p.optString("text", "")
                    } else if (p.has("text")) {
                        responseText += p.optString("text", "")
                    }
                    if (p.has("inlineData")) {
                        val inline = p.getJSONObject("inlineData")
                        imageBase64 = inline.optString("data").ifEmpty { null }
                    }
                }
            }

            // Search Grounding Metadata
            val searchSources = mutableListOf<SearchSource>()
            val groundingMetadata = candidate.optJSONObject("groundingMetadata")
            if (groundingMetadata != null) {
                val searchChunks = groundingMetadata.optJSONArray("groundingChunks")
                if (searchChunks != null) {
                    for (i in 0 until searchChunks.length()) {
                        val chunk = searchChunks.getJSONObject(i)
                        val web = chunk.optJSONObject("web")
                        if (web != null) {
                            val title = web.optString("title", "Source ${i + 1}")
                            val uri = web.optString("uri", "")
                            searchSources.add(SearchSource(title = title, uri = uri))
                        }
                    }
                }
            }

            val outputScan = AIAgentFirewall.scanOutput(responseText)
            val sanitizedThinking = thinkingText?.let { AIAgentFirewall.scanOutput(it).sanitizedOutput }
            val finalCleanText = outputScan.sanitizedOutput.ifBlank {
                if (imageBase64 != null) "Image generated successfully." else "Response received."
            }

            return@withContext GeminiGenerationResult(
                text = finalCleanText,
                thinking = sanitizedThinking,
                imageBase64 = imageBase64,
                sources = searchSources,
                firewallSummary = firewallResult.summary
            )
        } catch (e: Exception) {
            val safeError = AIAgentFirewall.scanOutput(e.localizedMessage ?: "Network error").sanitizedOutput
            Log.e(TAG, "Error in generateContent: $safeError")
            return@withContext GeminiGenerationResult(text = "", error = safeError)
        }
    }

    /**
     * Video Generation using gemini-omni-1.1-flash via Interactions API.
     */
    suspend fun generateVideo(
        prompt: String,
        aspectRatio: String = "16:9",
        duration: String = "5s",
        onProgress: (String) -> Unit = {}
    ): GeminiGenerationResult = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext GeminiGenerationResult(
                text = "",
                error = "Gemini API key is not configured. Please add GEMINI_API_KEY in the Secrets panel in AI Studio."
            )
        }

        try {
            onProgress("Initializing video generation request...")
            val reqObj = JSONObject()
            reqObj.put("model", "gemini-omni-1.1-flash")
            reqObj.put("input", prompt)
            reqObj.put("background", false)
            reqObj.put("store", true)
            reqObj.put("stream", false)

            val format = JSONObject()
            format.put("type", "video")
            format.put("aspect_ratio", aspectRatio)
            format.put("duration", duration)
            reqObj.put("response_format", format)

            val createUrl = "${BASE_URL}v1beta/interactions"
            val body = reqObj.toString().toRequestBody(jsonMediaType)
            val postReq = Request.Builder()
                .url(createUrl)
                .addHeader("x-goog-api-key", apiKey)
                .post(body)
                .build()

            val postResp = okHttpClient.newCall(postReq).execute()
            val postStr = postResp.body?.string().orEmpty()
            if (!postResp.isSuccessful) {
                val safeErr = AIAgentFirewall.scanOutput(postStr).sanitizedOutput
                return@withContext GeminiGenerationResult(
                    text = "",
                    error = "Video creation failed (${postResp.code}): $safeErr"
                )
            }

            val postJson = JSONObject(postStr)
            val interactionId = postJson.optString("id", "")
            var status = postJson.optString("status", "")

            var attempts = 0
            var finalJson = postJson
            while ((status == "in_progress" || status == "queued") && attempts < 60) {
                delay(4000L)
                attempts++
                onProgress("Rendering video ($status)... ${attempts * 4}s")
                val pollUrl = "${BASE_URL}v1beta/interactions/$interactionId"
                val pollReq = Request.Builder()
                    .url(pollUrl)
                    .addHeader("x-goog-api-key", apiKey)
                    .get()
                    .build()
                val pollResp = okHttpClient.newCall(pollReq).execute()
                val pollStr = pollResp.body?.string().orEmpty()
                if (pollResp.isSuccessful) {
                    finalJson = JSONObject(pollStr)
                    status = finalJson.optString("status", "")
                }
            }

            if (status != "completed" && status != "succeeded") {
                return@withContext GeminiGenerationResult(
                    text = "",
                    error = "Video generation status: $status (attempts: $attempts)"
                )
            }

            val steps = finalJson.optJSONArray("steps")
            var base64Video: String? = null
            if (steps != null) {
                for (i in 0 until steps.length()) {
                    val step = steps.getJSONObject(i)
                    if (step.optString("type") == "model_output") {
                        val contents = step.optJSONArray("content")
                        if (contents != null) {
                            for (j in 0 until contents.length()) {
                                val c = contents.getJSONObject(j)
                                if (c.optString("type") == "video") {
                                    base64Video = c.optString("data").ifEmpty { null }
                                }
                            }
                        }
                    }
                }
            }

            return@withContext GeminiGenerationResult(
                text = "Video rendered successfully ($aspectRatio, $duration)",
                videoBase64 = base64Video
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error generating video", e)
            return@withContext GeminiGenerationResult(text = "", error = e.localizedMessage ?: "Video generation failed")
        }
    }
}
