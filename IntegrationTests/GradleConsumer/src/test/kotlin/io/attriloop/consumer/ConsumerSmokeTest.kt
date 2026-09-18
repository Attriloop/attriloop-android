package io.attriloop.consumer

import io.attriloop.sdk.Attriloop
import io.attriloop.sdk.AttriloopEvent
import io.attriloop.sdk.AttriloopTestResult
import org.junit.Assert.assertEquals
import org.junit.Test

class ConsumerSmokeTest {
    @Test
    fun publicSurfaceIsResolvable() {
        assertEquals("PURCHASE", AttriloopEvent.PURCHASE.raw)
        assertEquals("APP_OPEN", AttriloopEvent.APP_OPEN.raw)
        assertEquals("REENGAGEMENT", AttriloopEvent.REENGAGEMENT.raw)
        assertEquals("configure", Attriloop::configure.name)
        assertEquals("setCustomerUserId", Attriloop::setCustomerUserId.name)
        assertEquals("sendEvent", Attriloop::sendEvent.name)
        assertEquals("sendCustomEvent", Attriloop::sendCustomEvent.name)
        assertEquals("sendTestEvent", Attriloop::sendTestEvent.name)
        assertEquals("flushQueue", Attriloop::flushQueue.name)
        assertEquals("handleDeepLink", Attriloop::handleDeepLink.name)
        assertEquals("getDeepLink", Attriloop::getDeepLink.name)
        assertEquals("onDeepLink", Attriloop::onDeepLink.name)
        val unused: AttriloopTestResult? = null
        assertEquals(null, unused)
    }
}
