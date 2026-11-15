package com.example.data.remote

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.example.domain.security.AIAgentFirewall
import com.example.domain.security.SensitiveDataMasker
import com.example.domain.security.ThreatLevel
import kotlinx.coroutines.CancellationException
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
import java.io.IOException
import java.util.concurrent.TimeUnit

data class GeminiGenerationResult(
    val text: String,
    val thinking: String? = null,
    val imageBase64: String? = null,
    val videoBase64: String? = null,
    val sources: List<SearchSource> = emptyList(),
    val firewallSummary: String? = null,
    val firewallThreatLevel: ThreatLevel = ThreatLevel.CLEAN,
    val outputRedactionsCount: Int = 0,
    val durationMs: Double = 0.0,
    val modelUsed: String? = null,
    val error: String? = null
)

data class SearchSource(
    val title: String,
    val uri: String
)

object GeminiService {
    private const val TAG = "GeminiService"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/"

    private val TRANSIENT_HTTP_CODES = setOf(429, 500, 502, 503, 504)
    private const val MAX_RETRIES_PER_MODEL = 2

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun getApiKey(): String {
        return BuildConfig.GEMINI_API_KEY
    }

    internal fun resolveModelCandidates(
        requestedModel: String,
        generateImage: Boolean,
        enableHighThinking: Boolean
    ): List<String> {
        val raw = when {
            generateImage -> listOf(
                requestedModel.ifBlank { "gemini-3-pro-image-preview" },
                "gemini-nano-banana-2.1",
                "gemini-3-pro-image",
                "gemini-3.1-flash-lite-image"
            )
            enableHighThinking || requestedModel == "gemini-4-argon" -> listOf(
                requestedModel.ifBlank { "gemini-4-argon" },
                "gemini-4-argon",
                "gemini-4-hydrogen",
                "gemini-3.8-flash",
                "gemini-2.5-pro",
                "gemini-2.5-flash"
            )
            requestedModel.contains("lite", ignoreCase = true) -> listOf(
                requestedModel,
                "gemini-3.5-flash-lite",
                "gemini-3.1-flash-lite",
                "gemini-3.8-flash",
                "gemini-2.5-flash-lite",
                "gemini-2.5-flash"
            )
            else -> listOf(
                requestedModel.ifBlank { "gemini-3.8-flash" },
                "gemini-3.8-flash",
                "gemini-4-hydrogen",
                "gemini-3.5-flash-lite",
                "gemini-3.1-flash-lite",
                "gemini-2.5-flash"
            )
        }
        return raw.filter { it.isNotBlank() }.distinct()
    }

