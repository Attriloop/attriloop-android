# Attriloop Android SDK — Google Play Data Safety guidance

This is the SDK provider's technical inventory for Google Play's Data Safety form. It
is not legal advice. The publisher of each host app remains responsible for one complete
and accurate declaration covering the app, Attriloop and every other SDK in all active
versions and regions.

## SDK permissions and access

The merged Attriloop release manifest contains only `android.permission.INTERNET`. The
SDK does not request advertising ID, location, phone, contacts, media/storage, camera or
microphone permissions, and it does not read Android ID, IMEI, MAC address or the list
of installed apps.

The SDK uses the official Google Play Install Referrer service. It also sends requests
over HTTPS; Cloudflare derives a country code from the request IP. When no deterministic
referrer exists, Android attribution may compare a one-way, short-window fingerprint of
IP, platform and locale. No equivalent fingerprint fallback runs on iOS.

## Data Safety inventory

Use this table as an input to Play Console, then adjust it for the host app's actual
configuration and event calls.

| Google Play data type | Attriloop behavior | Collection / purpose |
| --- | --- | --- |
| Device or other IDs | Generates a random Attriloop installation ID and links install/events to it. The Android fallback also derives a short-window matching fingerprint from IP/platform/locale. It does not use Advertising ID. | Collected when configured; app functionality and analytics. |
| Approximate location | Persists the country code inferred server-side from the network request; it never requests GPS or Android location permissions. | Collected when configured; analytics and attribution functionality. |
| App interactions | Sends first open, reengagement and only the event names/fields the host app explicitly emits. | Install is required once configured; later events depend on host usage. App functionality and analytics. |
| Purchase history | Sent only if the host app calls a purchase/refund/revenue event. Attriloop never reads Play Billing payment credentials. | Optional by integration; app functionality and analytics. |
| Name, email address, phone number, other info | Sent only if the host app explicitly includes these parameters. Known contact/date fields are normalized and SHA-256 hashed before disk or network, but pseudonymous/hashed data still requires disclosure. | Optional by integration; analytics. Do not send unless approved and documented. |
| Technical device signals | OS version, manufacturer/model, locale and timezone are sent at first open to operate and analyze attribution. | Collected when configured; app functionality and analytics. Confirm the closest Play Console category with the app's privacy reviewer. |

Never pass health, precise location, contacts, messages, photos, payment-card data or
other sensitive content in custom event parameters. Attriloop does not need them for
links-only organic attribution.

## Collection, sharing and security answers

- Data leaves the device and is therefore “collected” under Google Play's definition,
  including pseudonymous values and data sent by an SDK.
- Production transport is HTTPS. The SDK accepts plain HTTP only for explicit debug
  use on `localhost`/`127.0.0.1`; never ship a production build pointed there. The host
  may answer that all collected data is encrypted in transit only after checking every
  other app/SDK flow too.
- Attriloop processes each customer's data on that customer's behalf and does not build
  cross-customer advertising profiles. A transfer to Attriloop may qualify for Google's
  service-provider exception from “sharing” only when the actual contract/DPA and use
  satisfy that definition. Collection still must be declared.
- The SDK has no consent UI. The host app must decide whether collection is required or
  optional, initialize it only after any legally required disclosure/consent, and offer
  the deletion-request path promised in Play Console. Attriloop exposes a tenant-scoped
  subject deletion API, but the publisher must verify the requester and connect its UI
  or support process to that API.
- Do not use this beta SDK in a child-directed app until Families Policy suitability,
  disclosures and any required SDK self-certification have been reviewed.

## Host-app release checklist

1. Inventory every `sendEvent` / `sendCustomEvent` parameter and remove unused personal
   or sensitive fields.
2. Complete Data Safety for the whole package, including other SDKs. Closed/open and
   production tracks require the form; an app exclusively on internal testing is the
   limited exception described by Google.
3. Confirm the privacy policy names the categories, purposes, service providers,
   retention and deletion route actually used in production.
4. Inspect the final app bundle's merged manifest and network traffic; do not rely only
   on this library's source manifest.
5. Re-review the form whenever the SDK, event schema, backend processing or another
   dependency changes.

Current Google references:

- [Provide information for Google Play's Data Safety section](https://support.google.com/googleplay/android-developer/answer/10787469)
- [Declare your app's data use](https://developer.android.com/privacy-and-security/declare-data-use)
- [Using SDKs safely and securely](https://support.google.com/googleplay/android-developer/answer/13326895)
