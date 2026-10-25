package no.oystein.bokhylle.claude

import android.graphics.Bitmap
import android.util.Base64
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.batches.BatchCreateParams
import no.oystein.bokhylle.core.Json
import no.oystein.bokhylle.core.PageLayout
import no.oystein.bokhylle.core.obj
import no.oystein.bokhylle.image.ImageOps
import java.io.ByteArrayOutputStream
import java.time.Duration
import kotlin.math.max
import kotlin.math.roundToInt

/** Resultatet for én side: rå JSON fra Claude (lagres i blocksJson) og tokenforbruket. */
data class ClaudePageResult(val json: String, val layout: PageLayout, val inputTokens: Long, val outputTokens: Long)

class ClaudeException(message: String, val retryable: Boolean = false) : Exception(message)

/**
 * Sideanalyse med Claude: det beskårne sidebildet inn, ordnede blokker (JSON) ut.
 * Hele bøker sendes som Message Batches (halv pris), enkeltsider som vanlige kall.
 */
object ClaudeOcr {
    /** Langsiden skaleres ned til dette før sending, for å holde tokenbruken nede. */
    const val MAX_IMAGE_SIDE = 1568
    private const val MAX_TOKENS = 16000L

    fun client(apiKey: String, baseUrl: String?): AnthropicClient {
        val builder = AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .maxRetries(2)
            .timeout(Duration.ofMinutes(3))
        if (!baseUrl.isNullOrBlank()) builder.baseUrl(baseUrl)
        return builder.build()
    }

