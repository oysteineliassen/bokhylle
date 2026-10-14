package no.oystein.bokhylle.core

/** En side slik sorteringen ser den. */
data class OrderInput(
    val id: Long,
    /** Importen siden kom fra (tidspunkt for importen). Tidligere importer kommer først i fotorekkefølgen. */
    val batch: Long,
    /** Rekkefølgen siden ble valgt/delt i, innenfor importen. */
    val importIndex: Int,
    /** Når bildet ble tatt (EXIF), hvis kjent. */
    val captureTime: Long? = null,
    val number: PageNumber? = null,
    /** Brukeren har flyttet siden selv. Den plasseres da rett etter [manualAfter] (null = først i boka). */
    val manual: Boolean = false,
    val manualAfter: Long? = null,
    /** Brukeren har godkjent siden; den flagges ikke lenger. */
    val accepted: Boolean = false,
)

enum class PageFlag(val description: String) {
    MANGLER_SIDETALL("Fant ikke sidetall"),
    DUPLIKAT("Samme sidetall finnes flere ganger"),
    HOPP("Hopp i sidetallene"),
    TILBAKEGANG("Sidetallet går bakover i forhold til bildene rundt"),
}

/** Sider som ser ut til å mangle mellom to sider i boka. */
data class MissingPages(
    /** Siden de manglende sidene skal stå etter. */
    val afterPageId: Long,
    val from: PageNumber,
    val to: PageNumber,
    /** Hvor mange som mangler. Kan være færre enn intervallet hvis det står sider uten sidetall imellom. */
    val count: Int,
) {
    val label: String get() = if (from == to) from.label else "${from.label}–${to.label}"
}

data class OrderResult(
    val order: List<Long>,
    val flags: Map<Long, Set<PageFlag>>,
    /** Sidetall utledet for sider uten trykt sidetall som passer nøyaktig inn i et hull (f.eks. bildesider). */
    val inferred: Map<Long, PageNumber>,
    val missing: List<MissingPages>,
)

/**
 * Sorterer sidene på sidetall. Sider uten sidetall (eller med et sidetall som åpenbart er feillest) plasseres
 * ved naboen de ble fotografert sammen med. Manuelt flyttede sider blir der brukeren la dem.
 */
object PageOrdering {

