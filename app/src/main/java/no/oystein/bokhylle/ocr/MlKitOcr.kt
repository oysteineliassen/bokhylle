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
            // Fargen vurderes per linje: ML Kit kan slå en faktaboks sammen med brødteksten rundt.
            val coloredLines = mutableListOf<OcrLine>()
            val plainLines = mutableListOf<OcrLine>()
            for (line in block.lines) {
                val lb = box(line.boundingBox)
                val padded = Box((lb.left - 0.01f).coerceAtLeast(0f), (lb.top - 0.006f).coerceAtLeast(0f), (lb.right + 0.01f).coerceAtMost(1f), (lb.bottom + 0.006f).coerceAtMost(1f))
                val colored = lb.width > 0.03f && ImageOps.colorDistance(ImageOps.backgroundColor(bitmap, padded), paper) > COLORED_DISTANCE
                val ocrLine = OcrLine(
                    text = line.text,
                    left = lb.left, top = lb.top, right = lb.right, bottom = lb.bottom,
                    block = bi,
                    confidence = line.confidence,
                    elements = line.elements.map { e ->
                        val eb = box(e.boundingBox)
                        OcrElement(e.text, eb.left, eb.top, eb.right, eb.bottom)
                    },
                )
                if (colored) coloredLines += ocrLine else plainLines += ocrLine
            }
            // Blandet blokk: de fargede linjene får en egen blokk.
            val boxIndex = if (plainLines.isEmpty()) bi else SPLIT_OFFSET + bi
            lines += plainLines
            lines += coloredLines.map { it.copy(block = boxIndex) }
            if (plainLines.isNotEmpty() || coloredLines.isEmpty()) blocks += OcrBlockInfo(bi, b, cut, colored = false)
            if (coloredLines.isNotEmpty()) {
                val cb = coloredLines.map { Box(it.left, it.top, it.right, it.bottom) }.reduce(Box::union)
                blocks += OcrBlockInfo(boxIndex, cb, cut, colored = true)
            }
        }
        lines.sortWith(compareBy({ it.block % SPLIT_OFFSET }, { it.top }))
        return OcrPage(lines, blocks)
    }

    /** Blokknummer for fargede linjer som er skilt ut fra en blandet blokk. */
    private const val SPLIT_OFFSET = 10000

    /** Hvor ulik bakgrunnen må være papirfargen (RGB-avstand) for at blokken regnes som faktaboks. */
    private const val COLORED_DISTANCE = 28f
}
