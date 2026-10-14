package no.oystein.bokhylle.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import no.oystein.bokhylle.core.PageFlag
import no.oystein.bokhylle.core.PageNumber

@Entity(tableName = "books")
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tittel: String,
    val forfatter: String = "",
    /** Siden som brukes som omslag. null = første side. */
    val coverPageId: Long? = null,
    val opprettet: Long = System.currentTimeMillis(),
    /** Id-en til siden som sist ble lest (følger siden selv om rekkefølgen endres). */
    val sistLestSide: Long? = null,
    /** Claude-modellen som brukes for denne boka (se ClaudeModels). */
    val claudeModell: String = "claude-sonnet-5-5",
    /** Fast id på tvers av telefoner, brukt ved eksport/import for å kjenne igjen samme bok. */
    val uuid: String = java.util.UUID.randomUUID().toString(),
    /** Brukeren har kontrollert teksten; sidebildene er skalert ned og originalene slettet. */
    val ferdigKontrollert: Boolean = false,
    /** Sidebildene er slettet (bare figurene er beholdt). */
    val bilderSlettet: Boolean = false,
    /** Hvor mange sider boka har totalt (brukerens tall); null = høyeste sidetall som er funnet. */
    val antallSiderTotalt: Int? = null,
    /** Send hver ny opplasting til Claude som egen batch. */
    val claudeAuto: Boolean = false,
    /** Sist noe i boka ble endret (tekst, rekkefølge, sider); brukes ved gjenoppretting for å finne nyeste versjon. */
    val sistEndret: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "pages",
    foreignKeys = [ForeignKey(entity = Book::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId"), Index(value = ["bookId", "kildeHash"])],
)
data class Page(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    /** Det beskårne og opprettede sidebildet. Mens siden venter på behandling peker den på originalkopien. */
    val bildeSti: String,
    /** Hele bildet før beskjæring (nedskalert), så hjørnene kan justeres senere. */
    val originalSti: String? = null,
    /** Sidens hjørner i originalbildet (JSON, normalisert 0–1). null = hele bildet. */
    val beskjaering: String? = null,
    /** Råtekst fra OCR. */
    val ocrTekst: String = "",
    /** Brukerens egne rettelser. Vinner over [ryddetTekst] når den finnes. */
    val redigertTekst: String? = null,
    /** Sidetallet slik det står trykt («47», «xii»). */
    val oppdagetSidetall: String? = null,
    val sorteringsIndeks: Int = 0,
    /** 0..1, snitt av ML Kits sikkerhet per linje. */
    val ocrSikkerhet: Float = 0f,
    val flagget: Boolean = false,

    /** Tekst etter opprydding (uten sidetall/topptekst, med avsnitt). */
    val ryddetTekst: String? = null,
    /** ML Kit-resultatet med posisjoner (JSON), så sidetall og opprydding kan kjøres på nytt uten ny OCR. */
    val ocrLinjer: String? = null,
    /** Claudes svar for siden (JSON med blokker), uendret. */
    val blocksJson: String? = null,
    /** Ferdig sideoppsett som leseren viser (JSON): fra Claude eller ML Kit, med figurbilder og manuelle rettelser. */
    val layoutJson: String? = null,
    /** Hvor teksten kommer fra: [KILDE_MLKIT] eller [KILDE_CLAUDE]. */
    val ocrKilde: String = KILDE_MLKIT,
    /** Claude feilet eller var utilgjengelig; siden kan kjøres med Claude senere. */
    val trengerClaude: Boolean = false,
    /** Områder brukeren har markert som figur/faktaboks (JSON). */
    val manuelleOmrader: String? = null,
    val status: Int = STATUS_NY,
    /** Brukeren har skrevet inn sidetallet selv; ny gjenkjenning overskriver det ikke. */
    val sidetallManuelt: Boolean = false,
    /** Sidetall utledet fra naboene for sider uten trykt tall (f.eks. bildesider). */
    val utledetSidetall: String? = null,
    /** Hvorfor siden er flagget: navn fra [PageFlag], kommaseparert. */
    val flaggGrunner: String = "",
    /** Brukeren har sett på siden og godtatt den; den flagges ikke igjen. */
    val godkjent: Boolean = false,
    /** Brukeren har flyttet siden selv. Den står da rett etter [plassertEtter] (null = først). */
    val manuellPlassering: Boolean = false,
    val plassertEtter: Long? = null,
    /** Importen siden kom fra (tidspunkt), og rekkefølgen innenfor den. */
    val importBatch: Long = 0,
    val importIndeks: Int = 0,
    /** Når bildet ble tatt (EXIF), hvis kjent. */
    val fotoTidspunkt: Long? = null,
    /** SHA-256 av originalfilen, så samme bilde ikke legges inn to ganger. */
    val kildeHash: String = "",
    /** Teksten med små bokstaver, for fulltekstsøk. */
    val sokeTekst: String = "",
) {
    companion object {
        /** Kopiert inn, venter på komprimering og OCR. */
        const val STATUS_NY = 0
        const val STATUS_FERDIG = 1
        /** Bildet kunne ikke leses. */
        const val STATUS_FEIL = -1

        const val KILDE_MLKIT = "mlkit"
        const val KILDE_CLAUDE = "claude"

        fun searchText(text: String) = text.lowercase()
    }
}

/** Teksten som skal vises i leseren. */
val Page.visningsTekst: String get() = redigertTekst ?: ryddetTekst ?: ocrTekst

val Page.sidetall: PageNumber? get() = oppdagetSidetall?.let(PageNumber::parse)

/** Trykt sidetall, eller utledet i parentes. */
val Page.sidetallVisning: String? get() = oppdagetSidetall ?: utledetSidetall?.let { "($it)" }

val Page.flagg: Set<PageFlag>
    get() = flaggGrunner.split(',').mapNotNull { n -> PageFlag.entries.firstOrNull { it.name == n } }.toSet()

/** En bok med det biblioteket trenger å vise. */
data class BookSummary(
    @androidx.room.Embedded val book: Book,
    val antallSider: Int,
    val ventende: Int,
    val flaggede: Int,
    val omslag: String?,
    /** Hvor mange sider som er lest (posisjonen til sist leste side). */
    val lestTil: Int,
)

val BookSummary.fremdrift: Float get() = if (antallSider == 0) 0f else lestTil.toFloat() / antallSider

/** Faktisk tokenforbruk og kostnad for ett Claude-kall (eller én side i en batch). */
@Entity(
    tableName = "api_bruk",
    indices = [Index("bookId")],
)
data class ApiUsage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Ikke fremmednøkkel: forbruket skal telle med i totalen også etter at boka er slettet. */
    val bookId: Long,
    val modell: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val kostnadUsd: Double,
    val batch: Boolean,
    val tidspunkt: Long = System.currentTimeMillis(),
)

/** En Message Batch som er sendt til Claude og venter på resultat. */
@Entity(tableName = "claude_jobber", indices = [Index("bookId")])
data class ClaudeJob(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val batchId: String,
    val modell: String,
    /** Sidene i batchen (kommaseparerte id-er). custom_id i batchen er side-id-en. */
    val sideIder: String,
    val opprettet: Long = System.currentTimeMillis(),
    val ferdig: Boolean = false,
)

data class UsageTotal(val inputTokens: Long, val outputTokens: Long, val kostnadUsd: Double)

/** Sidetallet for en ferdig behandlet side (for «sider lagt inn» i biblioteket). */
data class PageNumberRow(val bookId: Long, val oppdagetSidetall: String?, val utledetSidetall: String?)

/** Treff i fulltekstsøket. */
data class SearchHit(
    val pageId: Long,
    val bookId: Long,
    val tittel: String,
    val oppdagetSidetall: String?,
    val tekst: String,
)
