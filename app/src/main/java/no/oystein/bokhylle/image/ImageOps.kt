package no.oystein.bokhylle.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import no.oystein.bokhylle.core.Box
import no.oystein.bokhylle.core.PageCropDetector
import no.oystein.bokhylle.core.Quad
import java.io.File
import java.io.FileOutputStream
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

object ImageOps {
    const val MAX_SIDE = 2000
    const val JPEG_QUALITY = 85

    /** Leser et bilde, retter opp etter EXIF-orienteringen og skalerer ned til maks [maxSide] på langsiden. */
    fun decodeUpright(file: File, maxSide: Int = MAX_SIDE): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null

        val orientation = runCatching {
            ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
        }
        val scale = maxSide.toFloat() / max(decoded.width, decoded.height)
        if (scale < 1f) matrix.postScale(scale, scale)
        if (matrix.isIdentity) return decoded
        val result = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (result != decoded) decoded.recycle()
        return result
    }

    fun decode(path: String): Bitmap? = BitmapFactory.decodeFile(path)

    fun saveJpeg(bitmap: Bitmap, file: File, quality: Int = JPEG_QUALITY) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
    }

    /** Når bildet ble tatt, fra EXIF (millisekunder), hvis kjent. */
    fun captureTime(file: File): Long? = runCatching {
        val exif = ExifInterface(file.path)
        exif.dateTimeOriginal ?: exif.dateTimeDigitized ?: exif.dateTime
    }.getOrNull()

    /** Finner sidens hjørner på en liten gråtonekopi av bildet. */
    fun detectPage(bitmap: Bitmap): Quad? {
        val scale = 400f / max(bitmap.width, bitmap.height)
        val w = max(8, (bitmap.width * scale).roundToInt())
        val h = max(8, (bitmap.height * scale).roundToInt())
        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small != bitmap) small.recycle()
        val lum = IntArray(px.size) { luminance(px[it]) }
        return PageCropDetector.detect(lum, w, h)
    }

    /** Retter opp perspektivet og beskjærer til hjørnene. Resultatet blir maks [MAX_SIDE] på langsiden. */
    fun warp(source: Bitmap, quad: Quad): Bitmap {
        val sw = source.width.toFloat()
        val sh = source.height.toFloat()
        val src = quad.points.flatMap { listOf(it.x * sw, it.y * sh) }.toFloatArray()
        fun dist(i: Int, j: Int) = hypot(src[2 * i] - src[2 * j], src[2 * i + 1] - src[2 * j + 1])
        var outW = (dist(0, 1) + dist(3, 2)) / 2f
        var outH = (dist(0, 3) + dist(1, 2)) / 2f
        val scale = MAX_SIDE / max(outW, outH)
        if (scale < 1f) { outW *= scale; outH *= scale }
        val w = max(1, outW.roundToInt())
        val h = max(1, outH.roundToInt())
        val dst = floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat())
        val matrix = Matrix()
        matrix.setPolyToPoly(src, 0, dst, 0, 4)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /** Klipper ut et område (normalisert) av bildet, f.eks. en figur. */
    fun crop(source: Bitmap, box: Box): Bitmap? {
        val l = (box.left.coerceIn(0f, 1f) * source.width).roundToInt()
        val t = (box.top.coerceIn(0f, 1f) * source.height).roundToInt()
        val r = (box.right.coerceIn(0f, 1f) * source.width).roundToInt()
        val b = (box.bottom.coerceIn(0f, 1f) * source.height).roundToInt()
        if (r - l < 8 || b - t < 8) return null
        return Bitmap.createBitmap(source, l, t, r - l, b - t)
    }

    fun luminance(c: Int): Int = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000

    /** Papirfargen: snittet av de lyseste halvparten av punktene i et rutenett over siden. */
    fun paperColor(bitmap: Bitmap): Int = backgroundColor(bitmap, Box(0f, 0f, 1f, 1f))

    /** Bakgrunnsfargen i et område: tekst er mørk, så de lyseste punktene viser bakgrunnen. */
    fun backgroundColor(bitmap: Bitmap, box: Box): Int {
        val samples = mutableListOf<Int>()
        val steps = 24
        for (i in 0..steps) for (j in 0..steps) {
            val x = ((box.left + box.width * i / steps) * (bitmap.width - 1)).roundToInt().coerceIn(0, bitmap.width - 1)
            val y = ((box.top + box.height * j / steps) * (bitmap.height - 1)).roundToInt().coerceIn(0, bitmap.height - 1)
            samples += bitmap.getPixel(x, y)
        }
        val bright = samples.sortedByDescending { luminance(it) }.take(max(1, samples.size / 2))
        return Color.rgb(bright.sumOf { Color.red(it) } / bright.size, bright.sumOf { Color.green(it) } / bright.size, bright.sumOf { Color.blue(it) } / bright.size)
    }

    fun colorDistance(a: Int, b: Int): Float {
        val dr = (Color.red(a) - Color.red(b)).toFloat()
        val dg = (Color.green(a) - Color.green(b)).toFloat()
        val db = (Color.blue(a) - Color.blue(b)).toFloat()
        return sqrt(dr * dr + dg * dg + db * db)
    }
}
