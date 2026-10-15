package no.oystein.bokhylle.claude

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.oystein.bokhylle.core.ExchangeRate
import no.oystein.bokhylle.core.NorgesBankRate
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Dollarkursen prisene vises med (API-et fakturerer i USD). Hentes fra Norges Bank én gang i døgnet når
 * telefonen er på nett; uten nett eller før første henting brukes [FALLBACK].
 */
object ExchangeRates {
    const val FALLBACK = 10.5
    private const val PREFS = "valutakurs"

    /** Kursen som brukes nå (lest fra lagret verdi ved oppstart, oppdatert av [RateWorker]). */
    @Volatile var nokPerUsd: Double = FALLBACK
        private set

    /** Dato for kursen (ÅÅÅÅ-MM-DD), eller null når den faste reserven brukes. */
    @Volatile var date: String? = null
        private set

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString("kurs", null)?.toDoubleOrNull()?.let { nokPerUsd = it; date = prefs.getString("dato", null) }
    }

    private fun save(context: Context, rate: ExchangeRate) {
        nokPerUsd = rate.nokPerUnit
        date = rate.date
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("kurs", rate.nokPerUnit.toString()).putString("dato", rate.date).apply()
    }

    /** Henter dagens kurs. Gir null ved feil (da beholdes den forrige). */
    suspend fun refresh(context: Context): ExchangeRate? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(NorgesBankRate.USD_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/vnd.sdmx.data+json, application/json")
            val body = try {
                if (conn.responseCode != 200) null else conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
            val rate = body?.let(NorgesBankRate::parse)
            if (rate != null) save(context, rate) else Log.w(TAG, "kurs: kunne ikke lese svaret fra Norges Bank")
            rate
        } catch (e: Exception) {
            Log.w(TAG, "kurs: henting feilet: ${e.message}")
            null
        }
    }

    /** Daglig henting i bakgrunnen (bare med nett). */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<RateWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("valutakurs", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private const val TAG = "Bokhylle"
}

class RateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        if (ExchangeRates.refresh(applicationContext) != null) Result.success() else Result.retry()
}
