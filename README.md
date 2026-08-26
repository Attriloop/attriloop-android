# Attriloop Android SDK

Kotlin SDK for Attriloop — mobile attribution for influencer/creator links.

## Distribution status

The repository and artifact are currently a private beta. The library is tested and
builds a release AAR, but external customers must not be sent the JitPack coordinate
until the repository/tag is publicly resolvable (or an authenticated package registry
is selected).

## Install after public release

Add JitPack to your repositories, then the dependency:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.victorgxn:attriloop-android:0.1.0")
}
```

The library declares `INTERNET` and ships `consumer-rules.pro` (R8/ProGuard safe).
It depends on `com.android.installreferrer:installreferrer` for deterministic
Play Store attribution.

It does not declare `AD_ID`, location, contacts, phone or storage permissions. Country
is derived server-side from the request IP, and Android's probabilistic fallback uses a
one-way fingerprint of IP/platform/locale when no deterministic referrer exists. Review
the complete [Google Play Data Safety guidance](PRIVACY.md) before publishing a host app.

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

## Parity with iOS

Same public surface as the iOS `AttriloopSDK`: `configure`, `sendEvent`,
`sendCustomEvent`, `getAttriloopId`, `getAttributionParams` (sync + async callback).
Event names are identical (`AttriloopEvent`).

## Build & test

```bash
./gradlew test                # debug + release unit tests (requires Android SDK + JDK 17)
./gradlew assembleRelease     # build the release AAR
./gradlew publishToMavenLocal # build the AAR locally
```

CI inspects the merged release manifest, so a transitive dependency cannot silently
add advertising ID, location, phone, contacts or legacy storage access without failing
the build and forcing a disclosure/consent review.

## Releasing

JitPack builds from a Git tag (config in `jitpack.yml`). Only after choosing a public
distribution path, push a tag matching the `version` in `build.gradle.kts`:

```bash
git tag 0.1.0 && git push origin 0.1.0
```

Then the first resolve of `com.github.victorgxn:attriloop-android:0.1.0` triggers the
JitPack build; confirm it at `https://jitpack.io/#victorgxn/attriloop-android`.
