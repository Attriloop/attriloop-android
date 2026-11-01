# Attriloop Android SDK

Kotlin SDK for Attriloop — mobile attribution for creator links and paid ads (Meta,
TikTok), with RevenueCat attribution.

## Installation

`0.3.0` is the current source version (see [Migrating to 0.3.0](#migrating-to-030)). The
tested `0.2.1` AAR is available as a [GitHub preview release](https://github.com/Attriloop/attriloop-android/releases/tag/artifact-0.2.1). Neither is a public Maven Central coordinate yet. Once the protected release has completed and the coordinate
resolves publicly, keep `mavenCentral()` in the consumer repositories and add the
dependency:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.attriloop:attriloop-android:0.3.0")
}
```

The library declares `INTERNET` and ships `consumer-rules.pro` (R8/ProGuard safe).
It depends on `com.android.installreferrer:installreferrer` for deterministic
Play Store attribution.

It does not declare `AD_ID`, location, contacts, phone or storage permissions and it
never reads the advertising ID. Country is derived server-side from the request IP.
Review the complete [Google Play Data Safety guidance](PRIVACY.md) before publishing a
host app.

The library manifest also merges a tiny `AttriloopInitProvider` (authority
`${applicationId}.attriloop-init`, not exported). It only stores the application context
so `getAttriloopId()` works before `configure`; it does no I/O. To remove it:

```xml
<provider
    android:name="io.attriloop.sdk.AttriloopInitProvider"
    android:authorities="${applicationId}.attriloop-init"
    tools:node="remove" />
```

Then read the id with `Attriloop.getAttriloopId(context)` before `configure` (without a
context and without the provider, `attriloopId` returns `""` and logs in debug).

### Install id

```kotlin
val id = Attriloop.attriloopId          // Kotlin; Java: Attriloop.getAttriloopId()
val id2 = Attriloop.getAttriloopId(ctx) // when the init provider was removed
```

The id is created and persisted on the first read, even before `configure`, and stays
the same for the life of the installation (`configure`, the install POST and every event
use it).

### Customer user id and automatic opens

```kotlin
// After your own login: attach your user id to every request (persisted).
Attriloop.setCustomerUserId(user.id)   // pass null (or "") on logout
```

`setCustomerUserId` works before and after `configure`, from any thread; last write
wins. Events carry the id set when they were recorded; events queued offline without one
get the current id when they are sent.
Values that look like an email or phone number are refused (the previous value is kept)
— send those hashed through `setUserAttributes`.

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

// Verify the key/endpoint during setup. Callback runs on the main thread.
Attriloop.sendTestEvent { result ->
    Log.d("Attriloop", "${result.success} — ${result.message}")
}

// Attribution params: waits for the initial match (about 30 s at most).
lifecycleScope.launch {
    val params = Attriloop.awaitAttributionParams()
    when (params["attriloop_match_status"]) {
        "matched" -> showPaywallFor(params["attriloop_campaign"] as String?)
        "matched_no_params", "organic", "skipped" -> showDefaultPaywall()
        "failed" -> showDefaultPaywall() // retried on the next call / launch
    }
}
// …or with a callback (main thread):
Attriloop.getAttributionParams { params -> /* same map */ }
Attriloop.getAttribution { result -> /* result.status, result.params */ }
// Cached, non-blocking:
val cached = Attriloop.getAttributionParams()
val status = Attriloop.getMatchStatus()

// Raw Play Install Referrer (same bytes posted on /v1/install). Null until Play
// returns a non-empty string. There is no setter — a host-supplied referrer is
// not merged with Play's.
val rawReferrer = Attriloop.getPlayInstallReferrer()
```

## Attribution params

Keys of the map (`getAttributionParams`, `awaitAttributionParams`):

| Key                       | Value                                                          |
| ------------------------- | -------------------------------------------------------------- |
| `attriloop_id`            | install id, always present                                     |
| `attriloop_match_status`  | see below, always present                                      |
| `attriloop_adnetwork`     | `meta`, `tiktok`, `google` (absent when unknown)               |
| `attriloop_campaign`      | campaign name, else campaign id                                |
| `attriloop_adset`         | ad set name, else id                                           |
| `attriloop_ad`            | ad name, else id                                               |
| `attriloop_matching_type` | `network`, `exact_ip`, `coordinates`, `city_region`, `geo`     |
| `attriloop_confidence`    | `low`, `medium`, `high`                                        |

Unknown keys are absent, never empty strings. The server decides every value; the SDK
does not compute what counts as attributed. The legacy server fields (`mediaSource`,
`campaignName`, `linkId`, `matchMethod`, `deep_link_value`, …) stay in the same map.

| `attriloop_match_status` | Meaning                                                       | Retryable |
| ------------------------ | ------------------------------------------------------------- | --------- |
| `matched`                | attributed, with network, campaign, ad set or ad              | no        |
| `matched_no_params`      | attributed, with none of those (for example a creator link)   | no        |
| `organic`                | initial match done, not attributed                            | no        |
| `skipped`                | no match will be attempted (erased subject)                   | no        |
| `failed`                 | timed out or network failure                                  | yes       |
| `not_configured`         | read before `configure`                                       | yes       |

Settled answers return from cache without waiting. After `failed` the SDK polls again on
the next call and on the next launch. While the server says a later upgrade is still
possible (late Play referrer, referral claim), the SDK polls once per launch and replaces
the cached map when the decision changes. `AttriloopMatchStatus.pending` / `ambiguous`
stay in the enum for `getMatchStatus()` but never appear in the map.

## User attributes (Meta / TikTok matching)

```kotlin
Attriloop.setUserAttributes(
    email = user.email,
    name = user.fullName,          // or firstName = …, lastName = …
    phoneNumber = "+34 600 000 000",
    dateOfBirth = "1990-04-07",    // YYYY-MM-DD, YYYY/MM/DD or YYYYMMDD
    gender = "f",                  // m / male / f / female
)
Attriloop.clearUserAttributes()    // on logout: deletes the stored hashes
```

Every value is normalized and SHA-256 hashed **on the device** before it is queued;
plaintext is never persisted or sent. Email, names, date of birth and gender use Meta's
rules (lowercase, trimmed, names without punctuation, DOB `YYYYMMDD`, gender `m`/`f`).
Phone gets one hash per network: `ph_meta` (digits with country code) and `ph_tiktok`
(E.164 with `+`); include the country code, it is never guessed. A full `name` is split
on its first space; explicit `firstName` / `lastName` win. A value that cannot be
normalized is dropped without dropping the rest. A 64-character hex value is treated as
already hashed. `sendCustomEvent("user_attributes", mapOf("phoneNumber" to …))` (also
`phone`, `birthdate`, `birthday`, `dateOfBirth`) is handled the same way.

**Consent:** Android has no ATT-style gate, so the SDK sends these whenever you call it.
Your app needs its own consent basis (and Data safety disclosure, see
[PRIVACY.md](PRIVACY.md)) before calling `setUserAttributes`.

## RevenueCat

RevenueCat is a separate SDK; Attriloop does not depend on it. Before the paywall loads,
set the attributes from `getRevenueCatAttributes` (it waits for the match, 5 s by
default). Keep RevenueCat's App User ID as **your** user id.

```kotlin
lifecycleScope.launch {
    val attrs = Attriloop.getRevenueCatAttributes(timeoutMs = 5_000)
    Purchases.sharedInstance.setAttributes(attrs)
    Purchases.sharedInstance.syncAttributesAndOfferingsIfNeededWith({ /* error */ }) { offerings ->
        // show the paywall with `offerings`
    }
}
// Callback form (main thread): Attriloop.getRevenueCatAttributes { attrs -> … }
```

The map always has `attriloop_id` (the key the RevenueCat webhook joins on) and
`attriloop_match_status`, plus the other `attriloop_*` keys when known. RevenueCat's
reserved `$mediaSource`, `$campaign`, `$adGroup` and `$ad` are set only for a `matched`
install, because RevenueCat never lets them change once set. Values are cut to 500
characters. On timeout you still get `attriloop_id` (enough for the join) and `failed`;
call the helper again on the next app start. Apps that set the legacy custom attribute
`attriloopId` keep working — the server still reads it — but new integrations should use
the helper.

## Migrating to 0.3.0

- Before `configure` the status is now `notConfigured` / `"not_configured"`. It used to
  be `skipped`, which now means "the server will not attempt a match". Update code that
  checked `.skipped` to detect a missing `configure`.
- `getAttributionParams` adds the `attriloop_*` keys; legacy keys are unchanged.
- An install that is unattributed inside the referral-claim window now reports `organic`
  (it used to stay `pending`); a later upgrade still reaches the cache on relaunch.
- `getAttriloopId()` no longer returns `""` before `configure` (init provider).
- `setCustomerUserId` refuses emails and phone numbers and works before `configure`; queued events without an id get it at send time.
- Every request carries `X-Attriloop-SDK: android/0.3.0`.

## How attribution works

1. On first-open the SDK fetches the **Google Play Install Referrer**. The redirect
   edge packs our `at_click` token (plus `utm_source` / `utm_campaign`) into the
   Play Store URL's `referrer` param — the only field Google forwards — so the
   backend decodes it to the exact click (deterministic, `match_method = play_referrer`).
2. If no referrer click id is present, the install stays **unattributed** unless the
   customer turned on the server-side **probabilistic rung** for the app (off by
   default, configurable per app and per ad network). That rung may match an ad click
   on the request IP / coarse location within its own window, labelled with
   `attriloop_matching_type` and `attriloop_confidence`. It feeds ad-network signals and
   analytics only and **never pays a creator**.
3. An App Link opened with the app installed resolves through `/v1/resolve` and emits
   one automatic reengagement for that link; it never reassigns the install.
4. `getAttribution()` / `awaitAttributionParams()` poll `GET /v1/attribution` until the
   backend reports a decision (`attriloop_match_status`). Silence is never `organic`:
   a wait without a decision ends `failed`.

`claimReferralCode` is public. The host must present the claim UI. If you skip it,
a store-drop install that never received Play stays unattributed. The creator is
not paid. A claim against an already-billable method returns `409 already_attributed`. An empty map is **not** organic — call
   `getMatchStatus()` / `getAttribution`.

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
`getAttributionParams` (sync, callback, `awaitAttributionParams` suspend), `getMatchStatus`,
`getAttribution`, `setUserAttributes`, `clearUserAttributes`, `getRevenueCatAttributes`,
`handleDeepLink`, `getDeepLink`, `onDeepLink`. Both SDKs return the same params keys and
statuses for the same server response, and the same user-attribute hashes (shared test
vectors). Event names are identical (`AttriloopEvent`). Host callbacks
(`onDeepLink`, `getAttributionParams`, `sendTestEvent`) run on the main thread.
Differences: Android adds the Play Install Referrer (deterministic) and
`getPlayInstallReferrer()` (read-only; no host write). iOS sends user attributes only
with ATT authorization, to a separate tracking host; Android has no such gate. iOS adds AdServices,
SKAdNetwork, AdAttributionKit, and `onReferralCodeFromUniversalLink`.
`claimReferralCode` exists on both.

## Build & test

```bash
./gradlew test                # unit tests incl. the shared hash + params fixtures (Android SDK + JDK 17)
./gradlew assembleRelease     # build the release AAR
./gradlew -p IntegrationTests/GradleConsumer test  # clean-consumer public API smoke
./gradlew publishToMavenLocal # unsigned local Maven smoke only
```

`src/test/resources/user-attributes.json` and `attribution-poll.json` are copied from
the attriloop-saas repo (`scripts/sync-test-vectors.sh`); never edit them here.

CI inspects the merged release manifest, so a transitive dependency cannot silently
add advertising ID, location, phone, contacts or legacy storage access without failing
the build and forcing a disclosure/consent review.

## Releasing

A `v<version>` tag (for example `v0.3.0`) starts the protected `maven-central` GitHub environment. After an
approver reviews the production smoke-test evidence, CI signs and publishes the AAR,
sources, documentation and POM through Central Portal, then creates the GitHub Release.
The repository must contain these secrets:

- `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD` (Central user token)
- `SIGNING_KEY` and `SIGNING_PASSWORD` (ASCII-armored PGP private key)

The `com.attriloop` namespace must already be verified in Central Portal. Published
Maven Central coordinates are immutable, so never push the release tag before the
clean-consumer smoke test passes.
