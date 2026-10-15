package no.oystein.bokhylle.backup

import no.oystein.bokhylle.BokhylleApp
import no.oystein.bokhylle.core.Json
import no.oystein.bokhylle.core.PageLayout
import no.oystein.bokhylle.core.arr
import no.oystein.bokhylle.core.bool
import no.oystein.bokhylle.core.num
import no.oystein.bokhylle.core.obj
import no.oystein.bokhylle.core.str
import no.oystein.bokhylle.data.Book
import no.oystein.bokhylle.data.Page
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupFormatException(message: String) : Exception(message)

/** Kort informasjon om en bok i en fil, uten å pakke ut bildene. */
data class BookInfo(
    val uuid: String,
    val title: String,
    val author: String,
    val changed: Long,
    val pages: Int,
    val editedPages: Int,
)

/**
 * Én bok som .bokhylle-fil (zip): book.json (metadata, uuid, formatversjon), pages.json (tekst, rettelser,
 * Claude-svar, sidetall, rekkefølge) og mappen bilder/ med side- og figurbilder.
 */
object BookPackage {
    const val FORMAT = "bokhylle-bok"
    const val VERSION = 1
    const val EXTENSION = "bokhylle"
    private const val IMAGES = "bilder/"

    /** @param withImages med sidebilder og originaler; ellers bare tekst og figurer (mye mindre fil). */
    suspend fun export(app: BokhylleApp, bookId: Long, out: OutputStream, withImages: Boolean, onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        val book = app.db.books().get(bookId) ?: throw BackupFormatException("Fant ikke boka")
        val pages = app.db.pages().forBook(bookId)
        val index = pages.withIndex().associate { (i, p) -> p.id to i }
        val zip = ZipOutputStream(out.buffered())
        val files = mutableMapOf<String, File>() // navn i zip -> fil

        fun addImage(path: String?): String? {
            val f = path?.let(::File)?.takeIf { it.exists() } ?: return null
            val name = IMAGES + f.name
            files[name] = f
            return name
        }

        val pageJson = pages.mapIndexed { i, p ->
            val layout = PageLayout.decode(p.layoutJson)
            val portable = layout?.copy(blocks = layout.blocks.map { b -> b.copy(image = addImage(b.image), photo = addImage(b.photo), drawing = addImage(b.drawing)) })
            mapOf(
                "index" to i,
                "ocrTekst" to p.ocrTekst,
                "redigertTekst" to p.redigertTekst,
                "ryddetTekst" to p.ryddetTekst,
                "blocksJson" to p.blocksJson,
                "layoutJson" to portable?.encode(),
                "ocrLinjer" to p.ocrLinjer,
                "ocrKilde" to p.ocrKilde,
                "ocrSikkerhet" to p.ocrSikkerhet,
                "oppdagetSidetall" to p.oppdagetSidetall,
                "sidetallManuelt" to p.sidetallManuelt,
                "utledetSidetall" to p.utledetSidetall,
                "sorteringsIndeks" to p.sorteringsIndeks,
                "flagget" to p.flagget,
                "flaggGrunner" to p.flaggGrunner,
                "godkjent" to p.godkjent,
                "manuellPlassering" to p.manuellPlassering,
                "plassertEtter" to p.plassertEtter?.let { index[it] },
                "importBatch" to p.importBatch,
                "importIndeks" to p.importIndeks,
                "fotoTidspunkt" to p.fotoTidspunkt,
                "kildeHash" to p.kildeHash,
                "trengerClaude" to p.trengerClaude,
                "manuelleOmrader" to p.manuelleOmrader,
                "beskjaering" to p.beskjaering,
                "status" to p.status,
                "bilde" to if (withImages) addImage(p.bildeSti) else null,
                "original" to if (withImages && p.originalSti != p.bildeSti) addImage(p.originalSti) else null,
            )
        }
        val bookJson = mapOf(
            "format" to FORMAT,
            "version" to VERSION,
            "uuid" to book.uuid,
            "tittel" to book.tittel,
            "forfatter" to book.forfatter,
            "opprettet" to book.opprettet,
            "sistEndret" to book.sistEndret,
            "claudeModell" to book.claudeModell,
            "ferdigKontrollert" to book.ferdigKontrollert,
            "bilderSlettet" to (book.bilderSlettet || !withImages),
            "omslag" to book.coverPageId?.let { index[it] },
            // Omslagsbildet er lite og tas med også når sidebildene ikke gjør det.
            "omslagBilde" to addImage(book.omslagBilde),
            "sistLest" to book.sistLestSide?.let { index[it] },
            "sider" to pages.size,
            "rettedeSider" to pages.count { it.redigertTekst != null },
            "eksportert" to System.currentTimeMillis(),
        )
        zip.setLevel(Deflater.DEFAULT_COMPRESSION)
        zip.putNextEntry(ZipEntry("book.json")); zip.write(Json.write(bookJson).toByteArray()); zip.closeEntry()
        zip.putNextEntry(ZipEntry("pages.json")); zip.write(Json.write(pageJson).toByteArray()); zip.closeEntry()
        // Bildene er allerede komprimert (WebP); rask lagring holder.
        zip.setLevel(Deflater.NO_COMPRESSION)
        var done = 0
        for ((name, file) in files) {
            zip.putNextEntry(ZipEntry(name))
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            onProgress(++done, files.size)
        }
        zip.finish()
        zip.flush()
    }

