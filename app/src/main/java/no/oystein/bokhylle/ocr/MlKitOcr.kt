package no.oystein.bokhylle.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import no.oystein.bokhylle.core.Box
import no.oystein.bokhylle.core.OcrBlockInfo
import no.oystein.bokhylle.core.OcrElement
import no.oystein.bokhylle.core.OcrLine
import no.oystein.bokhylle.core.OcrPage
import no.oystein.bokhylle.image.ImageOps

/** OCR på telefonen med ML Kit (latinsk skrift). Fungerer uten nett, og er reserve når Claude ikke kan brukes. */
object MlKitOcr {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Gjenkjenner teksten og finner ut hvilke blokker som er kuttet av bildekanten eller har farget bakgrunn. */
    suspend fun recognize(bitmap: Bitmap): OcrPage {
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        fun box(r: Rect?) = if (r == null) Box(0f, 0f, 0f, 0f) else
            Box((r.left / w).coerceIn(0f, 1f), (r.top / h).coerceIn(0f, 1f), (r.right / w).coerceIn(0f, 1f), (r.bottom / h).coerceIn(0f, 1f))

        val paper = ImageOps.paperColor(bitmap)
        val lines = mutableListOf<OcrLine>()
        val blocks = mutableListOf<OcrBlockInfo>()
        result.textBlocks.forEachIndexed { bi, block ->
            val b = box(block.boundingBox)
            val edge = 0.006f
            val cut = b.left <= edge || b.top <= edge || b.right >= 1 - edge || b.bottom >= 1 - edge
            // Litt luft rundt blokken, så bakgrunnen mellom linjene også teller med.
            val padded = Box((b.left - 0.01f).coerceAtLeast(0f), (b.top - 0.005f).coerceAtLeast(0f), (b.right + 0.01f).coerceAtMost(1f), (b.bottom + 0.005f).coerceAtMost(1f))
            val colored = b.width > 0.05f && b.height > 0.02f &&
                ImageOps.colorDistance(ImageOps.backgroundColor(bitmap, padded), paper) > COLORED_DISTANCE
            blocks += OcrBlockInfo(bi, b, cut, colored)
            for (line in block.lines) {
                val lb = box(line.boundingBox)
                lines += OcrLine(
                    text = line.text,
                    left = lb.left, top = lb.top, right = lb.right, bottom = lb.bottom,
                    block = bi,
                    confidence = line.confidence,
                    elements = line.elements.map { e ->
                        val eb = box(e.boundingBox)
                        OcrElement(e.text, eb.left, eb.top, eb.right, eb.bottom)
                    },
                )
            }
        }
        return OcrPage(lines, blocks)
    }

    /** Hvor ulik bakgrunnen må være papirfargen (RGB-avstand) for at blokken regnes som faktaboks. */
    private const val COLORED_DISTANCE = 28f
}
