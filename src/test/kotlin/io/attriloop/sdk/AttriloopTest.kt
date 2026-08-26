package io.attriloop.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic unit tests (no Android framework / SharedPreferences — see follow-up). */
class AttriloopTest {

    @Test fun parsesSharedAndBrandedDeepLinks() {
        assertEquals(
            Attriloop.LinkContext("slug", "go.attriloop.com", "token"),
            Attriloop.deepLinkContext("GO.ATTRILOOP.COM", listOf("l", "token", "slug")),
        )
        assertEquals(
            Attriloop.LinkContext("slug", "go.customer.com", null),
            Attriloop.deepLinkContext("go.customer.com", listOf("l", "slug")),
        )
        assertNull(Attriloop.deepLinkContext("go.attriloop.com", listOf("l")))
        assertNull(Attriloop.deepLinkContext("go.attriloop.com", listOf("l", "a", "b", "c")))
    }

    // ── CUSTOM guard (A1) ──────────────────────────────────────────────────

    @Test fun customWithoutNameIsDroppable() {
        assertTrue(Attriloop.isDroppableCustom(AttriloopEvent.CUSTOM, null))
        assertTrue(Attriloop.isDroppableCustom(AttriloopEvent.CUSTOM, ""))
    }

    @Test fun customWithNameIsNotDroppable() {
        assertFalse(Attriloop.isDroppableCustom(AttriloopEvent.CUSTOM, "share_referral"))
    }

    @Test fun nonCustomEventsAreNeverDroppable() {
        assertFalse(Attriloop.isDroppableCustom(AttriloopEvent.PURCHASE, null))
        assertFalse(Attriloop.isDroppableCustom(AttriloopEvent.LOGIN, ""))
    }

    @Test fun sensitiveParamsAreNormalizedAndHashedBeforeQueueing() {
        val out = Attriloop.sanitizedParameters(
            mapOf("email" to " USER@example.com ", "phone" to "+34 600-12-34", "currency" to "EUR"),
        )
        assertEquals(
            "b4c9a289323b21a01c3e940f150eb9b8c542587f1abfd8f0e1cc1ffc5e475514",
            out["email"],
        )
        assertEquals(64, (out["phone"] as String).length)
        assertEquals("EUR", out["currency"])
    }

    // ── classifyOutcome ────────────────────────────────────────────────────

    @Test fun classifyOk() {
        assertEquals(Attriloop.Outcome.OK, Attriloop.classifyOutcome(200))
        assertEquals(Attriloop.Outcome.OK, Attriloop.classifyOutcome(204))
    }

    @Test fun classifyPermanentOn4xx() {
        assertEquals(Attriloop.Outcome.PERMANENT, Attriloop.classifyOutcome(400))
        assertEquals(Attriloop.Outcome.PERMANENT, Attriloop.classifyOutcome(422))
    }

    @Test fun classifyTransientOnRetryableCodes() {
        assertEquals(Attriloop.Outcome.TRANSIENT, Attriloop.classifyOutcome(408))
        assertEquals(Attriloop.Outcome.TRANSIENT, Attriloop.classifyOutcome(429))
        assertEquals(Attriloop.Outcome.TRANSIENT, Attriloop.classifyOutcome(500))
        assertEquals(Attriloop.Outcome.TRANSIENT, Attriloop.classifyOutcome(503))
        assertEquals(Attriloop.Outcome.TRANSIENT, Attriloop.classifyOutcome(401))
        assertEquals(Attriloop.Outcome.TRANSIENT, Attriloop.classifyOutcome(403))
    }

    @Test fun baseUrlRequiresTlsExceptLocalDebug() {
        assertTrue(Attriloop.isAllowedBaseUrl("https://api.attriloop.com", false))
        assertFalse(Attriloop.isAllowedBaseUrl("http://api.attriloop.com", true))
        assertFalse(Attriloop.isAllowedBaseUrl("http://localhost:8787", false))
        assertTrue(Attriloop.isAllowedBaseUrl("http://localhost:8787", true))
        assertTrue(Attriloop.isAllowedBaseUrl("http://127.0.0.1:8787", true))
    }

    @Test fun interpretsConnectivityResponsesWithoutTrustingHttpAlone() {
        assertEquals(
            AttriloopTestResult(true, "SDK connected — test event received."),
            Attriloop.interpretTestResponse(
                200,
                """{"ok":true,"message":"SDK connected — test event received."}""",
            ),
        )
        assertEquals(
            AttriloopTestResult(false, "Invalid API key."),
            Attriloop.interpretTestResponse(401, """{"ok":false,"message":"Invalid API key."}""),
        )
        assertFalse(Attriloop.interpretTestResponse(200, """{"ok":false}""").success)
    }

    // ── cappedQueue (cap eviction math) ────────────────────────────────────

    @Test fun cappedQueueKeepsNewestDropsOldest() {
        val arr = JSONArray()
        for (i in 0 until 505) arr.put(JSONObject().put("eid", "e$i"))
        val out = Attriloop.cappedQueue(arr, 500)
        assertEquals(500, out.length())
        assertEquals("e5", out.getJSONObject(0).getString("eid")) // e0..e4 evicted
        assertEquals("e504", out.getJSONObject(499).getString("eid"))
    }

    @Test fun cappedQueueUnderLimitIsUnchanged() {
        val arr = JSONArray()
            .put(JSONObject().put("eid", "a"))
            .put(JSONObject().put("eid", "b"))
        assertEquals(2, Attriloop.cappedQueue(arr, 500).length())
    }

    // ── jsonToMap ──────────────────────────────────────────────────────────

    @Test fun jsonToMapMapsJsonNullToKotlinNull() {
        val json = JSONObject().put("a", "x").put("b", JSONObject.NULL).put("n", 3)
        val map = Attriloop.jsonToMap(json)
        assertEquals("x", map["a"])
        assertNull(map["b"])
        assertEquals(3, map["n"])
    }
}
