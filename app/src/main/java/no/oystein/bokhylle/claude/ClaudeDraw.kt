package no.oystein.bokhylle.claude

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.anthropic.client.AnthropicClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.caverock.androidsvg.RenderOptions
import com.caverock.androidsvg.SVG
import kotlin.math.roundToInt

/**
 * «Tegn figuren på nytt»: Claude ser på fotoet av en figur og tegner den som en ren vektortegning (SVG).
 * Fungerer for diagrammer, grafer, skjemaer og enkle tegninger; fotografier og detaljerte illustrasjoner
 * avvises av Claude selv («IKKE_DIAGRAM»), og da beholdes fotoet.
 */
object ClaudeDraw {
    /** [svg] er null når figuren ikke egner seg for å tegnes på nytt. */
    data class Result(val svg: String?, val inputTokens: Long, val outputTokens: Long)

    /** Anslag per figur: bildet + instruksjonene inn, en SVG ut. */
    const val EST_INPUT = 2200L
    const val EST_OUTPUT = 5000L
    private const val MAX_TOKENS = 24000L
    const val NOT_DIAGRAM = "IKKE_DIAGRAM"

    fun estimate(model: ClaudeModel, figures: Int) = figures * model.cost(EST_INPUT, EST_OUTPUT, batch = false)

    /**
     * @param marked bildet er et utsnitt av siden der figuren er markert med en rød ramme (se DrawWorker);
     *   ellers er det bare figuren.
     */
    fun redraw(client: AnthropicClient, model: String, imageBase64: String, description: String?, caption: String?, marked: Boolean = false): Result {
        val hint = buildString {
            append("Tegn denne figuren på nytt som SVG.")
            if (marked) {
                append(
                    "\nBildet viser et utsnitt av boksiden. Den røde rammen viser omtrent hvor figuren er. Ta med alt som " +
                        "hører til figuren, også det som stikker utenfor rammen (etiketter, piler, strektegning), men ikke " +
                        "brødtekst, overskrifter over figuren eller bildeteksten. Ikke tegn den røde rammen.",
                )
            }
            if (!description.isNullOrBlank()) append("\nBeskrivelse: ").append(description)
            if (!caption.isNullOrBlank()) append("\nBildeteksten (vises under figuren, skal ikke med i tegningen): ").append(caption)
        }
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(MAX_TOKENS)
            .system(SYSTEM_PROMPT)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build())
            .addUserMessageOfBlockParams(
                listOf(
                    ContentBlockParam.ofImage(
                        ImageBlockParam.builder()
                            .source(Base64ImageSource.builder().mediaType(Base64ImageSource.MediaType.IMAGE_JPEG).data(imageBase64).build())
                            .build(),
                    ),
                    ContentBlockParam.ofText(TextBlockParam.builder().text(hint).build()),
                ),
            )
        if (model == ClaudeModels.SONNET_ID) {
            builder.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        val message = client.messages().create(builder.build())
        val stop = message.stopReason().orElse(null)
        if (stop == StopReason.REFUSAL) throw ClaudeException("Claude avviste figuren")
        if (stop == StopReason.MAX_TOKENS) throw ClaudeException("Tegningen ble for stor")
        val text = message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
        val usage = message.usage()
        val input = usage.inputTokens() + usage.cacheCreationInputTokens().orElse(0L) + usage.cacheReadInputTokens().orElse(0L)
        return Result(extractSvg(text), input, usage.outputTokens())
    }

    /** Selve <svg>…</svg> fra svaret, uten skript, bilder eller lenker utenfor tegningen. null = ikke et diagram. */
    fun extractSvg(text: String): String? {
        val start = text.indexOf("<svg")
        val end = text.lastIndexOf("</svg>")
        if (start < 0 || end < start) return null
        return text.substring(start, end + 6)
            .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<(image|foreignObject)[\\s\\S]*?(/>|</\\1>)", RegexOption.IGNORE_CASE), "")
    }

    /** Tegner SVG-en som et bilde, [width] piksler bredt, på hvit bakgrunn (for leseren og EPUB-en). */
    fun render(svg: String, width: Int = 1600): Bitmap {
        val doc = SVG.getFromString(svg)
        val box = doc.documentViewBox
        val aspect = when {
            box != null && box.width() > 0f -> box.height() / box.width()
            doc.documentWidth > 0f -> doc.documentHeight / doc.documentWidth
            else -> 0.66f
        }.coerceIn(0.1f, 4f)
        val height = (width * aspect).roundToInt()
        if (box == null && doc.documentWidth > 0f) doc.setDocumentViewBox(0f, 0f, doc.documentWidth, doc.documentHeight)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        doc.renderToCanvas(canvas, RenderOptions.create().viewPort(0f, 0f, width.toFloat(), height.toFloat()))
        return bitmap
    }

    private val SYSTEM_PROMPT = """
        Du får et foto av én figur fra en trykt bok (fotografert med mobil, kan være litt skjevt, med skygger og
        gjennomslag fra baksiden). Tegn figuren på nytt som én selvstendig SVG, slik den ville sett ut trykt.

        Regler:
        - Svar bare med SVG-koden: <svg xmlns="http://www.w3.org/2000/svg" viewBox="…"> … </svg>. Ingen forklaring.
        - Gjengi figuren trofast: samme former, kurver, piler, linjer, skravering, farger og plassering.
          Rett opp skjevhet og perspektiv fra fotoet; ikke ta med skygger, papirstruktur eller gjennomslag.
        - All tekst i figuren (titler, etiketter, akser, tall) skal stå nøyaktig som i boka, som <text>.
          Bruk font-family "serif" eller "sans-serif" som ligner boka.
        - Bildeteksten under figuren skal IKKE med (den vises for seg).
        - Bruk bare enkle elementer: path, line, polyline, polygon, rect, circle, ellipse, text, tspan, g,
          og eventuelt linearGradient. Ingen <image>, ingen skript, ingen eksterne ressurser.
        - Har figuren farget bakgrunn (f.eks. en faktaboks), tegn bakgrunnen som en rect, med avrundede hjørner
          hvis boka har det.
        - Hold SVG-en kompakt: bruk path med kurver i stedet for tusenvis av punkter, og gjentatte streker
          (skravering) som én path.
        - Er figuren et fotografi, et maleri eller en detaljert illustrasjon som ikke kan gjengis trofast med
          enkle former, svar bare med ordet $NOT_DIAGRAM.
    """.trimIndent()
}
