package io.attriloop.consumer

import android.app.Application
import android.content.Context
import android.net.Uri
import io.attriloop.sdk.Attriloop
import io.attriloop.sdk.AttriloopAttribution
import io.attriloop.sdk.AttriloopEvent
import io.attriloop.sdk.AttriloopMatchStatus
import io.attriloop.sdk.AttriloopTestResult

/** Compiles against the public SDK surface the way a host app would. */
fun touchPublicSurface(app: Application) {
    val early: String = Attriloop.attriloopId // minted before configure (init provider)
    val withContext: String = Attriloop.getAttriloopId(app as Context)
    Attriloop.setCustomerUserId("user-1") // works before configure
    Attriloop.configure(app, apiKey = "at_live_xxx")
    Attriloop.setCustomerUserId(null)
    Attriloop.sendEvent(AttriloopEvent.PURCHASE, parameters = mapOf("revenue" to 1.0, "currency" to "USD"))
    Attriloop.sendCustomEvent("shared_profile")
    Attriloop.setUserAttributes(
        email = "ana@example.com",
        firstName = "Ana",
        lastName = "Perez",
        name = null,
        phoneNumber = "+34 600 000 000",
        dateOfBirth = "1990-04-07",
        gender = "f",
    )
    Attriloop.setUserAttributes(email = "ana@example.com")
    Attriloop.clearUserAttributes()
    Attriloop.handleDeepLink(null as Uri?)
    Attriloop.getDeepLink()
    Attriloop.onDeepLink { }
    val params: Map<String, Any?> = Attriloop.getAttributionParams()
    Attriloop.getAttributionParams { _: Map<String, Any?> -> }
    Attriloop.getAttribution { _: AttriloopAttribution -> }
    val status: AttriloopMatchStatus = Attriloop.getMatchStatus()
    when (status) {
        AttriloopMatchStatus.matched,
        AttriloopMatchStatus.matchedNoParams,
        AttriloopMatchStatus.organic,
        AttriloopMatchStatus.ambiguous,
        AttriloopMatchStatus.failed,
        AttriloopMatchStatus.pending,
        AttriloopMatchStatus.skipped,
        AttriloopMatchStatus.notConfigured,
        -> Unit
    }
    Attriloop.getRevenueCatAttributes { attrs: Map<String, String> -> Purchases.sharedInstance.setAttributes(attrs) }
    Attriloop.getRevenueCatAttributes(timeoutMs = 3_000) { _: Map<String, String> -> }
    Attriloop.flushQueue()
    Attriloop.sendTestEvent { _: AttriloopTestResult -> }
    check(early.length + withContext.length + params.size >= 0)
}

/** README RevenueCat snippet (suspend form), set before the paywall. */
suspend fun revenueCatBeforePaywall() {
    val attrs = Attriloop.getRevenueCatAttributes(timeoutMs = 5_000)
    Purchases.sharedInstance.setAttributes(attrs)
}

/** README suspend wait for the initial match. */
suspend fun awaitParams(): String? =
    Attriloop.awaitAttributionParams()["attriloop_match_status"] as String?
