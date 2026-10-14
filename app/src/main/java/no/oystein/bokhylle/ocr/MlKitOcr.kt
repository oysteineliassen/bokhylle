package no.oystein.bokhylle.ocr

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.Text
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

    /**
     * Hvor mange grader (med klokka) bildet må roteres for at teksten skal stå riktig vei: 0, 90, 180 eller 270.
     * Telefonen vet ikke alltid hvilken vei den ble holdt når den ligger flatt over boka, så EXIF-retningen
     * kan være feil. Teksten leses i hver retning, og retningen med mest lesbar tekst vinner.
     */
    suspend fun detectRotation(bitmap: Bitmap): Int {
        val scale = minOf(1f, ORIENT_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height))
        val small = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
        try {
            val upright = readability(small, 0)
            // Tydelig, rett tekst: ingen grunn til å prøve de andre retningene.
            if (upright.letters >= CLEAR_LETTERS && upright.confidence >= CLEAR_CONFIDENCE) return 0
            var best = 0
            var bestScore = upright.score
            for (degrees in intArrayOf(90, 270, 180)) {
                val r = readability(small, degrees)
                if (r.score > bestScore * WIN_FACTOR && r.letters >= MIN_LETTERS) {
                    best = degrees
                    bestScore = r.score
                }
            }
            return best
        } finally {
            if (small != bitmap) small.recycle()
        }
    }

    private class Readability(val letters: Int, val confidence: Float, val score: Float)

    private suspend fun readability(bitmap: Bitmap, degrees: Int): Readability {
        val rotated = if (degrees == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
        try {
            val result = recognizer.process(InputImage.fromBitmap(rotated, 0)).await()
            var letters = 0
            var weighted = 0f
            for (line in result.textBlocks.flatMap(Text.TextBlock::getLines)) {
                // Linjer ML Kit selv mener står på skrå eller på tvers, teller ikke.
                if (kotlin.math.abs(line.angle) > 30f) continue
                // Bare ord med minst tre bokstaver: tekst lest feil vei gir mest løse tegn og korte biter.
                val n = line.elements.sumOf { e -> e.text.count(Char::isLetter).takeIf { it >= 3 } ?: 0 }
                letters += n
                weighted += n * line.confidence
            }
            return Readability(letters, if (letters > 0) weighted / letters else 0f, weighted)
        } finally {
            if (rotated != bitmap) rotated.recycle()
        }
    }

    private const val ORIENT_SIDE = 1200
    private const val CLEAR_LETTERS = 150
    private const val CLEAR_CONFIDENCE = 0.8f
    private const val MIN_LETTERS = 20
    private const val WIN_FACTOR = 1.5f

    /** Blokknummer for fargede linjer som er skilt ut fra en blandet blokk. */
    private const val SPLIT_OFFSET = 10000

    /** Hvor ulik bakgrunnen må være papirfargen (RGB-avstand) for at blokken regnes som faktaboks. */
    private const val COLORED_DISTANCE = 28f
}
