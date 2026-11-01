package io.attriloop.sdk

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Persistence + main-thread delivery — the Android equivalent of the iOS suite that
 * injects an isolated `UserDefaults` domain. Does not call `configure()` (that would
 * start Play Referrer + network).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AttriloopPersistenceTest {
    @Before
    fun setUp() {
        Attriloop.attachPersistenceForTest(ApplicationProvider.getApplicationContext<Application>())
    }

    @After
    fun tearDown() {
        Attriloop.resetForTest()
    }

    @Test
    fun sendTestEventBeforeConfigureReportsOnMain() {
        Attriloop.resetForTest()
        val latch = CountDownLatch(1)
        val onMain = AtomicBoolean(false)
        Attriloop.sendTestEvent { result ->
            onMain.set(Looper.myLooper() == Looper.getMainLooper())
            assertFalse(result.success)
            latch.countDown()
        }
        ShadowLooper.idleMainLooper()
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue(onMain.get())
    }

    @Test
    fun customerUserIdPersistsAndClears() {
        Attriloop.setCustomerUserId(" u-1 ")
        assertEquals("u-1", Attriloop.persistedCustomerUserId())
        Attriloop.setCustomerUserId(null)
        assertNull(Attriloop.persistedCustomerUserId())
    }

    @Test
    fun enqueueCapsAtMaxDroppingOldest() {
        for (i in 0 until 505) Attriloop.enqueueForTest(JSONObject().put("eid", "e$i"))
        val q = Attriloop.queuedEidsForTest()
        assertEquals(500, q.size)
        assertEquals("e5", q.first())
        assertEquals("e504", q.last())
    }

    @Test
    fun removeQueuedByEidRemovesTheRightItem() {
        listOf("a", "b", "c").forEach { Attriloop.enqueueForTest(JSONObject().put("eid", it)) }
        Attriloop.removeQueuedForTest(setOf("b"))
        assertEquals(listOf("a", "c"), Attriloop.queuedEidsForTest())
    }

    @Test
    fun removeQueuedMissingEidRemovesNothing() {
        listOf("a", "b").forEach { Attriloop.enqueueForTest(JSONObject().put("eid", it)) }
        Attriloop.removeQueuedForTest(setOf("evicted"))
        assertEquals(listOf("a", "b"), Attriloop.queuedEidsForTest())
    }

    @Test
    fun testEventBodyCarriesSubjectAndAppId() {
        val body = Attriloop.testEventBody()
        assertEquals("test-attriloop-id", body.getString("attriloopId"))
        assertTrue(body.getString("appId").isNotEmpty())
    }

    @Test
    fun attributionCallbackRunsOnMainThread() {
        Attriloop.setAttributionForTest(mapOf("linkId" to "abc", "mediaSource" to "creator"))
        val latch = CountDownLatch(1)
        val onMain = AtomicBoolean(false)
        val received = AtomicReference<Map<String, Any?>>()
        Attriloop.getAttributionParams { params ->
            onMain.set(Looper.myLooper() == Looper.getMainLooper())
            received.set(params)
            latch.countDown()
        }
        ShadowLooper.idleMainLooper()
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue(onMain.get())
        assertEquals("abc", received.get()["linkId"])
        assertEquals(AttriloopMatchStatus.matched, Attriloop.getMatchStatus())
    }

    @Test
    fun unconfiguredAttributionCallbackFiresNotConfigured() {
        Attriloop.resetForTest()
        val latch = CountDownLatch(1)
        val received = AtomicReference<AttriloopAttribution>()
        Attriloop.getAttribution { result ->
            received.set(result)
            latch.countDown()
        }
        ShadowLooper.idleMainLooper()
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals(AttriloopMatchStatus.notConfigured, received.get().status)
        assertEquals("not_configured", received.get().params["attriloop_match_status"])
        assertEquals(AttriloopMatchStatus.notConfigured, Attriloop.getMatchStatus())
    }

    @Test
    fun deepLinkCallbackRunsOnMainOncePerFreshResolution() {
        Attriloop.setDeepLinkForTest("product/42", pending = true)
        val latch = CountDownLatch(1)
        val onMain = AtomicBoolean(false)
        val received = AtomicReference<String>()
        Attriloop.onDeepLink { value ->
            onMain.set(Looper.myLooper() == Looper.getMainLooper())
            received.set(value)
            latch.countDown()
        }
        ShadowLooper.idleMainLooper()
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue(onMain.get())
        assertEquals("product/42", received.get())
        assertEquals("product/42", Attriloop.getDeepLink())

        val second = CountDownLatch(1)
        Attriloop.onDeepLink { second.countDown() }
        ShadowLooper.idleMainLooper()
        assertFalse("stale resolution must not re-broadcast", second.await(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun playInstallReferrerIsReadOnlyPlayBytes() {
        assertNull(Attriloop.getPlayInstallReferrer())
        Attriloop.setPlayInstallReferrerForTest("utm_source=creator&at_click=tok")
        assertEquals("utm_source=creator&at_click=tok", Attriloop.getPlayInstallReferrer())
        assertEquals("utm_source=creator&at_click=tok", Attriloop.persistedPlayInstallReferrer())
        Attriloop.dropPlayInstallReferrerMemoryForTest()
        assertNull(Attriloop.getPlayInstallReferrer())
        Attriloop.restorePlayInstallReferrerFromPrefsForTest()
        assertEquals("utm_source=creator&at_click=tok", Attriloop.getPlayInstallReferrer())
        Attriloop.setPlayInstallReferrerForTest("")
        assertNull(Attriloop.getPlayInstallReferrer())
        assertNull(Attriloop.persistedPlayInstallReferrer())
    }
}
