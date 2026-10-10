package dev.korryr.phantom.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class TextRecognitionManager {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Runs ML Kit OCR on [bitmap] and returns the detected text.
     *
     * KEY FIX: Instead of using [com.google.mlkit.vision.text.Text.text] (which dumps all
     * blocks in an arbitrary, non-guaranteed order), we sort every TextBlock and its Lines
     * by their bounding-box top coordinate. This ensures the output always reads top-to-bottom,
     * left-to-right — matching the visual reading order of a quiz screen.
     *
     * Without this fix, ML Kit can return option D before the question, sending Groq a garbled
     * text that produces wrong answers.
     */
    suspend fun recognizeText(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(inputImage)
                .addOnSuccessListener { result ->
                    val allLines = result.textBlocks.flatMap { it.lines }
                        .sortedBy { it.boundingBox?.top ?: 0 }

                    val rows = mutableListOf<MutableList<com.google.mlkit.vision.text.Text.Line>>()
                    for (line in allLines) {
                        val top = line.boundingBox?.top ?: 0
                        val currentRow = rows.lastOrNull()
                        val rowTop = currentRow?.first()?.boundingBox?.top ?: 0
                        // Treat items within 30 vertical pixels as the same visual row
                        if (currentRow != null && kotlin.math.abs(top - rowTop) < 30) {
                            currentRow.add(line)
                        } else {
                            rows.add(mutableListOf(line))
                        }
                    }

                    val text = rows.joinToString("\n") { row ->
                        // Sort horizontally within each row
                        row.sortedBy { it.boundingBox?.left ?: 0 }
                           .joinToString("\t") { it.text }
                    }
                    if (cont.isActive) cont.resume(text)
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume("")
                }
        }
    }

    fun close() {
        recognizer.close()
    }
}
