package no.oystein.bokhylle

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import no.oystein.bokhylle.ui.BokhylleNavHost
import no.oystein.bokhylle.ui.theme.BokhylleTheme

class MainActivity : ComponentActivity() {
    private var openBook by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        setContent {
            BokhylleTheme {
                BokhylleNavHost(openBookId = openBook, onOpened = { openBook = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val id = intent?.getLongExtra(EXTRA_BOOK, -1L) ?: -1L
        if (id >= 0) openBook = id
    }

    companion object {
        const val EXTRA_BOOK = "no.oystein.bokhylle.BOK"
    }
}