    fun order(pages: List<OrderInput>): OrderResult {
        if (pages.isEmpty()) return OrderResult(emptyList(), emptyMap(), emptyMap(), emptyList())
        val byId = pages.associateBy { it.id }

        // 1. Fotorekkefølge: importvis, og innen en import etter EXIF-tid når alle bildene har det.
        val batches = pages.groupBy { it.batch }.toSortedMap()
        val capture = batches.values.map { batch ->
            if (batch.all { it.captureTime != null }) batch.sortedWith(compareBy({ it.captureTime }, { it.importIndex }))
            else batch.sortedBy { it.importIndex }
        }
        val captureIndex = capture.flatten().withIndex().associate { (i, p) -> p.id to i }

        // 2. Sider med sidetall som ikke følger rekkefølgen de ble fotografert i.
        val suspicious = mutableMapOf<Long, PageFlag>()
        for (batch in capture) {
            val numbered = batch.filter { it.number != null && !it.manual }
            val keep = longestNonDecreasing(numbered.map { it.number!! }).map { numbered[it].id }.toSet()
            var previous: PageNumber? = null
            for (p in numbered) {
                if (p.id !in keep) {
                    suspicious[p.id] = if (previous != null && p.number!! < previous) PageFlag.TILBAKEGANG else PageFlag.HOPP
                }
                previous = p.number
            }
        }
        // Et avvikende sidetall stoles likevel på når det passer inn ved siden av andre sider (typisk et bilde tatt
        // på nytt, eller en manglende side lagt til senere). Ellers er det trolig feillest, og siden blir der den ble fotografert.
        val goodNumbers = pages.filter { it.number != null && !it.manual && it.id !in suspicious }.map { it.number!! }.toSet()
        fun fitsIn(n: PageNumber) = n in goodNumbers || n.copy(value = n.value - 1) in goodNumbers || n.next() in goodNumbers
        val trusted = pages.filter { p ->
            val n = p.number ?: return@filter false
            !p.manual && (p.id !in suspicious || fitsIn(n))
        }.sortedWith(compareBy({ it.number }, { captureIndex[it.id] })).map { it.id }
        val trustedSet = trusted.toSet()

        // 3. De andre sidene festes til en nabo: brukerens valg, ellers forrige (eller neste) bilde i samme import.
        val after = mutableMapOf<Long?, MutableList<Long>>() // null-nøkkel = først i boka
        val before = mutableMapOf<Long, MutableList<Long>>()
        val atEnd = mutableListOf<Long>()
        val floating = pages.filter { it.id !in trustedSet }.sortedWith(
            compareBy({ if (it.manual) 0 else 1 }, { captureIndex[it.id] }),
        )
        for (p in floating) {
            if (p.manual && (p.manualAfter == null || p.manualAfter in byId)) {
                after.getOrPut(p.manualAfter) { mutableListOf() } += p.id
                continue
            }
            val batch = capture.first { b -> b.any { it.id == p.id } }
            val i = batch.indexOfFirst { it.id == p.id }
            when {
                i > 0 -> after.getOrPut(batch[i - 1].id) { mutableListOf() } += p.id
                batch.size > 1 -> before.getOrPut(batch[1].id) { mutableListOf() } += p.id
                else -> atEnd += p.id
            }
        }

        val order = mutableListOf<Long>()
        val visited = mutableSetOf<Long>()
        fun emit(id: Long) {
            if (!visited.add(id)) return
            before[id]?.forEach(::emit)
            order += id
            after[id]?.forEach(::emit)
        }
        after[null]?.forEach(::emit)
        trusted.forEach(::emit)
        atEnd.forEach(::emit)
        // Sider som henger i en ring av manuelle plasseringer (skal ikke skje) havner sist i stedet for å forsvinne.
        capture.flatten().forEach { emit(it.id) }

        // 4. Hull i sidetallene: utled sidetall der sidene uten tall passer nøyaktig, ellers meld manglende sider.
        val flags = mutableMapOf<Long, MutableSet<PageFlag>>()
        fun flag(id: Long, f: PageFlag) {
            val p = byId.getValue(id)
            if (!p.accepted && !p.manual) flags.getOrPut(id) { mutableSetOf() } += f
        }
        suspicious.forEach { (id, f) -> flag(id, f) }
        pages.filter { it.number != null }.groupBy { it.number }.values.filter { it.size > 1 }
            .forEach { group -> group.forEach { flag(it.id, PageFlag.DUPLIKAT) } }

        val inferred = mutableMapOf<Long, PageNumber>()
        val missing = mutableListOf<MissingPages>()
        var lastNumbered: Int? = null
        val between = mutableListOf<Long>()
        for ((index, id) in order.withIndex()) {
            val n = byId.getValue(id).number
            if (n == null || id !in trustedSet) {
                if (lastNumbered != null) between += id
                continue
            }
            val prevIndex = lastNumbered
            if (prevIndex != null) {
                val prev = byId.getValue(order[prevIndex]).number!!
                val gap = n.value - prev.value - 1
                if (n.roman == prev.roman && gap > 0) {
                    if (gap == between.size) {
                        between.forEachIndexed { k, b ->
                            if (byId.getValue(b).number == null) inferred[b] = prev.copy(value = prev.value + 1 + k)
                        }
                    } else if (gap > between.size) {
                        missing += MissingPages(
                            afterPageId = order[index - 1],
                            from = prev.next(),
                            to = n.copy(value = n.value - 1),
                            count = gap - between.size,
                        )
                        flag(id, PageFlag.HOPP)
                    }
                }
            }
            lastNumbered = index
            between.clear()
        }
        pages.filter { it.number == null && it.id !in inferred }.forEach { flag(it.id, PageFlag.MANGLER_SIDETALL) }

        return OrderResult(order, flags, inferred, missing)
    }

    /** Indeksene i den lengste ikke-synkende delsekvensen (O(n log n)). Ved likhet foretrekkes de tidligste. */
    internal fun longestNonDecreasing(values: List<PageNumber>): List<Int> {
        if (values.isEmpty()) return emptyList()
        val tailIndex = mutableListOf<Int>()
        val parent = IntArray(values.size) { -1 }
        for (i in values.indices) {
            // Første hale som er strengt større enn values[i].
            var lo = 0
            var hi = tailIndex.size
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (values[tailIndex[mid]] <= values[i]) lo = mid + 1 else hi = mid
            }
            if (lo > 0) parent[i] = tailIndex[lo - 1]
            if (lo == tailIndex.size) tailIndex += i else tailIndex[lo] = i
        }
        val result = mutableListOf<Int>()
        var k = tailIndex.last()
        while (k >= 0) { result += k; k = parent[k] }
        return result.reversed()
    }
}

/** Hvilke sider som er lagt inn, som intervaller: «1–64, 80–112». Romertall (forord) for seg. */
object PageRanges {
    fun describe(numbers: Collection<PageNumber>): String {
        val parts = mutableListOf<String>()
        for (roman in listOf(true, false)) {
            val sorted = numbers.filter { it.roman == roman }.map { it.value }.distinct().sorted()
            var i = 0
            while (i < sorted.size) {
                var j = i
                while (j + 1 < sorted.size && sorted[j + 1] == sorted[j] + 1) j++
                val a = PageNumber(sorted[i], roman).label
                val b = PageNumber(sorted[j], roman).label
                parts += if (i == j) a else "$a–$b"
                i = j + 1
            }
        }
        return parts.joinToString(", ")
    }
}
