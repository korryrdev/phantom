package dev.korryr.phantom.ai

import dev.korryr.phantom.BuildConfig
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.BufferedReader
import java.io.IOException
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers

class GroqRepository @Inject constructor(
    private val client: OkHttpClient
) {
    companion object {
        private const val BASE_URL = "https://api.groq.com/openai/v1/chat/completions"

        // llama-3.3-70b-versatile: best accuracy for knowledge-based multiple choice
        private const val MODEL = "llama-3.3-70b-versatile"

        // FIX: Was 600 — too small. A question (≈100 chars) + 4 options (≈50 chars each)
        // easily exceeds 600. Option D was silently dropped, forcing Groq to guess from 3.
        // 1200 comfortably fits any quiz screen's content.
        private const val MAX_OCR_CHARS = 1200

        // Matches common OCR noise patterns for filtering
        private val TIMESTAMP_REGEX    = Regex("^\\d{1,2}:\\d{2}(\\s*[AaPpMm]{2})?$")
        private val BATTERY_REGEX      = Regex("^\\d{1,3}%$")
        private val SINGLE_CHAR_REGEX  = Regex("^[^a-zA-Z0-9]?$")
    }

    /**
     * REWRITTEN prompt — v2.
     *
     * Changes from v1:
     * - Explicitly handles A/B/C/D labeled options, True/False, and unlabeled options
     * - Instructs the model to strip OCR noise (timestamps, battery %, notification icons)
     * - Tightened output format rules: zero preamble, exact text only
     * - max_tokens raised to 50 so longer answer options don't get cut mid-word
     */
    private val systemPrompt = """
        You are a precision quiz-answer extractor. You receive raw OCR text captured from a quiz or exam screen. The text may contain OCR noise, typos, or screen-capture artifacts.

        YOUR JOB:
        1. Identify the quiz question (usually ends with "?" or is an incomplete statement).
        2. Identify all answer choices (may be labeled A/B/C/D, 1/2/3/4, True/False, or just listed line by line).
        3. Using your knowledge, determine which choice is correct.
        4. Output ONLY the exact text of the correct answer as it appears in the OCR.

        STRICT OUTPUT RULES:
        - Copy the correct answer TEXT exactly — do NOT paraphrase or reword it.
        - If options are labeled (e.g. "A. Paris" or "B) Rome"), output ONLY the choice text, NOT the label (e.g. "Paris", not "A. Paris") — unless the label itself is the answer.
        - For True/False questions: output exactly "True" or "False".
        - Zero preamble: no "The answer is", no "Correct:", no "Option A", no explanation whatsoever.
        - Ignore OCR noise: timestamps like "9:41", battery indicators like "87%", carrier names, single random characters/symbols.
        - If the question is unclear due to OCR, still pick the best answer from the visible options.
        - Maximum 20 words in your response.
    """.trimIndent()

    /**
     * Cleans raw OCR text before sending to Groq.
     *
     * Improvements over v1:
     * - Filters out timestamp lines (e.g. "9:41", "12:34 PM") — status bar noise
     * - Filters out battery percentage lines (e.g. "87%") — status bar noise
     * - Filters out single-character lines (OCR'd icons, bullets that came out as letters)
     * - Removes consecutive duplicate lines (OCR sometimes reads the same text twice)
     * - Keeps short lines like "A.", "True", "False" — these are valid answer choices
     * - Char limit raised to 1200 so options never get silently truncated
     */
    private fun cleanOcrText(rawOcrText: String): String {
        val lines = rawOcrText
            .lines()
            .map { it.trim() }
            .filter { line ->
                line.isNotEmpty() &&
                !TIMESTAMP_REGEX.matches(line) &&
                !BATTERY_REGEX.matches(line) &&
                !SINGLE_CHAR_REGEX.matches(line) &&
                // Must contain at least one letter or digit (filters pure-symbol noise)
                line.any { it.isLetterOrDigit() }
            }

        // Remove consecutive duplicates (OCR sometimes double-reads the same text block)
        val deduped = mutableListOf<String>()
        for (line in lines) {
            if (deduped.isEmpty() || deduped.last() != line) {
                deduped.add(line)
            }
        }

        return deduped.joinToString("\n").take(MAX_OCR_CHARS)
    }

    /**
     * Builds the JSON request body, optionally with streaming enabled.
     */
    private fun buildRequestBody(cleanedText: String, stream: Boolean): String =
        JSONObject().apply {
            put("model", MODEL)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", cleanedText)
                })
            })
            put("temperature", 0.0)
            put("max_tokens", 50)  // FIX: was 30 — raised to 50 to handle longer options
            if (stream) put("stream", true)
        }.toString()

    private fun buildRequest(body: String): Request {
        val apiKey = BuildConfig.GROQ_API_KEY
        return Request.Builder()
            .url(BASE_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
    }

    // ── Streaming API — emits accumulated answer text as tokens arrive ─────────

    /**
     * Streams the answer from Groq using SSE (Server-Sent Events).
     * Each emission is the ACCUMULATED answer text so far.
     * The final emission is the complete answer.
     */
    fun getAnswerStreaming(rawOcrText: String): Flow<String> = callbackFlow {
        val apiKey = BuildConfig.GROQ_API_KEY
        if (apiKey.isEmpty()) {
            trySend("Error: API Key missing")
            close()
            return@callbackFlow
        }

        val cleanedText = cleanOcrText(rawOcrText)
        Timber.d("Streaming to Groq (${cleanedText.length} chars):\n$cleanedText")

        val body = buildRequestBody(cleanedText, stream = true)
        val request = buildRequest(body)
        val call = client.newCall(request)

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Timber.e(e, "Groq streaming call failed")
                trySend("Error: ${e.message?.take(30)}")
                close()
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "unknown"
                    Timber.e("Groq streaming error ${response.code}: $errorBody")
                    trySend("API error: ${response.code}")
                    close()
                    return
                }

                try {
                    val reader: BufferedReader = response.body!!.source().inputStream().bufferedReader()
                    val accumulated = StringBuilder()

                    while (!isClosedForSend) {
                        val line = reader.readLine() ?: break
                        if (line.startsWith("data: ")) {
                            val data = line.removePrefix("data: ").trim()
                            if (data == "[DONE]") break

                            try {
                                val delta = JSONObject(data)
                                    .getJSONArray("choices")
                                    .getJSONObject(0)
                                    .getJSONObject("delta")

                                if (delta.has("content")) {
                                    val token = delta.getString("content")
                                    accumulated.append(token)
                                    trySend(accumulated.toString().trim())
                                }
                            } catch (e: Exception) {
                                Timber.w(e, "Failed to parse SSE chunk: $data")
                            }
                        }
                    }

                    if (accumulated.isNotEmpty()) {
                        val finalAnswer = accumulated.toString().trim()
                        Timber.d("Groq streaming complete: $finalAnswer")
                        trySend(finalAnswer)
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error reading SSE stream")
                    trySend("Error: ${e.message?.take(30)}")
                } finally {
                    response.close()
                    close()
                }
            }
        })

        awaitClose {
            Timber.d("Streaming flow cancelled — cancelling HTTP call")
            call.cancel()
        }
    }

    // ── Non-streaming fallback (async, non-blocking) ──────────────────────────

    /**
     * Non-streaming fallback. Uses enqueue() for non-blocking execution.
     */
    suspend fun getAnswer(rawOcrText: String): String = withContext(Dispatchers.IO) {
        try {
            val apiKey = BuildConfig.GROQ_API_KEY
            if (apiKey.isEmpty()) return@withContext "Error: API Key missing"

            val cleanedText = cleanOcrText(rawOcrText)
            Timber.d("Sending to Groq (${cleanedText.length} chars): ${cleanedText.take(120)}")

            val body = buildRequestBody(cleanedText, stream = false)
            val request = buildRequest(body)

            val responseBody = suspendCancellableCoroutine { cont ->
                val call = client.newCall(request)
                cont.invokeOnCancellation { call.cancel() }

                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            val body = it.body?.string() ?: ""
                            if (!it.isSuccessful) {
                                if (cont.isActive) cont.resumeWithException(
                                    IOException("API error ${it.code}: $body")
                                )
                            } else {
                                if (cont.isActive) cont.resume(body)
                            }
                        }
                    }
                })
            }

            val answer = JSONObject(responseBody)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()

            Timber.d("Groq answer: $answer")
            answer
        } catch (e: Exception) {
            Timber.e(e, "Groq call failed")
            "Error: ${e.message?.take(30)}"
        }
    }
}
