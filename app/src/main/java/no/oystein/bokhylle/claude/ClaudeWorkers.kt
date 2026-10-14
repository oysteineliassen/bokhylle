package no.oystein.bokhylle.claude

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.batches.BatchCreateParams
import com.anthropic.models.messages.batches.MessageBatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.oystein.bokhylle.app
import no.oystein.bokhylle.data.ApiUsage
import no.oystein.bokhylle.data.ClaudeJob
import no.oystein.bokhylle.data.Page

private const val TAG = "Bokhylle"

/** Feil som ikke blir bedre av å prøve igjen (feil nøkkel, ugyldig forespørsel). */
private fun Throwable.permanent() = this is UnauthorizedException || this is PermissionDeniedException ||
    this is BadRequestException || (this is ClaudeException && !retryable)

/** Sender sidene i en bok til Claude som Message Batches (maks [CHUNK] sider per batch). */
class ClaudeSubmitWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext.app
        val bookId = inputData.getLong(KEY_BOOK, -1)
        val ids = inputData.getString(KEY_PAGES).orEmpty().split(',').mapNotNull { it.toLongOrNull() }
        val book = app.db.books().get(bookId) ?: return@withContext Result.success()
        val model = ClaudeModel.of(book.claudeModell).id
        // Sider som allerede ligger i en batch som venter, sendes ikke på nytt (ved nytt forsøk etter feil).
        val waiting = app.db.claude().openJobs().flatMap { j -> j.sideIder.split(',').mapNotNull { it.toLongOrNull() } }.toSet()
        val pages = ids.filter { it !in waiting }.mapNotNull { app.db.pages().get(it) }.filter { it.status == Page.STATUS_FERDIG }
        if (pages.isEmpty()) return@withContext Result.success()
        try {
            val client = app.claude.client()
            for (chunk in pages.chunked(CHUNK)) {
                val params = BatchCreateParams.builder()
                chunk.forEach { p -> params.addRequest(ClaudeOcr.batchRequest(p.id, model, ClaudeOcr.encodeImage(p.bildeSti))) }
                val batch = client.messages().batches().create(params.build())
                app.db.claude().insertJob(ClaudeJob(bookId = bookId, batchId = batch.id(), modell = model, sideIder = chunk.joinToString(",") { it.id.toString() }))
                Log.d(TAG, "claude: batch ${batch.id()} sendt med ${chunk.size} sider")
            }
            app.claude.schedulePoll()
            Result.success()
        } catch (e: Exception) {
            // Stakksporingen inneholder aldri API-nøkkelen (den ligger bare i en header).
            Log.w(TAG, "claude: kunne ikke sende batch", e)
            if (e.permanent() || runAttemptCount >= 3) {
                // Behold ML Kit-teksten, og merk sidene så de kan sendes senere.
                val sent = app.db.claude().openJobs().flatMap { j -> j.sideIder.split(',').mapNotNull { it.toLongOrNull() } }.toSet()
                app.db.pages().setNeedsClaude(pages.map { it.id }.filter { it !in sent }, true)
                Result.success()
            } else Result.retry()
        }
    }

    companion object {
        const val KEY_BOOK = "bok"
        const val KEY_PAGES = "sider"
        const val CHUNK = 40
    }
}

/** Sjekker batchene som venter, henter resultatene når de er klare, og planlegger seg selv på nytt til alle er ferdige. */
class ClaudePollWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext.app
        val jobs = app.db.claude().openJobs()
        if (jobs.isEmpty()) return@withContext Result.success()
        val client = try { app.claude.client() } catch (e: Exception) { return@withContext Result.success() }
        var pending = 0
        for (job in jobs) {
            try {
                val batch = client.messages().batches().retrieve(job.batchId)
                if (batch.processingStatus() != MessageBatch.ProcessingStatus.ENDED) { pending++; continue }
                val model = ClaudeModel.of(job.modell)
                val ok = mutableSetOf<Long>()
                val results = client.messages().batches().resultsStreaming(job.batchId).use { stream ->
                    stream.stream().collect(java.util.stream.Collectors.toList())
                }
                for (item in results) {
                    val pageId = item.customId().toLongOrNull() ?: continue
                    val message = item.result().succeeded().orElse(null)?.message() ?: continue
                    try {
                        val r = ClaudeOcr.parse(message)
                        app.db.pages().setClaudeResult(pageId, r.json)
                        app.db.claude().insertUsage(
                            ApiUsage(bookId = job.bookId, modell = model.id, inputTokens = r.inputTokens, outputTokens = r.outputTokens,
                                kostnadUsd = model.cost(r.inputTokens, r.outputTokens, batch = true), batch = true),
                        )
                        ok += pageId
                    } catch (e: Exception) {
                        Log.w(TAG, "claude: side $pageId: ${e.message}")
                    }
                }
                val failed = job.sideIder.split(',').mapNotNull { it.toLongOrNull() }.filter { it !in ok }
                if (failed.isNotEmpty()) app.db.pages().setNeedsClaude(failed, true)
                app.db.claude().finish(job.id)
                Log.d(TAG, "claude: batch ${job.batchId} ferdig, ${ok.size} ok, ${failed.size} feilet")
                app.processor.rebuild(job.bookId)
            } catch (e: Exception) {
                Log.w(TAG, "claude: sjekk av batch feilet", e)
                if (e is NotFoundException) {
                    // Batchen finnes ikke lenger (utløpt): merk sidene for ny sending.
                    app.db.pages().setNeedsClaude(job.sideIder.split(',').mapNotNull { it.toLongOrNull() }, true)
                    app.db.claude().finish(job.id)
                } else pending++
            }
        }
        if (pending > 0) Result.retry() else Result.success()
    }

    companion object {
        const val NAME = "claude-sjekk"
    }
}

/** Omkjøring av én side med et vanlig kall. */
class ClaudePageWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext.app
        val page = app.db.pages().get(inputData.getLong(KEY_PAGE, -1)) ?: return@withContext Result.success()
        val book = app.db.books().get(page.bookId) ?: return@withContext Result.success()
        val model = ClaudeModel.of(book.claudeModell)
        try {
            val r = ClaudeOcr.analyzePage(app.claude.client(), model.id, ClaudeOcr.encodeImage(page.bildeSti))
            app.db.pages().setClaudeResult(page.id, r.json)
            app.db.claude().insertUsage(
                ApiUsage(bookId = page.bookId, modell = model.id, inputTokens = r.inputTokens, outputTokens = r.outputTokens,
                    kostnadUsd = model.cost(r.inputTokens, r.outputTokens, batch = false), batch = false),
            )
            app.processor.rebuild(page.bookId)
            Log.d(TAG, "claude: side ${page.id} analysert (${r.inputTokens}+${r.outputTokens} tokens)")
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "claude: side ${page.id} feilet", e)
            if (e.permanent() || runAttemptCount >= 3) {
                app.db.pages().setNeedsClaude(listOf(page.id), true)
                Result.success()
            } else Result.retry()
        }
    }

    companion object {
        const val KEY_PAGE = "side"
    }
}
