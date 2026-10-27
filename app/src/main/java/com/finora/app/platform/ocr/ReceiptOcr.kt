package com.finora.app.platform.ocr

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device text recognition. The BUNDLED ML Kit model ships inside the APK, so this works
 * offline and the image never leaves the phone. Lines are re-assembled left-to-right within
 * each visual row so "Paneer Tikka ..... 280.00" reads as one line.
 */
class ReceiptOcr(private val context: Context) {
    suspend fun recognize(uri: Uri): String = suspendCancellableCoroutine { cont ->
        val image = try { InputImage.fromFilePath(context, uri) } catch (e: Exception) { cont.resumeWithException(e); return@suspendCancellableCoroutine }
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                val lines = result.textBlocks.flatMap { it.lines }.mapNotNull { l -> l.boundingBox?.let { it to l.text } }
                cont.resume(assembleRows(lines.map { (box, text) -> Box(box.left, box.top, box.bottom, text) }))
                recognizer.close()
            }
            .addOnFailureListener { e -> recognizer.close(); if (cont.isActive) cont.resumeWithException(e) }
    }

    data class Box(val left: Int, val top: Int, val bottom: Int, val text: String)

    companion object {
        /** Groups fragments whose vertical centres are within half a line height, then orders each row by x. */
        fun assembleRows(boxes: List<Box>): String {
            if (boxes.isEmpty()) return ""
            val sorted = boxes.sortedBy { (it.top + it.bottom) / 2 }
            val rows = mutableListOf<MutableList<Box>>()
            for (b in sorted) {
                val c = (b.top + b.bottom) / 2
                val h = (b.bottom - b.top).coerceAtLeast(1)
                val row = rows.lastOrNull()?.takeIf { r -> val rc = r.map { (it.top + it.bottom) / 2 }.average(); kotlin.math.abs(rc - c) < h / 2.0 }
                if (row != null) row += b else rows += mutableListOf(b)
            }
            return rows.joinToString("\n") { r -> r.sortedBy { it.left }.joinToString(" ") { it.text } }
        }
    }
}
