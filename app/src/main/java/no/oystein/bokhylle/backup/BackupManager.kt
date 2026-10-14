package no.oystein.bokhylle.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import no.oystein.bokhylle.BokhylleApp
import no.oystein.bokhylle.core.Json
import no.oystein.bokhylle.core.arr
import no.oystein.bokhylle.core.bool
import no.oystein.bokhylle.core.num
import no.oystein.bokhylle.core.obj
import no.oystein.bokhylle.core.str
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Hvordan en bok i kopien forholder seg til biblioteket. */
enum class CompareStatus(val label: String) {
    NEW("Ny (finnes ikke i appen)"),
    MISSING("Mangler i kopien"),
    OLDER("Eldre i kopien enn i appen"),
    NEWER("Nyere i kopien enn i appen"),
    SAME("Lik"),
}

data class BookCompare(
    val uuid: String,
    val title: String,
    val status: CompareStatus,
    /** Sist endret i kopien / i appen (null hvis den ikke finnes der). */
    val backupChanged: Long?,
    val localChanged: Long?,
    val backupEdited: Int,
    val localEdited: Int,
)

data class RestorePreview(
    val created: Long,
    val beforeRestore: Boolean,
    val books: List<BookInfo>,
    val compare: List<BookCompare>,
) {
    /** Kopien er eldre enn biblioteket: noen bøker er nyere i appen, eller mangler i kopien. */
    val olderThanLibrary: Boolean get() = compare.any { it.status == CompareStatus.OLDER || it.status == CompareStatus.MISSING }

    /** Hva som går tapt med «Erstatt alt»: bøker som ikke er i kopien, og rettede sider i bøker som er eldre i kopien. */
    val lostBooksOnReplace: Int get() = compare.count { it.status == CompareStatus.MISSING }
    val lostEditsOnReplace: Int
        get() = compare.sumOf { c ->
            when (c.status) {
                CompareStatus.MISSING -> c.localEdited
                CompareStatus.OLDER -> (c.localEdited - c.backupEdited).coerceAtLeast(0)
                else -> 0
            }
        }
}

enum class RestoreMode { MERGE, REPLACE_ALL }

/**
 * Sikkerhetskopi av hele biblioteket (.bokhylle-backup): manifest.json, settings.json (leserinnstillinger;
 * aldri API-nøkkelen) og én .bokhylle-fil per bok under books/.
 */
class BackupManager(private val app: BokhylleApp) {
    private val prefs = app.getSharedPreferences("sikkerhetskopi", Context.MODE_PRIVATE)

    var folder: Uri?
        get() = prefs.getString("mappe", null)?.let(Uri::parse)
        set(v) = prefs.edit().putString("mappe", v?.toString()).apply()

    /** 0 = av, 1 = daglig, 7 = ukentlig. */
    var frequencyDays: Int
        get() = prefs.getInt("hyppighet", 0)
        set(v) = prefs.edit().putInt("hyppighet", v).apply()

    var withOriginals: Boolean
        get() = prefs.getBoolean("originaler", true)
        set(v) = prefs.edit().putBoolean("originaler", v).apply()

    var lastSuccess: Long
        get() = prefs.getLong("sist_ok", 0L)
        set(v) = prefs.edit().putLong("sist_ok", v).apply()

    var lastError: String?
        get() = prefs.getString("sist_feil", null)
        set(v) = prefs.edit().putString("sist_feil", v).apply()

    /** Kopien som ble tatt automatisk før siste gjenoppretting (for «Angre»). */
    val preRestoreFile: File get() = File(app.filesDir, "sikkerhet/for-gjenoppretting.$EXTENSION")
    val canUndo: Boolean get() = preRestoreFile.exists()

