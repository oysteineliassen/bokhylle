package no.oystein.bokhylle.claude

import kotlin.math.roundToInt

/** Modellene appen kan bruke, med listepriser (USD per million tokens, oktober 2026). */
data class ClaudeModel(
    val id: String,
    val name: String,
    val description: String,
    val inputPerMillion: Double,
    val outputPerMillion: Double,
) {
    fun cost(inputTokens: Long, outputTokens: Long, batch: Boolean): Double {
        val full = inputTokens * inputPerMillion / 1e6 + outputTokens * outputPerMillion / 1e6
        return if (batch) full * BATCH_DISCOUNT else full
    }

    val isAuto: Boolean get() = id == AUTO_ID

    /** Anslått kostnad for [pages] sider. */
    fun estimate(pages: Int, batch: Boolean): Double = pages * cost(EST_INPUT_PER_PAGE, EST_OUTPUT_PER_PAGE, batch)

    companion object {
        /** Message Batches API koster halvparten. */
        const val BATCH_DISCOUNT = 0.5

        /**
         * Anslag per side: bildet (~1568×1100 px ≈ 2300 tokens) + instruksjonene (~900),
         * og svaret (JSON med teksten til en vanlig bokside + litt tenking).
         */
        const val EST_INPUT_PER_PAGE = 3200L
        const val EST_OUTPUT_PER_PAGE = 1500L

        val SONNET = ClaudeModel(ClaudeModels.SONNET_ID, "Claude Sonnet 5.5", "Best på figurer, faktabokser og fotnoter", 2.00, 10.00)
        val HAIKU = ClaudeModel(ClaudeModels.HAIKU_ID, "Claude Haiku 5.5", "Mye billigere, holder for enkle romaner", 0.10, 0.50)
        val ALL = listOf(SONNET, HAIKU)

        /**
         * «Auto»: Haiku på rene tekstsider, Sonnet på sider med figurer, faktabokser, fotnoter eller bilder
         * (se PageComplexity). Er ingen ekte modell; [forPage] velger. Prisene er Sonnets (verste tilfelle).
         */
        const val AUTO_ID = "auto"
        val AUTO = ClaudeModel(AUTO_ID, "Auto", "Haiku på rene tekstsider, Sonnet på sider med figurer, faktabokser og fotnoter", 2.00, 10.00)

        /** Valgene på bokskjermen. */
        val CHOICES = listOf(AUTO, SONNET, HAIKU)
        val DEFAULT = AUTO

        fun of(id: String?): ClaudeModel = CHOICES.firstOrNull { it.id == id } ?: DEFAULT

        /** Den ekte modellen svaret kom fra (for forbruket), ut fra modellnavnet i API-svaret. */
        fun fromApi(name: String?): ClaudeModel? = ALL.firstOrNull { name != null && name.startsWith(it.id) }
    }
}

/** Beløp i kroner (omregnet), med dollar i parentes, f.eks. «53 øre (0,05 USD)». */
fun formatUsd(amount: Double): String {
    val nb = java.util.Locale("nb", "NO")
    val nok = amount * ExchangeRates.nokPerUsd
    val kroner = when {
        nok < 0.01 -> "under 1 øre"
        nok < 1 -> "${(nok * 100).roundToInt()} øre"
        nok < 100 -> "%.2f kr".format(nb, nok)
        else -> "%.0f kr".format(nb, nok)
    }
    val usd = when {
        amount < 0.01 -> "under 0,01 USD"
        amount < 10 -> "%.2f USD".format(nb, amount)
        else -> "%.0f USD".format(nb, amount)
    }
    return "$kroner ($usd)"
}

/**
 * Modellen som faktisk brukes for en side: bokas valg, eller ved «Auto» Sonnet for sider med figurer,
 * faktabokser, fotnoter, markerte områder eller bilder, og Haiku for rene tekstsider.
 */
fun ClaudeModel.forPage(page: no.oystein.bokhylle.data.Page): ClaudeModel {
    if (!isAuto) return this
    val strong = no.oystein.bokhylle.core.PageComplexity.needsStrongModel(
        no.oystein.bokhylle.core.PageLayout.decode(page.layoutJson),
        no.oystein.bokhylle.core.OcrPage.decode(page.ocrLinjer)?.lines.orEmpty(),
        hasManualRegions = !page.manuelleOmrader.isNullOrBlank() && page.manuelleOmrader != "[]",
    )
    return if (strong) ClaudeModel.SONNET else ClaudeModel.HAIKU
}

/** Anslått kostnad for disse sidene (ved «Auto» regnet per side). */
fun ClaudeModel.estimate(pages: List<no.oystein.bokhylle.data.Page>, batch: Boolean): Double =
    if (!isAuto) estimate(pages.size, batch) else pages.sumOf { forPage(it).estimate(1, batch) }
