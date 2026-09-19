package io.attriloop.sdk

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Standard event types. INSTALL is emitted by the backend on first-open. */
enum class AttriloopEvent(val raw: String) {
    LOGIN("LOGIN"),
    SIGN_UP("SIGN_UP"),
    REGISTER("REGISTER"),
    PURCHASE("PURCHASE"),
    ADD_TO_WISHLIST("ADD_TO_WISHLIST"),
    INITIATE_CHECKOUT("INITIATE_CHECKOUT"),
    START_TRIAL("START_TRIAL"),
    SUBSCRIBE("SUBSCRIBE"),
    REENGAGEMENT("REENGAGEMENT"), // emitted automatically after an App Link resolves
    APP_OPEN("APP_OPEN"), // emitted automatically on cold start and every return to foreground
    LEVEL_START("LEVEL_START"),
    LEVEL_COMPLETE("LEVEL_COMPLETE"),
    TUTORIAL_COMPLETE("TUTORIAL_COMPLETE"),
    SEARCH("SEARCH"),
    SHARE("SHARE"),
    CUSTOM("CUSTOM"),
}

/** Result returned by [Attriloop.sendTestEvent] during SDK onboarding. */
data class AttriloopTestResult(val success: Boolean, val message: String)

/**
 * Attriloop Android SDK. Mirrors the iOS surface.
 *
 * ```kotlin
 * Attriloop.configure(context, "at_live_...")
 * Attriloop.sendEvent(AttriloopEvent.PURCHASE, parameters = mapOf("revenue" to 29.99, "currency" to "USD"))
 * Attriloop.sendCustomEvent("share_referral", parameters = mapOf("channel" to "whatsapp"))
 * ```
 *
 * On first-open it fetches the Google Play Install Referrer (deterministic
 * attribution: the redirect edge packs our `at_click` token into the Play Store
 * `referrer` param) and posts it with the install.
 */
object Attriloop {
    private const val PREFS = "com.attriloop.sdk"
    private const val ID_KEY = "attriloop_id"
    private const val INSTALLED_KEY = "installed"
    private const val ATTR_KEY = "attribution"
    private const val MATCH_STATUS_KEY = "matchStatus"
    private const val CLICK_KEY = "clickId" // at_click captured from a deep link
    private const val DEEPLINK_KEY = "deepLink" // last resolved deep_link_value
    private const val QUEUE_KEY = "queue" // persisted offline event buffer
    private const val REFERRER_SENT_KEY = "referrerSent" // Play referrer delivered (with the install or as referrer_update)
    private const val PLAY_REFERRER_KEY = "playInstallReferrer" // raw Play Install Referrer string, read-only to the host
    private const val CUSTOMER_USER_ID_KEY = "customerUserId" // host app's own user id, attached to every request
    private const val CUSTOMER_USER_ID_MAX = 128
    private const val MAX_QUEUE = 500 // oldest dropped beyond this
    /** Events per `POST /v1/events` request (the edge accepts up to 50). */
    internal const val BATCH_SIZE = 25
    /** Play Services can cold-start slowly; bound first-open latency at this many seconds. */
    internal const val REFERRER_FALLBACK_SECONDS = 8L
    private const val SDK_VERSION = "android/0.2.1"
    private const val DEFAULT_BASE_URL = "https://api.attriloop.com"

    private val io = Executors.newSingleThreadScheduledExecutor()
    private lateinit var prefs: SharedPreferences
    private var apiKey = ""
    private var baseUrl = DEFAULT_BASE_URL
    private var debug = false
    private var packageName = ""

    // One in-flight queue-drain chain at a time.
    private val flushing = AtomicBoolean(false)
    // Set for the rest of the session when the edge answers 404 to POST /v1/events
    // (an older edge without batch ingest) — the drain then uses /v1/event only.
    @Volatile
    private var batchUnsupported = false
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    @Volatile
    private var attribution: Map<String, Any?> = emptyMap()
    @Volatile
    private var matchStatus: AttriloopMatchStatus = AttriloopMatchStatus.skipped
    /** Last non-empty string Play returned. Null until then. Never a host-supplied value. */
    @Volatile
    private var playInstallReferrer: String? = null
    @Volatile
    private var pollHeardPending = false
    @Volatile
    private var pollTransportFailed = false

    // Async attribution waiters, flushed once the poll chain resolves or gives up.
    private val handlers = ArrayList<(Map<String, Any?>) -> Unit>()
    private val attributionHandlers = ArrayList<(AttriloopAttribution) -> Unit>()

    // Deferred deep-link destination (`deep_link_value`) + its subscribers.
    //
    // ONCE-ONLY DELIVERY: a resolved deep_link_value is broadcast to onDeepLink
    // subscribers EXACTLY ONCE per resolution, then consumed. `deepLinkPending` is a
    // non-persisted, in-memory flag that is true ONLY while a value resolved in THIS
    // session (via setDeepLink — from an App Link resolve or the attribution poll) is
    // still waiting to be delivered. A value merely reloaded from SharedPreferences in
    // configure() is NOT pending: it populates `deepLink` for getDeepLink() reads but
    // must never auto-re-broadcast to onDeepLink on a later (e.g. organic) launch.
    @Volatile
    private var deepLink: String? = null
    @Volatile
    private var deepLinkPending: Boolean = false
    private val deepLinkHandlers = ArrayList<(String) -> Unit>()

    // Guards: install is sent exactly once across the referrer callbacks; only one
    // attribution poll chain runs at a time.
    private val installSent = AtomicBoolean(false)
    private val polling = AtomicBoolean(false)
    // In-memory once-only guard for the referrer_update POST (REFERRER_SENT_KEY is the
    // durable one, committed only after the POST lands).
    private val referrerUpdateClaimed = AtomicBoolean(false)
    // A late Play referrer that arrived while the fallback install POST was still in
    // flight; sent as referrer_update once that install is acknowledged.
    @Volatile
    private var pendingReferrerUpdate: PlayReferrerHandoff? = null

    /**
     * Public install id. The host sets RevenueCat custom subscriber attribute
     * `attriloopId` from this value **before the paywall**. This SDK does not
     * depend on RevenueCat — the host wires the two. Skipping that attribute
     * records the purchase and does not pay the creator.
     */
    @JvmStatic
    var attriloopId: String = ""
        private set

    /** The host app's own user id, once set via [setCustomerUserId]; attached to every request. */
    @Volatile
    private var customerUserId: String? = null

    // Foreground tracking for the automatic APP_OPEN event: one per cold start, then one
    // each time the app returns from background (all Activities stopped → one started).
    private val lifecycleRegistered = AtomicBoolean(false)
    private var startedActivities = 0

