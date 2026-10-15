package no.oystein.bokhylle.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ExchangeRateTest {
    // Forkortet svar på samme form som Norges Banks SDMX-JSON.
    private val sample = """
        {"meta":{"id":"x"},"data":{"dataSets":[{"action":"Information","series":{"0:0:0:0":{"attributes":[0,0,0,0],
        "observations":{"0":["10.6234"]}}}}],"structure":{"name":"Exchange rates","dimensions":{"series":[
        {"id":"FREQ","values":[{"id":"B","name":"Business"}]},{"id":"BASE_CUR","values":[{"id":"USD","name":"US dollar"}]}],
        "observation":[{"id":"TIME_PERIOD","name":"Time period","role":"time","values":[{"start":"2026-10-14T00:00:00","end":"2026-10-14T23:59:59","id":"2026-10-14","name":"2026-10-14"}]}]},
        "attributes":{"series":[{"id":"DECIMALS","values":[{"id":"4","name":"4"}]},{"id":"UNIT_MULT","values":[{"id":"0","name":"Units"}]}]}}}}
    """.trimIndent()

    @Test
    fun `dollarkursen leses med dato`() {
        val r = assertNotNull(NorgesBankRate.parse(sample))
        assertEquals(10.6234, r.nokPerUnit, 1e-6)
        assertEquals("2026-10-14", r.date)
    }

    @Test
    fun `flere observasjoner gir den siste`() {
        val two = sample.replace("\"observations\":{\"0\":[\"10.6234\"]}", "\"observations\":{\"0\":[\"10.50\"],\"1\":[\"10.70\"]}")
            .replace("\"values\":[{\"start\":\"2026-10-14T00:00:00\"", "\"values\":[{\"id\":\"2026-10-13\"},{\"start\":\"2026-10-14T00:00:00\"")
        val r = assertNotNull(NorgesBankRate.parse(two))
        assertEquals(10.70, r.nokPerUnit, 1e-6)
        assertEquals("2026-10-14", r.date)
    }

    @Test
    fun `ugyldig svar gir null`() {
        assertNull(NorgesBankRate.parse("<html>feil</html>"))
        assertNull(NorgesBankRate.parse("{\"data\":{}}"))
        assertNull(NorgesBankRate.parse(sample.replace("10.6234", "0.0001")))
    }
}
