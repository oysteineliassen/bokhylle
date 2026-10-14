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
import no.oystein.bokhylle.data.Page
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
            File(dir, "side-$n.jpg").also { f -> FileOutputStream(f).use { drawPage(n).compress(Bitmap.CompressFormat.JPEG, 90, it) } }
        }
        // Et oppslag med side 9 og 10 i samme bilde.
        val spread = File(dir, "oppslag-9-10.jpg").also { f -> FileOutputStream(f).use { drawSpread(9).compress(Bitmap.CompressFormat.JPEG, 90, it) } }
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
                        "layout" to (p.layoutJson?.take(600) ?: ""),
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
        val body = BODY[(n - 1) % BODY.size]
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