    /** Leser bare book.json. Kaster [BackupFormatException] hvis filen ikke er en gyldig .bokhylle-fil. */
    fun peek(input: InputStream): BookInfo {
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.name == "book.json") return info(parseBook(zip.readBytes()))
            }
        }
        throw BackupFormatException("Filen er ikke en Bokhylle-bok (mangler book.json)")
    }

    private fun parseBook(bytes: ByteArray): Map<String, Any?> {
        val root = runCatching { Json.parse(String(bytes)).obj() }.getOrNull() ?: throw BackupFormatException("book.json er skadet")
        if (root.str("format") != FORMAT) throw BackupFormatException("Ukjent filformat")
        val version = root.num("version")?.toInt() ?: 0
        if (version > VERSION) throw BackupFormatException("Filen er laget med en nyere versjon av Bokhylle (format $version). Oppdater appen.")
        if (version < 1) throw BackupFormatException("Ugyldig formatversjon")
        return root
    }

    private fun info(root: Map<String, Any?>) = BookInfo(
        uuid = root.str("uuid") ?: "",
        title = root.str("tittel") ?: "Uten tittel",
        author = root.str("forfatter") ?: "",
        changed = root.num("sistEndret")?.toLong() ?: 0L,
        pages = root.num("sider")?.toInt() ?: 0,
        editedPages = root.num("rettedeSider")?.toInt() ?: 0,
    )

    /**
     * Importerer en bok. Bildene pakkes ut til en midlertidig mappe og flyttes inn når alt er lest,
     * så en skadet fil ikke etterlater en halv bok.
     * @param newUuid gi boka ny uuid (for «Behold begge»).
     * @return id-en til den nye boka.
     */
    suspend fun import(app: BokhylleApp, input: InputStream, newUuid: Boolean, onProgress: (Int) -> Unit = {}): Long {
        val temp = File(app.cacheDir, "import-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            var bookRoot: Map<String, Any?>? = null
            var pagesRoot: List<Any?>? = null
            ZipInputStream(input.buffered()).use { zip ->
                var count = 0
                while (true) {
                    val e = zip.nextEntry ?: break
                    when {
                        e.name == "book.json" -> bookRoot = parseBook(zip.readBytes())
                        e.name == "pages.json" -> pagesRoot = runCatching { Json.parse(String(zip.readBytes())) as? List<Any?> }.getOrNull()
                            ?: throw BackupFormatException("pages.json er skadet")
                        e.name.startsWith(IMAGES) && !e.isDirectory -> {
                            val name = File(e.name).name
                            if (name.contains("..")) throw BackupFormatException("Ugyldig filnavn i arkivet")
                            File(temp, name).outputStream().use { zip.copyTo(it) }
                            onProgress(++count)
                        }
                    }
                }
            }
            val b = bookRoot ?: throw BackupFormatException("Mangler book.json")
            val pagesJson = pagesRoot ?: throw BackupFormatException("Mangler pages.json")

            val bookId = app.db.books().insert(
                Book(
                    tittel = b.str("tittel") ?: "Uten tittel",
                    forfatter = b.str("forfatter") ?: "",
                    opprettet = b.num("opprettet")?.toLong() ?: System.currentTimeMillis(),
                    sistEndret = b.num("sistEndret")?.toLong() ?: System.currentTimeMillis(),
                    claudeModell = b.str("claudeModell") ?: Book(tittel = "").claudeModell,
                    uuid = if (newUuid) UUID.randomUUID().toString() else (b.str("uuid") ?: UUID.randomUUID().toString()),
                    ferdigKontrollert = b.bool("ferdigKontrollert"),
                    bilderSlettet = b.bool("bilderSlettet"),
                ),
            )
            try {
                fillBook(app, bookId, b, pagesJson, temp)
            } catch (e: Exception) {
                app.repo.deleteBook(bookId) // ingen halve bøker
                throw e
            }
            return bookId
        } finally {
            temp.deleteRecursively()
        }
    }

    private suspend fun fillBook(app: BokhylleApp, bookId: Long, b: Map<String, Any?>, pagesJson: List<Any?>, temp: File) {
        val dir = app.files.bookDir(bookId)
        fun place(name: String?): String? {
            val f = name?.let { File(temp, File(it).name) }?.takeIf { it.exists() } ?: return null
            val target = File(dir, f.name)
            if (!f.renameTo(target)) { f.copyTo(target, overwrite = true); f.delete() }
            return target.path
        }
        val placed = mutableMapOf<String, String?>()
        fun image(name: String?) = name?.let { placed.getOrPut(it) { place(it) } }

        val ids = mutableListOf<Long>()
        val pageObjects = pagesJson.mapNotNull { it.obj() }.sortedBy { it.num("index") ?: 0.0 }
        for (o in pageObjects) {
            val layout = PageLayout.decode(o.str("layoutJson"))
            val local = layout?.copy(blocks = layout.blocks.map { blk -> blk.copy(image = image(blk.image), photo = image(blk.photo), drawing = image(blk.drawing)) })
            val picture = image(o.str("bilde"))
            val original = image(o.str("original")) ?: picture
            ids += app.db.pages().insert(
                Page(
                    bookId = bookId,
                    // Uten sidebilde peker stien til en fil som ikke finnes; leseren viser da teksten alene.
                    bildeSti = picture ?: File(dir, "mangler-${UUID.randomUUID()}.webp").path,
                    originalSti = original,
                    ocrTekst = o.str("ocrTekst").orEmpty(),
                    redigertTekst = o.str("redigertTekst"),
                    ryddetTekst = o.str("ryddetTekst"),
                    blocksJson = o.str("blocksJson"),
                    layoutJson = local?.encode(),
                    ocrLinjer = o.str("ocrLinjer"),
                    ocrKilde = o.str("ocrKilde") ?: Page.KILDE_MLKIT,
                    ocrSikkerhet = o.num("ocrSikkerhet")?.toFloat() ?: 0f,
                    oppdagetSidetall = o.str("oppdagetSidetall"),
                    sidetallManuelt = o.bool("sidetallManuelt"),
                    utledetSidetall = o.str("utledetSidetall"),
                    sorteringsIndeks = o.num("sorteringsIndeks")?.toInt() ?: 0,
                    flagget = o.bool("flagget"),
                    flaggGrunner = o.str("flaggGrunner").orEmpty(),
                    godkjent = o.bool("godkjent"),
                    manuellPlassering = o.bool("manuellPlassering"),
                    importBatch = o.num("importBatch")?.toLong() ?: 0L,
                    importIndeks = o.num("importIndeks")?.toInt() ?: 0,
                    fotoTidspunkt = o.num("fotoTidspunkt")?.toLong(),
                    kildeHash = o.str("kildeHash").orEmpty(),
                    trengerClaude = o.bool("trengerClaude"),
                    manuelleOmrader = o.str("manuelleOmrader"),
                    beskjaering = o.str("beskjaering"),
                    // Sider som ikke var ferdig behandlet kan ikke behandles uten bildet.
                    status = if (picture == null && (o.num("status")?.toInt() ?: 1) != Page.STATUS_FERDIG) Page.STATUS_FEIL
                    else (o.num("status")?.toInt() ?: Page.STATUS_FERDIG),
                    sokeTekst = Page.searchText(o.str("redigertTekst") ?: o.str("ryddetTekst") ?: o.str("ocrTekst").orEmpty()),
                ),
            )
        }
        // Peke-felt lagres som indekser i filen; gjør dem om til de nye id-ene.
        pageObjects.forEachIndexed { i, o ->
            val after = o.num("plassertEtter")?.toInt()?.let { ids.getOrNull(it) }
            if (after != null) app.db.pages().get(ids[i])?.let { app.db.pages().update(it.copy(plassertEtter = after)) }
        }
        b.num("omslag")?.toInt()?.let { ids.getOrNull(it) }?.let { app.db.books().setCover(bookId, it) }
        image(b.str("omslagBilde"))?.let { app.db.books().setCoverImage(bookId, it) }
        b.num("sistLest")?.toInt()?.let { ids.getOrNull(it) }?.let { app.db.books().setLastRead(bookId, it) }
        // setLastRead/setCover endrer ikke sistEndret; behold tidspunktet fra filen.
    }
}