    /** Sidebildet som base64-JPEG, maks [MAX_IMAGE_SIDE] på langsiden. */
    fun encodeImage(path: String): String {
        val bitmap = ImageOps.decode(path) ?: throw ClaudeException("Kan ikke lese sidebildet")
        val scale = MAX_IMAGE_SIDE.toFloat() / max(bitmap.width, bitmap.height)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt(), (bitmap.height * scale).roundToInt(), true)
        } else bitmap
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        if (scaled != bitmap) scaled.recycle()
        bitmap.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun content(imageBase64: String): List<ContentBlockParam> = listOf(
        ContentBlockParam.ofImage(
            ImageBlockParam.builder()
                .source(Base64ImageSource.builder().mediaType(Base64ImageSource.MediaType.IMAGE_JPEG).data(imageBase64).build())
                .build(),
        ),
        ContentBlockParam.ofText(TextBlockParam.builder().text("Analyser denne boksiden og returner JSON etter skjemaet.").build()),
    )

    private val outputConfig: OutputConfig by lazy {
        val schema = JsonOutputFormat.Schema.builder()
        @Suppress("UNCHECKED_CAST")
        (SCHEMA as Map<String, Any?>).forEach { (k, v) -> schema.putAdditionalProperty(k, JsonValue.from(v)) }
        OutputConfig.builder()
            .effort(OutputConfig.Effort.MEDIUM)
            .format(JsonOutputFormat.builder().schema(schema.build()).build())
            .build()
    }

    /** Enkeltkall for én side (omkjøring). */
    fun analyzePage(client: AnthropicClient, model: String, imageBase64: String): ClaudePageResult {
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(MAX_TOKENS)
            .system(SYSTEM_PROMPT)
            .outputConfig(outputConfig)
            .addUserMessageOfBlockParams(content(imageBase64))
        if (model == ClaudeModels.SONNET_ID) {
            // Avviser sikkerhetsfilteret siden, prøver API-et automatisk en annen modell.
            builder.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        return parse(client.messages().create(builder.build()))
    }

    /** Én forespørsel i en batch; custom_id er side-id-en. */
    fun batchRequest(pageId: Long, model: String, imageBase64: String): BatchCreateParams.Request =
        BatchCreateParams.Request.builder()
            .customId(pageId.toString())
            .params(
                BatchCreateParams.Request.Params.builder()
                    .model(model)
                    .maxTokens(MAX_TOKENS)
                    .system(SYSTEM_PROMPT)
                    .outputConfig(outputConfig)
                    .addUserMessageOfBlockParams(content(imageBase64))
                    .build(),
            )
            .build()

    fun parse(message: Message): ClaudePageResult {
        val stop = message.stopReason().orElse(null)
        if (stop == StopReason.REFUSAL) throw ClaudeException("Claude avviste siden")
        if (stop == StopReason.MAX_TOKENS) throw ClaudeException("Svaret ble for langt")
        val text = message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
        val root = runCatching { Json.parse(text).obj() }.getOrNull() ?: throw ClaudeException("Ugyldig JSON fra Claude")
        val layout = PageLayout.fromJson(root, PageLayout.SOURCE_CLAUDE)
        val usage = message.usage()
        val input = usage.inputTokens() + usage.cacheCreationInputTokens().orElse(0L) + usage.cacheReadInputTokens().orElse(0L)
        return ClaudePageResult(text, layout, input, usage.outputTokens())
    }

    private val SYSTEM_PROMPT = """
        Du får et foto av én side i en trykt bok. Siden er allerede beskåret og rettet opp. Gjengi innholdet som JSON
        etter skjemaet, slik at boka kan leses som e-bok.

        Regler:
        - Skriv teksten nøyaktig slik den står, på bokas språk. Ikke oversett, ikke rett stavemåte, ikke oppsummer.
        - "blocks" i leserekkefølge. Typer:
          * "heading": overskrift (kapittel, del, mellomtittel). Står det et kapittelnummer over eller foran
            tittelen (ofte et stort tall i egen farge), skal det med først i overskriften: «43 Tidsbegrenset spising».
          * "paragraph": ett avsnitt brødtekst. Slå sammen linjene i avsnittet til én tekst, og fjern orddeling ved
            linjeslutt («bok-» + «hylle» → «bokhylle»), men behold bindestrek i ord som skal ha den («NRK-sjefen»).
            Er siste ord på siden delt over til neste side, la det stå med bindestrek til slutt.
          * "box": faktaboks, digresjon eller annen innrammet/farget tekst utenfor brødteksten. Tittel i "title",
            innhold i "text" (avsnitt skilt med en blank linje).
          * "figure": bilde, illustrasjon, diagram, kart eller tabell som bilde. "description": kort beskrivelse
            (én-to setninger) på bokas språk. Tekst inne i figuren (etiketter, akser) skal IKKE i brødteksten,
            bare nevnes i beskrivelsen ved behov. "kind": hva slags figur det er:
            "diagram" (flytskjema, modell, tabell som bilde), "kart", "graf" (kurver, søyler, sektordiagram),
            "tegning" (strektegning, skisse), "foto" (fotografi), "illustrasjon" (detaljert/malt kunstnerisk bilde)
            eller "logo" (logoer og forlagsmerker, sertifiserings- og miljømerker som FSC, PEFC og Svanemerket,
            strekkoder/ISBN og QR-koder).
          * "caption": bildetekst til en figur.
          * "footnote": fotnotetekst nederst på siden; nummeret i "number", teksten i "text".
        - Fotnotehenvisninger (små hevede tall eller tegn etter et ord) skrives i teksten som [^n], f.eks. «sant.[^3]».
          Vanlige tall i teksten skal stå som de er.
        - Sidetallet og løpende topp-/bunntekst (bok- eller kapitteltittel som gjentas på hver side) skal ikke i
          blokkene, bare i "page_number", "running_header" og "running_footer" (null hvis de ikke finnes).
        - Ta ikke med tekst som er kuttet av bildekanten (rester av nabosiden).
        - "continues_from_previous": true hvis første avsnitt fortsetter fra forrige side (begynner midt i en setning).
          "continues_to_next": true hvis siste avsnitt fortsetter på neste side.
        - "bbox" for hver blokk: [x0, y0, x1, y1] normalisert 0–1 i forhold til bildet (x mot høyre, y nedover).
          Vær nøyaktig for figurer, siden de klippes ut fra bildet.
        - Felter som ikke gjelder blokken settes til null ("kind" er null for alt som ikke er figurer). Tom side: tom "blocks"-liste.
    """.trimIndent()

    private val nullableString = mapOf("type" to listOf("string", "null"))
    private val SCHEMA: Map<String, Any?> = mapOf(
        "type" to "object",
        "additionalProperties" to false,
        "required" to listOf("page_number", "running_header", "running_footer", "continues_from_previous", "continues_to_next", "blocks"),
        "properties" to mapOf(
            "page_number" to nullableString,
            "running_header" to nullableString,
            "running_footer" to nullableString,
            "continues_from_previous" to mapOf("type" to "boolean"),
            "continues_to_next" to mapOf("type" to "boolean"),
            "blocks" to mapOf(
                "type" to "array",
                "items" to mapOf(
                    "type" to "object",
                    "additionalProperties" to false,
                    "required" to listOf("type", "text", "title", "description", "kind", "number", "bbox"),
                    "properties" to mapOf(
                        "type" to mapOf("type" to "string", "enum" to listOf("heading", "paragraph", "box", "figure", "caption", "footnote")),
                        "text" to nullableString,
                        "title" to nullableString,
                        "description" to nullableString,
                        "kind" to mapOf(
                            "anyOf" to listOf(
                                mapOf("type" to "string", "enum" to no.oystein.bokhylle.core.FigureKind.entries.map { it.json }),
                                mapOf("type" to "null"),
                            ),
                        ),
                        "number" to nullableString,
                        "bbox" to mapOf("type" to "array", "items" to mapOf("type" to "number")),
                    ),
                ),
            ),
        ),
    )
}

object ClaudeModels {
    const val SONNET_ID = "claude-sonnet-5-5"
    const val HAIKU_ID = "claude-haiku-5-5"
}
