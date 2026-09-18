package io.attriloop.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic unit tests. Persistence + main-thread delivery: [AttriloopPersistenceTest]. */
class AttriloopTest {
    @Test fun parsesReadableSharedAndBrandedLinks() {
        for (path in listOf(listOf("faithlocked", "dxrling3x"), listOf("faithlocked--dxrling3x"))) {
            assertEquals(Attriloop.LinkContext("faithlocked--dxrling3x", "attriloop.com", null),
                Attriloop.deepLinkContext("attriloop.com", path))
        }
        assertEquals(Attriloop.LinkContext("dxrling3x", "go.faithlocked.app", null),
            Attriloop.deepLinkContext("go.faithlocked.app", listOf("dxrling3x")))
        assertNull(Attriloop.deepLinkContext("go.faithlocked.app", listOf("faithlocked", "dxrling3x")))
        assertNull(Attriloop.deepLinkContext("attriloop.com", listOf("app--other", "creator")))
    }


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

    @Test fun reengagementEventIdIsStablePerResolveToken() {
        assertEquals(
            "reengagement:resolve-click",
            Attriloop.reengagementEventId("resolve-click"),
        )
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
            Attriloop.interpretTestResponse(401, """{"ok":false,"message":"Invalid API key."}"""),
        )
        assertFalse(Attriloop.interpretTestResponse(200, """{"ok":false}""").success)
        assertEquals(
            "This key cannot send SDK events.",
            Attriloop.interpretTestResponse(403, null).message,
        )
        assertTrue(Attriloop.interpretTestResponse(429, null).message.contains("Rate limited"))
        assertTrue(Attriloop.interpretTestResponse(500, null).message.contains("HTTP 500"))
    }

    @Test fun testEventBodyAlwaysHasSubjectFields() {
        val body = Attriloop.testEventBody()
        assertTrue(body.has("attriloopId"))
        assertTrue(body.has("appId"))
    }

    @Test fun sendEventBeforeConfigureIsIgnored() {
        Attriloop.sendEvent(AttriloopEvent.LOGIN)
        Attriloop.flushQueue()
        Attriloop.setCustomerUserId("should-not-persist")
        assertNull(Attriloop.persistedCustomerUserId())
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

    // ── revenue / currency validation (parity with iOS validatedParams) ────

    @Test fun revenueIsCoercedToFiniteNonNegativeNumber() {
        assertEquals(9.99, Attriloop.coerceNonNegNumber(9.99))
        assertEquals(10.0, Attriloop.coerceNonNegNumber(10))
        assertEquals(10.0, Attriloop.coerceNonNegNumber(10L))
        assertEquals(4.5, Attriloop.coerceNonNegNumber("4.5"))
        assertEquals(0.0, Attriloop.coerceNonNegNumber(0))
        assertNull(Attriloop.coerceNonNegNumber(-1))
        assertNull(Attriloop.coerceNonNegNumber(Double.NaN))
        assertNull(Attriloop.coerceNonNegNumber(Double.POSITIVE_INFINITY))
        assertNull(Attriloop.coerceNonNegNumber("free"))
        assertNull(Attriloop.coerceNonNegNumber(true))
        assertNull(Attriloop.coerceNonNegNumber(null))
    }

    @Test fun badRevenueOrCurrencyDropsOnlyThatKey() {
        val out = Attriloop.sanitizedParameters(
            mapOf("revenue" to "abc", "currency" to "dollars", "sku" to "pro", "price" to "12.50"),
        )
        assertFalse(out.containsKey("revenue"))
        assertFalse(out.containsKey("currency"))
        assertEquals("pro", out["sku"])
        assertEquals(12.5, out["price"])
        val ok = Attriloop.sanitizedParameters(mapOf("revenue" to 29.99, "currency" to "usd"))
        assertEquals(29.99, ok["revenue"])
        assertEquals("usd", ok["currency"])
    }

    @Test fun nonFiniteDoublesNeverReachTheQueue() {
        val out = Attriloop.sanitizedParameters(mapOf("score" to Double.NaN, "level" to 3))
        assertFalse(out.containsKey("score"))
        assertEquals(3, out["level"])
        // org.json would return null from toString() for NaN — the whole queue would vanish.
        assertTrue(JSONObject(out).toString() != null)
    }

    // ── batch flush (POST /v1/events) ──────────────────────────────────────

    private fun queueOf(vararg eids: String): JSONArray {
        val arr = JSONArray()
        for (e in eids) arr.put(JSONObject().put("eid", e).put("event", "LOGIN"))
        return arr
    }

    @Test fun batchSliceTakesAtMostBatchSizeFromTheHead() {
        val arr = JSONArray()
        for (i in 0 until 30) arr.put(JSONObject().put("eid", "e$i"))
        val batch = Attriloop.batchSlice(arr, Attriloop.BATCH_SIZE)
        assertEquals(25, batch.size)
        assertEquals("e0", batch.first().getString("eid"))
        assertEquals("e24", batch.last().getString("eid"))
        assertEquals(3, Attriloop.batchSlice(queueOf("a", "b", "c"), Attriloop.BATCH_SIZE).size)
        assertTrue(Attriloop.batchSlice(JSONArray(), Attriloop.BATCH_SIZE).isEmpty())
    }

    @Test fun batchSliceStopsAtFirstEidlessLegacyItem() {
        val arr = queueOf("a", "b").put(JSONObject().put("event", "LOGIN")).put(JSONObject().put("eid", "d"))
        assertEquals(listOf("a", "b"), Attriloop.batchSlice(arr, 25).map { it.getString("eid") })
        // An eid-less head yields an empty batch → the drain uses the legacy single path.
        val legacyHead = JSONArray().put(JSONObject().put("event", "LOGIN")).put(JSONObject().put("eid", "b"))
        assertTrue(Attriloop.batchSlice(legacyHead, 25).isEmpty())
    }

    @Test fun withoutEidsRemovesByEidNotByIndex() {
        val out = Attriloop.withoutEids(queueOf("a", "b", "c", "d"), setOf("b", "d", "never-sent"))
        assertEquals(listOf("a", "c"), (0 until out.length()).map { out.getJSONObject(it).getString("eid") })
        // Missing eids (cap-evicted while in flight) remove nothing.
        assertEquals(2, Attriloop.withoutEids(queueOf("a", "b"), setOf("zzz")).length())
    }

    @Test fun partitionAllAccepted() {
        val body = """{"ok":true,"results":[{"eid":"a","status":"accepted"},{"eid":"b","status":"accepted"}]}"""
        val p = Attriloop.partitionBatchResults(listOf("a", "b"), body)
        assertEquals(setOf("a", "b"), p.remove)
        assertTrue(p.permanentDrops.isEmpty())
    }

    @Test fun partitionMixedRejectedAndSuppressedAreRemovedAsPermanentDrops() {
        val body = """{"ok":true,"results":[
            {"eid":"a","status":"accepted"},
            {"eid":"b","status":"rejected","detail":{"path":"parameters.revenue","message":"must be >= 0"}},
            {"eid":"c","status":"suppressed"}
        ]}"""
        val p = Attriloop.partitionBatchResults(listOf("a", "b", "c"), body)
        assertEquals(setOf("a", "b", "c"), p.remove)
        assertEquals(setOf("b", "c"), p.permanentDrops)
    }

    @Test fun partitionMissingEidIsTreatedAsDelivered() {
        val body = """{"ok":true,"results":[{"eid":"a","status":"accepted"}]}"""
        val p = Attriloop.partitionBatchResults(listOf("a", "b"), body)
        assertEquals(setOf("a", "b"), p.remove) // "b" unlisted → delivered, leaves the queue
        assertTrue(p.permanentDrops.isEmpty())
    }

    @Test fun partitionIgnoresResultsForEidsWeDidNotSend() {
        val body = """{"ok":true,"results":[{"eid":"a","status":"accepted"},{"eid":"ghost","status":"rejected"}]}"""
        val p = Attriloop.partitionBatchResults(listOf("a"), body)
        assertEquals(setOf("a"), p.remove)
        assertTrue(p.permanentDrops.isEmpty())
    }

    @Test fun partitionMalformed2xxBodyTreatsAllSentAsDelivered() {
        // A 2xx is the edge's acknowledgement; an unreadable body must not re-send forever.
        for (body in listOf(null, "", "not json", """{"ok":true}""", """{"ok":true,"results":"nope"}""")) {
            val p = Attriloop.partitionBatchResults(listOf("a", "b"), body)
            assertEquals("body=$body", setOf("a", "b"), p.remove)
            assertTrue("body=$body", p.permanentDrops.isEmpty())
        }
    }

    @Test fun batchEnvelopeMatchesContract() {
        val batch = Attriloop.batchSlice(queueOf("a", "b"), Attriloop.BATCH_SIZE)
        val envelope = JSONObject().put("events", JSONArray(batch))
        val events = envelope.getJSONArray("events")
        assertEquals(2, events.length())
        assertEquals("a", events.getJSONObject(0).getString("eid"))
        assertEquals("LOGIN", events.getJSONObject(1).getString("event"))
    }

    // ── late Play Install Referrer upgrade ─────────────────────────────────

    @Test fun referrerUpdateNeededOnlyAfterInstallWithFreshNonEmptyReferrer() {
        assertTrue(Attriloop.referrerUpdateNeeded(installAlreadySent = true, referrer = "at_click=abc", referrerAlreadySent = false))
        // Install not yet sent → the install itself carries the referrer (unchanged path).
        assertFalse(Attriloop.referrerUpdateNeeded(installAlreadySent = false, referrer = "at_click=abc", referrerAlreadySent = false))
        // Nothing to upgrade with.
        assertFalse(Attriloop.referrerUpdateNeeded(installAlreadySent = true, referrer = null, referrerAlreadySent = false))
        assertFalse(Attriloop.referrerUpdateNeeded(installAlreadySent = true, referrer = "", referrerAlreadySent = false))
        // At most once per install.
        assertFalse(Attriloop.referrerUpdateNeeded(installAlreadySent = true, referrer = "at_click=abc", referrerAlreadySent = true))
    }

    @Test fun referrerFallbackIsEightSeconds() {
        assertEquals(8L, Attriloop.REFERRER_FALLBACK_SECONDS)
    }

    // ── customerUserId + APP_OPEN ──────────────────────────────────────────

    @Test fun customerUserIdIsTrimmedCappedAndNullable() {
        assertNull(Attriloop.normalizeCustomerUserId(null))
        assertNull(Attriloop.normalizeCustomerUserId("   "))
        assertEquals("user-42", Attriloop.normalizeCustomerUserId("  user-42 "))
        assertEquals(128, Attriloop.normalizeCustomerUserId("x".repeat(300))!!.length)
    }

    @Test fun foregroundOpenFiresOnlyOnBackgroundToForegroundAfterColdStart() {
        assertFalse(Attriloop.foregroundOpenNeeded(startedBefore = 0, coldStartConsumed = false)) // cold start: configure() sends it
        assertTrue(Attriloop.foregroundOpenNeeded(startedBefore = 0, coldStartConsumed = true)) // back from background
        assertFalse(Attriloop.foregroundOpenNeeded(startedBefore = 1, coldStartConsumed = true)) // activity-to-activity
    }

    @Test fun appOpenIsAutomaticOnly() {
        assertEquals("APP_OPEN", AttriloopEvent.APP_OPEN.raw)
    }
}
