package no.oystein.bokhylle

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import no.oystein.bokhylle.ui.BokhylleNavHost
import no.oystein.bokhylle.ui.theme.BokhylleTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BokhylleTheme {
                BokhylleNavHost()
            }
        }
    }
}
