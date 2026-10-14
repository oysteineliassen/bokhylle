package no.oystein.bokhylle.claude

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import no.oystein.bokhylle.data.BookRepository
import java.util.concurrent.TimeUnit

/** Innstillinger og oppstart av Claude-jobber. */
class ClaudeService(private val context: Context) {
    val keys = ApiKeyStore(context)
    private val prefs = context.getSharedPreferences("claude", Context.MODE_PRIVATE)

    /** Bare for testing mot en lokal testserver (settes av testmottakeren i debug-bygget). */
    var baseUrl: String?
        get() = prefs.getString("base_url", null)
        set(value) = prefs.edit().putString("base_url", value).apply()

    val hasKey: Boolean get() = keys.isSet

    fun client() = ClaudeOcr.client(keys.get() ?: throw ClaudeException("Ingen API-nøkkel"), baseUrl)

    fun isOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Sender sidene som Message Batch(er). Venter på nett om telefonen er frakoblet. */
    fun submitBook(bookId: Long, pageIds: List<Long>) {
        if (pageIds.isEmpty()) return
        val request = OneTimeWorkRequestBuilder<ClaudeSubmitWorker>()
            .setInputData(workDataOf(ClaudeSubmitWorker.KEY_BOOK to bookId, ClaudeSubmitWorker.KEY_PAGES to pageIds.joinToString(",")))
            .setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .addTag(BookRepository.bookTag(bookId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("claude-send-$bookId", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Vanlig kall for én side (raskere enn batch, full pris). */
    fun runPage(bookId: Long, pageId: Long) {
        val request = OneTimeWorkRequestBuilder<ClaudePageWorker>()
            .setInputData(workDataOf(ClaudePageWorker.KEY_PAGE to pageId))
            .setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(BookRepository.bookTag(bookId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("claude-side-$pageId", ExistingWorkPolicy.REPLACE, request)
    }

    /** Sjekker batchene som venter. Første sjekk etter [delayMinutes] minutter. */
    fun schedulePoll(delayMinutes: Long = 1, replace: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<ClaudePollWorker>()
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 2, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ClaudePollWorker.NAME,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
}
