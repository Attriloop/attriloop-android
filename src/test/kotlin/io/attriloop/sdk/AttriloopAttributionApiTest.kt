package io.attriloop.sdk

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * add-sdk-attribution-api 5.1–5.6 and add-revenuecat-attribution 7.1 against a local
 * MockWebServer. `configure` runs with side effects off (no Play Referrer, install
 * or network), so each test drives exactly the requests it asserts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AttriloopAttributionApiTest {
    private lateinit var server: MockWebServer
    private lateinit var app: Application
    private val attributionBodies = ConcurrentLinkedQueue<String>()
    private val attributionHits = AtomicInteger(0)
    private val eventBodies: MutableList<JSONObject> = Collections.synchronizedList(ArrayList())
    private val sdkHeaders: MutableList<String?> = Collections.synchronizedList(ArrayList())
    @Volatile private var attributionStatusCode = 200
    @Volatile private var lastAttributionBody = PENDING

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                sdkHeaders.add(request.getHeader("X-Attriloop-SDK"))
                val path = request.path ?: ""
                return when {
                    path.startsWith("/v1/attribution") -> {
                        attributionHits.incrementAndGet()
                        if (attributionStatusCode != 200) return MockResponse().setResponseCode(attributionStatusCode)
                        val body = attributionBodies.poll() ?: lastAttributionBody
                        lastAttributionBody = body
                        MockResponse().setBody(body)
                    }
                    path.startsWith("/v1/events") -> {
                        val json = JSONObject(request.body.readUtf8())
                        val events = json.getJSONArray("events")
                        val results = org.json.JSONArray()
                        for (i in 0 until events.length()) {
                            eventBodies.add(events.getJSONObject(i))
                            results.put(JSONObject().put("eid", events.getJSONObject(i).getString("eid")).put("status", "accepted"))
                        }
                        MockResponse().setBody(JSONObject().put("ok", true).put("results", results).toString())
                    }
                    path.startsWith("/v1/event") -> {
                        eventBodies.add(JSONObject(request.body.readUtf8()))
                        MockResponse().setBody("""{"ok":true}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        Attriloop.resetForTest()
        try {
            server.shutdown()
        } catch (_: Exception) {
            // already shut down by an offline test
        }
    }

    private fun attach(fastPolls: Boolean = true) {
        Attriloop.attachPersistenceForTest(app, server.url("/").toString())
        if (fastPolls) Attriloop.pollDelaysMs = longArrayOf(40, 40, 40, 40)
    }

    private fun <T> runSuspend(block: suspend () -> T): T {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Result<T>>()
        block.startCoroutine(Continuation(EmptyCoroutineContext) { result.set(it); latch.countDown() })
        assertTrue("suspend call finished", latch.await(15, TimeUnit.SECONDS))
        return result.get().getOrThrow()
    }

    private fun awaitOnMain(latch: CountDownLatch, timeoutMs: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            if (latch.await(10, TimeUnit.MILLISECONDS)) return
        }
        throw AssertionError("callback never ran")
    }

    private fun prefs() = app.getSharedPreferences("com.attriloop.sdk.test", Context.MODE_PRIVATE)

    // ── 5.1 install id ──────────────────────────────────────────────────────

    @Test fun idReadBeforeConfigureIsMintedThroughTheInitProviderAndPersisted() {
        Attriloop.detachForTest(null)
        assertEquals("", Attriloop.attriloopId)
        Robolectric.setupContentProvider(AttriloopInitProvider::class.java, "${app.packageName}.attriloop-init")
        val id = Attriloop.attriloopId
        assertTrue(id.isNotEmpty())
        assertEquals(id, Attriloop.attriloopId)
        assertEquals(id, prefs().getString("attriloop_id", null))
    }

    @Test fun configureKeepsTheEarlyIdAndTheInstallBodyUsesIt() {
        Attriloop.detachForTest(app)
        val early = Attriloop.attriloopId
        assertTrue(early.isNotEmpty())
        Attriloop.configureSideEffects = false
        Attriloop.configure(app, "at_test", debug = true)
        assertEquals(early, Attriloop.attriloopId)
        assertEquals(early, Attriloop.installBodyForTest().getString("attriloopId"))
        assertEquals("android/0.3.0", Attriloop.installBodyForTest().getString("sdkVersion"))
    }

    @Test fun providerRemovedThenContextOverloadMintsAndPersists() {
        Attriloop.detachForTest(null)
        assertEquals("", Attriloop.attriloopId)
        val id = Attriloop.getAttriloopId(app)
        assertTrue(id.isNotEmpty())
        assertEquals(id, prefs().getString("attriloop_id", null))
        assertEquals(id, Attriloop.attriloopId)
        Attriloop.configureSideEffects = false
        Attriloop.configure(app, "at_test")
        assertEquals(id, Attriloop.attriloopId)
    }

    // ── 5.3 waiting ─────────────────────────────────────────────────────────

    @Test fun decisionArrivingDuringTheWaitIsReturnedOnce() {
        attach()
        attributionBodies.addAll(listOf(PENDING, PENDING, MATCHED))
        val params = runSuspend { Attriloop.awaitAttributionParams() }
        assertEquals("matched", params["attriloop_match_status"])
        assertEquals("meta", params["attriloop_adnetwork"])
        assertEquals("Spring Sale", params["attriloop_campaign"])
        assertEquals("test-attriloop-id", params["attriloop_id"])
        assertEquals("meta_ads", params["mediaSource"]) // legacy key kept
        assertEquals(3, attributionHits.get())
        assertEquals(AttriloopMatchStatus.matched, Attriloop.getMatchStatus())
    }

    @Test fun timeoutYieldsFailed() {
        attach()
        val params = runSuspend { Attriloop.awaitAttributionParams() }
        assertEquals(
            mapOf("attriloop_id" to "test-attriloop-id", "attriloop_match_status" to "failed"),
            params,
        )
        assertEquals(5, attributionHits.get()) // first request + 4 backoff polls
        assertEquals(AttriloopMatchStatus.failed, Attriloop.getMatchStatus())
    }

    @Test fun transportFailureYieldsFailedThenTheNextCallRetries() {
        attach()
        attributionStatusCode = 503
        assertEquals("failed", runSuspend { Attriloop.awaitAttributionParams() }["attriloop_match_status"])
        attributionStatusCode = 200
        attributionBodies.add(ORGANIC)
        val before = attributionHits.get()
        assertEquals("organic", runSuspend { Attriloop.awaitAttributionParams() }["attriloop_match_status"])
        assertEquals(before + 1, attributionHits.get())
    }

    @Test fun notConfiguredReturnsImmediatelyWithTheMintedId() {
        Attriloop.detachForTest(app)
        val params = runSuspend { Attriloop.awaitAttributionParams() }
        assertEquals("not_configured", params["attriloop_match_status"])
        assertEquals(Attriloop.attriloopId, params["attriloop_id"])
        assertTrue((params["attriloop_id"] as String).isNotEmpty())
        assertEquals(0, attributionHits.get())
    }

    @Test fun threeConcurrentCallersShareOnePollChain() {
        attach(fastPolls = false)
        Attriloop.pollDelaysMs = longArrayOf(150, 150, 150, 150)
        attributionBodies.addAll(listOf(PENDING, MATCHED))
        val results = Collections.synchronizedList(ArrayList<Map<String, Any?>>())
        val latch = CountDownLatch(3)
        repeat(3) {
            val block: suspend () -> Map<String, Any?> = { Attriloop.awaitAttributionParams() }
            block.startCoroutine(Continuation(EmptyCoroutineContext) { r -> results.add(r.getOrThrow()); latch.countDown() })
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        assertEquals(2, attributionHits.get())
        assertEquals(1, results.toSet().size)
        assertEquals("matched", results.first()["attriloop_match_status"])
    }

    @Test fun settledCacheReturnsWithoutARequest() {
        attach()
        attributionBodies.add(ORGANIC)
        runSuspend { Attriloop.awaitAttributionParams() }
        val hits = attributionHits.get()
        val again = runSuspend { Attriloop.awaitAttributionParams() }
        assertEquals("organic", again["attriloop_match_status"])
        assertEquals(hits, attributionHits.get())

        val latch = CountDownLatch(1)
        val onMain = AtomicBoolean(false)
        Attriloop.getAttributionParams { params ->
            onMain.set(Looper.myLooper() == Looper.getMainLooper())
            assertEquals("organic", params["attriloop_match_status"])
            latch.countDown()
        }
        awaitOnMain(latch)
        assertTrue(onMain.get())
        assertEquals(hits, attributionHits.get())
    }

    @Test fun callbackFormWaitsForTheDecisionOnTheMainThread() {
        attach()
        attributionBodies.addAll(listOf(PENDING, MATCHED_NO_PARAMS))
        val latch = CountDownLatch(1)
        val received = AtomicReference<AttriloopAttribution>()
        Attriloop.getAttribution { received.set(it); latch.countDown() }
        awaitOnMain(latch)
        assertEquals(AttriloopMatchStatus.matchedNoParams, received.get().status)
        assertEquals("matched_no_params", received.get().params["attriloop_match_status"])
    }

    // ── 5.4 re-poll once per launch ─────────────────────────────────────────

    @Test fun organicCacheBecomesMatchedAfterRelaunchUpgrade() {
        attach()
        attributionBodies.add(ORGANIC_IN_WINDOW)
        assertEquals("organic", runSuspend { Attriloop.awaitAttributionParams() }["attriloop_match_status"])
        val hits = attributionHits.get()

        attributionBodies.add(MATCHED)
        Attriloop.relaunchForTest()
        val deadline = System.currentTimeMillis() + 5_000
        while (Attriloop.getMatchStatus() != AttriloopMatchStatus.matched && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertEquals(hits + 1, attributionHits.get()) // exactly one launch poll
        assertEquals("matched", Attriloop.getAttributionParams()["attriloop_match_status"])
        assertEquals("Spring Sale", Attriloop.getAttributionParams()["attriloop_campaign"])
    }

    @Test fun relaunchKeepsTheSettledCacheWhenTheLaunchPollHasNoDecision() {
        attach()
        attributionBodies.addAll(listOf(ORGANIC_IN_WINDOW, PENDING))
        runSuspend { Attriloop.awaitAttributionParams() }
        Attriloop.relaunchForTest()
        Attriloop.awaitIo()
        assertEquals(2, attributionHits.get())
        assertEquals(AttriloopMatchStatus.organic, Attriloop.getMatchStatus())
    }

    @Test fun finalAnswerIsNotRepolledOnRelaunch() {
        attach()
        attributionBodies.add(MATCHED)
        runSuspend { Attriloop.awaitAttributionParams() }
        Attriloop.relaunchForTest()
        Attriloop.awaitIo()
        assertEquals(1, attributionHits.get())
    }

    // ── upgrade from 0.2.x ──────────────────────────────────────────────────

    @Test fun legacy021CacheGainsContractKeysOnConfigure() {
        Attriloop.detachForTest(app)
        prefs().edit().putString(
            "attribution",
            JSONObject().put("matchMethod", "deeplink").put("mediaSource", "meta_ads")
                .put("campaignName", "Spring Sale").put("matchStatus", "matched")
                .put("terminal", true).put("status", "attributed").toString(),
        ).commit()
        Attriloop.configureSideEffects = false
        Attriloop.configure(app, "at_test")
        val params = Attriloop.getAttributionParams()
        assertEquals("matched", params["attriloop_match_status"])
        assertEquals("Spring Sale", params["attriloop_campaign"])
        assertEquals("meta_ads", params["mediaSource"])
        assertEquals(Attriloop.attriloopId, params["attriloop_id"])
        assertEquals(AttriloopMatchStatus.matched, Attriloop.getMatchStatus())
        assertEquals("Spring Sale", Attriloop.revenueCatAttributes(params)["\$campaign"])
    }

    @Test fun legacy021CachePollsOnceOnLaunchAndThenStaysSettled() {
        attach()
        prefs().edit().putString(
            "attribution",
            JSONObject().put("matchMethod", "deeplink").put("mediaSource", "meta_ads")
                .put("campaignName", "Spring Sale").put("matchStatus", "matched")
                .put("terminal", true).put("status", "attributed").toString(),
        ).commit()
        attributionBodies.add(MATCHED)
        Attriloop.relaunchForTest()
        Attriloop.awaitIo()
        assertEquals(1, attributionHits.get())
        assertEquals("US 18-34", Attriloop.getAttributionParams()["attriloop_adset"])
        Attriloop.relaunchForTest()
        Attriloop.awaitIo()
        assertEquals(1, attributionHits.get())
    }

    @Test fun restoredParamsKeepsA030CacheAndAnUnsettledLegacyBody() {
        val current = mapOf<String, Any?>("attriloop_match_status" to "organic", "attriloop_id" to "x")
        assertEquals(current, AttributionDecision.restoredParams(current, "x"))
        assertFalse(AttributionDecision.isLegacyCache(current))
        val pending = mapOf<String, Any?>("matchStatus" to "pending", "terminal" to false)
        assertTrue(AttributionDecision.isLegacyCache(pending))
        assertEquals(pending, AttributionDecision.restoredParams(pending, "x"))
    }

    @Test fun synchronousGetterAlwaysCarriesTheStatusBeforeTheFirstDecision() {
        Attriloop.detachForTest(app)
        Attriloop.configureSideEffects = false
        Attriloop.configure(app, "at_test")
        val params = Attriloop.getAttributionParams()
        assertEquals("failed", params["attriloop_match_status"])
        assertEquals(Attriloop.attriloopId, params["attriloop_id"])
    }

    // ── 5.5 customer user id ────────────────────────────────────────────────

    @Test fun customerUserIdSetBeforeConfigureReachesTheInstallBody() {
        Attriloop.detachForTest(app)
        Attriloop.setCustomerUserId("u_42")
        Attriloop.configureSideEffects = false
        Attriloop.configure(app, "at_test")
        assertEquals("u_42", Attriloop.installBodyForTest().getString("customerUserId"))
    }

    @Test fun customerUserIdSetWithoutAnyContextIsPersistedByConfigure() {
        Attriloop.detachForTest(null)
        Attriloop.setCustomerUserId("u_7")
        Attriloop.configureSideEffects = false
        Attriloop.configure(app, "at_test")
        assertEquals("u_7", Attriloop.persistedCustomerUserId())
        assertEquals("u_7", Attriloop.installBodyForTest().getString("customerUserId"))
    }

    @Test fun queuedEventsWithoutAnIdAreStampedAtSendTime() {
        attach()
        Attriloop.setCustomerUserId(null)
        Attriloop.enqueueForTest(JSONObject().put("eid", "e1").put("attriloopId", "x").put("appId", "a").put("event", "LOGIN"))
        Attriloop.enqueueForTest(
            JSONObject().put("eid", "e2").put("attriloopId", "x").put("appId", "a").put("event", "LOGIN")
                .put("customerUserId", "u_old"),
        )
        assertFalse(Attriloop.queuedItemsForTest().contains("u_42"))
        Attriloop.setCustomerUserId("u_42")
        Attriloop.flushAndAwaitForTest()
        val byEid = eventBodies.associateBy { it.getString("eid") }
        assertEquals("u_42", byEid.getValue("e1").getString("customerUserId"))
        assertEquals("u_old", byEid.getValue("e2").getString("customerUserId"))
        assertTrue(sdkHeaders.all { it == "android/0.3.0" })
    }

    @Test fun eventQueuedWhileLoggedInKeepsThatIdAfterLogout() {
        attach()
        server.shutdown() // offline: the event stays queued
        Attriloop.setCustomerUserId("u_A")
        Attriloop.sendEvent(AttriloopEvent.LOGIN)
        Attriloop.flushAndAwaitForTest()
        Attriloop.setCustomerUserId(null)
        val queued = org.json.JSONArray(Attriloop.persistedQueueRawForTest()).getJSONObject(0)
        assertEquals("u_A", queued.getString("customerUserId"))
        assertEquals("u_A", Attriloop.stampedForSend(queued).getString("customerUserId"))
    }

    @Test fun eventQueuedWhileLoggedInIsSentWithThatIdAfterLogout() {
        attach()
        Attriloop.setCustomerUserId("u_A")
        // Enqueue-time stamp, exactly as sendEvent writes it, without triggering a drain.
        Attriloop.enqueueForTest(
            JSONObject().put("eid", "e1").put("attriloopId", "x").put("appId", "a").put("event", "LOGIN")
                .put("customerUserId", "u_A"),
        )
        Attriloop.setCustomerUserId(null)
        Attriloop.flushAndAwaitForTest()
        assertEquals("u_A", eventBodies.single().getString("customerUserId"))
    }

    @Test fun blankOrNullClearsTheCustomerUserId() {
        attach()
        Attriloop.setCustomerUserId("u_42")
        Attriloop.setCustomerUserId("  ")
        assertNull(Attriloop.persistedCustomerUserId())
        Attriloop.enqueueForTest(JSONObject().put("eid", "e1").put("attriloopId", "x").put("appId", "a").put("event", "LOGIN"))
        Attriloop.flushAndAwaitForTest()
        assertFalse(eventBodies.single().has("customerUserId"))
        Attriloop.setCustomerUserId("u_1")
        Attriloop.setCustomerUserId(null)
        assertNull(Attriloop.persistedCustomerUserId())
    }

    @Test fun emailOrPhoneIsRefusedAndThePreviousValueKept() {
        attach()
        Attriloop.setCustomerUserId("u_42")
        Attriloop.setCustomerUserId("ana@example.com")
        assertEquals("u_42", Attriloop.persistedCustomerUserId())
        Attriloop.setCustomerUserId("+34 (600) 000-000")
        assertEquals("u_42", Attriloop.persistedCustomerUserId())
        assertFalse(Attriloop.looksLikeContactDetail("user_123456"))
        assertFalse(Attriloop.looksLikeContactDetail("123456")) // 6 digits: not a phone
        assertTrue(Attriloop.looksLikeContactDetail("1234567"))
    }

    // ── 5.6 user attributes ─────────────────────────────────────────────────

    @Test fun setUserAttributesQueuesOnlyHashes() {
        attach()
        Attriloop.setUserAttributes(email = "Ana@Example.com ", name = "María José O'Neill", dateOfBirth = "31/02/1990")
        Attriloop.awaitIo()
        val raw = Attriloop.persistedQueueRawForTest() ?: ""
        Attriloop.flushAndAwaitForTest()
        val item = eventBodies.single()
        assertEquals("CUSTOM", item.getString("event"))
        assertEquals("user_attributes", item.getString("name"))
        val params = item.getJSONObject("parameters")
        assertEquals(setOf("email", "first_name", "last_name"), params.keys().asSequence().toSet())
        assertEquals(UserAttributes.sha256("ana@example.com"), params.getString("email"))
        for (key in params.keys()) assertTrue(Regex("^[0-9a-f]{64}$").matches(params.getString(key)))
        if (raw.isNotEmpty()) {
            assertFalse(raw.contains("Ana@", ignoreCase = true))
            assertFalse(raw.contains("Neill"))
        }
    }

    @Test fun offlineQueueHoldsNoPlaintext() {
        attach()
        server.shutdown() // offline: the flush fails and the item stays queued
        Attriloop.setUserAttributes(email = "ana@example.com", phoneNumber = "+34 600 000 000", gender = "female")
        Attriloop.flushAndAwaitForTest()
        val raw = Attriloop.persistedQueueRawForTest()
        assertNotNull(raw)
        assertTrue(raw!!.contains("user_attributes"))
        assertFalse(raw.contains("ana@example.com"))
        assertFalse(raw.contains("600"))
        assertFalse(raw.contains("female"))
        val params = JSONObject(org.json.JSONArray(raw).getJSONObject(0).toString()).getJSONObject("parameters")
        assertEquals(setOf("email", "ph_meta", "ph_tiktok", "gender"), params.keys().asSequence().toSet())
        for (key in params.keys()) assertTrue(Regex("^[0-9a-f]{64}$").matches(params.getString(key)))
    }

    @Test fun appstackStyleCustomEventSendsPhoneHashesOnly() {
        attach()
        Attriloop.sendCustomEvent("user_attributes", mapOf("phoneNumber" to "+34 600 000 000"))
        Attriloop.flushAndAwaitForTest()
        val params = eventBodies.single().getJSONObject("parameters")
        assertEquals(setOf("ph_meta", "ph_tiktok"), params.keys().asSequence().toSet())
        assertEquals(UserAttributes.sha256("+34600000000"), params.getString("ph_tiktok"))
    }

    @Test fun clearUserAttributesSendsClear() {
        attach()
        Attriloop.clearUserAttributes()
        Attriloop.flushAndAwaitForTest()
        val item = eventBodies.single()
        assertEquals("user_attributes", item.getString("name"))
        assertEquals(true, item.getJSONObject("parameters").getBoolean("clear"))
    }

    @Test fun nothingUsableQueuesNothing() {
        attach()
        Attriloop.setUserAttributes(gender = "nonbinary", phoneNumber = "12345")
        Attriloop.flushAndAwaitForTest()
        assertTrue(eventBodies.isEmpty())
    }

    @Test fun setUserAttributesBeforeConfigureIsIgnored() {
        Attriloop.detachForTest(app)
        Attriloop.setUserAttributes(email = "ana@example.com")
        assertEquals(0, server.requestCount)
    }

    // ── RevenueCat helper (add-revenuecat-attribution 7.1) ──────────────────

    @Test fun revenueCatMatchedDeterministicSetsReservedKeys() {
        attach()
        attributionBodies.add(MATCHED_DETERMINISTIC)
        val attrs = runSuspend { Attriloop.getRevenueCatAttributes() }
        assertEquals("test-attriloop-id", attrs["attriloop_id"])
        assertEquals("matched", attrs["attriloop_match_status"])
        assertEquals("meta", attrs["\$mediaSource"])
        assertEquals("Spring Sale", attrs["\$campaign"])
        assertEquals("US 18-34", attrs["\$adGroup"])
        assertEquals("Video A", attrs["\$ad"])
        assertEquals("network", attrs["attriloop_matching_type"])
        assertFalse(attrs.keys.any { it in setOf("\$keyword", "\$creative", "\$gpsAdId", "\$idfa") })
        // Same values as getAttributionParams().
        val params = Attriloop.getAttributionParams()
        for (key in AttributionDecision.OPTIONAL_KEYS + "attriloop_id" + "attriloop_match_status") {
            assertEquals(key, params[key], attrs[key])
        }
    }

    @Test fun revenueCatProbabilisticBelowThresholdHasNoReservedKeys() {
        attach()
        attributionBodies.add(PROBABILISTIC_LOW) // server reports organic
        val attrs = runSuspend { Attriloop.getRevenueCatAttributes() }
        assertEquals(mapOf("attriloop_id" to "test-attriloop-id", "attriloop_match_status" to "organic"), attrs)
    }

    @Test fun revenueCatOrganic() {
        assertEquals(
            mapOf("attriloop_id" to "id", "attriloop_match_status" to "organic"),
            Attriloop.revenueCatAttributes(mapOf("attriloop_id" to "id", "attriloop_match_status" to "organic", "mediaSource" to null)),
        )
    }

    @Test fun revenueCatMatchedNoParamsHasNoReservedKeys() {
        val attrs = Attriloop.revenueCatAttributes(
            mapOf("attriloop_id" to "id", "attriloop_match_status" to "matched_no_params", "attriloop_matching_type" to "network"),
        )
        assertFalse(attrs.keys.any { it.startsWith("$") })
    }

    @Test fun revenueCatTimeoutReturnsIdAndFailedWithoutCancellingTheWait() {
        attach(fastPolls = false)
        Attriloop.pollDelaysMs = longArrayOf(300, 300, 300, 300)
        attributionBodies.addAll(listOf(PENDING, PENDING, MATCHED))
        val attrs = runSuspend { Attriloop.getRevenueCatAttributes(timeoutMs = 100) }
        assertEquals(mapOf("attriloop_id" to "test-attriloop-id", "attriloop_match_status" to "failed"), attrs)
        // The shared wait keeps going and settles the cache.
        val later = runSuspend { Attriloop.awaitAttributionParams() }
        assertEquals("matched", later["attriloop_match_status"])
    }

    @Test fun revenueCatNotConfiguredHasMintedIdAndNotConfigured() {
        Attriloop.detachForTest(app)
        val latch = CountDownLatch(1)
        val received = AtomicReference<Map<String, String>>()
        val onMain = AtomicBoolean(false)
        Attriloop.getRevenueCatAttributes { attrs ->
            onMain.set(Looper.myLooper() == Looper.getMainLooper())
            received.set(attrs)
            latch.countDown()
        }
        awaitOnMain(latch)
        assertTrue(onMain.get())
        assertEquals("not_configured", received.get()["attriloop_match_status"])
        assertEquals(Attriloop.attriloopId, received.get()["attriloop_id"])
        assertTrue(received.get().getValue("attriloop_id").isNotEmpty())
    }

    @Test fun revenueCatValuesAreTruncatedTo500() {
        val long = "x".repeat(800)
        val attrs = Attriloop.revenueCatAttributes(
            mapOf(
                "attriloop_id" to "id",
                "attriloop_match_status" to "matched",
                "attriloop_adnetwork" to "meta",
                "attriloop_campaign" to long,
            ),
        )
        assertEquals(500, attrs.getValue("attriloop_campaign").length)
        assertEquals(500, attrs.getValue("\$campaign").length)
        assertTrue(attrs.keys.all { it.length <= 40 })
    }

    private companion object {
        const val PENDING = """{"matchStatus":"pending","terminal":false,"status":"pending","applyDeepLink":false,"attriloop_match_status":"pending","attribution_params":{"attriloop_match_status":"pending"}}"""
        const val MATCHED = """{"matchMethod":"probabilistic","matchingType":"exact_ip","confidence":"high","mediaSource":"meta_ads","campaignName":"Spring Sale","adNetwork":"meta","matchStatus":"matched","terminal":true,"status":"attributed","applyDeepLink":false,"deep_link_value":null,"attriloop_match_status":"matched","attribution_params":{"attriloop_match_status":"matched","attriloop_adnetwork":"meta","attriloop_campaign":"Spring Sale","attriloop_adset":"US 18-34","attriloop_ad":"Video A","attriloop_matching_type":"exact_ip","attriloop_confidence":"high"}}"""
        const val MATCHED_DETERMINISTIC = """{"matchMethod":"play_referrer","matchingType":"network","confidence":"high","mediaSource":"meta_ads","campaignName":"Spring Sale","adNetwork":"meta","matchStatus":"matched","terminal":true,"status":"attributed","applyDeepLink":false,"attriloop_match_status":"matched","attribution_params":{"attriloop_match_status":"matched","attriloop_adnetwork":"meta","attriloop_campaign":"Spring Sale","attriloop_adset":"US 18-34","attriloop_ad":"Video A","attriloop_matching_type":"network","attriloop_confidence":"high"}}"""
        const val MATCHED_NO_PARAMS = """{"matchMethod":"deeplink","mediaSource":"creator_ana","matchStatus":"matched","terminal":true,"status":"attributed","applyDeepLink":false,"attriloop_match_status":"matched_no_params","attribution_params":{"attriloop_match_status":"matched_no_params","attriloop_matching_type":"network","attriloop_confidence":"high"}}"""
        const val ORGANIC = """{"matchMethod":"organic","matchStatus":"organic","terminal":true,"status":"organic","applyDeepLink":false,"attriloop_match_status":"organic","attribution_params":{"attriloop_match_status":"organic"}}"""
        const val ORGANIC_IN_WINDOW = """{"matchMethod":"unattributed","matchStatus":"pending","terminal":false,"status":"pending","applyDeepLink":false,"attriloop_match_status":"organic","attribution_params":{"attriloop_match_status":"organic"}}"""
        const val PROBABILISTIC_LOW = """{"matchMethod":"unattributed","matchingType":null,"confidence":null,"adNetwork":null,"matchStatus":"pending","terminal":false,"status":"pending","applyDeepLink":false,"attriloop_match_status":"organic","attribution_params":{"attriloop_match_status":"organic"}}"""
    }
}
