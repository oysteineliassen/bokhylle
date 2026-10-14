package no.oystein.bokhylle

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.oystein.bokhylle.backup.RestoreWorker
import java.io.File
import java.util.UUID

/**
 * Åpner .bokhylle- og .bokhylle-backup-filer fra filbehandler, nedlastinger eller «Del».
 * Filen kopieres inn mens tilgangen gjelder, og importskjermen i appen viser innholdet før noe endres.
 */
class ImportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = when (intent?.action) {
            Intent.ACTION_SEND -> stream(intent)
            else -> intent?.data
        }
        if (uri == null) { finish(); return }
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(cacheDir, RestoreWorker.INCOMING).apply { mkdirs() }
                    val f = File(dir, "${UUID.randomUUID()}.zip")
                    contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } } ?: return@runCatching null
                    f
                }.getOrNull()
            }
            if (file == null) {
                Toast.makeText(this@ImportActivity, "Kunne ikke lese filen", Toast.LENGTH_LONG).show()
            } else {
                startActivity(
                    Intent(this@ImportActivity, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_IMPORT, file.path)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
            }
            finish()
        }
    }

    @Suppress("DEPRECATION")
    private fun stream(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri
}
