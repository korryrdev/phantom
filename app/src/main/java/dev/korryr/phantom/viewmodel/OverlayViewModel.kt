package dev.korryr.phantom.viewmodel

import android.graphics.Bitmap
import dev.korryr.phantom.ai.GroqRepository
import dev.korryr.phantom.capture.ScreenCaptureManager
import dev.korryr.phantom.ocr.TextRecognitionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

data class OverlayState(
    val answer: String = "Tap to scan",
    val isLoading: Boolean = false,
    /** Phased status text shown during loading: "Scanning...", "Thinking...", etc. */
    val statusText: String = ""
)

class OverlayViewModel(
    private val groqRepository: GroqRepository,
    private val textRecognitionManager: TextRecognitionManager = TextRecognitionManager()
) {
    companion object {
        private const val MIN_QUESTION_LENGTH = 10
        private const val SIMILARITY_THRESHOLD = 0.85
        private const val CACHE_MAX_SIZE = 10
        private val WHITESPACE_REGEX = "\\s+".toRegex()   // Pre-compiled — avoids alloc per call

        /** How often the background OCR loop runs (ms) */
        private const val PRE_FETCH_INTERVAL_MS = 2000L

        /** Max age of pre-fetched OCR text to consider "fresh" (ms) */
        private const val PRE_FETCH_FRESHNESS_MS = 1500L
    }

    private val _state = MutableStateFlow(OverlayState())
    val state: StateFlow<OverlayState> = _state.asStateFlow()

    private var captureJob: Job? = null
    private var preFetchJob: Job? = null
    private var lastQuestionText: String = ""
    private var screenCaptureManager: ScreenCaptureManager? = null

    // ── Pre-fetched OCR cache ──────────────────────────────────────────────────
    @Volatile private var preFetchedText: String = ""
    @Volatile private var preFetchedAtMs: Long = 0L

    // ── In-memory LRU answer cache ─────────────────────────────────────────────
    private val answerCache = object : LinkedHashMap<String, String>(
        CACHE_MAX_SIZE + 1, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > CACHE_MAX_SIZE
    }

    init {
        // Pre-warm MLKit so the FIRST user tap is not slowed by model initialisation.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dummy = Bitmap.createBitmap(1, 1, Bitmap.Config.RGB_565)
                textRecognitionManager.recognizeText(dummy)
                dummy.recycle()
                Timber.d("MLKit pre-warm complete")
            } catch (e: Exception) {
                Timber.w(e, "MLKit pre-warm failed (non-fatal)")
            }
        }
    }

    fun setScreenCaptureManager(manager: ScreenCaptureManager) {
        screenCaptureManager = manager
        Timber.d("ScreenCaptureManager set")
    }

    // ── Speculative OCR pre-fetch ──────────────────────────────────────────────

    /**
     * Starts a background loop that runs OCR on the latest captured frame every
     * [PRE_FETCH_INTERVAL_MS]. The result is stored with a timestamp so `scanOnce()`
     * can use it instantly if it's fresh enough.
     */
    fun startPreFetch(scope: CoroutineScope) {
        if (preFetchJob != null) return   // already running
        Timber.d("Starting speculative OCR pre-fetch loop")

        preFetchJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val bitmap = screenCaptureManager?.captureFrame()
                    if (bitmap != null) {
                        val text = textRecognitionManager.recognizeText(bitmap)
                        if (text.length >= MIN_QUESTION_LENGTH) {
                            preFetchedText = text
                            preFetchedAtMs = System.currentTimeMillis()
                            Timber.v("Pre-fetch OCR (${text.length} chars): ${text.take(60)}")
                        }
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Pre-fetch OCR error (non-fatal)")
                }
                delay(PRE_FETCH_INTERVAL_MS)
            }
        }
    }

    fun stopPreFetch() {
        preFetchJob?.cancel()
        preFetchJob = null
        Timber.d("Stopped OCR pre-fetch loop")
    }

    // ── Scan logic ─────────────────────────────────────────────────────────────

    @Volatile
    private var scanInProgress = false

    fun scanOnce(scope: CoroutineScope) {
        if (scanInProgress) {
            Timber.d("Scan already in progress, ignoring")
            return
        }
        Timber.i("Manual scan triggered")
        scanInProgress = true
        _state.value = _state.value.copy(isLoading = true, statusText = "Scanning...")

        captureJob = scope.launch {
            try {
                // ── Phase 1: Get OCR text (prefer fresh, fallback to pre-fetched) ──
                val ageMs = System.currentTimeMillis() - preFetchedAtMs
                val cachedText = if (preFetchedText.length >= MIN_QUESTION_LENGTH && ageMs < PRE_FETCH_FRESHNESS_MS) {
                    preFetchedText
                } else {
                    ""
                }

                // Always attempt a fresh capture for accuracy
                val freshText: String = try {
                    val bitmap = withContext(Dispatchers.IO) {
                        screenCaptureManager?.captureFrame()
                    }
                    if (bitmap != null) {
                        withContext(Dispatchers.IO) {
                            textRecognitionManager.recognizeText(bitmap)
                        }
                    } else ""
                } catch (e: Exception) {
                    Timber.w(e, "Fresh capture failed, using cached")
                    ""
                }

                // Pick the longer (more complete) OCR result
                val text = if (freshText.length >= cachedText.length && freshText.length >= MIN_QUESTION_LENGTH) {
                    Timber.i("Using fresh OCR (${freshText.length} chars)")
                    freshText
                } else if (cachedText.length >= MIN_QUESTION_LENGTH) {
                    Timber.i("Using pre-fetched OCR (${ageMs}ms old, ${cachedText.length} chars)")
                    cachedText
                } else {
                    Timber.w("Both OCR results too short (fresh=${freshText.length}, cached=${cachedText.length})")
                    _state.value = OverlayState(answer = "No question found", isLoading = false)
                    scanInProgress = false
                    return@launch
                }

                if (text.length < MIN_QUESTION_LENGTH) {
                    Timber.w("Text too short (${text.length} < $MIN_QUESTION_LENGTH)")
                    _state.value = OverlayState(answer = "No question found", isLoading = false)
                    scanInProgress = false
                    return@launch
                }

                // ── Phase 2: Check caches ───────────────────────────────────────
                val normalizedText = normalizeForCache(text)

                // Exact cache hit
                answerCache[normalizedText]?.let { cached ->
                    Timber.d("Cache hit (exact) — returning cached answer")
                    _state.value = OverlayState(answer = cached, isLoading = false)
                    scanInProgress = false
                    return@launch
                }

                // Fuzzy cache hit (Jaccard similarity)
                if (lastQuestionText.isNotBlank() && isSimilar(lastQuestionText, text)) {
                    val prevAnswer = _state.value.answer
                    if (!prevAnswer.startsWith("Error") && prevAnswer != "Tap to scan"
                        && prevAnswer != "No question found" && prevAnswer != "Capture failed"
                    ) {
                        Timber.d("Question unchanged (Jaccard) — reusing previous answer")
                        _state.value = OverlayState(answer = prevAnswer, isLoading = false)
                        scanInProgress = false
                        return@launch
                    }
                }

                // ── Phase 3: Groq API (streaming) ───────────────────────────────
                lastQuestionText = text
                _state.value = _state.value.copy(statusText = "Thinking...")
                Timber.i("Calling Groq API (streaming)")

                var finalAnswer = ""
                groqRepository.getAnswerStreaming(text)
                    .catch { e ->
                        Timber.e(e, "Streaming error")
                        finalAnswer = "Error: ${e.message?.take(40)}"
                        _state.value = OverlayState(answer = finalAnswer, isLoading = false)
                    }
                    .collect { partialAnswer ->
                        finalAnswer = partialAnswer
                        _state.value = _state.value.copy(
                            answer = partialAnswer,
                            statusText = "",
                            isLoading = true
                        )
                    }

                // Stream complete — finalize
                if (finalAnswer.isNotEmpty() && !finalAnswer.startsWith("Error")) {
                    answerCache[normalizedText] = finalAnswer
                    Timber.d("Cached answer for future lookups")
                }
                _state.value = OverlayState(answer = finalAnswer.ifEmpty { "No answer" }, isLoading = false)
                Timber.i("Answer displayed: $finalAnswer")

            } catch (e: Exception) {
                Timber.e(e, "Scan error")
                _state.value = OverlayState(
                    answer = "Error: ${e.message?.take(40)}",
                    isLoading = false
                )
            } finally {
                scanInProgress = false
            }
        }
    }

    private fun normalizeForCache(text: String): String =
        text.lowercase().replace(WHITESPACE_REGEX, " ").trim()

    private fun isSimilar(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return false
        val wordsA = a.lowercase().splitToSequence(WHITESPACE_REGEX).filter { it.isNotEmpty() }.toHashSet()
        val wordsB = b.lowercase().splitToSequence(WHITESPACE_REGEX).filter { it.isNotEmpty() }.toHashSet()
        if (wordsA.isEmpty() || wordsB.isEmpty()) return false
        val overlap = wordsA.intersect(wordsB).size
        val maxSize = maxOf(wordsA.size, wordsB.size)
        val similarity = overlap.toDouble() / maxSize
        return similarity >= SIMILARITY_THRESHOLD
    }

    fun stop() {
        Timber.i("Stopping OverlayViewModel — cancelling capture, releasing resources")
        stopPreFetch()
        captureJob?.cancel()
        captureJob = null
        textRecognitionManager.close()
        screenCaptureManager?.release()
        screenCaptureManager = null
    }
}
