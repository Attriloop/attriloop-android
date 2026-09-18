package io.attriloop.consumer

import android.app.Application
import android.net.Uri
import io.attriloop.sdk.Attriloop
import io.attriloop.sdk.AttriloopEvent
import io.attriloop.sdk.AttriloopTestResult

/** Compiles against the public SDK surface the way a host app would. */
fun touchPublicSurface(app: Application) {
    Attriloop.configure(app, apiKey = "at_live_xxx")
    Attriloop.setCustomerUserId("user-1")
    Attriloop.sendEvent(AttriloopEvent.PURCHASE, parameters = mapOf("revenue" to 1.0, "currency" to "USD"))
    Attriloop.sendCustomEvent("shared_profile")
    Attriloop.handleDeepLink(null as Uri?)
    Attriloop.getDeepLink()
    Attriloop.onDeepLink { }
    Attriloop.getAttributionParams()
    Attriloop.getAttributionParams { }
    Attriloop.flushQueue()
    Attriloop.sendTestEvent { _: AttriloopTestResult -> }
    Attriloop.attriloopId
}
