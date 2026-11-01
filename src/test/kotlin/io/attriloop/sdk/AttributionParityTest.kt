package io.attriloop.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Platform parity (add-sdk-attribution-api 7.2): the shared `attribution-poll.json`
 * bodies must give the same contract keys, settle bit and re-poll bit on iOS and
 * Android. A body that is still unsettled when the wait ends yields `failed`.
 */
class AttributionParityTest {
    private val fixture: JSONObject by lazy {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("attribution-poll.json"))
        JSONObject(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
    }

    /** Params after this body was the last poll answer of a wait. */
    private fun finalParams(body: Map<String, Any?>, id: String): Map<String, Any?> {
        val decision = AttributionDecision.decide(body)
        return if (decision.settled) {
            AttributionDecision.merge(body, decision, id)
        } else {
            mapOf("attriloop_id" to id, "attriloop_match_status" to "failed")
        }
    }

    @Test fun everyRecordedBodyGivesTheSharedContractKeys() {
        val id = fixture.getString("attriloopId")
        val cases = fixture.getJSONArray("cases")
        assertTrue(cases.length() >= 10)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val name = case.getString("name")
            val body = Attriloop.jsonToMap(case.getJSONObject("body"))
            val decision = AttributionDecision.decide(body)
            assertEquals("$name settled", case.getBoolean("settled"), decision.settled)
            assertEquals("$name repollNextLaunch", case.getBoolean("repollNextLaunch"), decision.repollNextLaunch)

            val params = finalParams(body, id)
            val expected = case.getJSONObject("expected").let { e ->
                e.keys().asSequence().associateWith { e.getString(it) as Any? }
            } + ("attriloop_id" to id)
            val contract = params.filterKeys { it.startsWith("attriloop_") }
            assertEquals(name, expected, contract)
            assertFalse("$name has no pending", contract.values.contains("pending"))
            assertFalse("$name has no empty values", contract.values.contains(""))

            val legacy = case.getJSONObject("legacy")
            for (key in legacy.keys()) assertEquals("$name legacy $key", legacy.get(key), params[key])
        }
    }

    @Test fun persistedMergedMapRestoresToTheSameDecision() {
        val id = fixture.getString("attriloopId")
        val cases = fixture.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val body = Attriloop.jsonToMap(case.getJSONObject("body"))
            val decision = AttributionDecision.decide(body)
            if (!decision.settled) continue
            val merged = AttributionDecision.merge(body, decision, id)
            val roundTrip = Attriloop.jsonToMap(JSONObject(merged))
            val again = AttributionDecision.decide(roundTrip)
            assertEquals(case.getString("name"), decision, again)
            assertEquals(decision.status, AttriloopMatchStatus.restored(true, null, roundTrip))
        }
    }

    @Test fun everyWireStatusMapsToItsEnum() {
        val values = mapOf(
            "matched" to AttriloopMatchStatus.matched,
            "matched_no_params" to AttriloopMatchStatus.matchedNoParams,
            "organic" to AttriloopMatchStatus.organic,
            "skipped" to AttriloopMatchStatus.skipped,
            "failed" to AttriloopMatchStatus.failed,
            "not_configured" to AttriloopMatchStatus.notConfigured,
        )
        for ((wire, status) in values) {
            val decision = AttributionDecision.decide(mapOf("attriloop_match_status" to wire))
            assertTrue(wire, decision.settled)
            assertEquals(wire, status, decision.status)
            assertEquals(wire, wire, decision.contract["attriloop_match_status"])
        }
        for (wire in listOf("pending", "ambiguous", "something_new")) {
            assertFalse(wire, AttributionDecision.decide(mapOf("attriloop_match_status" to wire)).settled)
        }
    }

    @Test fun oldEdgeAmbiguousBecomesOrganicInParams() {
        val decision = AttributionDecision.decide(mapOf("matchStatus" to "ambiguous", "terminal" to true))
        assertTrue(decision.settled)
        assertEquals(AttriloopMatchStatus.organic, decision.status)
    }

    @Test fun unconfiguredRestoreIsNotConfigured() {
        assertEquals(AttriloopMatchStatus.notConfigured, AttriloopMatchStatus.restored(false, "organic", emptyMap()))
        assertEquals(AttriloopMatchStatus.failed, AttriloopMatchStatus.restored(true, "failed", emptyMap()))
        assertEquals(AttriloopMatchStatus.pending, AttriloopMatchStatus.restored(true, null, emptyMap()))
    }
}