    @JvmStatic
    @JvmOverloads
    fun configure(context: Context, apiKey: String, baseUrl: String? = null, debug: Boolean = false) {
        val app = context.applicationContext
        this.prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        this.apiKey = apiKey
        this.packageName = app.packageName
        this.baseUrl = DEFAULT_BASE_URL
        if (baseUrl != null && isAllowedBaseUrl(baseUrl, debug)) this.baseUrl = baseUrl.trimEnd('/')
        this.debug = debug

        this.attriloopId = prefs.getString(ID_KEY, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(ID_KEY, it).apply()
        }
        prefs.getString(ATTR_KEY, null)?.let {
            // Corrupt persisted cache must never crash the host app at startup.
            try {
                attribution = jsonToMap(JSONObject(it))
            } catch (e: Exception) {
                if (debug) e.printStackTrace()
            }
        }
        matchStatus = AttriloopMatchStatus.restored(
            configured = apiKey.isNotEmpty(),
            persisted = prefs.getString(MATCH_STATUS_KEY, null),
            params = attribution,
        )
        // Reload the last persisted deep_link_value for getDeepLink() reads, but leave
        // it NOT pending: a value from a previous launch is already-consumed and must
        // not re-fire onDeepLink (see the once-only note on deepLinkPending).
        deepLink = prefs.getString(DEEPLINK_KEY, null)
        customerUserId = prefs.getString(CUSTOMER_USER_ID_KEY, null)
        restorePlayInstallReferrerFromPrefs()
        registerForegroundTracking(app)

        // INSTALLED_KEY is committed only after the install POST succeeds (sendInstall),
        // so a first-open with no connectivity retries on the next launch instead of
        // permanently losing the install.
        if (!prefs.getBoolean(INSTALLED_KEY, false)) {
            fetchReferrerThenInstall(app)
        }
        fetchAttribution(0)
        flushQueue() // re-send any events buffered offline in a previous session
        sendAppOpen() // cold start counts as the first open of this process
    }

    /**
     * Attach the host app's own user id to every install/event from now on (and persist
     * it across launches). Lets the customer join Attriloop installs with their users and
     * with RevenueCat without manual reconciliation. Pass null to clear. Values are
     * trimmed and capped at 128 characters; never pass an email or phone here — use the
     * hashed advanced-matching parameters on sendEvent for that.
     */
    @JvmStatic
    fun setCustomerUserId(id: String?) {
        if (!isConfigured("setCustomerUserId")) return
        val normalized = normalizeCustomerUserId(id)
        customerUserId = normalized
        prefs.edit().apply {
            if (normalized == null) remove(CUSTOMER_USER_ID_KEY) else putString(CUSTOMER_USER_ID_KEY, normalized)
        }.apply()
    }

    /** Trim, drop empties and cap at [CUSTOMER_USER_ID_MAX]. Pure; for tests. */
    internal fun normalizeCustomerUserId(id: String?): String? {
        val trimmed = id?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (trimmed.length > CUSTOMER_USER_ID_MAX) trimmed.substring(0, CUSTOMER_USER_ID_MAX) else trimmed
    }

    /** Spoken alphabet. Same as `@attriloop/core` — no confusable mapping. */
    internal const val REFERRAL_CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private const val REFERRAL_CODE_LENGTH = 6

    /**
     * Submit a creator referral code the user typed or heard. The edge upgrades an
     * unattributed or organic install when the claim is valid. Empty or malformed
     * input is ignored and never sent. Does not persist the code locally —
     * console regeneration is the kill switch for a leaked printed code.
     */
    @JvmStatic
    @JvmOverloads
    fun claimReferralCode(code: String, callback: ((Boolean) -> Unit)? = null) {
        if (!isConfigured("claimReferralCode")) {
            dispatchToMain { callback?.invoke(false) }
            return
        }
        val normalized = normalizeReferralCode(code)
        if (normalized == null) {
            dispatchToMain { callback?.invoke(false) }
            return
        }
        claimReferralCodeAttempt(normalized, 3, callback)
    }

