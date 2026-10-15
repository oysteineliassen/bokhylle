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
        val DEFAULT = SONNET

        fun of(id: String?): ClaudeModel = ALL.firstOrNull { it.id == id } ?: DEFAULT
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
