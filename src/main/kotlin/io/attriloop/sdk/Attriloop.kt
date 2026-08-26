package io.attriloop.sdk

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
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
    LEVEL_START("LEVEL_START"),
    LEVEL_COMPLETE("LEVEL_COMPLETE"),
    TUTORIAL_COMPLETE("TUTORIAL_COMPLETE"),
    SEARCH("SEARCH"),
    SHARE("SHARE"),
    CUSTOM("CUSTOM"),
}

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
    private const val CLICK_KEY = "clickId" // at_click captured from a deep link
    private const val DEEPLINK_KEY = "deepLink" // last resolved deep_link_value
    private const val QUEUE_KEY = "queue" // persisted offline event buffer
    private const val MAX_QUEUE = 500 // oldest dropped beyond this
    private const val SDK_VERSION = "android/0.1.0"
    private const val DEFAULT_BASE_URL = "https://api.attriloop.com"

    private val io = Executors.newSingleThreadScheduledExecutor()
    private lateinit var prefs: SharedPreferences
    private var apiKey = ""
    private var baseUrl = DEFAULT_BASE_URL
    private var debug = false
    private var packageName = ""

    // One in-flight queue-drain chain at a time.
    private val flushing = AtomicBoolean(false)
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    @Volatile
    private var attribution: Map<String, Any?> = emptyMap()

    // Async attribution waiters, flushed once the poll chain resolves or gives up.
    private val handlers = ArrayList<(Map<String, Any?>) -> Unit>()

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

    /** Public install/device id — the attribution subject. */
    @JvmStatic
    var attriloopId: String = ""
        private set

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
        // Reload the last persisted deep_link_value for getDeepLink() reads, but leave
        // it NOT pending: a value from a previous launch is already-consumed and must
        // not re-fire onDeepLink (see the once-only note on deepLinkPending).
        deepLink = prefs.getString(DEEPLINK_KEY, null)

        // INSTALLED_KEY is committed only after the install POST succeeds (sendInstall),
        // so a first-open with no connectivity retries on the next launch instead of
        // permanently losing the install.
        if (!prefs.getBoolean(INSTALLED_KEY, false)) {
            fetchReferrerThenInstall(app)
        }
        fetchAttribution(0)
        flushQueue() // re-send any events buffered offline in a previous session
    }

    /**
     * Record an in-app event. The event is persisted to a durable on-disk queue
     * FIRST, then a flush is attempted — so a transient network failure, timeout, or
     * the process being killed mid-send never loses it. Pending events re-send on the
     * next sendEvent, on the next configure(), and right after the install POST lands.
     */
    @JvmStatic
    @JvmOverloads
    fun sendEvent(event: AttriloopEvent, name: String? = null, parameters: Map<String, Any?>? = null) {
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

    /** A CUSTOM event with no usable name can never be ingested. Pure; for tests. */
    internal fun isDroppableCustom(event: AttriloopEvent, name: String?): Boolean =
        event == AttriloopEvent.CUSTOM && name.isNullOrEmpty()

    private val sensitiveParameterKeys = setOf(
        "email", "name", "phone", "phone_number", "date_of_birth", "dob", "first_name", "last_name",
    )

    /** Hash advanced-matching identifiers before they reach disk or the network. */
    internal fun sanitizedParameters(parameters: Map<String, Any?>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for ((key, value) in parameters) {
            if (key.lowercase(Locale.US) !in sensitiveParameterKeys) {
                out[key] = value
                continue
            }
            val raw = value as? String ?: continue
            hashedSensitiveValue(raw, key)?.let { out[key] = it }
        }
        return out
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
        io.execute {
            val arr = readQueue()
            arr.put(item)
            val out = cappedQueue(arr, MAX_QUEUE)
            if (debug && out.length() < arr.length()) {
                println("[Attriloop] event queue full — dropping ${arr.length() - out.length()} oldest")
            }
            prefs.edit().putString(QUEUE_KEY, out.toString()).apply()
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
        if (!flushing.compareAndSet(false, true)) return
        sendNextQueued()
    }

    private fun sendNextQueued() {
        io.execute {
            val arr = readQueue()
            if (arr.length() == 0) {
                flushing.set(false)
                return@execute
            }
            val head = arr.getJSONObject(0)
            // Single attempt (the queue itself is the retry mechanism). Runs on the
            // single `io` thread, so enqueue/drain are serialized — no index race.
            val outcome = postOnce("/v1/event", head)
            if (outcome == Outcome.TRANSIENT) {
                flushing.set(false) // retry on next flush trigger
                return@execute
            }
            if (outcome == Outcome.PERMANENT && debug) {
                println("[Attriloop] dropping permanently-rejected event")
            }
            val cur = readQueue()
            val next = JSONArray()
            for (i in 1 until cur.length()) next.put(cur.get(i))
            prefs.edit().putString(QUEUE_KEY, next.toString()).apply()
            sendNextQueued() // keep draining
        }
    }

    // Note: `attriloopId` (a @JvmStatic property) already exposes `getAttriloopId()` to
    // Java via its generated getter — a separate fun would clash on the JVM signature.

    /** Resolved attribution params (mediaSource, campaignName, linkId, …); empty until resolved. */
    @JvmStatic
    fun getAttributionParams(): Map<String, Any?> = attribution

    /**
     * Async variant: invokes [callback] with the resolved params (or the current cache
     * if already resolved). Always fires — even if attribution never resolves it is
     * eventually called with whatever is cached. Mirrors the iOS completion API.
     */
    @JvmStatic
    fun getAttributionParams(callback: (Map<String, Any?>) -> Unit) {
        if (attribution.isNotEmpty()) {
            callback(attribution)
            return
        }
        synchronized(handlers) { handlers.add(callback) }
        // Re-check: the poll chain may have resolved between the first check and adding
        // the handler — flush now so the callback can't hang. Otherwise ensure a poll runs.
        if (attribution.isNotEmpty()) flushHandlers(attribution) else fetchAttribution(0)
    }

    private fun flushHandlers(result: Map<String, Any?>) {
        val pending: List<(Map<String, Any?>) -> Unit>
        synchronized(handlers) {
            if (handlers.isEmpty()) return
            pending = ArrayList(handlers)
            handlers.clear()
        }
        pending.forEach { it(result) }
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
        if (uri == null || !::prefs.isInitialized) return // configure() must run first
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
        pending.forEach { it(value) }
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
     * SDK's background thread — dispatch to the main thread before touching UI. Treat the
     * value as UNTRUSTED input when routing.
     */
    @JvmStatic
    fun onDeepLink(callback: (String) -> Unit) {
        synchronized(deepLinkHandlers) { deepLinkHandlers.add(callback) }
        deliverDeepLink() // fires + consumes only if a fresh this-session value is pending
    }

    // ── internals ──────────────────────────────────────────────────────────

    private fun fetchReferrerThenInstall(context: Context) {
        val client = InstallReferrerClient.newBuilder(context).build()
        // Some OEM Play Store implementations never invoke the listener. Bound first-
        // open latency so attribution still sends (without a referrer) after 5 seconds.
        io.schedule({
            try { client.endConnection() } catch (_: Exception) {}
            sendInstall(null)
        }, 5, TimeUnit.SECONDS)
        client.startConnection(object : InstallReferrerStateListener {
            override fun onInstallReferrerSetupFinished(responseCode: Int) {
                var referrer: String? = null
                if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
                    try {
                        referrer = client.installReferrer.installReferrer
                    } catch (e: Exception) {
                        if (debug) e.printStackTrace()
                    }
                }
                try {
                    client.endConnection()
                } catch (_: Exception) {
                }
                sendInstall(referrer)
            }

            override fun onInstallReferrerServiceDisconnected() {
                // No-op: onInstallReferrerSetupFinished always fires first (with a
                // non-OK code if the service is unavailable) and is what sends the
                // install. Sending here too would double-POST.
            }
        })
    }

    private fun sendInstall(referrer: String?) {
        if (!installSent.compareAndSet(false, true)) return // exactly once across callbacks
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
            .put("installType", "new_install")
        if (!referrer.isNullOrEmpty()) body.put("installReferrer", referrer)
        // Deterministic click id from an App Link opened before install (if any). The
        // Play referrer above is the primary signal; this covers a pre-install app-link tap.
        prefs.getString(CLICK_KEY, null)?.let { if (it.isNotEmpty()) body.put("clickId", it) }
        postWithResult("/v1/install", body, 0) { ok ->
            if (ok) {
                prefs.edit().putBoolean(INSTALLED_KEY, true).apply()
                fetchAttribution(0)
                flushQueue() // any events sent before the install landed
            } else {
                installSent.set(false) // failed → allow a fresh attempt on next launch
            }
        }
    }

    /** Poll until attribution resolves (it runs async on the backend), backing off. */
    private fun fetchAttribution(attempt: Int) {
        if (attriloopId.isEmpty()) return
        if (attempt == 0 && !polling.compareAndSet(false, true)) return // a chain already runs
        val delays = longArrayOf(2000, 4000, 8000, 16000)
        io.execute {
            val enc = URLEncoder.encode(attriloopId, "UTF-8")
            val json = get("/v1/attribution?attriloopId=$enc")
            val status = json?.optString("status", "pending")
            if (json != null && status != "pending") {
                attribution = jsonToMap(json)
                prefs.edit().putString(ATTR_KEY, json.toString()).apply()
                polling.set(false)
                // Deferred deep link: surface deep_link_value for navigation ONLY on a
                // trustworthy match (deterministic rung or high confidence) so a
                // low-confidence fingerprint guess doesn't misroute the user.
                val dlv = json.optString("deep_link_value", "")
                if (dlv.isNotEmpty() && dlv != deepLink) {
                    val method = json.optString("matchMethod", "")
                    val deterministic = method == "deeplink" || method == "play_referrer"
                    if (deterministic || json.optString("confidence", "") == "high") setDeepLink(dlv)
                }
                flushHandlers(attribution)
            } else if (attempt < delays.size) {
                io.schedule({ fetchAttribution(attempt + 1) }, delays[attempt], TimeUnit.MILLISECONDS)
            } else {
                // Exhausted while still pending — release the lock and call any waiters
                // with whatever is cached so async completions never hang.
                polling.set(false)
                flushHandlers(attribution)
            }
        }
    }

    /** POST that reports 2xx success and retries on failure — used for the install + queued events. */
    private fun postWithResult(path: String, body: JSONObject, attempt: Int, onResult: (Boolean) -> Unit) {
        io.execute {
            val ok = try {
                val conn = open(path, "POST")
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = conn.responseCode
                conn.disconnect()
                code in 200..299
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

    /** One POST attempt (no internal retry). Must be called on the `io` thread. */
    private fun postOnce(path: String, body: JSONObject): Outcome = try {
        val conn = open(path, "POST")
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        conn.disconnect()
        classifyOutcome(code)
    } catch (e: Exception) {
        if (debug) e.printStackTrace()
        Outcome.TRANSIENT
    }

    private fun get(path: String): JSONObject? = try {
        val conn = open(path, "GET")
        val code = conn.responseCode
        val text = if (code in 200..299) {
            conn.inputStream.bufferedReader().use(BufferedReader::readText)
        } else {
            null
        }
        conn.disconnect()
        text?.let { JSONObject(it) }
    } catch (e: Exception) {
        if (debug) e.printStackTrace()
        null
    }

    private fun open(path: String, method: String): HttpURLConnection {
        val conn = URL("$baseUrl$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        return conn
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
}
