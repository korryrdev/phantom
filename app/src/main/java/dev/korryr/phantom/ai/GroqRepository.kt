package dev.korryr.phantom.ai

import dev.korryr.phantom.BuildConfig
import kotlinx.coroutines.Dispatchers
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

class GroqRepository @Inject constructor(
    private val client: OkHttpClient
) {
    companion object {
        private const val BASE_URL = "https://api.groq.com/openai/v1/chat/completions"

        // llama-3.3-70b-versatile: smarter model for better answer accuracy
        private const val MODEL = "llama-3.3-70b-versatile"

        // Keep OCR text short so the model processes it faster and with less noise
        private const val MAX_OCR_CHARS = 600
    }

    private val systemPrompt = """
        You are an expert trivia solver playing a multiple-choice game.
        I will give you OCR text containing a question and up to 4 possible answers. Ignore typos. Figure out the question, find the exact matching correct answer from the provided options, and output ONLY that exact option text.
        
        CRITICAL RULES:
        - Output ONLY the EXACT text of the correct answer option from the provided text.
        - DO NOT output conversational filler like "The answer is" or "Correct option:".
        - If no clear options are visible, output your best guess in 3 words max.
        - NO punctuation (unless part of the answer), NO explanation, NO emojis, NO shape names/colors.
        - ALWAYS answer, even if unsure.
    """.trimIndent()

    /**
     * Cleans raw OCR text: removes blank lines but keeps short lines (options like "A.", "True").
     */
    private fun cleanOcrText(rawOcrText: String): String =
        rawOcrText
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .take(MAX_OCR_CHARS)

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
            put("max_tokens", 30)
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
     *
     * This is non-blocking: we use OkHttp's enqueue() instead of execute().
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

                    reader.forEachLine { line ->
                        if (!isClosedForSend) {
                            if (line.startsWith("data: ")) {
                                val data = line.removePrefix("data: ").trim()
                                if (data == "[DONE]") return@forEachLine

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
                    }

                    // Ensure we emit the final answer if we got anything
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

            // Non-blocking: bridge OkHttp enqueue to coroutine
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
