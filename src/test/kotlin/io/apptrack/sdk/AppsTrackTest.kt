package io.apptrack.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic unit tests (no Android framework / SharedPreferences — see follow-up). */
class AppsTrackTest {

    // ── CUSTOM guard (A1) ──────────────────────────────────────────────────

    @Test fun customWithoutNameIsDroppable() {
        assertTrue(AppsTrack.isDroppableCustom(AppsTrackEvent.CUSTOM, null))
        assertTrue(AppsTrack.isDroppableCustom(AppsTrackEvent.CUSTOM, ""))
    }

    @Test fun customWithNameIsNotDroppable() {
        assertFalse(AppsTrack.isDroppableCustom(AppsTrackEvent.CUSTOM, "share_referral"))
    }

    @Test fun nonCustomEventsAreNeverDroppable() {
        assertFalse(AppsTrack.isDroppableCustom(AppsTrackEvent.PURCHASE, null))
        assertFalse(AppsTrack.isDroppableCustom(AppsTrackEvent.LOGIN, ""))
    }

    // ── classifyOutcome ────────────────────────────────────────────────────

    @Test fun classifyOk() {
        assertEquals(AppsTrack.Outcome.OK, AppsTrack.classifyOutcome(200))
        assertEquals(AppsTrack.Outcome.OK, AppsTrack.classifyOutcome(204))
    }

    @Test fun classifyPermanentOn4xx() {
        assertEquals(AppsTrack.Outcome.PERMANENT, AppsTrack.classifyOutcome(400))
        assertEquals(AppsTrack.Outcome.PERMANENT, AppsTrack.classifyOutcome(422))
    }

    @Test fun classifyTransientOnRetryableCodes() {
        assertEquals(AppsTrack.Outcome.TRANSIENT, AppsTrack.classifyOutcome(408))
        assertEquals(AppsTrack.Outcome.TRANSIENT, AppsTrack.classifyOutcome(429))
        assertEquals(AppsTrack.Outcome.TRANSIENT, AppsTrack.classifyOutcome(500))
        assertEquals(AppsTrack.Outcome.TRANSIENT, AppsTrack.classifyOutcome(503))
    }

    // ── cappedQueue (cap eviction math) ────────────────────────────────────

    @Test fun cappedQueueKeepsNewestDropsOldest() {
        val arr = JSONArray()
        for (i in 0 until 505) arr.put(JSONObject().put("eid", "e$i"))
        val out = AppsTrack.cappedQueue(arr, 500)
        assertEquals(500, out.length())
        assertEquals("e5", out.getJSONObject(0).getString("eid")) // e0..e4 evicted
        assertEquals("e504", out.getJSONObject(499).getString("eid"))
    }

    @Test fun cappedQueueUnderLimitIsUnchanged() {
        val arr = JSONArray()
            .put(JSONObject().put("eid", "a"))
            .put(JSONObject().put("eid", "b"))
        assertEquals(2, AppsTrack.cappedQueue(arr, 500).length())
    }

    // ── jsonToMap ──────────────────────────────────────────────────────────

    @Test fun jsonToMapMapsJsonNullToKotlinNull() {
        val json = JSONObject().put("a", "x").put("b", JSONObject.NULL).put("n", 3)
        val map = AppsTrack.jsonToMap(json)
        assertEquals("x", map["a"])
        assertNull(map["b"])
        assertEquals(3, map["n"])
    }
}
