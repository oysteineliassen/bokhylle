package no.oystein.bokhylle.core

/**
 * Boka som ren tekst (.txt): teksten slik leseren viser den (avsnitt samlet over sideskift), med overskrifter
 * understreket, faktabokser markert med «|», figurer som en linje med beskrivelsen, og fotnoter samlet som
 * «Noter» før hvert nytt hovedkapittel.
 */
object TextExport {
    fun render(title: String, author: String, pages: List<PageLayout>): String {
        val out = StringBuilder()
        out.append(title).append('\n')
        if (author.isNotBlank()) out.append(author).append('\n')
        out.append('\n')
        val pendingNotes = mutableListOf<String>()

        fun flushNotes() {
            if (pendingNotes.isEmpty()) return
            out.append("Noter\n-----\n")
            pendingNotes.forEach { out.append(it).append('\n') }
            out.append('\n')
            pendingNotes.clear()
        }

        fun withNoteMarks(b: ReadingFlow.Block): String {
            val sb = StringBuilder(b.text)
            for (n in b.notes.sortedByDescending { it.start }) {
                sb.replace(n.start, n.end, "[${n.number}]")
                val note = pages.getOrNull(n.page)?.blocks?.firstOrNull { it.type == BlockType.FOOTNOTE && it.number == n.number }
                if (note != null) pendingNotes.add(0, "[${n.number}] ${note.text.trim()}")
            }
            return sb.toString()
        }

        fun underline(text: String, c: Char) {
            val line = text.replace('\n', ' ').trim()
            out.append(line).append('\n').append(c.toString().repeat(line.length.coerceIn(3, 60))).append("\n\n")
        }

        for (b in ReadingFlow.build(pages)) {
            val chapter = b.type == BlockType.HEADING && (b.level == 1 || (b.level == 0 && b.breakBefore))
            if (chapter) {
                flushNotes()
                out.append("\n\n")
            }
            when (b.type) {
                BlockType.HEADING -> underline(withNoteMarks(b), if (chapter) '=' else '-')
                BlockType.BOX -> {
                    b.title?.let { out.append("| ").append(it.trim()).append("\n|\n") }
                    withNoteMarks(b).trim().lines().forEach { out.append("| ").append(it).append('\n') }
                    out.append('\n')
                }
                BlockType.FIGURE -> {
                    val d = b.description?.takeIf { it.isNotBlank() }?.replace('\n', ' ')
                    out.append(if (d != null) "[Figur: $d]" else "[Figur]").append("\n\n")
                }
                BlockType.FOOTNOTE -> Unit
                else -> {
                    val t = withNoteMarks(b).trim()
                    if (t.isNotEmpty()) out.append(t).append("\n\n")
                }
            }
        }
        flushNotes()
        return out.toString().trimEnd() + "\n"
    }
}
