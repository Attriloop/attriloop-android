# Attriloop Android SDK

Kotlin SDK for Attriloop — mobile attribution for influencer/creator links.

## Installation

Release `0.2.1` uses Maven Central. Once the protected release has completed and the
coordinate resolves publicly, keep `mavenCentral()` in the consumer repositories and
add the dependency:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.attriloop:attriloop-android:0.2.1")
}
```

The library declares `INTERNET` and ships `consumer-rules.pro` (R8/ProGuard safe).
It depends on `com.android.installreferrer:installreferrer` for deterministic
Play Store attribution.

It does not declare `AD_ID`, location, contacts, phone or storage permissions. Country
is derived server-side from the request IP, and Android's probabilistic fallback uses a
one-way fingerprint of IP/platform/locale when no deterministic referrer exists. Review
the complete [Google Play Data Safety guidance](PRIVACY.md) before publishing a host app.

### Customer user id and automatic opens

```kotlin
// After your own login: attach your user id to every install/event (persisted).
Attriloop.setCustomerUserId(user.id)   // pass null on logout
```

The SDK also emits an automatic `APP_OPEN` event on cold start and on every return
from background. Do not send it manually; it powers daily active users per link and
creator in the dashboard and is excluded from your event counts.

## Usage

```kotlin
import io.attriloop.sdk.Attriloop
import io.attriloop.sdk.AttriloopEvent

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Attriloop.configure(this, apiKey = "at_live_...")
    }
}

// Later, anywhere:
Attriloop.sendEvent(
    AttriloopEvent.PURCHASE,
    parameters = mapOf("revenue" to 29.99, "currency" to "USD"),
)

// CUSTOM events require a name (use the dedicated helper):
Attriloop.sendCustomEvent("share_referral", parameters = mapOf("channel" to "whatsapp"))

// Verify the key/endpoint during setup. Callback runs off the main thread.
Attriloop.sendTestEvent { result ->
    Log.d("Attriloop", "${result.success} — ${result.message}")
}

// Deferred attribution / creator params (resolved a few seconds after first-open):
val params = Attriloop.getAttributionParams() // mediaSource, campaignName, linkId, …
// …or be called back the moment it resolves:
Attriloop.getAttributionParams { p -> /* p["mediaSource"], p["campaignName"], p["linkId"] */ }
```

## How attribution works

1. On first-open the SDK fetches the **Google Play Install Referrer**. The redirect
   edge packs our `at_click` token (plus `utm_source` / `utm_campaign`) into the
   Play Store URL's `referrer` param — the only field Google forwards — so the
   backend decodes it to the exact click (deterministic, `match_method = play_referrer`).
2. If no referrer click id is present, the backend falls back to a coarse
   fingerprint (IP + platform + locale) within the attribution window.
3. An App Link opened with the app installed resolves through `/v1/resolve` and emits
   one automatic reengagement for that link; it never reassigns the install.
4. `getAttributionParams()` polls `GET /v1/attribution` until the backend resolves
   the install, then caches the result.

## App Links (open the app if installed)

Forward incoming App Link intents so the click token is captured and the deferred
destination (`deep_link_value`) is delivered:

```kotlin
// In your launcher Activity (onCreate + onNewIntent):
Attriloop.handleDeepLink(intent?.data)

// Route the user once the destination is known (relative, UNTRUSTED route):
Attriloop.onDeepLink { value -> router.navigate(value) }
```

The edge serves `/.well-known/assetlinks.json` for the package + signing certificate
entered in the **Attriloop console → SDK setup**.

## Parity with iOS

Same public surface as the iOS `AttriloopSDK`: `configure`, `setCustomerUserId`,
`sendEvent`, `sendCustomEvent`, `sendTestEvent`, `flushQueue`, `getAttriloopId`,
`getAttributionParams` (sync + async callback), `handleDeepLink`, `getDeepLink`,
`onDeepLink`. Event names are identical (`AttriloopEvent`). Differences: Android adds
the Play Install Referrer (deterministic) and a coarse fingerprint fallback; iOS adds
AdServices, SKAdNetwork and AdAttributionKit.

## Build & test

```bash
./gradlew test                # debug + release unit tests (requires Android SDK + JDK 17)
./gradlew assembleRelease     # build the release AAR
./gradlew publishToMavenLocal # unsigned local consumer smoke only
```

CI inspects the merged release manifest, so a transitive dependency cannot silently
add advertising ID, location, phone, contacts or legacy storage access without failing
the build and forcing a disclosure/consent review.

## Releasing

The `v0.2.1` tag starts the protected `maven-central` GitHub environment. After an
approver reviews the production smoke-test evidence, CI signs and publishes the AAR,
sources, documentation and POM through Central Portal, then creates the GitHub Release.
The repository must contain these secrets:

- `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD` (Central user token)
- `SIGNING_KEY` and `SIGNING_PASSWORD` (ASCII-armored PGP private key)

The `com.attriloop` namespace must already be verified in Central Portal. Published
Maven Central coordinates are immutable, so never push the release tag before the
clean-consumer smoke test passes.
