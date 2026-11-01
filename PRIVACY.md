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
referrer exists, the install stays unattributed unless the customer enables Attriloop's
server-side probabilistic rung for the app (off by default, configurable per app and per
ad network, on iOS and Android alike). That rung may match an ad click on the request IP
and coarse location within a short window. It runs on the server, adds no device signal
to the SDK, is labelled with a matching type and confidence, feeds ad-network signals and
analytics only, and never pays a creator.

The library manifest merges `AttriloopInitProvider`, a non-exported content provider that
only stores the application context so the installation ID can be read before
`configure`. It reads no data and makes no request.

## Data Safety inventory

Use this table as an input to Play Console, then adjust it for the host app's actual
configuration and event calls.

| Google Play data type                         | Attriloop behavior                                                                                                                                                                                          | Collection / purpose                                                                                                                   |
| --------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| Device or other IDs                           | Generates a random Attriloop installation ID (created on first read, even before `configure`) and links install/events to it. It does not use Advertising ID or any hardware identifier.                  | Collected when configured; app functionality and analytics.                                                                            |
| Approximate location                          | Persists the country code inferred server-side from the network request; it never requests GPS or Android location permissions.                                                                             | Collected when configured; analytics and attribution functionality.                                                                    |
| App interactions                              | Sends first open, reengagement, an automatic `APP_OPEN` on cold start and on return from background, and only the event names/fields the host app explicitly emits.                                         | Install is required once configured; later events depend on host usage. App functionality and analytics.                               |
| User IDs                                      | Only if the host app calls `setCustomerUserId`: the host's own user id (1–128 printable characters), attached to every request and stored per installation. Emails and phone numbers are refused. Pass `null` on logout. | Optional by integration; app functionality and analytics. Declare it as a user identifier.                                             |
| Purchase history                              | Sent only if the host app calls a purchase/refund/revenue event. Attriloop never reads Play Billing payment credentials.                                                                                    | Optional by integration; app functionality and analytics.                                                                              |
| Name, email address, phone number             | Only if the host app calls `setUserAttributes` (or includes these event parameters). Normalized and SHA-256 hashed on the device before disk or network; the edge stores only the latest hashes per installation and erases them on a deletion request. Hashed data is still personal data and requires disclosure. | Optional by integration; linked to the user. Advertising or marketing (ad-network match keys for Meta / TikTok) and analytics. Requires the host's consent basis. |
| Personal info → Other info                    | Only via `setUserAttributes`: date of birth (hashed `YYYYMMDD`) and gender (hashed `m`/`f`). Same hashing, storage and erasure as above.                                                                    | Optional by integration; linked to the user. Advertising or marketing and analytics. Requires the host's consent basis.                |
| Technical device signals                      | OS version, manufacturer/model, locale and timezone are sent at first open to operate and analyze attribution.                                                                                              | Collected when configured; app functionality and analytics. Confirm the closest Play Console category with the app's privacy reviewer. |

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
- `setUserAttributes` has no consent gate on Android (unlike iOS, where it requires ATT
  authorization). Call it only after your app has a consent basis for sending hashed
  contact details to ad networks, and declare the data types above as collected and
  linked to the user.
- The SDK has no consent UI. The host app must decide whether collection is required or
  optional, initialize it only after any legally required disclosure/consent, and offer
  the deletion-request path promised in Play Console. Attriloop exposes a tenant-scoped
  subject deletion API, but the publisher must verify the requester and connect its UI
  or support process to that API.
- Do not use this SDK in a child-directed app until Families Policy suitability,
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