    private fun buildRequestPayload(
        hardenedSystemInstruction: String,
        framedPrompt: String,
        history: List<Pair<String, String>>,
        imageBase64Input: String?,
        enableSearch: Boolean,
        enableHighThinking: Boolean,
        generateImage: Boolean,
        imageAspectRatio: String,
        imageResolution: String,
        includeThinkingConfig: Boolean,
        includeSearchTool: Boolean
    ): JSONObject {
        val root = JSONObject()

        val sysObj = JSONObject()
        val sysParts = JSONArray()
        sysParts.put(JSONObject().put("text", hardenedSystemInstruction))
        sysObj.put("parts", sysParts)
        root.put("systemInstruction", sysObj)

        if (enableSearch && includeSearchTool) {
            val toolsArr = JSONArray()
            val searchTool = JSONObject().put("googleSearch", JSONObject())
            toolsArr.put(searchTool)
            root.put("tools", toolsArr)
        }

        val contentsArr = JSONArray()
        val normalizedHistory = history
            .filter { it.second.isNotBlank() }
            .dropWhile { it.first == "model" || it.first == "assistant" }
        for ((role, text) in normalizedHistory) {
            val item = JSONObject()
            item.put("role", if (role == "model" || role == "assistant") "model" else "user")
            val parts = JSONArray()
            parts.put(JSONObject().put("text", text))
            item.put("parts", parts)
            contentsArr.put(item)
        }

        val currentItem = JSONObject()
        currentItem.put("role", "user")
        val currentParts = JSONArray()
        currentParts.put(JSONObject().put("text", framedPrompt))

        if (imageBase64Input != null) {
            val inlineData = JSONObject()
            inlineData.put("mimeType", "image/jpeg")
            inlineData.put("data", imageBase64Input)
            currentParts.put(JSONObject().put("inlineData", inlineData))
        }

        currentItem.put("parts", currentParts)
        contentsArr.put(currentItem)
        root.put("contents", contentsArr)

        val genConfig = JSONObject()
        if (enableHighThinking && includeThinkingConfig) {
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

        return root
    }

    internal fun extractApiErrorMessage(statusCode: Int, rawBody: String): String {
        val parsed = try {
            val json = JSONObject(rawBody)
            json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
        val candidateMsg = parsed ?: rawBody.take(300)
        val sanitized = AIAgentFirewall.scanOutput(candidateMsg).sanitizedOutput.trim()
        return when (statusCode) {
            503 -> if (sanitized.isNotBlank()) "Service temporarily overloaded (503): $sanitized" else "Gemini service temporarily unavailable (503). Please try again shortly."
            429 -> if (sanitized.isNotBlank()) "Rate limit reached (429): $sanitized" else "Too many requests (429). Please wait a moment and retry."
            else -> if (sanitized.isNotBlank()) "HTTP $statusCode: $sanitized" else "HTTP $statusCode"
        }
    }

    /**
     * Standard text / multimodal generation with automatic retry on transient 503/429 errors
     * and seamless model fallback.
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
        imageResolution: String = "1K",
        onStatus: (String) -> Unit = {}
    ): GeminiGenerationResult = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext GeminiGenerationResult(
                text = "",
                error = "Gemini API key is not configured. Please add GEMINI_API_KEY in the Secrets panel in AI Studio."
            )
        }

        val startTime = System.currentTimeMillis()
        val firewallResult = AIAgentFirewall.screenInput(prompt)
        val hardenedSystemInstruction = AIAgentFirewall.buildHardenedSystemPrompt(systemInstruction)

        val imageBase64Input = imageBitmap?.let { bmp ->
            val stream = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        }

        val candidates = resolveModelCandidates(
            requestedModel = model,
            generateImage = generateImage,
            enableHighThinking = enableHighThinking
        )

        var lastCode = 0
        var lastErrorBody = ""
        var lastModel = candidates.firstOrNull() ?: "gemini-3.8-flash"
        var lastException: Exception? = null

        try {
            for ((index, candidateModel) in candidates.withIndex()) {
                lastModel = candidateModel
                if (index > 0) {
                    onStatus("Switching to fallback model $candidateModel...")
                }

                var includeThinking = enableHighThinking
                var includeSearch = enableSearch
                var attempt = 0
                var strippedUnsupportedConfig = false

                while (attempt <= MAX_RETRIES_PER_MODEL) {
                    val root = buildRequestPayload(
                        hardenedSystemInstruction = hardenedSystemInstruction,
                        framedPrompt = firewallResult.framedPrompt,
                        history = history,
                        imageBase64Input = imageBase64Input,
                        enableSearch = enableSearch,
                        enableHighThinking = enableHighThinking,
                        generateImage = generateImage,
                        imageAspectRatio = imageAspectRatio,
                        imageResolution = imageResolution,
                        includeThinkingConfig = includeThinking,
                        includeSearchTool = includeSearch
                    )

                    val endpoint = "${BASE_URL}v1beta/models/$candidateModel:generateContent"
                    val body = root.toString().toRequestBody(jsonMediaType)
                    val request = Request.Builder()
                        .url(endpoint)
                        .addHeader("x-goog-api-key", apiKey)
                        .post(body)
                        .build()

                    var statusCode = 0
                    var respString = ""
                    var callException: IOException? = null

                    try {
                        okHttpClient.newCall(request).execute().use { response ->
                            statusCode = response.code
                            respString = response.body?.string().orEmpty()
                        }
                    } catch (ioe: IOException) {
                        callException = ioe
                        lastException = ioe
                    }

                    if (callException != null) {
                        Log.w(TAG, "Network issue calling $candidateModel (attempt ${attempt + 1}): ${callException.message}")
                        if (attempt < MAX_RETRIES_PER_MODEL) {
                            attempt++
                            onStatus("Network hiccup on $candidateModel, retrying...")
                            delay(750L * attempt)
                            continue
                        }
                        break
                    }

                    if (statusCode in 200..299) {
                        return@withContext parseSuccessResponse(
                            respString = respString,
                            modelUsed = candidateModel,
                            firewallSummary = firewallResult.summary,
                            firewallThreatLevel = firewallResult.threatLevel,
                            durationMs = (System.currentTimeMillis() - startTime).toDouble()
                        )
                    }

                    lastCode = statusCode
                    lastErrorBody = respString

                    // Fail immediately if API key itself is invalid
                    if ((statusCode == 400 || statusCode == 401 || statusCode == 403) &&
                        (respString.contains("API_KEY_INVALID", ignoreCase = true) ||
                            respString.contains("API key not valid", ignoreCase = true))
                    ) {
                        val safeMsg = extractApiErrorMessage(statusCode, respString)
                        Log.e(TAG, "Invalid Gemini API key ($statusCode)")
                        return@withContext GeminiGenerationResult(text = "", error = safeMsg)
                    }

                    // If optional config (thinkingLevel / googleSearch) caused a 400 on this model, strip & retry once
                    if (statusCode == 400 && !strippedUnsupportedConfig && (includeThinking || includeSearch)) {
                        Log.w(TAG, "Model $candidateModel rejected optional config (400); retrying with standard payload")
                        includeThinking = false
                        includeSearch = false
                        strippedUnsupportedConfig = true
                        continue
                    }

                    // Transient overload/rate-limit/server error: retry same model with backoff
                    if (statusCode in TRANSIENT_HTTP_CODES) {
                        Log.w(TAG, "Transient Gemini API status ($candidateModel): $statusCode (attempt ${attempt + 1})")
                        if (attempt < MAX_RETRIES_PER_MODEL) {
                            attempt++
                            onStatus("$candidateModel busy ($statusCode), retrying...")
                            delay(800L * attempt)
                            continue
                        }
                        // Exhausted retries for this model -> move to next fallback model
                        break
                    }

                    // Model not found (404) or forbidden for this specific preview model -> try next fallback model
                    if (statusCode == 404 || statusCode == 403) {
                        Log.w(TAG, "Model $candidateModel unavailable ($statusCode); trying fallback if available")
                        break
                    }

                    // Any other unhandled client error
                    val safeMsg = extractApiErrorMessage(statusCode, respString)
                    Log.e(TAG, "Gemini API error ($candidateModel): $statusCode")
                    return@withContext GeminiGenerationResult(text = "", error = safeMsg)
                }
            }

            if (lastCode != 0) {
                val safeMsg = extractApiErrorMessage(lastCode, lastErrorBody)
                Log.e(TAG, "Gemini API failed across all fallback models (last: $lastModel, HTTP $lastCode)")
                return@withContext GeminiGenerationResult(
                    text = "",
                    firewallSummary = firewallResult.summary,
                    firewallThreatLevel = firewallResult.threatLevel,
                    durationMs = (System.currentTimeMillis() - startTime).toDouble(),
                    modelUsed = lastModel,
                    error = "Unable to reach Gemini right now ($safeMsg)"
                )
            } else {
                val safeErr = SensitiveDataMasker.maskText(
                    AIAgentFirewall.scanOutput(lastException?.localizedMessage ?: "Network error").sanitizedOutput
                )
                Log.e(TAG, "Network failure across all models: $safeErr")
                return@withContext GeminiGenerationResult(
                    text = "",
                    firewallSummary = firewallResult.summary,
                    firewallThreatLevel = firewallResult.threatLevel,
                    durationMs = (System.currentTimeMillis() - startTime).toDouble(),
                    modelUsed = lastModel,
                    error = safeErr
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val safeError = SensitiveDataMasker.maskText(
                AIAgentFirewall.scanOutput(e.localizedMessage ?: "Network error").sanitizedOutput
            )
            Log.e(TAG, "Error in generateContent: $safeError")
            return@withContext GeminiGenerationResult(
                text = "",
                firewallSummary = firewallResult.summary,
                firewallThreatLevel = firewallResult.threatLevel,
                durationMs = (System.currentTimeMillis() - startTime).toDouble(),
                modelUsed = lastModel,
                error = safeError
            )
        }
    }

    private fun parseSuccessResponse(
        respString: String,
        modelUsed: String,
        firewallSummary: String,
        firewallThreatLevel: ThreatLevel,
        durationMs: Double
    ): GeminiGenerationResult {
        val jsonResp = JSONObject(respString)
        val candidates = jsonResp.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            return GeminiGenerationResult(
                text = "No response generated.",
                modelUsed = modelUsed,
                firewallSummary = firewallSummary,
                firewallThreatLevel = firewallThreatLevel,
                durationMs = durationMs
            )
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

        return GeminiGenerationResult(
            text = finalCleanText,
            thinking = sanitizedThinking,
            imageBase64 = imageBase64,
            sources = searchSources,
            firewallSummary = firewallSummary,
            firewallThreatLevel = firewallThreatLevel,
            outputRedactionsCount = outputScan.secretsRedacted,
            durationMs = durationMs,
            modelUsed = modelUsed
        )
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

        val firewallResult = AIAgentFirewall.screenInput(prompt)

        try {
            onProgress("Initializing video generation request...")
            val reqObj = JSONObject()
            reqObj.put("model", "gemini-omni-1.1-flash")
            reqObj.put("input", firewallResult.sanitizedPrompt)
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

            var postCode = 0
            var postStr = ""
            var attempt = 0
            while (attempt <= MAX_RETRIES_PER_MODEL) {
                val postReq = Request.Builder()
                    .url(createUrl)
                    .addHeader("x-goog-api-key", apiKey)
                    .post(body)
                    .build()

                okHttpClient.newCall(postReq).execute().use { postResp ->
                    postCode = postResp.code
                    postStr = postResp.body?.string().orEmpty()
                }

                if (postCode in 200..299) break
                if (postCode in TRANSIENT_HTTP_CODES && attempt < MAX_RETRIES_PER_MODEL) {
                    attempt++
                    Log.w(TAG, "Transient video API status ($postCode), retrying ($attempt)...")
                    onProgress("Video service busy ($postCode), retrying...")
                    delay(1000L * attempt)
                } else {
                    break
                }
            }

            if (postCode !in 200..299) {
                val safeErr = extractApiErrorMessage(postCode, postStr)
                Log.e(TAG, "Video creation failed ($postCode)")
                return@withContext GeminiGenerationResult(
                    text = "",
                    error = "Video creation failed: $safeErr"
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
                okHttpClient.newCall(pollReq).execute().use { pollResp ->
                    val pollStr = pollResp.body?.string().orEmpty()
                    if (pollResp.isSuccessful) {
                        finalJson = JSONObject(pollStr)
                        status = finalJson.optString("status", "")
                    }
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
                videoBase64 = base64Video,
                modelUsed = "gemini-omni-1.1-flash"
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val safeError = AIAgentFirewall.scanOutput(e.localizedMessage ?: "Video generation failed").sanitizedOutput
            Log.e(TAG, "Error generating video: $safeError")
            return@withContext GeminiGenerationResult(text = "", error = safeError)
        }
    }
}
