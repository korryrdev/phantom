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
import kotlinx.coroutines.withTimeoutOrNull
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
        private val WHITESPACE_REGEX = "\\s+".toRegex()

        // FIX: Reduced pre-fetch interval to 800ms for faster caching behavior
        private const val PRE_FETCH_INTERVAL_MS = 800L

        private const val PRE_FETCH_FRESHNESS_MS = 1000L

        // FIX: New — prevents the overlay from being stuck on the spinner indefinitely
        // if the network hangs or the API is unreachable.
        private const val SCAN_TIMEOUT_MS = 15_000L

        // Fraction of bitmap height to crop from top (status bar: ~5%)
        private const val CROP_TOP_FRACTION = 0.05f
        // Fraction of bitmap height to crop from bottom (nav bar: ~10%)
        private const val CROP_BOTTOM_FRACTION = 0.10f
    }

    private val _state = MutableStateFlow(OverlayState())
    val state: StateFlow<OverlayState> = _state.asStateFlow()

    private var captureJob: Job? = null
    private var preFetchJob: Job? = null
    private var lastQuestionText: String = ""
    private var screenCaptureManager: ScreenCaptureManager? = null

    @Volatile private var preFetchedText: String = ""
    @Volatile private var preFetchedAtMs: Long = 0L

    private val answerCache = object : LinkedHashMap<String, String>(
        CACHE_MAX_SIZE + 1, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > CACHE_MAX_SIZE
    }

    init {
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

    // ── UI Chrome Cropping ─────────────────────────────────────────────────────

    /**
     * Crops the status bar (top) and navigation bar (bottom) from a captured bitmap
     * before feeding it to OCR.
     *
     * WHY: The screen capture includes the status bar (clock, battery %, carrier name)
     * and the nav bar. ML Kit OCR reads these and includes text like "9:41", "87%",
     * "Safaricom" in the result. This noise gets sent to Groq and confuses it into
     * thinking those are part of the question, producing wrong answers.
     *
     * The cropped bitmap is the caller's responsibility to recycle (via finally block).
     * If cropping produces an invalid height, the original bitmap is returned uncropped.
     */
    private fun cropForOcr(bitmap: Bitmap): Bitmap {
        val topCrop    = (bitmap.height * CROP_TOP_FRACTION).toInt()
        val bottomCrop = (bitmap.height * CROP_BOTTOM_FRACTION).toInt()
        val newHeight  = bitmap.height - topCrop - bottomCrop
        return if (newHeight > 10) {
            Bitmap.createBitmap(bitmap, 0, topCrop, bitmap.width, newHeight)
        } else {
            bitmap  // fallback: return original if dimensions are too small to crop
        }
    }

    /**
     * Runs OCR on a cropped version of [bitmap].
     * Automatically recycles the cropped copy after OCR completes.
     * The original [bitmap] is never recycled here (ScreenCaptureManager owns it).
     */
    private suspend fun recognizeWithCrop(bitmap: Bitmap): String {
        val cropped = cropForOcr(bitmap)
        val isCopy  = cropped !== bitmap  // did cropForOcr create a new bitmap?
        return try {
            textRecognitionManager.recognizeText(cropped)
        } finally {
            // Only recycle if cropForOcr created a NEW bitmap — not the original reference
            if (isCopy) cropped.recycle()
        }
    }

    // ── Speculative OCR pre-fetch ──────────────────────────────────────────────

    fun startPreFetch(scope: CoroutineScope) {
        if (preFetchJob != null) return
        Timber.d("Starting speculative OCR pre-fetch loop (interval=${PRE_FETCH_INTERVAL_MS}ms)")

        preFetchJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val bitmap = screenCaptureManager?.captureFrame()
                    if (bitmap != null) {
                        // FIX: Use recognizeWithCrop — status bar noise no longer pollutes cache
                        val text = recognizeWithCrop(bitmap)
                        if (text.length >= MIN_QUESTION_LENGTH) {
                            preFetchedText  = text
                            preFetchedAtMs  = System.currentTimeMillis()
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
            // FIX: Wrap in a timeout — previously a hung network left the overlay stuck
            // on the spinner forever. Now it fails gracefully after 15s.
            val result = withTimeoutOrNull(SCAN_TIMEOUT_MS) {
                runScan()
            }

            if (result == null) {
                Timber.w("Scan timed out after ${SCAN_TIMEOUT_MS}ms")
                _state.value = OverlayState(answer = "Timeout — retry", isLoading = false)
                scanInProgress = false
            }
        }
    }

    /**
     * Core scan logic, extracted for clarity and to support the timeout wrapper.
     */
    private suspend fun runScan() {
        try {
            // ── Phase 1: Get OCR text ──────────────────────────────────────────
            val ageMs      = System.currentTimeMillis() - preFetchedAtMs
            val cachedText = if (preFetchedText.length >= MIN_QUESTION_LENGTH
                && ageMs < PRE_FETCH_FRESHNESS_MS
            ) preFetchedText else ""

            val text: String
            if (cachedText.isNotEmpty()) {
                Timber.i("Using pre-fetched OCR (${ageMs}ms old, ${cachedText.length} chars)")
                text = cachedText
            } else {
                Timber.i("Cached OCR too old or empty, capturing fresh...")
                val freshText: String = try {
                    val bitmap = withContext(Dispatchers.IO) {
                        screenCaptureManager?.captureFrame()
                    }
                    if (bitmap != null) {
                        withContext(Dispatchers.IO) { recognizeWithCrop(bitmap) }
                    } else ""
                } catch (e: Exception) {
                    Timber.w(e, "Fresh capture failed")
                    ""
                }

                if (freshText.length >= MIN_QUESTION_LENGTH) {
                    Timber.i("Using fresh OCR (${freshText.length} chars)")
                    text = freshText
                } else {
                    Timber.w("No valid OCR text found")
                    _state.value = OverlayState(answer = "No question found", isLoading = false)
                    scanInProgress = false
                    return
                }
            }

            if (text.length < MIN_QUESTION_LENGTH) {
                Timber.w("Text too short (${text.length} < $MIN_QUESTION_LENGTH)")
                _state.value = OverlayState(answer = "No question found", isLoading = false)
                scanInProgress = false
                return
            }

            // ── Phase 2: Check caches ──────────────────────────────────────────
            val normalizedText = normalizeForCache(text)

            answerCache[normalizedText]?.let { cached ->
                Timber.d("Cache hit (exact) — returning cached answer")
                _state.value = OverlayState(answer = cached, isLoading = false)
                scanInProgress = false
                return
            }

            if (lastQuestionText.isNotBlank() && isSimilar(lastQuestionText, text)) {
                val prevAnswer = _state.value.answer
                if (!prevAnswer.startsWith("Error") && prevAnswer != "Tap to scan"
                    && prevAnswer != "No question found" && prevAnswer != "Capture failed"
                    && prevAnswer != "Timeout — retry"
                ) {
                    Timber.d("Question unchanged (Jaccard) — reusing previous answer")
                    _state.value = OverlayState(answer = prevAnswer, isLoading = false)
                    scanInProgress = false
                    return
                }
            }

            // ── Phase 3: Groq API (streaming) ─────────────────────────────────
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
                        answer     = partialAnswer,
                        statusText = "",
                        isLoading  = true
                    )
                }

            if (finalAnswer.isNotEmpty() && !finalAnswer.startsWith("Error")) {
                answerCache[normalizedText] = finalAnswer
                Timber.d("Cached answer for future lookups")
            }
            _state.value = OverlayState(
                answer    = finalAnswer.ifEmpty { "No answer" },
                isLoading = false
            )
            Timber.i("Answer displayed: $finalAnswer")

        } catch (e: Exception) {
            Timber.e(e, "Scan error")
            _state.value = OverlayState(
                answer    = "Error: ${e.message?.take(40)}",
                isLoading = false
            )
        } finally {
            scanInProgress = false
        }
    }

    private fun normalizeForCache(text: String): String =
        text.lowercase().replace(WHITESPACE_REGEX, " ").trim()

    private fun isSimilar(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return false
        val wordsA = a.lowercase().splitToSequence(WHITESPACE_REGEX).filter { it.isNotEmpty() }.toHashSet()
        val wordsB = b.lowercase().splitToSequence(WHITESPACE_REGEX).filter { it.isNotEmpty() }.toHashSet()
        if (wordsA.isEmpty() || wordsB.isEmpty()) return false
        val overlap  = wordsA.intersect(wordsB).size
        val maxSize  = maxOf(wordsA.size, wordsB.size)
        val similarity = overlap.toDouble() / maxSize
        return similarity >= SIMILARITY_THRESHOLD
    }

    fun stop() {
        Timber.i("Stopping OverlayViewModel")
        stopPreFetch()
        captureJob?.cancel()
        captureJob = null
        textRecognitionManager.close()
        screenCaptureManager?.release()
        screenCaptureManager = null
    }
}