    /** NFKC, strip whitespace/hyphens, uppercase, allowlist. Pure; for tests. */
    internal fun normalizeReferralCode(raw: String): String? {
        val folded = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFKC)
            .uppercase(Locale.US)
        val compact = buildString {
            for (ch in folded) {
                if (ch.isWhitespace() || ch == '-') continue
                append(ch)
            }
        }
        if (compact.length != REFERRAL_CODE_LENGTH) return null
        if (compact.any { it !in REFERRAL_CODE_ALPHABET }) return null
        return compact
    }

    private fun claimReferralCodeAttempt(
        code: String,
        remaining: Int,
        callback: ((Boolean) -> Unit)?,
    ) {
        io.execute {
            val body = JSONObject()
                .put("attriloopId", attriloopId)
                .put("code", code)
            val response = postForResponse("/v1/referral-claim", body)
            if (response != null && response.code in 200..299) {
                dispatchToMain { callback?.invoke(true) }
                return@execute
            }
            if (response?.code == 404 && remaining > 0 && referralClaimError(response.body) == "install_not_found") {
                io.schedule(
                    { claimReferralCodeAttempt(code, remaining - 1, callback) },
                    1000,
                    TimeUnit.MILLISECONDS,
                )
                return@execute
            }
            dispatchToMain { callback?.invoke(false) }
        }
    }

    internal fun referralClaimError(body: String?): String? {
        if (body.isNullOrEmpty()) return null
        return try {
            JSONObject(body).optString("error").takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    /** Add `customerUserId` to an outgoing body when the host has set one. */
    private fun JSONObject.withCustomerUserId(): JSONObject {
        customerUserId?.let { put("customerUserId", it) }
        return this
    }

    /**
     * Automatic APP_OPEN: once per cold start (from configure) and once per return to
     * the foreground. The backend dedupes per subject per UTC day for active-user
     * counts, so an occasional extra open is harmless; a missing one is not.
     */
    private fun sendAppOpen() {
        val eventTime = synchronized(iso) { iso.format(Date()) }
        enqueue(
            JSONObject()
                .put("attriloopId", attriloopId)
                .put("appId", packageName)
                .put("event", AttriloopEvent.APP_OPEN.raw)
                .put("eid", UUID.randomUUID().toString())
                .put("eventTime", eventTime)
                .withCustomerUserId(),
        )
        flushQueue()
    }

    /**
     * Count started Activities to detect background → foreground transitions without an
     * androidx.lifecycle dependency. Registered once per process; the cold-start open is
     * sent by configure(), so the first Activity start is deliberately NOT counted.
     */
    private fun registerForegroundTracking(app: Context) {
        val application = app as? Application ?: return
        if (!lifecycleRegistered.compareAndSet(false, true)) return
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var coldStartConsumed = false
            override fun onActivityStarted(activity: Activity) {
                val wasBackground = startedActivities == 0
                startedActivities++
                if (wasBackground) {
                    if (!coldStartConsumed) coldStartConsumed = true else sendAppOpen()
                }
            }
            override fun onActivityStopped(activity: Activity) {
                if (startedActivities > 0) startedActivities--
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /** Pure decision helper for tests: should this Activity start emit APP_OPEN? */
    internal fun foregroundOpenNeeded(startedBefore: Int, coldStartConsumed: Boolean): Boolean =
        startedBefore == 0 && coldStartConsumed

    /**
     * Record an in-app event. The event is persisted to a durable on-disk queue
     * FIRST, then a flush is attempted — so a transient network failure, timeout, or
     * the process being killed mid-send never loses it. Pending events re-send on the
     * next sendEvent, on the next configure(), and right after the install POST lands.
     */
    @JvmStatic
    @JvmOverloads
    fun sendEvent(event: AttriloopEvent, name: String? = null, parameters: Map<String, Any?>? = null) {
        // configure() must run first: `prefs` is lateinit and the queue lives there. An
        // uncaught UninitializedPropertyAccessException on the `io` thread would kill the
        // HOST process (Android crashes on any uncaught exception, on any thread).
        if (!isConfigured("sendEvent")) return
        if (event == AttriloopEvent.REENGAGEMENT || event == AttriloopEvent.APP_OPEN) {
            if (debug) println("[Attriloop] ${event.raw} is emitted automatically — event dropped")
            return
        }
        // A CUSTOM event with no name is rejected by the backend (422) and the offline
        // queue would then drop it as a permanent failure — silently losing it. Refuse
        // it here so the mistake is visible in debug. Use sendCustomEvent(name, …).
        if (isDroppableCustom(event, name)) {
            if (debug) println("[Attriloop] CUSTOM event requires a non-empty name — event dropped")
            return
        }
        // SimpleDateFormat is NOT thread-safe and sendEvent runs on arbitrary caller
        // threads — synchronize access to the shared formatter.
        val eventTime = synchronized(iso) { iso.format(Date()) }
        val item = JSONObject()
            .put("attriloopId", attriloopId)
            .put("appId", packageName)
            .put("event", event.raw)
            .put("eid", UUID.randomUUID().toString()) // idempotency key; backend dedupes retries
            .put("eventTime", eventTime)
            .withCustomerUserId()
        if (name != null) item.put("name", name)
        if (parameters != null) item.put("parameters", JSONObject(sanitizedParameters(parameters)))
        enqueue(item)
        flushQueue()
    }

    /** Record a CUSTOM event. [name] is the custom event label and is required. */
    @JvmStatic
    @JvmOverloads
    fun sendCustomEvent(name: String, parameters: Map<String, Any?>? = null) =
        sendEvent(AttriloopEvent.CUSTOM, name, parameters)

    /** Credit an installed-app open to its resolve-only click without reassigning the install. */
    private fun sendReengagement(clickId: String) {
        val eventTime = synchronized(iso) { iso.format(Date()) }
        enqueue(
            JSONObject()
                .put("attriloopId", attriloopId)
                .put("appId", packageName)
                .put("event", AttriloopEvent.REENGAGEMENT.raw)
                .put("clickId", clickId)
                .put("eid", reengagementEventId(clickId))
                .put("eventTime", eventTime),
        )
        flushQueue()
    }

    /** Stable per resolve token so retries/replays never count the same reopen twice. */
    internal fun reengagementEventId(clickId: String): String = "reengagement:$clickId"

    /**
     * Verify that the configured SDK key can reach Attriloop. The callback runs on
     * the main thread so setup screens can update UI directly. A successful response
     * also stamps the app as connected in the dashboard.
     */
    @JvmStatic
    fun sendTestEvent(callback: (AttriloopTestResult) -> Unit) {
        if (apiKey.isBlank()) {
            dispatchToMain { callback(AttriloopTestResult(false, "Call configure() before sendTestEvent().")) }
            return
        }
        io.execute {
            val result = try {
                withConnection("/v1/test", "POST") { conn ->
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(testEventBody().toString().toByteArray()) }
                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    interpretTestResponse(code, stream?.let(::readBounded))
                }
            } catch (e: Exception) {
                if (debug) e.printStackTrace()
                AttriloopTestResult(false, "Couldn't reach Attriloop. Check the endpoint and network.")
            }
            dispatchToMain { callback(result) }
        }
    }

    /** Body of `POST /v1/test` — same subject fields iOS sends. Pure; for tests. */
    internal fun testEventBody(): JSONObject = JSONObject()
        .put("attriloopId", attriloopId)
        .put("appId", packageName)

    /** Convert the connectivity endpoint response to the stable public result. */
    internal fun interpretTestResponse(code: Int, body: String?): AttriloopTestResult {
        val json = try {
            body?.takeIf(String::isNotBlank)?.let(::JSONObject)
        } catch (_: Exception) {
            null
        }
        val message = json?.optString("message")?.takeIf(String::isNotBlank)
        return if (code in 200..299 && json?.optBoolean("ok", true) != false) {
            AttriloopTestResult(true, message ?: "SDK connected.")
        } else {
            AttriloopTestResult(
                false,
                message ?: when (code) {
                    401 -> "Invalid API key."
                    403 -> "This key cannot send SDK events."
                    429 -> "Rate limited. Try again shortly."
                    else -> "Attriloop returned HTTP $code."
                },
            )
        }
    }

    /** A CUSTOM event with no usable name can never be ingested. Pure; for tests. */
    internal fun isDroppableCustom(event: AttriloopEvent, name: String?): Boolean =
        event == AttriloopEvent.CUSTOM && name.isNullOrEmpty()

    private val sensitiveParameterKeys = setOf(
        "email", "name", "phone", "phone_number", "date_of_birth", "dob", "first_name", "last_name",
    )

    /**
     * Hash advanced-matching identifiers before they reach disk or the network, and
     * VALIDATE the keys the backend is strict about (mirrors the iOS validatedParams):
     * `revenue`/`price` must be a finite non-negative number and `currency` three
     * letters. A bad value drops just that key — never the whole event, which would
     * otherwise 422 and be discarded from the offline queue as a permanent failure
     * (a silently lost purchase). Non-finite doubles anywhere are dropped because
     * org.json cannot serialize them (see enqueue).
     */
    internal fun sanitizedParameters(parameters: Map<String, Any?>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for ((key, value) in parameters) {
            val lower = key.lowercase(Locale.US)
            when {
                lower in sensitiveParameterKeys -> {
                    val raw = value as? String ?: continue
                    hashedSensitiveValue(raw, key)?.let { out[key] = it }
                }
                lower == "revenue" || lower == "price" ->
                    coerceNonNegNumber(value)?.let { out[key] = it }
                lower == "currency" -> {
                    val code = value as? String
                    if (code != null && Regex("^[A-Za-z]{3}$").matches(code)) out[key] = code
                }
                value is Double && !value.isFinite() -> continue
                value is Float && !value.isFinite() -> continue
                else -> out[key] = value
            }
        }
        return out
    }

    /**
     * Coerce to a finite, non-negative Double: accepts Int/Long/Float/Double and numeric
     * strings ("9.99"). Booleans and anything else return null. Pure; for tests.
     */
    internal fun coerceNonNegNumber(value: Any?): Double? {
        val d: Double? = when (value) {
            is Boolean -> null
            is Double -> value
            is Float -> value.toDouble()
            is Int -> value.toDouble()
            is Long -> value.toDouble()
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull()
            else -> null
        }
        return d?.takeIf { it.isFinite() && it >= 0.0 }
    }

    internal fun hashedSensitiveValue(value: String, key: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.matches(Regex("^[0-9a-fA-F]{64}$"))) return trimmed.lowercase(Locale.US)
        val normalized = when (key.lowercase(Locale.US)) {
            "phone", "phone_number", "date_of_birth", "dob" -> trimmed.filter(Char::isDigit)
            else -> trimmed.lowercase(Locale.US).split(Regex("\\s+")).joinToString(" ")
        }
        if (normalized.isEmpty()) return null
        return MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /** Append to the persisted queue (capped — oldest dropped first). Runs on `io`. */
    private fun enqueue(item: JSONObject) {
        if (!::prefs.isInitialized) return
        io.execute {
            val arr = readQueue()
            arr.put(item)
            val out = cappedQueue(arr, MAX_QUEUE)
            if (debug && out.length() < arr.length()) {
                println("[Attriloop] event queue full — dropping ${arr.length() - out.length()} oldest")
            }
            // Android's org.json returns null from toString() when any value is NaN /
            // Infinity; putString(key, null) would then ERASE the whole queue. Parameters
            // are sanitized upstream, but never let a serialization failure wipe it.
            val serialized: String? = out.toString()
            val persisted = serialized ?: run {
                if (debug) println("[Attriloop] queue serialization failed — event dropped, queue kept")
                arr.remove(arr.length() - 1)
                cappedQueue(arr, MAX_QUEUE).toString()
            }
            prefs.edit().putString(QUEUE_KEY, persisted).apply()
        }
    }

    /** Cap a queue to [max], dropping the oldest entries. Pure; exposed for tests. */
    internal fun cappedQueue(arr: JSONArray, max: Int): JSONArray {
        if (arr.length() <= max) return arr
        val out = JSONArray()
        for (i in (arr.length() - max) until arr.length()) out.put(arr.get(i))
        return out
    }

    private fun readQueue(): JSONArray =
        prefs.getString(QUEUE_KEY, null)?.let {
            try {
                JSONArray(it)
            } catch (e: Exception) {
                if (debug) e.printStackTrace()
                JSONArray()
            }
        } ?: JSONArray()

    /**
     * Drain the persisted queue head-first. A delivered (2xx) OR permanently-rejected
     * (4xx) head is removed; a transient failure (5xx / network / 408 / 429) stops the
     * chain so the rest stay queued for a later flush. One in-flight chain at a time,
     * guarded by [flushing]. Dropping permanent rejections is what stops one bad event
     * (e.g. a 422) from head-of-line-blocking the whole queue forever.
     */
    @JvmStatic
    fun flushQueue() {
        if (!isConfigured("flushQueue")) return
        if (!flushing.compareAndSet(false, true)) return
        sendNextQueued()
    }

    /** True once configure() ran with a non-empty key. Logs in debug so the integration mistake is visible. */
    private fun isConfigured(caller: String): Boolean {
        if (::prefs.isInitialized && apiKey.isNotEmpty()) return true
        if (debug) println("[Attriloop] $caller called before configure() — ignored")
        return false
    }

    /**
     * One drain step. Preferred path: up to [BATCH_SIZE] head items in ONE
     * `POST /v1/events`; the per-item verdicts in the 2xx body decide what leaves the
     * queue (see [partitionBatchResults]). A permanent whole-batch failure (a 4xx that
     * [classifyOutcome] does not treat as transient — e.g. 422 envelope, 413, or 404
     * from an edge without batch ingest) falls back to the legacy single-item
     * `POST /v1/event` for the head, so a bad envelope never head-of-line-blocks and
     * an older edge keeps working (404 is remembered for the session).
     */
    private fun sendNextQueued() {
        io.execute {
            val arr = readQueue()
            if (arr.length() == 0) {
                flushing.set(false)
                return@execute
            }
            // Single attempt per step (the queue itself is the retry mechanism). Runs on
            // the single `io` thread, so enqueue/drain are serialized.
            val batch = if (batchUnsupported) emptyList() else batchSlice(arr, BATCH_SIZE)
            if (batch.isEmpty()) {
                sendHeadLegacy(arr.getJSONObject(0))
                return@execute
            }
            val sentEids = batch.map { it.getString("eid") }
            val response = postForResponse("/v1/events", JSONObject().put("events", JSONArray(batch)))
            if (response == null) {
                flushing.set(false) // network / exception → retry on next flush trigger
                return@execute
            }
            when (classifyOutcome(response.code)) {
                Outcome.OK -> {
                    val partition = partitionBatchResults(sentEids, response.body)
                    if (debug && partition.permanentDrops.isNotEmpty()) {
                        println("[Attriloop] dropping ${partition.permanentDrops.size} rejected/suppressed event(s): ${partition.permanentDrops}")
                    }
                    removeQueued(partition.remove)
                    sendNextQueued() // keep draining
                }
                Outcome.PERMANENT -> {
                    if (response.code == 404) {
                        batchUnsupported = true
                        if (debug) println("[Attriloop] /v1/events unavailable (404) — using /v1/event for this session")
                    } else if (debug) {
                        println("[Attriloop] /v1/events rejected the batch (HTTP ${response.code}) — falling back to /v1/event")
                    }
                    sendHeadLegacy(arr.getJSONObject(0))
                }
                Outcome.TRANSIENT -> flushing.set(false)
            }
        }
    }

    /** Legacy single-item delivery of the queue head via `POST /v1/event`. On `io`. */
    private fun sendHeadLegacy(head: JSONObject) {
        val outcome = postOnce("/v1/event", head)
        if (outcome == Outcome.TRANSIENT) {
            flushing.set(false) // retry on next flush trigger
            return
        }
        if (outcome == Outcome.PERMANENT && debug) {
            println("[Attriloop] dropping permanently-rejected event")
        }
        val eid = head.optString("eid", "")
        if (eid.isNotEmpty()) {
            removeQueued(setOf(eid))
        } else {
            // Legacy item without an eid: head removal is the only handle we have.
            val cur = readQueue()
            val next = JSONArray()
            for (i in 1 until cur.length()) next.put(cur.get(i))
            prefs.edit().putString(QUEUE_KEY, next.toString()).apply()
        }
        sendNextQueued() // keep draining
    }

    /**
     * Remove BY eid, never by index: the queue is re-read after the POST, so a cap
     * eviction that happened meanwhile can't make us drop a different, never-sent
     * event. An eid that is already gone was cap-evicted — nothing to do. On `io`.
     */
    private fun removeQueued(eids: Set<String>) {
        if (eids.isEmpty()) return
        val next = withoutEids(readQueue(), eids)
        prefs.edit().putString(QUEUE_KEY, next.toString()).apply()
    }

    /**
     * Contiguous head items that carry a non-empty eid, at most [max]. Stops at the first
     * eid-less (legacy) item, which the drain then sends via the single-item path so it
     * can be removed by position. Pure; exposed for tests.
     */
    internal fun batchSlice(arr: JSONArray, max: Int): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        for (i in 0 until minOf(arr.length(), max)) {
            val item = arr.optJSONObject(i) ?: break
            if (item.optString("eid", "").isEmpty()) break
            out.add(item)
        }
        return out
    }

    /** Copy of [arr] without the items whose eid is in [eids]. Pure; exposed for tests. */
    internal fun withoutEids(arr: JSONArray, eids: Set<String>): JSONArray {
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i)
            if (item != null && item.optString("eid", "") in eids) continue
            out.put(arr.get(i))
        }
        return out
    }

    /** What a 2xx batch response means for the items that were sent. */
    internal data class BatchPartition(
        /** eids to remove from the queue (delivered, rejected, suppressed, or unlisted). */
        val remove: Set<String>,
        /** Subset of [remove] the edge refused for good: `rejected` (invalid) or `suppressed` (erased subject). */
        val permanentDrops: Set<String>,
    )

    /**
     * Partition the eids of a sent batch by the `results` of a 2xx `/v1/events` body:
     * `{ ok, results: [{ eid, status: accepted|rejected|suppressed, detail? }] }`.
     * - accepted → delivered; rejected → permanent (would 422 forever); suppressed →
     *   the subject was erased; all three leave the queue.
     * - A sent eid MISSING from `results` (or with an unknown status) is treated as
     *   delivered: the request was accepted as a whole and retrying it would only
     *   re-send something the edge dedupes on eid anyway.
     * - A MALFORMED or empty 2xx body is treated as "everything delivered" for the same
     *   reason — a 2xx is the edge's acknowledgement; keeping the batch queued would
     *   re-send it forever against a body we cannot read. Logged in debug.
     * Result entries for eids we did not send are ignored. Pure; exposed for tests.
     */
    internal fun partitionBatchResults(sentEids: List<String>, body: String?): BatchPartition {
        val sent = sentEids.toSet()
        val results = try {
            body?.takeIf(String::isNotBlank)?.let(::JSONObject)?.optJSONArray("results")
        } catch (e: Exception) {
            null
        }
        if (results == null) {
            if (debug) println("[Attriloop] /v1/events 2xx without a readable results array — treating ${sent.size} event(s) as delivered")
            return BatchPartition(sent, emptySet())
        }
        val permanent = HashSet<String>()
        for (i in 0 until results.length()) {
            val entry = results.optJSONObject(i) ?: continue
            val eid = entry.optString("eid", "")
            if (eid !in sent) continue
            when (entry.optString("status", "")) {
                "rejected", "suppressed" -> {
                    permanent.add(eid)
                    if (debug) {
                        val detail = entry.optJSONObject("detail")
                        println("[Attriloop] event $eid ${entry.optString("status")}: ${detail?.optString("path")} ${detail?.optString("message")}")
                    }
                }
                else -> Unit // accepted (or unknown) → delivered
            }
        }
        return BatchPartition(sent, permanent)
    }

    // Note: `attriloopId` (a @JvmStatic property) already exposes `getAttriloopId()` to
    // Java via its generated getter — a separate fun would clash on the JVM signature.

    /** Resolved attribution params (mediaSource, campaignName, linkId, …); empty until resolved.
     *  An empty map is **not** organic; read [getMatchStatus] for why it is empty. */
    @JvmStatic
    fun getAttributionParams(): Map<String, Any?> = attribution

    /** Current decision lifecycle. `pending` until the server answers; never inferred as organic. */
    @JvmStatic
    fun getMatchStatus(): AttriloopMatchStatus = matchStatus

    /**
     * Raw Google Play Install Referrer string this process last received from Play, or
     * null if Play has not returned one (sideload, empty referrer, timeout). Same bytes
     * posted as `installReferrer` on `/v1/install`. Not a new signal. There is no setter:
     * a host-supplied string that disagrees with Play is a second source of truth, and
     * this SDK does not pick a winner.
     */
    @JvmStatic
    fun getPlayInstallReferrer(): String? = playInstallReferrer

    /**
     * Async variant: invokes [callback] on the main thread with the resolved params
     * (or the current cache if already resolved). Always fires — even if attribution
     * never resolves it is eventually called with whatever is cached. Mirrors the iOS
     * completion API (`DispatchQueue.main`). Not configured → fires immediately with `emptyMap()`.
     */
    @JvmStatic
    fun getAttributionParams(callback: (Map<String, Any?>) -> Unit) {
        if (apiKey.isEmpty()) {
            matchStatus = AttriloopMatchStatus.skipped
            dispatchToMain { callback(emptyMap()) }
            return
        }
        if (shouldDeliverAttribution()) {
            val snapshot = attribution
            dispatchToMain { callback(snapshot) }
            return
        }
        synchronized(handlers) { handlers.add(callback) }
        if (shouldDeliverAttribution()) flushHandlers() else fetchAttribution(0)
    }

    /** Status plus params, same delivery schedule as [getAttributionParams]. */
    @JvmStatic
    fun getAttribution(callback: (AttriloopAttribution) -> Unit) {
        if (apiKey.isEmpty()) {
            matchStatus = AttriloopMatchStatus.skipped
            dispatchToMain { callback(AttriloopAttribution(AttriloopMatchStatus.skipped, emptyMap())) }
            return
        }
        if (shouldDeliverAttribution()) {
            val snapshot = AttriloopAttribution(matchStatus, attribution)
            dispatchToMain { callback(snapshot) }
            return
        }
        synchronized(attributionHandlers) { attributionHandlers.add(callback) }
        if (shouldDeliverAttribution()) flushHandlers() else fetchAttribution(0)
    }

    private fun shouldDeliverAttribution(): Boolean = when (matchStatus) {
        AttriloopMatchStatus.matched,
        AttriloopMatchStatus.organic,
        AttriloopMatchStatus.ambiguous,
        AttriloopMatchStatus.failed,
        AttriloopMatchStatus.skipped,
        -> true
        AttriloopMatchStatus.pending -> false
    }

    private fun flushHandlers() {
        val params = attribution
        val result = AttriloopAttribution(matchStatus, params)
        val paramWaiters: List<(Map<String, Any?>) -> Unit>
        val statusWaiters: List<(AttriloopAttribution) -> Unit>
        synchronized(handlers) {
            paramWaiters = if (handlers.isEmpty()) emptyList() else ArrayList(handlers).also { handlers.clear() }
        }
        synchronized(attributionHandlers) {
            statusWaiters = if (attributionHandlers.isEmpty()) emptyList() else ArrayList(attributionHandlers).also { attributionHandlers.clear() }
        }
        if (paramWaiters.isEmpty() && statusWaiters.isEmpty()) return
        paramWaiters.forEach { callback -> dispatchToMain { callback(params) } }
        statusWaiters.forEach { callback -> dispatchToMain { callback(result) } }
    }

    /** Host callbacks must not kill the app; iOS delivers these on the main queue. */
    private inline fun invokeSafely(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            if (debug) e.printStackTrace()
        }
    }

    /**
     * Post to the main looper (always async, matching iOS `DispatchQueue.main.async`).
     * On a JVM unit-test classpath without a looper, run inline so pure tests stay hermetic.
     */
    private fun dispatchToMain(block: () -> Unit) {
        val looper = try {
            Looper.getMainLooper()
        } catch (_: Throwable) {
            null
        }
        if (looper == null) {
            invokeSafely(block)
            return
        }
        Handler(looper).post { invokeSafely(block) }
    }

    // ── deep links ───────────────────────────────────────────────────────────

    /**
     * Handle a deep link / App Link that launched or resumed the app. Call from
     * `onCreate` / `onNewIntent` with `intent.data`. Accepts either an explicit
     * `at_click` query token, or a namespaced App Link `https://<host>/l/<token>/<slug>`
     * which is resolved via `/v1/resolve` to fetch the click id + `deep_link_value`.
     */
    @JvmStatic
    fun handleDeepLink(uri: Uri?) {
        if (uri == null || !isConfigured("handleDeepLink")) return
        val click = uri.getQueryParameter("at_click")
        if (!click.isNullOrEmpty()) {
            prefs.edit().putString(CLICK_KEY, click).apply()
            fetchAttribution(0)
            return
        }
        deepLinkContext(uri.host, uri.pathSegments)?.let(::resolveDeepLink)
    }

    internal data class LinkContext(val slug: String, val host: String, val token: String?)

    /** Pure parser for branded `/l/<slug>` and shared `/l/<token>/<slug>` links. */
    internal fun deepLinkContext(host: String?, segments: List<String>): LinkContext? {
        val normalizedHost = host?.lowercase(Locale.US)?.takeIf { it.isNotBlank() } ?: return null
        fun validSlug(value: String) = value.length <= 64 && Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?$").matches(value)
        if (segments.size == 1 && segments[0] != "l" && validSlug(segments[0])) {
            return LinkContext(segments[0], normalizedHost, null)
        }
        if (normalizedHost == "attriloop.com" && segments.size == 2 &&
            segments[0].length <= 30 && !segments[0].contains("--") && !segments[1].contains("--") &&
            Regex("^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$").matches(segments[0]) &&
            validSlug(segments[1]) && validSlug("${segments[0]}--${segments[1]}")) {
            return LinkContext("${segments[0]}--${segments[1]}", normalizedHost, null)
        }
        if (segments.size == 2 && segments[0] == "l" && segments[1].isNotEmpty()) {
            return LinkContext(segments[1], normalizedHost, null)
        }
        if (
            segments.size == 3 && segments[0] == "l" &&
            segments[1].isNotEmpty() && segments[2].isNotEmpty()
        ) {
            return LinkContext(segments[2], normalizedHost, segments[1])
        }
        return null
    }

    /** Resolve an App Link in its host/project namespace. Runs on `io`. */
    private fun resolveDeepLink(context: LinkContext) {
        if (apiKey.isEmpty()) return
        io.execute {
            val encSlug = URLEncoder.encode(context.slug, "UTF-8")
            val encHost = URLEncoder.encode(context.host, "UTF-8")
            val tokenQuery = context.token?.let { "&token=${URLEncoder.encode(it, "UTF-8")}" } ?: ""
            val json = get("/v1/resolve?slug=$encSlug&host=$encHost$tokenQuery") ?: return@execute
            json.optString("at_click", "").takeIf { it.isNotEmpty() }?.let {
                prefs.edit().putString(CLICK_KEY, it).apply()
                sendReengagement(it)
            }
            json.optString("deep_link_value", "").takeIf { it.isNotEmpty() }?.let { setDeepLink(it) }
            fetchAttribution(0)
        }
    }

    /**
     * Cache a THIS-session deep-link resolution and broadcast it to onDeepLink
     * subscribers exactly once. Called only from resolveDeepLink / fetchAttribution, so
     * the value is always a FRESH resolution: it is marked pending, then delivered and
     * consumed by deliverDeepLink. Runs on the `io` thread.
     */
    private fun setDeepLink(value: String) {
        deepLink = value
        deepLinkPending = true
        // Persist so getDeepLink() survives process death. deliverDeepLink removes the
        // key once the value is consumed, so a delivered resolution never re-broadcasts
        // on the next launch.
        prefs.edit().putString(DEEPLINK_KEY, value).apply()
        deliverDeepLink()
    }

    /**
     * Deliver a pending (fresh, this-session) resolution to the current subscribers
     * exactly once, then consume it by clearing the pending flag. No-op when nothing is
     * pending or no one is subscribed yet — in the latter case the value stays pending
     * and a later onDeepLink call delivers it.
     *
     * The persisted DEEPLINK_KEY is intentionally NOT removed: it feeds getDeepLink()
     * across launches AND, reloaded into `deepLink` in configure(), it lets the
     * attribution poll's `dlv != deepLink` guard suppress a re-fire when the server
     * re-returns the same deep_link_value on the next launch. Consuming the persisted
     * value here would make `deepLink` reload as null and defeat that guard, re-firing
     * the destination on every organic launch. Re-fire is blocked instead by
     * `deepLinkPending` (in-memory, false on a cold launch).
     */
    private fun deliverDeepLink() {
        val value: String
        val pending: List<(String) -> Unit>
        synchronized(deepLinkHandlers) {
            if (!deepLinkPending || deepLinkHandlers.isEmpty()) return
            value = deepLink ?: return
            pending = ArrayList(deepLinkHandlers)
            deepLinkPending = false // consumed — this resolution won't be delivered again
        }
        pending.forEach { callback -> dispatchToMain { callback(value) } }
    }

    /** The last resolved deferred deep-link destination (`deep_link_value`), or null. */
    @JvmStatic
    fun getDeepLink(): String? = deepLink

    /**
     * Subscribe to deep-link resolutions. Fires with the `deep_link_value` each time
     * one resolves in THIS session (App Link or deferred install). If a fresh resolution
     * is already pending it fires immediately; a value merely restored from a previous
     * launch does NOT fire (once-only semantics — see the note on deepLinkPending), so
     * the destination is never re-navigated on an organic launch. Callbacks run on the
     * main thread (matching iOS). Treat the value as UNTRUSTED input when routing.
     */
    @JvmStatic
    fun onDeepLink(callback: (String) -> Unit) {
        synchronized(deepLinkHandlers) { deepLinkHandlers.add(callback) }
        deliverDeepLink() // fires + consumes only if a fresh this-session value is pending
    }

    // ── internals ──────────────────────────────────────────────────────────

    private fun fetchReferrerThenInstall(context: Context) {
        val client = InstallReferrerClient.newBuilder(context).build()
        // Some OEM Play Store implementations never invoke the listener, and Play
        // Services can cold-start slowly. Bound first-open latency so attribution still
        // sends (without a referrer) after REFERRER_FALLBACK_SECONDS; a referrer that
        // arrives later is delivered as a `referrer_update` install (see
        // sendReferrerUpdateIfLate) instead of being lost.
        io.schedule({
            try { client.endConnection() } catch (_: Exception) {}
            sendInstall(null)
        }, REFERRER_FALLBACK_SECONDS, TimeUnit.SECONDS)
        client.startConnection(object : InstallReferrerStateListener {
            override fun onInstallReferrerSetupFinished(responseCode: Int) {
                var handoff: PlayReferrerHandoff? = null
                if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
                    try {
                        val details = client.installReferrer
                        val referrer = details.installReferrer
                        if (!referrer.isNullOrEmpty()) {
                            // Live ReferrerDetails: only these four getters exist.
                            // Server-side timestamp methods are not on the reference.
                            handoff = PlayReferrerHandoff(
                                referrer,
                                details.referrerClickTimestampSeconds,
                                details.installBeginTimestampSeconds,
                            )
                            rememberPlayInstallReferrer(referrer)
                        }
                    } catch (e: Exception) {
                        if (debug) e.printStackTrace()
                    }
                }
                try {
                    client.endConnection()
                } catch (_: Exception) {
                }
                // Before the fallback: single install carrying the referrer (unchanged).
                // After it: the fallback already claimed the one-shot install → upgrade it.
                if (!sendInstall(handoff)) sendReferrerUpdateIfLate(handoff)
            }

            override fun onInstallReferrerServiceDisconnected() {
                // No-op: onInstallReferrerSetupFinished always fires first (with a
                // non-OK code if the service is unavailable) and is what sends the
                // install. Sending here too would double-POST.
            }
        })
    }

    /** Cache the Play-supplied string for [getPlayInstallReferrer]. Empty is forgotten. */
    private fun rememberPlayInstallReferrer(value: String?) {
        val stored = value?.takeIf { it.isNotEmpty() }
        playInstallReferrer = stored
        if (::prefs.isInitialized) {
            prefs.edit().apply {
                if (stored == null) remove(PLAY_REFERRER_KEY) else putString(PLAY_REFERRER_KEY, stored)
            }.apply()
        }
    }

    private fun restorePlayInstallReferrerFromPrefs() {
        playInstallReferrer = if (::prefs.isInitialized) {
            prefs.getString(PLAY_REFERRER_KEY, null)?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
    }

    /** Play Referrer string + the two documented ReferrerDetails clocks. */
    internal data class PlayReferrerHandoff(
        val referrer: String,
        val referrerClickTimestampSeconds: Long = 0,
        val installBeginTimestampSeconds: Long = 0,
    )

    /** Play reports 0 when a clock is unknown; omit those fields from the wire. */
    internal fun playTimestampOrNull(seconds: Long): Long? =
        if (seconds > 0) seconds else null

    /** Common `/v1/install` body; [installType] is `new_install` or `referrer_update`. */
    private fun installBody(handoff: PlayReferrerHandoff?, installType: String): JSONObject {
        val device = JSONObject()
            .put("os", "android")
            .put("osVersion", Build.VERSION.RELEASE ?: "")
            .put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("locale", Locale.getDefault().toLanguageTag())
            .put("timezone", TimeZone.getDefault().id)
        val body = JSONObject()
            .put("attriloopId", attriloopId)
            .put("appId", packageName)
            .put("device", device)
            .put("sdkVersion", SDK_VERSION)
            .put("installType", installType)
            .withCustomerUserId()
        if (handoff != null && handoff.referrer.isNotEmpty()) {
            body.put("installReferrer", handoff.referrer)
            playTimestampOrNull(handoff.referrerClickTimestampSeconds)?.let {
                body.put("referrerClickTimestampSeconds", it)
            }
            playTimestampOrNull(handoff.installBeginTimestampSeconds)?.let {
                body.put("installBeginTimestampSeconds", it)
            }
        }
        // Deterministic click id from an App Link opened before install (if any). The
        // Play referrer above is the primary signal; this covers a pre-install app-link tap.
        prefs.getString(CLICK_KEY, null)?.let { if (it.isNotEmpty()) body.put("clickId", it) }
        return body
    }

    /** Send the one-shot install. Returns false when another caller already claimed it. */
    private fun sendInstall(handoff: PlayReferrerHandoff?): Boolean {
        if (!installSent.compareAndSet(false, true)) return false // exactly once across callbacks
        val hasReferrer = handoff != null && handoff.referrer.isNotEmpty()
        val body = installBody(handoff, "new_install")
        postWithResult("/v1/install", body, 0) { ok ->
            if (ok) {
                prefs.edit().putBoolean(INSTALLED_KEY, true).apply()
                // The install already carried the referrer → never send referrer_update.
                if (hasReferrer) prefs.edit().putBoolean(REFERRER_SENT_KEY, true).apply()
                fetchAttribution(0)
                flushQueue() // any events sent before the install landed
                // A referrer that arrived while this POST was in flight goes out now, so
                // the edge always sees new_install before its referrer_update.
                pendingReferrerUpdate?.let {
                    pendingReferrerUpdate = null
                    sendReferrerUpdate(it)
                }
            } else {
                installSent.set(false) // failed → allow a fresh attempt on next launch
                pendingReferrerUpdate = null // next launch re-queries the referrer itself
            }
        }
        return true
    }

    /**
     * Decide whether a Play referrer that arrived AFTER the install was claimed must be
     * delivered as a `referrer_update`. True only when the one-shot install has already
     * gone out (so it could not carry the referrer), the referrer is non-empty, and no
     * referrer has been delivered for this install yet. Pure; exposed for tests.
     */
    internal fun referrerUpdateNeeded(installAlreadySent: Boolean, referrer: String?, referrerAlreadySent: Boolean): Boolean =
        installAlreadySent && !referrer.isNullOrEmpty() && !referrerAlreadySent

    /** Listener path when the fallback install already claimed the one-shot send. */
    private fun sendReferrerUpdateIfLate(handoff: PlayReferrerHandoff?) {
        val needed = referrerUpdateNeeded(
            installAlreadySent = installSent.get(),
            referrer = handoff?.referrer,
            referrerAlreadySent = prefs.getBoolean(REFERRER_SENT_KEY, false),
        )
        if (!needed || handoff == null || !referrerUpdateClaimed.compareAndSet(false, true)) return
        if (prefs.getBoolean(INSTALLED_KEY, false)) {
            sendReferrerUpdate(handoff)
        } else {
            pendingReferrerUpdate = handoff // sent from sendInstall once the install lands
        }
    }

    /**
     * Second `/v1/install` with `installType: "referrer_update"` + `installReferrer`, so
     * the backend can upgrade an already-recorded organic install to the deterministic
     * Play referrer match. Same retry/backoff as the install; REFERRER_SENT_KEY is
     * committed only once it lands, so it is sent at most once per install.
     */
    private fun sendReferrerUpdate(handoff: PlayReferrerHandoff) {
        val body = installBody(handoff, "referrer_update")
        postWithResult("/v1/install", body, 0) { ok ->
            if (ok) {
                prefs.edit().putBoolean(REFERRER_SENT_KEY, true).apply()
                fetchAttribution(0) // attribution may have changed from organic
            } else {
                referrerUpdateClaimed.set(false)
            }
        }
    }

    /** Poll until attribution resolves (it runs async on the backend), backing off. */
    private fun fetchAttribution(attempt: Int) {
        if (apiKey.isEmpty() || attriloopId.isEmpty()) {
            if (apiKey.isEmpty()) matchStatus = AttriloopMatchStatus.skipped
            return
        }
        if (attempt == 0 && !polling.compareAndSet(false, true)) return // a chain already runs
        if (attempt == 0) {
            pollHeardPending = false
            pollTransportFailed = false
        }
        val delays = longArrayOf(2000, 4000, 8000, 16000)
        io.execute {
            val enc = URLEncoder.encode(attriloopId, "UTF-8")
            val json = get("/v1/attribution?attriloopId=$enc")
            if (json != null) {
                val (status, keepPolling) = AttriloopMatchStatus.interpret(
                    json.optString("matchStatus").takeIf { json.has("matchStatus") && it.isNotEmpty() },
                    if (json.has("terminal") && !json.isNull("terminal")) json.optBoolean("terminal") else null,
                    json.optString("status").takeIf { json.has("status") && it.isNotEmpty() },
                )
                if (!keepPolling) {
                    attribution = jsonToMap(json)
                    matchStatus = status
                    prefs.edit()
                        .putString(ATTR_KEY, json.toString())
                        .putString(MATCH_STATUS_KEY, status.raw)
                        .apply()
                    polling.set(false)
                    val dlv = json.optString("deep_link_value", "")
                    if (dlv.isNotEmpty() && dlv != deepLink) {
                        val apply = if (json.has("applyDeepLink") && !json.isNull("applyDeepLink")) {
                            json.optBoolean("applyDeepLink")
                        } else {
                            null
                        }
                        if (AttriloopMatchStatus.shouldApplyDeepLink(apply, status)) setDeepLink(dlv)
                    }
                    flushHandlers()
                    return@execute
                }
                pollHeardPending = true
            } else {
                pollTransportFailed = true
            }
            if (attempt < delays.size) {
                io.schedule({ fetchAttribution(attempt + 1) }, delays[attempt], TimeUnit.MILLISECONDS)
            } else {
                polling.set(false)
                if (!shouldDeliverAttribution()) {
                    matchStatus = when {
                        pollHeardPending -> AttriloopMatchStatus.pending
                        pollTransportFailed -> AttriloopMatchStatus.failed
                        else -> AttriloopMatchStatus.pending
                    }
                }
                flushHandlers()
            }
        }
    }

    /** POST that reports 2xx success and retries on failure — used for the install + queued events. */
    private fun postWithResult(path: String, body: JSONObject, attempt: Int, onResult: (Boolean) -> Unit) {
        io.execute {
            val ok = try {
                withConnection(path, "POST") { conn ->
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toString().toByteArray()) }
                    conn.responseCode in 200..299
                }
            } catch (e: Exception) {
                if (debug) e.printStackTrace()
                false
            }
            if (!ok && attempt < 3) {
                val delays = longArrayOf(2000, 5000, 15000)
                io.schedule(
                    { postWithResult(path, body, attempt + 1, onResult) },
                    delays[minOf(attempt, 2)],
                    TimeUnit.MILLISECONDS,
                )
            } else {
                onResult(ok)
            }
        }
    }

    /** Delivery outcome of a single POST. */
    internal enum class Outcome { OK, PERMANENT, TRANSIENT }

    /** Map an HTTP status to a delivery outcome. Pure; exposed for tests. */
    internal fun classifyOutcome(code: Int): Outcome = when {
        code in 200..299 -> Outcome.OK
        code == 408 || code == 429 -> Outcome.TRANSIENT // timeout / rate-limited → retry
        code == 401 || code == 403 -> Outcome.TRANSIENT // provisioning / key rotation must not drain queue
        code in 400..499 -> Outcome.PERMANENT // 4xx → server will keep rejecting
        else -> Outcome.TRANSIENT // 5xx or unknown
    }

    /** Never allow an SDK key/event body over plaintext except local debug development. */
    internal fun isAllowedBaseUrl(value: String, debug: Boolean): Boolean = try {
        val url = URL(value)
        when (url.protocol.lowercase(Locale.US)) {
            "https" -> !url.host.isNullOrBlank()
            "http" -> debug && (url.host == "localhost" || url.host == "127.0.0.1")
            else -> false
        }
    } catch (_: Exception) {
        false
    }

    /** Status + bounded body of one POST; null when the request itself failed (network). */
    private class HttpResponse(val code: Int, val body: String?)

    /** One POST attempt that also returns the response body. Must be called on `io`. */
    private fun postForResponse(path: String, body: JSONObject): HttpResponse? = try {
        withConnection(path, "POST") { conn ->
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            HttpResponse(code, stream?.let(::readBounded))
        }
    } catch (e: Exception) {
        if (debug) e.printStackTrace()
        null
    }

    /** One POST attempt (no internal retry). Must be called on the `io` thread. */
    private fun postOnce(path: String, body: JSONObject): Outcome = try {
        withConnection(path, "POST") { conn ->
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            classifyOutcome(conn.responseCode)
        }
    } catch (e: Exception) {
        if (debug) e.printStackTrace()
        Outcome.TRANSIENT
    }

    private fun get(path: String): JSONObject? = try {
        withConnection(path, "GET") { conn ->
            val code = conn.responseCode
            if (code in 200..299) JSONObject(readBounded(conn.inputStream)) else null
        }
    } catch (e: Exception) {
        if (debug) e.printStackTrace()
        null
    }

    /** Open, run, and ALWAYS disconnect — an exception mid-request must not leak the socket. */
    private inline fun <T> withConnection(path: String, method: String, block: (HttpURLConnection) -> T): T {
        val conn = URL("$baseUrl$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            return block(conn)
        } finally {
            conn.disconnect()
        }
    }

    private const val MAX_RESPONSE_BYTES = 64 * 1024

    /** Read at most [MAX_RESPONSE_BYTES] of a response body; edge responses are tiny. */
    private fun readBounded(stream: java.io.InputStream): String =
        stream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(4096)
            var total = 0
            while (total < MAX_RESPONSE_BYTES) {
                val n = input.read(buf, 0, minOf(buf.size, MAX_RESPONSE_BYTES - total))
                if (n < 0) break
                out.write(buf, 0, n)
                total += n
            }
            out.toString("UTF-8")
        }

    /** Flatten a JSON object to a map, mapping JSON null → Kotlin null. Exposed for tests. */
    internal fun jsonToMap(json: JSONObject): Map<String, Any?> {
        val map = HashMap<String, Any?>()
        for (key in json.keys()) {
            val v = json.get(key)
            map[key] = if (v == JSONObject.NULL) null else v
        }
        return map
    }

    // ── test seams (mirrors iOS injecting an isolated UserDefaults suite) ──

    /** Drain the SDK executor so tests can observe persisted writes. */
    internal fun awaitIo() {
        io.submit { }.get(5, TimeUnit.SECONDS)
    }

    /** Init prefs + identity without Play Referrer, polling, or network. */
    internal fun attachPersistenceForTest(context: Context) {
        resetForTest()
        val app = context.applicationContext
        prefs = app.getSharedPreferences("$PREFS.test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        apiKey = "at_test"
        debug = true
        packageName = app.packageName
        attriloopId = "test-attriloop-id"
        matchStatus = AttriloopMatchStatus.pending
        playInstallReferrer = null
        prefs.edit().putString(ID_KEY, attriloopId).commit()
    }

    /** Drop in-memory + on-disk SDK state so later tests (and the pure JVM suite) stay isolated. */
    internal fun resetForTest() {
        try {
            awaitIo()
        } catch (_: Exception) {
            // Executor may be idle; isolation still proceeds.
        }
        apiKey = ""
        debug = false
        baseUrl = DEFAULT_BASE_URL
        packageName = ""
        attriloopId = ""
        customerUserId = null
        attribution = emptyMap()
        matchStatus = AttriloopMatchStatus.skipped
        playInstallReferrer = null
        pollHeardPending = false
        pollTransportFailed = false
        synchronized(handlers) { handlers.clear() }
        synchronized(attributionHandlers) { attributionHandlers.clear() }
        deepLink = null
        deepLinkPending = false
        synchronized(deepLinkHandlers) { deepLinkHandlers.clear() }
        installSent.set(false)
        polling.set(false)
        referrerUpdateClaimed.set(false)
        pendingReferrerUpdate = null
        flushing.set(false)
        batchUnsupported = false
        if (::prefs.isInitialized) prefs.edit().clear().commit()
    }

    internal fun persistedCustomerUserId(): String? =
        if (::prefs.isInitialized) prefs.getString(CUSTOMER_USER_ID_KEY, null) else null

    internal fun enqueueForTest(item: JSONObject) {
        enqueue(item)
        awaitIo()
    }

    internal fun queuedEidsForTest(): List<String> {
        val arr = readQueue()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.optString("eid")?.takeIf { it.isNotEmpty() }
        }
    }

    internal fun removeQueuedForTest(eids: Set<String>) {
        io.submit { removeQueued(eids) }.get(5, TimeUnit.SECONDS)
    }

    /** Tests only. Writes the same cache [getPlayInstallReferrer] reads. */
    internal fun setPlayInstallReferrerForTest(value: String?) {
        rememberPlayInstallReferrer(value)
    }

    internal fun persistedPlayInstallReferrer(): String? =
        if (::prefs.isInitialized) prefs.getString(PLAY_REFERRER_KEY, null) else null

    /** Drop the in-memory cache only so a prefs restore can be asserted. */
    internal fun dropPlayInstallReferrerMemoryForTest() {
        playInstallReferrer = null
    }

    internal fun restorePlayInstallReferrerFromPrefsForTest() {
        restorePlayInstallReferrerFromPrefs()
    }

    internal fun setAttributionForTest(value: Map<String, Any?>) {
        attribution = value
        val (status, keepPolling) = AttriloopMatchStatus.interpret(
            value["matchStatus"] as? String,
            value["terminal"] as? Boolean,
            value["status"] as? String,
        )
        matchStatus = when {
            !keepPolling -> status
            value.isNotEmpty() -> AttriloopMatchStatus.matched
            else -> AttriloopMatchStatus.pending
        }
    }

    internal fun setDeepLinkForTest(value: String, pending: Boolean) {
        deepLink = value
        deepLinkPending = pending
        if (::prefs.isInitialized) prefs.edit().putString(DEEPLINK_KEY, value).apply()
    }
}