    suspend fun write(out: OutputStream, beforeRestore: Boolean = false, onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        val books = app.db.books().all()
        val zip = ZipOutputStream(out.buffered())
        val infos = books.map { b ->
            val pages = app.db.pages().forBook(b.id)
            mapOf(
                "uuid" to b.uuid, "tittel" to b.tittel, "forfatter" to b.forfatter, "sistEndret" to b.sistEndret,
                "sider" to pages.size, "rettedeSider" to pages.count { it.redigertTekst != null },
                "fil" to "books/${b.uuid}.${BookPackage.EXTENSION}",
            )
        }
        val manifest = mapOf(
            "format" to FORMAT, "version" to VERSION, "opprettet" to System.currentTimeMillis(),
            "forGjenoppretting" to beforeRestore, "boker" to infos,
        )
        val reader = app.readerPrefs.settings.value
        val settings = mapOf(
            "leser" to mapOf("skrift" to reader.fontSize, "linje" to reader.lineHeight, "serif" to reader.serif, "tema" to reader.theme.name),
        )
        zip.setLevel(Deflater.DEFAULT_COMPRESSION)
        zip.putNextEntry(ZipEntry("manifest.json")); zip.write(Json.write(manifest).toByteArray()); zip.closeEntry()
        zip.putNextEntry(ZipEntry("settings.json")); zip.write(Json.write(settings).toByteArray()); zip.closeEntry()
        zip.setLevel(Deflater.NO_COMPRESSION)
        books.forEachIndexed { i, b ->
            zip.putNextEntry(ZipEntry("books/${b.uuid}.${BookPackage.EXTENSION}"))
            BookPackage.export(app, b.id, NonClosing(zip), withImages = withOriginals)
            zip.closeEntry()
            onProgress(i + 1, books.size)
        }
        zip.finish()
        zip.flush()
    }

