package no.oystein.bokhylle.claude

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.oystein.bokhylle.R
import no.oystein.bokhylle.app
import no.oystein.bokhylle.core.BlockType
import no.oystein.bokhylle.core.PageLayout
import no.oystein.bokhylle.data.ApiUsage
import no.oystein.bokhylle.data.BookRepository
import no.oystein.bokhylle.data.Page
import no.oystein.bokhylle.data.PageImages
import no.oystein.bokhylle.image.ImageOps
import java.io.File
import java.util.UUID

/**
 * «Tegn figuren på nytt» i bakgrunnen (enkeltkall, ikke batch, så resultatet kommer med en gang).
 * Én figur: lager et forslag (SVG + bilde) som brukeren ser side om side med fotoet før det tas i bruk.
 * Hele boka: tegner alle figurer som ikke er tegnet ennå og tar tegningene i bruk; fotoene tas vare på.
 */
class DrawWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val app = context.app

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val pageId = inputData.getLong(KEY_PAGE, -1)
        if (pageId >= 0) drawOne(pageId, inputData.getString(KEY_IMAGE) ?: return@withContext Result.failure())
        else drawBook(inputData.getLong(KEY_BOOK, -1))
    }

    private data class Figure(val page: Page, val block: no.oystein.bokhylle.core.LayoutBlock, val caption: String?)

    private fun figuresOf(page: Page): List<Figure> {
        val blocks = PageLayout.decode(page.layoutJson)?.blocks.orEmpty()
        return blocks.mapIndexedNotNull { i, b ->
            val image = b.image
            if (b.type != BlockType.FIGURE || image == null || PageImages.isDrawing(image) || !File(image).exists()) null
            else Figure(page, b, blocks.getOrNull(i + 1)?.takeIf { it.type == BlockType.CAPTION }?.text)
        }
    }

    /** Tegner én figur. null = ikke et diagram (fotoet beholdes). */
    private suspend fun draw(f: Figure): Pair<File, File>? {
        val book = app.db.books().get(f.page.bookId) ?: return null
        // Figurer: «Auto» betyr den sterke modellen.
        val model = ClaudeModel.of(book.claudeModell).let { if (it.isAuto) ClaudeModel.SONNET else it }
        val context = contextImage(f)
        val r = ClaudeDraw.redraw(
            app.claude.client(), model.id, context ?: ClaudeOcr.encodeImage(f.block.image!!), f.block.description, f.caption,
            marked = context != null,
        )
        app.db.claude().insertUsage(
            ApiUsage(bookId = book.id, modell = model.id, inputTokens = r.inputTokens, outputTokens = r.outputTokens,
                kostnadUsd = model.cost(r.inputTokens, r.outputTokens, batch = false), batch = false),
        )
        val svg = r.svg ?: return null
        val name = "${PageImages.DRAWING_PREFIX}${f.page.id}-${UUID.randomUUID()}"
        val dir = app.files.bookDir(book.id)
        val svgFile = File(dir, "$name.svg").apply { writeText(svg) }
        val bitmap = ClaudeDraw.render(svg)
        val image = File(dir, "$name.webp")
        ImageOps.save(bitmap, image, ImageOps.FIGURE_QUALITY)
        bitmap.recycle()
        return svgFile to image
    }

    /**
     * Utsnitt av sidebildet rundt figuren, med figurens område markert med en rød ramme, så Claude ser det som
     * stikker utenfor (etiketter, piler) og hva som er brødtekst. null når sidebildet ikke finnes lenger.
     */
    private fun contextImage(f: Figure): String? {
        val box = f.block.box ?: return null
        if (!File(f.page.bildeSti).exists()) return null
        val page = ImageOps.decode(f.page.bildeSti) ?: return null
        try {
            val m = 0.12f
            val outer = no.oystein.bokhylle.core.Box(
                (box.left - m).coerceAtLeast(0f), (box.top - m).coerceAtLeast(0f),
                (box.right + m).coerceAtMost(1f), (box.bottom + m).coerceAtMost(1f),
            )
            val cut = ImageOps.crop(page, outer) ?: return null
            val img = cut.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
            if (img != cut) cut.recycle()
            val sx = img.width / (outer.width * page.width)
            val sy = img.height / (outer.height * page.height)
            val left = (box.left - outer.left) * page.width * sx
            val top = (box.top - outer.top) * page.height * sy
            val right = (box.right - outer.left) * page.width * sx
            val bottom = (box.bottom - outer.top) * page.height * sy
            android.graphics.Canvas(img).drawRect(
                left, top, right, bottom,
                android.graphics.Paint().apply {
                    color = android.graphics.Color.RED
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = maxOf(3f, img.width / 300f)
                },
            )
            val file = File(applicationContext.cacheDir, "tegning-utsnitt.jpg")
            ImageOps.saveJpeg(img, file)
            img.recycle()
            return ClaudeOcr.encodeImage(file.path).also { file.delete() }
        } finally {
            page.recycle()
        }
    }

    private suspend fun drawOne(pageId: Long, image: String): Result {
        val page = app.db.pages().get(pageId) ?: return Result.failure(workDataOf(KEY_ERROR to "Fant ikke siden"))
        val figure = figuresOf(page).firstOrNull { it.block.image == image }
            ?: return Result.failure(workDataOf(KEY_ERROR to "Fant ikke figuren"))
        return try {
            val files = draw(figure) ?: return Result.success(workDataOf(KEY_NOT_DIAGRAM to true))
            Log.d(TAG, "tegning: side $pageId tegnet på nytt")
            Result.success(workDataOf(KEY_DRAWING to files.second.path, KEY_SVG to files.first.path))
        } catch (e: Exception) {
            Log.w(TAG, "tegning: side $pageId feilet", e)
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: e.javaClass.simpleName)))
        }
    }

    private suspend fun drawBook(bookId: Long): Result {
        if (bookId < 0) return Result.failure()
        val skipped = skippedImages(applicationContext)
        val figures = app.db.pages().forBook(bookId).filter { it.status == Page.STATUS_FERDIG }
            .flatMap(::figuresOf).filter { File(it.block.image!!).name !in skipped }
        var done = 0
        var drawn = 0
        for (f in figures) {
            if (isStopped) return Result.retry()
            runCatching { setForeground(foreground(done, figures.size)) }
            setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to figures.size))
            try {
                val files = draw(f)
                if (files == null) {
                    remember(applicationContext, File(f.block.image!!).name)
                } else {
                    app.repo.useDrawing(f.page.id, f.block.image!!, files.second.path)
                    drawn++
                }
            } catch (e: Exception) {
                Log.w(TAG, "tegning: figur på side ${f.page.id} feilet", e)
                // Avvist eller for stor: hopp over figuren (også neste gang). Nettverksfeil o.l.: prøv igjen senere.
                if (e is ClaudeException) remember(applicationContext, File(f.block.image!!).name)
                else if (e.permanent() || runAttemptCount >= 2) return Result.failure(workDataOf(KEY_ERROR to (e.message ?: ""), KEY_DRAWN to drawn))
                else return Result.retry()
            }
            done++
        }
        Log.d(TAG, "tegning: $drawn av ${figures.size} figurer tegnet på nytt")
        return Result.success(workDataOf(KEY_DONE to done, KEY_TOTAL to figures.size, KEY_DRAWN to drawn))
    }

    private fun foreground(done: Int, total: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Tegning av figurer", NotificationManager.IMPORTANCE_LOW))
        }
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_book)
            .setContentTitle("Tegner figurer på nytt")
            .setContentText("Figur ${done + 1} av $total")
            .setProgress(total, done, false)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIFICATION_ID, n)
    }

    companion object {
        private const val TAG = "Bokhylle"
        const val KEY_PAGE = "side"
        const val KEY_IMAGE = "bilde"
        const val KEY_BOOK = "bok"
        const val KEY_DRAWING = "tegning"
        const val KEY_SVG = "svg"
        const val KEY_NOT_DIAGRAM = "ikke_diagram"
        const val KEY_ERROR = "feil"
        const val KEY_DONE = "ferdig"
        const val KEY_TOTAL = "totalt"
        const val KEY_DRAWN = "tegnet"
        private const val CHANNEL = "tegning"
        private const val NOTIFICATION_ID = 1003
        private const val PREFS = "tegning"
        private const val KEY_SKIPPED = "ikke_diagram"

        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun one(context: Context, pageId: Long, image: String): UUID {
            val request = OneTimeWorkRequestBuilder<DrawWorker>()
                .setInputData(workDataOf(KEY_PAGE to pageId, KEY_IMAGE to image))
                .setConstraints(online)
                .build()
            WorkManager.getInstance(context).enqueue(request)
            return request.id
        }

        fun bookWorkName(bookId: Long) = "tegn-bok-$bookId"

        fun book(context: Context, bookId: Long) {
            val request = OneTimeWorkRequestBuilder<DrawWorker>()
                .setInputData(workDataOf(KEY_BOOK to bookId))
                .setConstraints(online)
                .addTag(BookRepository.bookTag(bookId))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(bookWorkName(bookId), ExistingWorkPolicy.KEEP, request)
        }

        /** Figurer Claude har sagt er fotografier o.l.; de prøves ikke igjen ved «tegn hele boka». */
        private fun skippedImages(context: Context): Set<String> =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_SKIPPED, emptySet()).orEmpty()

        private fun remember(context: Context, name: String) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit().putStringSet(KEY_SKIPPED, skippedImages(context) + name).apply()
        }

        /** Figurer i boka som kan tegnes på nytt (ikke tegnet ennå, og ikke avvist som fotografi). */
        fun candidates(context: Context, pages: List<Page>): Int {
            val skipped = skippedImages(context)
            return pages.filter { it.status == Page.STATUS_FERDIG }.sumOf { p ->
                PageLayout.decode(p.layoutJson)?.blocks.orEmpty().count { b ->
                    b.type == BlockType.FIGURE && b.image != null && !PageImages.isDrawing(b.image) &&
                        File(b.image!!).name !in skipped
                }
            }
        }
    }
}
