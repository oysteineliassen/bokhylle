package no.oystein.bokhylle.core

/**
 * Enkel tekstform av et sideoppsett, brukt når brukeren retter teksten:
 *
 *     # Overskrift
 *
 *     Vanlig avsnitt med fotnote[^1].
 *
 *     [boks: Tittel på faktaboks]
 *     Innhold i boksen.
 *     [/boks]
 *
 *     [figur: Beskrivelse av figuren]
 *
 *     [bildetekst: Teksten under bildet]
 *
 *     [^1]: Fotnoteteksten.
 *
 * Blokker skilles med blank linje. Figurer beholder bildet sitt (de kobles i rekkefølge).
 */
object LayoutMarkup {
    private val figure = Regex("^\\[figur:\\s*(.*)]$", RegexOption.DOT_MATCHES_ALL)
    private val caption = Regex("^\\[bildetekst:\\s*(.*)]$", RegexOption.DOT_MATCHES_ALL)
    private val boxStart = Regex("^\\[boks(?::\\s*(.*?))?]$")
    private val footnote = Regex("^\\[\\^([^\\]]+)]:\\s*(.*)$", RegexOption.DOT_MATCHES_ALL)

    fun toMarkup(layout: PageLayout): String = layout.blocks.joinToString("\n\n") { b ->
        when (b.type) {
            BlockType.HEADING -> "# ${b.text}"
            BlockType.PARAGRAPH -> b.text
            BlockType.BOX -> buildString {
                append(if (b.title != null) "[boks: ${b.title}]" else "[boks]")
                if (b.text.isNotBlank()) append("\n").append(b.text)
                append("\n[/boks]")
            }
            BlockType.FIGURE -> "[figur: ${b.description.orEmpty()}]"
            BlockType.CAPTION -> "[bildetekst: ${b.text}]"
            BlockType.FOOTNOTE -> "[^${b.number ?: "?"}]: ${b.text}"
        }
    }

    fun parse(text: String, original: PageLayout?): PageLayout {
        val figures = ArrayDeque(original?.blocks.orEmpty().filter { it.type == BlockType.FIGURE })
        val boxes = ArrayDeque(original?.blocks.orEmpty().filter { it.type == BlockType.BOX })
        val blocks = mutableListOf<LayoutBlock>()
        val lines = text.replace("\r\n", "\n").lines()
        var i = 0
        val para = StringBuilder()
        fun flushPara() {
            val chunk = para.toString().trim()
            para.clear()
            if (chunk.isEmpty()) return
            blocks += when {
                chunk.startsWith("# ") -> LayoutBlock(BlockType.HEADING, chunk.removePrefix("# ").trim())
                figure.matches(chunk) -> {
                    val old = figures.removeFirstOrNull()
                    (old ?: LayoutBlock(BlockType.FIGURE)).copy(description = figure.matchEntire(chunk)!!.groupValues[1].trim().ifEmpty { null })
                }
                caption.matches(chunk) -> LayoutBlock(BlockType.CAPTION, caption.matchEntire(chunk)!!.groupValues[1].trim())
                footnote.matches(chunk) -> footnote.matchEntire(chunk)!!.let { m ->
                    LayoutBlock(BlockType.FOOTNOTE, m.groupValues[2].trim(), number = m.groupValues[1])
                }
                else -> LayoutBlock(BlockType.PARAGRAPH, chunk.lines().joinToString(" ") { it.trim() })
            }
        }
        while (i < lines.size) {
            val line = lines[i].trim()
            val start = boxStart.matchEntire(line)
            when {
                start != null -> {
                    flushPara()
                    val body = mutableListOf<String>()
                    i++
                    while (i < lines.size && lines[i].trim() != "[/boks]") { body += lines[i]; i++ }
                    val old = boxes.removeFirstOrNull()
                    val paragraphs = body.joinToString("\n").split(Regex("\\n\\s*\\n")).map { p -> p.lines().joinToString(" ") { it.trim() }.trim() }.filter { it.isNotEmpty() }
                    blocks += (old ?: LayoutBlock(BlockType.BOX)).copy(
                        title = start.groupValues.getOrNull(1)?.trim()?.ifEmpty { null },
                        text = paragraphs.joinToString("\n\n"),
                    )
                }
                line.isEmpty() -> flushPara()
                else -> para.append(lines[i]).append('\n')
            }
            i++
        }
        flushPara()
        return (original ?: PageLayout(emptyList())).copy(blocks = blocks)
    }
}

object LayoutOps {
    /**
     * Brukerens markerte områder i et oppsett fra Claude: blokker som ligger i området tas ut av brødteksten
     * og blir en figur eller faktaboks. (ML Kit-oppsett får områdene direkte i [LayoutBuilder].)
     */
    fun applyRegions(layout: PageLayout, regions: List<ManualRegion>): PageLayout {
        if (regions.isEmpty()) return layout
        var blocks = layout.blocks
        for (region in regions) {
            val inside = blocks.filter { b -> b.box != null && region.box.contains(b.box.centerX, b.box.centerY) }
            if (inside.isEmpty() && region.type == BlockType.BOX) continue
            val index = blocks.indexOfFirst { it in inside }.let { if (it < 0) insertIndex(blocks, region.box) else it }
            val texts = inside.map { b -> listOfNotNull(b.title, b.text.ifEmpty { null }, b.description).joinToString(" ") }.filter { it.isNotBlank() }
            val newBlock = when (region.type) {
                BlockType.BOX -> {
                    val heading = inside.firstOrNull { it.type == BlockType.HEADING }
                    LayoutBlock(
                        BlockType.BOX,
                        title = heading?.text ?: inside.firstOrNull { it.type == BlockType.BOX }?.title,
                        text = inside.filter { it !== heading }.map { it.text }.filter { it.isNotBlank() }.joinToString("\n\n"),
                        box = region.box, manual = true,
                    )
                }
                else -> LayoutBlock(
                    BlockType.FIGURE,
                    description = inside.firstOrNull { it.type == BlockType.FIGURE }?.description
                        ?: texts.takeIf { it.isNotEmpty() }?.let { "Tekst i figuren: " + it.joinToString(", ") },
                    box = region.box, manual = true,
                )
            }
            val rest = blocks.filter { it !in inside }.toMutableList()
            rest.add(index.coerceIn(0, rest.size), newBlock)
            blocks = rest
        }
        return layout.copy(blocks = blocks)
    }

    private fun insertIndex(blocks: List<LayoutBlock>, box: Box): Int {
        val i = blocks.indexOfFirst { it.box != null && it.box.top > box.top }
        return if (i < 0) blocks.size else i
    }
}
