# AppsTrack Android SDK

Kotlin SDK for AppsTrack — mobile attribution for influencer/creator links.

## Install (JitPack)

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
    implementation("com.github.victorgxn:apptrack-android:0.1.0")
}
```

The library declares `INTERNET` and ships `consumer-rules.pro` (R8/ProGuard safe).
It depends on `com.android.installreferrer:installreferrer` for deterministic
Play Store attribution.

## Usage

```kotlin
import io.apptrack.sdk.AppsTrack
import io.apptrack.sdk.AppsTrackEvent

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppsTrack.configure(this, apiKey = "at_live_...")
    }
}

// Later, anywhere:
AppsTrack.sendEvent(
    AppsTrackEvent.PURCHASE,
    parameters = mapOf("revenue" to 29.99, "currency" to "USD"),
)

// CUSTOM events require a name (use the dedicated helper):
AppsTrack.sendCustomEvent("share_referral", parameters = mapOf("channel" to "whatsapp"))

// Deferred attribution / creator params (resolved a few seconds after first-open):
val params = AppsTrack.getAttributionParams() // mediaSource, campaignName, linkId, …
// …or be called back the moment it resolves:
AppsTrack.getAttributionParams { p -> /* p["mediaSource"], p["campaignName"], p["linkId"] */ }
```

## How attribution works

1. On first-open the SDK fetches the **Google Play Install Referrer**. The redirect
   edge packs our `at_click` token (plus `utm_source` / `utm_campaign`) into the
   Play Store URL's `referrer` param — the only field Google forwards — so the
   backend decodes it to the exact click (deterministic, `match_method = play_referrer`).
2. If no referrer click id is present, the backend falls back to a coarse
   fingerprint (IP + platform + locale) within the attribution window.
3. `getAttributionParams()` polls `GET /v1/attribution` until the backend resolves
   the install, then caches the result.

## Parity with iOS

Same public surface as the iOS `AppsTrackSDK`: `configure`, `sendEvent`,
`sendCustomEvent`, `getApptrackId`, `getAttributionParams` (sync + async callback).
Event names are identical (`AppsTrackEvent`).

## Build & test

```bash
./gradlew testDebugUnitTest   # unit tests (requires Android SDK + JDK 17)
./gradlew publishToMavenLocal # build the AAR locally
```

## Releasing

JitPack builds from a Git tag (config in `jitpack.yml`). Push a tag matching the
`version` in `build.gradle.kts`:

```bash
git tag 0.1.0 && git push origin 0.1.0
```

Then the first resolve of `com.github.victorgxn:apptrack-android:0.1.0` triggers the
JitPack build; confirm it at `https://jitpack.io/#victorgxn/apptrack-android`.
