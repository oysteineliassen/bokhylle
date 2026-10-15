package no.oystein.bokhylle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import no.oystein.bokhylle.core.Json
import no.oystein.bokhylle.core.arr
import no.oystein.bokhylle.core.obj
import no.oystein.bokhylle.data.Page
import no.oystein.bokhylle.image.ImageOps
import java.io.File
import java.io.FileOutputStream

/**
 * Brukes av røyktesten (bokhylle/test/smoke.mjs):
 * TEST_IMPORT lager en testbok av syntetiske, skjevt «fotograferte» boksider og importerer dem i tilfeldig rekkefølge,
 * TEST_REPORT skriver bokas tilstand til loggen som JSON.
 */
class TestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.app
        app.appScope.launch {
            try {
                when (intent.action) {
                    "no.oystein.bokhylle.TEST_IMPORT" -> testImport(app)
                    "no.oystein.bokhylle.TEST_REPORT" -> report(app, intent.getStringExtra("title") ?: TITLE)
                    "no.oystein.bokhylle.TEST_CLAUDE" -> testClaude(app, intent.getStringExtra("mode") ?: "batch")
                    "no.oystein.bokhylle.TEST_BACKUP" -> testBackup(app)
                    "no.oystein.bokhylle.TEST_DRAW" -> testDraw(app)
                    "no.oystein.bokhylle.TEST_FIGURES" -> testFigureExchange(app)
                    "no.oystein.bokhylle.TEST_RATE" -> {
                        val r = no.oystein.bokhylle.claude.ExchangeRates.refresh(app)
                        Log.d(TAG, "test: kurs ${r?.nokPerUnit} dato ${r?.date} format=${no.oystein.bokhylle.claude.formatUsd(0.05)}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "test feilet", e)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun testImport(app: BokhylleApp) {
        val dir = File(app.cacheDir, "testsider").apply { deleteRecursively(); mkdirs() }
        // Side 5 mangler med vilje. Rekkefølgen er stokket, og side 2 er med to ganger (samme fil).
        val numbers = listOf(3, 1, 2, 4, 7, 6, 8)
        val files = numbers.map { n ->
            // Side 7 er fotografert liggende (uten EXIF-retning, som når telefonen ligger flatt): appen må snu den selv.
            val bitmap = if (n == 7) ImageOps.rotate(drawPage(n), 90) else drawPage(n)
            File(dir, "side-$n.jpg").also { f -> FileOutputStream(f).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
        }
        // Et oppslag med side 9 og 10 i samme bilde, også liggende (motsatt vei), så ryggen går på tvers av bildet.
        val spread = File(dir, "oppslag-9-10.jpg").also { f -> FileOutputStream(f).use { ImageOps.rotate(drawSpread(9), 270).compress(Bitmap.CompressFormat.JPEG, 90, it) } }
        val uris = files.map { Uri.fromFile(it) } + Uri.fromFile(files[2]) + Uri.fromFile(spread)
        val bookId = app.repo.createBook(TITLE, "Test Testesen")
        val result = app.importer.import(bookId, uris)
        Log.d(TAG, "test: importert ${result.added} nye, ${result.duplicates} duplikater")
    }

    /** Peker Claude-klienten mot testserveren (adb reverse) og sender testboka. */
    private suspend fun testClaude(app: BokhylleApp, mode: String) {
        app.claude.baseUrl = "http://127.0.0.1:8089"
        app.claude.keys.set("test-nokkel-1234")
        val book = app.db.books().all().firstOrNull { it.tittel == TITLE } ?: return
        val pages = app.db.pages().forBook(book.id)
        when (mode) {
            "single" -> app.claude.runPage(book.id, pages.first().id)
            "poll" -> app.claude.schedulePoll(0, replace = true)
            else -> app.claude.submitBook(book.id, pages.map { it.id })
        }
        Log.d(TAG, "test: claude $mode")
    }

    private fun drawings(pages: List<Page>) = pages.sumOf { p ->
        no.oystein.bokhylle.core.PageLayout.decode(p.layoutJson)?.blocks.orEmpty()
            .count { no.oystein.bokhylle.data.PageImages.isDrawing(it.image) && File(it.image!!).exists() }
    }

    /**
     * «Tegn diagrammene på nytt» for hele testboka (mot testserveren), så tilbake til fotoet for én figur.
     * Resultatet skrives til loggen («test: tegning …»).
     */
    private suspend fun testDraw(app: BokhylleApp) {
        val book = app.db.books().all().first { it.tittel == TITLE }
        val before = no.oystein.bokhylle.claude.DrawWorker.candidates(app, app.db.pages().forBook(book.id))
        no.oystein.bokhylle.claude.DrawWorker.book(app, book.id)
        val wm = androidx.work.WorkManager.getInstance(app)
        val info = wm.getWorkInfosForUniqueWorkFlow(no.oystein.bokhylle.claude.DrawWorker.bookWorkName(book.id))
            .first { list -> list.firstOrNull()?.state?.isFinished == true }.first()
        val pages = app.db.pages().forBook(book.id)
        val drawn = drawings(pages)
        // Bildet tegningen ble til, og fotoet som er tatt vare på.
        val block = pages.flatMap { no.oystein.bokhylle.core.PageLayout.decode(it.layoutJson)?.blocks.orEmpty().map { b -> it to b } }
            .firstOrNull { (_, b) -> no.oystein.bokhylle.data.PageImages.isDrawing(b.image) }
        val size = block?.second?.image?.let { path ->
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(path, o)
            "${o.outWidth}x${o.outHeight}"
        }
        val photoKept = block?.second?.photo?.let { File(it).exists() } == true
        // «Bruk fotoet igjen» legger tegningen til side; «Bruk tegningen igjen» tar den tilbake. Så fotoet igjen.
        block?.let { (p, b) -> app.repo.usePhoto(p.id, b.image!!) }
        val parked = block?.let { (p, b) ->
            no.oystein.bokhylle.core.PageLayout.decode(app.db.pages().get(p.id)?.layoutJson)?.blocks
                ?.firstOrNull { it.image == b.photo }?.drawing == b.image && File(b.image!!).exists()
        } == true
        block?.let { (p, b) -> app.repo.useParkedDrawing(p.id, b.photo!!) }
        val again = drawings(app.db.pages().forBook(book.id))
        block?.let { (p, b) -> app.repo.usePhoto(p.id, b.image!!) }
        val after = drawings(app.db.pages().forBook(book.id))
        Log.d(TAG, "test: tegning kandidater=$before status=${info.state} tegnet=$drawn bilde=$size foto=$photoKept " +
            "tilside=$parked igjen=$again etter=$after")
    }

    /**
     * Figurer via chat: eksporter figurfilen, lag en tegningsfil slik Claude i en chat ville gjort (en SVG per figur),
     * og importer den. Resultatet skrives til loggen («test: figurfil …»).
     */
    private suspend fun testFigureExchange(app: BokhylleApp) {
        val book = app.db.books().all().first { it.tittel == TITLE }
        val dir = File(app.cacheDir, "figurtest").apply { deleteRecursively(); mkdirs() }
        val figFile = File(dir, "bok.${no.oystein.bokhylle.backup.FigureExchange.FIGURES_EXTENSION}")
        val exported = figFile.outputStream().use { no.oystein.bokhylle.backup.FigureExchange.export(app, book.id, it) }
        var manifest: Map<String, Any?>? = null
        var contexts = 0
        var readme = false
        var tool = false
        java.util.zip.ZipInputStream(figFile.inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.name == "figurer.json") manifest = no.oystein.bokhylle.core.Json.parse(String(zip.readBytes())).obj()
                if (e.name.startsWith("kontekst/")) contexts++
                if (e.name == "LES-MEG.md") readme = String(zip.readBytes()).contains("tegninger.py pakk")
                if (e.name == "tegninger.py") tool = String(zip.readBytes()).contains("def pakk")
            }
        }
        val keys = manifest?.get("figures").arr().mapNotNull { it.obj()?.get("key") as? String }
        val drawFile = File(dir, "bok.${no.oystein.bokhylle.backup.FigureExchange.DRAWINGS_EXTENSION}")
        java.util.zip.ZipOutputStream(drawFile.outputStream()).use { zip ->
            val json = no.oystein.bokhylle.core.Json.write(
                mapOf(
                    "format" to "bokhylle-tegninger", "version" to 1, "book" to manifest?.get("book"),
                    "drawings" to keys.map { mapOf("key" to it, "svg" to "tegninger/$it.svg") } + mapOf("key" to "finnes-ikke", "svg" to "tegninger/x.svg"),
                ),
            )
            zip.putNextEntry(java.util.zip.ZipEntry("tegninger.json")); zip.write(json.toByteArray()); zip.closeEntry()
            for (k in keys) {
                zip.putNextEntry(java.util.zip.ZipEntry("tegninger/$k.svg"))
                zip.write("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 200 120\"><rect width=\"200\" height=\"120\" fill=\"#F4C766\"/><circle cx=\"100\" cy=\"60\" r=\"40\" fill=\"#E8A33C\"/></svg>".toByteArray())
                zip.closeEntry()
            }
        }
        val result = drawFile.inputStream().use { no.oystein.bokhylle.backup.FigureExchange.importDrawings(app, book.id, it) }
        val wrongBook = runCatching {
            val other = app.repo.createBook("Annen bok", "")
            try { drawFile.inputStream().use { no.oystein.bokhylle.backup.FigureExchange.importDrawings(app, other, it) } } finally { app.repo.deleteBook(other) }
        }.isFailure
        Log.d(TAG, "test: figurfil eksportert=$exported kontekst=$contexts importert=${result.added} hoppet=${result.skipped} " +
            "annenbok=$wrongBook oppskrift=${readme && tool} tegninger=${drawings(app.db.pages().forBook(book.id))}")
    }

    /**
     * Eksporterer testboka som .bokhylle og importerer den igjen som kopi, lager EPUB, og tar en sikkerhetskopi
     * av biblioteket og forhåndsviser den. Resultatet skrives til loggen («test: backup …»).
     */
    private suspend fun testBackup(app: BokhylleApp) {
        val book = app.db.books().all().first { it.tittel == TITLE }
        val pages = app.db.pages().forBook(book.id)
        val dir = File(app.cacheDir, "testkopi").apply { deleteRecursively(); mkdirs() }
        val bookFile = File(dir, "bok.bokhylle")
        bookFile.outputStream().use { no.oystein.bokhylle.backup.BookPackage.export(app, book.id, it, withImages = false) }
        val info = bookFile.inputStream().use { no.oystein.bokhylle.backup.BookPackage.peek(it) }
        val copyId = bookFile.inputStream().use { no.oystein.bokhylle.backup.BookPackage.import(app, it, newUuid = true) }
        val copy = app.db.pages().forBook(copyId)
        val sameText = copy.map { it.ryddetTekst } == pages.map { it.ryddetTekst }
        val figures = copy.flatMap { no.oystein.bokhylle.data.PageImages.figurePaths(it) }.count { File(it).exists() }
        // Tegnede figurer skal følge med (også etter at kopien er bygget opp på nytt).
        app.processor.rebuild(copyId)
        val drawingsOk = drawings(app.db.pages().forBook(copyId)) == drawings(pages)
        val epub = File(dir, "bok.epub")
        epub.outputStream().use { no.oystein.bokhylle.backup.EpubExporter.export(app, book.id, it) }
        val backupFile = File(dir, "alt.bokhylle-backup")
        backupFile.outputStream().use { app.backup.write(it) }
        val preview = backupFile.inputStream().use { app.backup.preview(it) }
        // Skadet fil skal gi en feilmelding, ikke krasj.
        val broken = File(dir, "skadet.bokhylle").apply { writeBytes(bookFile.readBytes().copyOf(200)) }
        val brokenOk = runCatching { broken.inputStream().use { no.oystein.bokhylle.backup.BookPackage.import(app, it, newUuid = true) } }.isFailure
        val booksAfter = app.db.books().all().size
        Log.d(
            TAG,
            "test: backup uuid=${info.uuid == book.uuid} sider=${copy.size}/${pages.size} tekst=$sameText figurer=$figures " +
                "epub=${epub.length()} kopi=${preview.books.size} like=${preview.compare.count { it.status.name == "SAME" }} " +
                "skadet=$brokenOk bøker=$booksAfter tegninger=$drawingsOk",
        )
        app.repo.deleteBook(copyId)
    }

    private suspend fun report(app: BokhylleApp, title: String) {
        val book = app.db.books().all().firstOrNull { it.tittel == title }
        if (book == null) { Log.d(TAG, "RAPPORT {}"); return }
        val pages = app.db.pages().forBook(book.id)
        val usage = app.db.claude().observeBookTotal(book.id).first()
        val json = Json.write(
            mapOf(
                "usage" to mapOf("input" to usage.inputTokens, "output" to usage.outputTokens, "usd" to usage.kostnadUsd),
                "openJobs" to app.db.claude().openJobs().size,
                "pages" to pages.map { p ->
                    mapOf(
                        "id" to p.id, "status" to p.status, "number" to p.oppdagetSidetall, "inferred" to p.utledetSidetall,
                        "flags" to p.flaggGrunner, "flagged" to p.flagget, "crop" to p.beskjaering,
                        "source" to p.ocrKilde, "text" to (p.redigertTekst ?: p.ryddetTekst ?: p.ocrTekst).take(300),
                        "layout" to (p.layoutJson?.take(5000) ?: ""),
                    )
                },
            ),
        )
        // Loggen kutter lange linjer; del opp.
        json.chunked(3000).forEachIndexed { i, part -> Log.d(TAG, "RAPPORT[$i] $part") }
        Log.d(TAG, "RAPPORT-SLUTT ${(json.length + 2999) / 3000}")
    }

    /** Et oppslag (to sider ved siden av hverandre, mørk rygg i midten) fotografert rett ovenfra. */
    private fun drawSpread(left: Int): Bitmap {
        val a = drawContent(left)
        val b = drawContent(left + 1)
        val photo = Bitmap.createBitmap(2300, 1650, Bitmap.Config.ARGB_8888)
        val c = Canvas(photo)
        c.drawColor(Color.rgb(55, 45, 40))
        c.drawBitmap(a, 140f, 100f, null)
        c.drawBitmap(b, 1160f, 100f, null)
        // Skygge i ryggen.
        c.drawRect(1120f, 100f, 1180f, 100f + a.height, Paint().apply { color = Color.rgb(150, 145, 135) })
        a.recycle(); b.recycle()
        return photo
    }

    /** Selve boksiden: topptekst med sidetall, avsnitt med innrykk, og en faktaboks på side 3. */
    private fun drawContent(n: Int): Bitmap {
        val pw = 1000; val ph = 1450
        val page = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        val c = Canvas(page)
        c.drawColor(Color.rgb(250, 248, 240))
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(20, 20, 20); textSize = 30f; typeface = Typeface.SERIF }
        val small = Paint(text).apply { textSize = 24f }
        val header = if (n % 2 == 0) "$n          TESTBOKA" else "KAPITTEL EN          $n"
        c.drawText(header, if (n % 2 == 0) 80f else 560f, 90f, small)
        var y = 170f
        // Siste linje er ulik på hver side, ellers ligner den en løpende bunntekst.
        val body = BODY[(n - 1) % BODY.size] + ">Her slutter side ${WORDS[n % WORDS.size]} av fortellingen."
        for ((i, line) in body.withIndex()) {
            val indent = if (line.startsWith(">")) 40f else 0f
            c.drawText(line.removePrefix(">"), 80f + indent, y, text)
            y += 46f
            if (i == 6 && n == 3) {
                // Faktaboks med farget bakgrunn.
                val box = Paint().apply { color = Color.rgb(205, 228, 240) }
                c.drawRect(70f, y, 930f, y + 230f, box)
                c.drawText("Fakta om bøker", 100f, y + 50f, Paint(text).apply { isFakeBoldText = true })
                c.drawText("Den eldste trykte boka er over tusen år", 100f, y + 105f, text)
                c.drawText("gammel og ligger i et museum i London.", 100f, y + 150f, text)
                y += 290f
            }
        }
        return page
    }

    /** En bokside med topptekst og sidetall, fotografert litt skjevt på et mørkt bord, med kanten av nabosiden synlig. */
    private fun drawPage(n: Int): Bitmap {
        val page = drawContent(n)
        val pw = page.width; val ph = page.height

        // «Fotografi»: mørkt bord, siden litt rotert, og en stripe av nabosiden til venstre.
        val photo = Bitmap.createBitmap(1300, 1800, Bitmap.Config.ARGB_8888)
        val pc = Canvas(photo)
        pc.drawColor(Color.rgb(60, 50, 45))
        pc.drawRect(0f, 160f, 150f, 1640f, Paint().apply { color = Color.rgb(240, 238, 230) })
        pc.drawRect(130f, 160f, 160f, 1640f, Paint().apply { color = Color.rgb(110, 100, 95) })
        pc.save()
        pc.translate(160f, 170f)
        pc.rotate(if (n % 2 == 0) 2.5f else -2f, pw / 2f, ph / 2f)
        pc.drawBitmap(page, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        pc.restore()
        page.recycle()
        return photo
    }

    companion object {
        private const val TAG = "Bokhylle"
        const val TITLE = "Testbok OCR"

        private val WORDS = listOf("null", "en", "to", "tre", "fire", "fem", "seks", "sju", "åtte", "ni", "ti", "elleve")

        // Linjer som begynner med «>» har innrykk (nytt avsnitt).
        private val BODY = listOf(
            listOf(
                ">Det var en gang en liten bok som sto i en",
                "stor hylle. Den var full av ord som ingen",
                "hadde lest på mange år, og den ventet.",
                ">Så kom det en leser inn i rommet. Hun tok",
                "boka ned fra hyllen og blåste støvet av den.",
                "Hun satte seg i en god stol ved vinduet og",
                "begynte å lese den gamle fortellingen.",
                ">Ute regnet det, men inne var det varmt.",
            ),
            listOf(
                ">Fortellingen handlet om en gammel bok-",
                "handel i en smal gate i byen. Der jobbet",
                "en mann som kjente alle bøkene sine.",
                ">Hver morgen låste han opp døren og satte",
                "kaffen på. Han likte lukten av papir og",
                "lyden av sider som ble bladd i.",
                ">En dag kom det en kunde han aldri hadde",
                "sett før, og hun spurte etter en bok.",
            ),
        )
    }
}