    /** Leser manifestet og sammenligner med biblioteket. Kaster [BackupFormatException] for ugyldige filer. */
    suspend fun preview(input: InputStream): RestorePreview {
        val manifest = ZipInputStream(input.buffered()).use { zip ->
            var m: Map<String, Any?>? = null
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.name == "manifest.json") { m = runCatching { Json.parse(String(zip.readBytes())).obj() }.getOrNull(); break }
            }
            m
        } ?: throw BackupFormatException("Filen er ikke en Bokhylle-sikkerhetskopi (mangler manifest)")
        if (manifest.str("format") != FORMAT) throw BackupFormatException("Ukjent filformat")
        val version = manifest.num("version")?.toInt() ?: 0
        if (version > VERSION) throw BackupFormatException("Kopien er laget med en nyere versjon av Bokhylle. Oppdater appen.")
        val books = manifest["boker"].arr().mapNotNull { it.obj() }.map { o ->
            BookInfo(o.str("uuid").orEmpty(), o.str("tittel") ?: "Uten tittel", o.str("forfatter").orEmpty(),
                o.num("sistEndret")?.toLong() ?: 0, o.num("sider")?.toInt() ?: 0, o.num("rettedeSider")?.toInt() ?: 0)
        }
        val local = app.db.books().all()
        val edited = local.associate { b -> b.id to app.db.pages().forBook(b.id).count { it.redigertTekst != null } }
        val byUuid = local.associateBy { it.uuid }
        val compare = books.map { info ->
            val l = byUuid[info.uuid]
            val status = when {
                l == null -> CompareStatus.NEW
                info.changed < l.sistEndret -> CompareStatus.OLDER
                info.changed > l.sistEndret -> CompareStatus.NEWER
                else -> CompareStatus.SAME
            }
            BookCompare(info.uuid, info.title, status, info.changed, l?.sistEndret, info.editedPages, l?.let { edited[it.id] } ?: 0)
        } + local.filter { b -> books.none { it.uuid == b.uuid } }.map { b ->
            BookCompare(b.uuid, b.tittel, CompareStatus.MISSING, null, b.sistEndret, 0, edited[b.id] ?: 0)
        }
        return RestorePreview(manifest.num("opprettet")?.toLong() ?: 0, manifest.bool("forGjenoppretting"), books, compare)
    }

    /**
     * Gjenoppretter fra en kopi. [useBackup] = bøkene (uuid) der kopiens versjon skal erstatte appens ved «Slå sammen».
     * Før noe erstattes tas det en automatisk kopi av biblioteket (unntatt når man angrer).
     */
    suspend fun restore(
        file: File,
        mode: RestoreMode,
        useBackup: Set<String>,
        takeSafetyCopy: Boolean = true,
        onProgress: (String) -> Unit = {},
    ): Int {
        val preview = file.inputStream().use { preview(it) }
        val local = app.db.books().all().associateBy { it.uuid }
        val replaces = mode == RestoreMode.REPLACE_ALL || preview.compare.any { it.uuid in useBackup && local.containsKey(it.uuid) }
        if (replaces && takeSafetyCopy) {
            onProgress("Tar sikkerhetskopi av biblioteket først …")
            safetyCopy()
        }
        if (mode == RestoreMode.REPLACE_ALL) {
            onProgress("Sletter biblioteket …")
            local.values.forEach { app.repo.deleteBook(it.id) }
        }
        var imported = 0
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                when {
                    e.name == "settings.json" -> applySettings(zip.readBytes())
                    e.name.startsWith("books/") && e.name.endsWith(".${BookPackage.EXTENSION}") -> {
                        val uuid = File(e.name).name.removeSuffix(".${BookPackage.EXTENSION}")
                        val existing = if (mode == RestoreMode.REPLACE_ALL) null else local[uuid]
                        if (existing != null && uuid !in useBackup) continue // behold appens versjon
                        val title = preview.books.firstOrNull { it.uuid == uuid }?.title ?: uuid
                        onProgress("Gjenoppretter «$title» …")
                        existing?.let { app.repo.deleteBook(it.id) }
                        BookPackage.import(app, NonClosing(zip), newUuid = false)
                        imported++
                    }
                }
            }
        }
        return imported
    }

    /** Kopi av biblioteket før en gjenoppretting; teller ikke med blant de 3 vanlige kopiene. */
    suspend fun safetyCopy() {
        val target = preRestoreFile
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".tmp")
        tmp.outputStream().use { write(it, beforeRestore = true) }
        tmp.renameTo(target)
        // Også i den valgte mappen, med eget navn så den ikke slettes av rotasjonen.
        folder?.let { uri ->
            runCatching {
                val dir = DocumentFile.fromTreeUri(app, uri) ?: return@runCatching
                val name = "Før gjenoppretting – ${SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.ROOT).format(Date())}.$EXTENSION"
                val doc = dir.createFile("application/octet-stream", name) ?: return@runCatching
                app.contentResolver.openOutputStream(doc.uri)?.use { out -> target.inputStream().use { it.copyTo(out) } }
            }
        }
    }

    /** Vanlig sikkerhetskopi til den valgte mappen. Beholder de [KEEP] siste. */
    suspend fun backupToFolder(onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        val uri = folder ?: throw IllegalStateException("Ingen mappe er valgt")
        val dir = DocumentFile.fromTreeUri(app, uri)?.takeIf { it.canWrite() }
            ?: throw IllegalStateException("Får ikke skrive til mappen (velg den på nytt)")
        val name = "$PREFIX${SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.ROOT).format(Date())}.$EXTENSION"
        val doc = dir.createFile("application/octet-stream", name) ?: throw IllegalStateException("Kunne ikke lage filen")
        try {
            app.contentResolver.openOutputStream(doc.uri)?.use { write(it, onProgress = onProgress) } ?: throw IllegalStateException("Kunne ikke åpne filen")
        } catch (e: Exception) {
            doc.delete()
            throw e
        }
        dir.listFiles().filter { (it.name ?: "").startsWith(PREFIX) && (it.name ?: "").endsWith(".$EXTENSION") }
            .sortedByDescending { it.name }
            .drop(KEEP)
            .forEach { it.delete() }
        lastSuccess = System.currentTimeMillis()
        lastError = null
    }

    private fun applySettings(bytes: ByteArray) {
        val leser = runCatching { Json.parse(String(bytes)).obj()?.get("leser").obj() }.getOrNull() ?: return
        app.readerPrefs.update { s ->
            s.copy(
                fontSize = leser.num("skrift")?.toFloat() ?: s.fontSize,
                lineHeight = leser.num("linje")?.toFloat() ?: s.lineHeight,
                serif = (leser["serif"] as? Boolean) ?: s.serif,
                theme = no.oystein.bokhylle.ui.reader.ReaderTheme.entries.firstOrNull { it.name == leser.str("tema") } ?: s.theme,
            )
        }
    }

    /** Lar en .bokhylle-fil inne i kopien leses/skrives uten at den ytre zip-strømmen lukkes. */
    private class NonClosingOut(out: OutputStream) : java.io.FilterOutputStream(out) {
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun close() = flush()
    }

    private class NonClosingIn(input: InputStream) : FilterInputStream(input) {
        override fun close() {}
    }

    @Suppress("FunctionName")
    private fun NonClosing(out: OutputStream): OutputStream = NonClosingOut(out)

    @Suppress("FunctionName")
    private fun NonClosing(input: InputStream): InputStream = NonClosingIn(input)

    companion object {
        const val FORMAT = "bokhylle-backup"
        const val VERSION = 1
        const val EXTENSION = "bokhylle-backup"
        const val PREFIX = "Bokhylle-"
        const val KEEP = 3

        /** Er filen en hel sikkerhetskopi (manifest.json) eller én bok (book.json)? null = ingen av delene. */
        fun kindOf(input: InputStream): String? = runCatching {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.name == "manifest.json") return@runCatching "backup"
                    if (e.name == "book.json") return@runCatching "book"
                }
                null
            }
        }.getOrNull()
    }
}
