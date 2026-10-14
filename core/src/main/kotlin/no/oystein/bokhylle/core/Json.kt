package no.oystein.bokhylle.core

/**
 * Minimal JSON-leser/-skriver, så kjernemodulen klarer seg uten Android (org.json) og kan testes på JVM.
 * Objekter blir [Map], lister [List], tall [Double], ellers String/Boolean/null.
 */
object Json {
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i != text.length) throw IllegalArgumentException("Uventet tegn ved ${p.i}")
        return v
    }

    fun write(value: Any?): String = StringBuilder().also { write(value, it) }.toString()

    private fun write(v: Any?, out: StringBuilder) {
        when (v) {
            null -> out.append("null")
            is String -> quote(v, out)
            is Boolean -> out.append(v)
            is Float -> num(v.toDouble(), out)
            is Double -> num(v, out)
            is Number -> out.append(v.toLong())
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, value) in v) {
                    if (value == null) continue
                    if (!first) out.append(',')
                    first = false
                    quote(k.toString(), out); out.append(':'); write(value, out)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                v.forEachIndexed { i, x -> if (i > 0) out.append(','); write(x, out) }
                out.append(']')
            }
            else -> quote(v.toString(), out)
        }
    }

    private fun num(d: Double, out: StringBuilder) {
        if (d.isNaN() || d.isInfinite()) out.append("null")
        else if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) out.append(d.toLong())
        else out.append(((d * 10000).let(Math::rint) / 10000).toString())
    }

    private fun quote(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) when (c) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            else -> if (c < ' ') out.append(String.format("\\u%04x", c.code)) else out.append(c)
        }
        out.append('"')
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

        fun value(): Any? {
            if (i >= s.length) throw IllegalArgumentException("Uventet slutt")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("Uventet '$c' ved $i")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw IllegalArgumentException("Ventet $word ved $i")
            i += word.length
            return v
        }

        private fun num(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDouble()
        }

        private fun str(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw IllegalArgumentException("Uavsluttet streng")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun arr(): List<Any?> {
            i++
            val list = mutableListOf<Any?>()
            ws()
            if (s[i] == ']') { i++; return list }
            while (true) {
                ws(); list += value(); ws()
                when (s[i++]) { ',' -> continue; ']' -> return list; else -> throw IllegalArgumentException("Ventet , eller ] ved ${i - 1}") }
            }
        }

        private fun obj(): Map<String, Any?> {
            i++
            val map = LinkedHashMap<String, Any?>()
            ws()
            if (s[i] == '}') { i++; return map }
            while (true) {
                ws(); val k = str(); ws()
                if (s[i++] != ':') throw IllegalArgumentException("Ventet : ved ${i - 1}")
                ws(); map[k] = value(); ws()
                when (s[i++]) { ',' -> continue; '}' -> return map; else -> throw IllegalArgumentException("Ventet , eller } ved ${i - 1}") }
            }
        }
    }
}

// Hjelpere for å lese JSON-verdier trygt.
@Suppress("UNCHECKED_CAST")
fun Any?.obj(): Map<String, Any?>? = this as? Map<String, Any?>
fun Any?.arr(): List<Any?> = this as? List<Any?> ?: emptyList()
fun Map<String, Any?>.str(key: String): String? = this[key] as? String
fun Map<String, Any?>.num(key: String): Double? = (this[key] as? Number)?.toDouble()
fun Map<String, Any?>.bool(key: String): Boolean = this[key] as? Boolean ?: false
