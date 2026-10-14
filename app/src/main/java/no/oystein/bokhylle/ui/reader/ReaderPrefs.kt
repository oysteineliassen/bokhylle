package no.oystein.bokhylle.ui.reader

import android.content.Context
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ReaderTheme(val label: String, val background: Color, val text: Color, val box: Color, val muted: Color) {
    LYS("Lys", Color(0xFFFFFFFF), Color(0xFF1B1B1B), Color(0xFFE8F0EA), Color(0xFF777777)),
    SEPIA("Sepia", Color(0xFFF4ECD8), Color(0xFF4B3A2A), Color(0xFFE6D8B8), Color(0xFF8A7560)),
    MORK("Mørk", Color(0xFF121212), Color(0xFFD6D3CE), Color(0xFF263229), Color(0xFF8C8C8C)),
}

data class ReaderSettings(
    val fontSize: Float = 19f,
    val lineHeight: Float = 1.5f,
    val serif: Boolean = true,
    val theme: ReaderTheme = ReaderTheme.LYS,
)

/** Leserinnstillingene, felles for alle bøker. */
class ReaderPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("leser", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<ReaderSettings> = _settings

    private fun load() = ReaderSettings(
        fontSize = prefs.getFloat("skrift", 19f),
        lineHeight = prefs.getFloat("linje", 1.5f),
        serif = prefs.getBoolean("serif", true),
        theme = ReaderTheme.entries.firstOrNull { it.name == prefs.getString("tema", null) } ?: ReaderTheme.LYS,
    )

    fun update(change: (ReaderSettings) -> ReaderSettings) {
        val s = change(_settings.value)
        _settings.value = s
        prefs.edit()
            .putFloat("skrift", s.fontSize)
            .putFloat("linje", s.lineHeight)
            .putBoolean("serif", s.serif)
            .putString("tema", s.theme.name)
            .apply()
    }
}
