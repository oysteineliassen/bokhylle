package no.oystein.bokhylle.core

/** En valutakurs: [nokPerUnit] kroner per enhet, notert på [date] (ÅÅÅÅ-MM-DD). */
data class ExchangeRate(val nokPerUnit: Double, val date: String)

/**
 * Leser dollarkursen fra Norges Banks åpne API (SDMX-JSON):
 * https://data.norges-bank.no/api/data/EXR/B.USD.NOK.SP?format=sdmx-json&lastNObservations=1
 * Tolerant for rekkefølgen på feltene: tar siste observasjon og tilhørende dato.
 */
object NorgesBankRate {
    const val USD_URL = "https://data.norges-bank.no/api/data/EXR/B.USD.NOK.SP?format=sdmx-json&lastNObservations=1"

    fun parse(json: String): ExchangeRate? {
        val root = runCatching { Json.parse(json) }.getOrNull() ?: return null
        val observations = findKey(root, "observations")?.obj() ?: return null
        // Nøklene er indekser («0», «1», …) inn i TIME_PERIOD-listen.
        val (index, value) = observations.entries.maxByOrNull { it.key.toIntOrNull() ?: -1 } ?: return null
        val raw = value.arr().firstOrNull() ?: return null
        val rate = (raw as? Number)?.toDouble() ?: raw.toString().replace(',', '.').toDoubleOrNull() ?: return null
        // Enhetsmultiplikator (f.eks. 100 for JPY); for USD er den 0 (= 1).
        val unitMult = findUnitMult(root)
        val perUnit = rate / Math.pow(10.0, unitMult.toDouble())
        if (perUnit !in 1.0..100.0) return null
        val dates = findTimePeriods(root)
        val date = dates.getOrNull(index.toIntOrNull() ?: -1) ?: dates.lastOrNull() ?: ""
        return ExchangeRate(perUnit, date)
    }

    private fun findKey(node: Any?, key: String): Any? {
        when (node) {
            is Map<*, *> -> {
                node[key]?.let { return it }
                for (v in node.values) findKey(v, key)?.let { return it }
            }
            is List<*> -> for (v in node) findKey(v, key)?.let { return it }
        }
        return null
    }

    /** Verdiene i dimensjonen TIME_PERIOD (datoene), i rekkefølge. */
    private fun findTimePeriods(node: Any?): List<String> {
        when (node) {
            is Map<*, *> -> {
                if (node["id"] == "TIME_PERIOD") {
                    return node["values"].arr().mapNotNull { v -> v.obj()?.let { (it["id"] ?: it["name"])?.toString() } }
                }
                for (v in node.values) findTimePeriods(v).takeIf { it.isNotEmpty() }?.let { return it }
            }
            is List<*> -> for (v in node) findTimePeriods(v).takeIf { it.isNotEmpty() }?.let { return it }
        }
        return emptyList()
    }

    private fun findUnitMult(node: Any?): Int {
        val attr = findAttribute(node, "UNIT_MULT") ?: return 0
        val first = attr["values"].arr().firstOrNull()?.obj() ?: return 0
        return (first["id"] ?: first["name"])?.toString()?.toIntOrNull() ?: 0
    }

    private fun findAttribute(node: Any?, id: String): Map<String, Any?>? {
        when (node) {
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                if (node["id"] == id && node["values"] != null) return node as Map<String, Any?>
                for (v in node.values) findAttribute(v, id)?.let { return it }
            }
            is List<*> -> for (v in node) findAttribute(v, id)?.let { return it }
        }
        return null
    }
}
