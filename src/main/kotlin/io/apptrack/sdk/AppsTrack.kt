package io.apptrack.sdk

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Standard event types. INSTALL is emitted by the backend on first-open. */
enum class AppsTrackEvent(val raw: String) {
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
 * AppsTrack Android SDK. Mirrors the iOS surface.
 *
 * ```kotlin
 * AppsTrack.configure(context, "at_live_...")
 * AppsTrack.sendEvent(AppsTrackEvent.PURCHASE, parameters = mapOf("revenue" to 29.99, "currency" to "USD"))
 * AppsTrack.sendCustomEvent("share_referral", parameters = mapOf("channel" to "whatsapp"))
 * ```
 *
 * On first-open it fetches the Google Play Install Referrer (deterministic
 * attribution: the redirect edge packs our `at_click` token into the Play Store
 * `referrer` param) and posts it with the install.
 */
object AppsTrack {
    private const val PREFS = "com.apptrack.sdk"
    private const val ID_KEY = "apptrack_id"
    private const val INSTALLED_KEY = "installed"
    private const val ATTR_KEY = "attribution"
    private const val QUEUE_KEY = "queue" // persisted offline event buffer
    private const val MAX_QUEUE = 500 // oldest dropped beyond this
    private const val SDK_VERSION = "android/0.1.0"

    private val io = Executors.newSingleThreadScheduledExecutor()
    private lateinit var prefs: SharedPreferences
    private var apiKey = ""
    private var baseUrl = "https://edge.apptrack.io"
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

    // Guards: install is sent exactly once across the referrer callbacks; only one
    // attribution poll chain runs at a time.
    private val installSent = AtomicBoolean(false)
    private val polling = AtomicBoolean(false)

    /** Public install/device id — the attribution subject. */
    @JvmStatic
    var apptrackId: String = ""
        private set

    @JvmStatic
    @JvmOverloads
    fun configure(context: Context, apiKey: String, baseUrl: String? = null, debug: Boolean = false) {
        val app = context.applicationContext
        this.prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        this.apiKey = apiKey
        this.packageName = app.packageName
        if (baseUrl != null) this.baseUrl = baseUrl.trimEnd('/')
        this.debug = debug

        this.apptrackId = prefs.getString(ID_KEY, null) ?: UUID.randomUUID().toString().also {
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
    fun sendEvent(event: AppsTrackEvent, name: String? = null, parameters: Map<String, Any?>? = null) {
        // A CUSTOM event with no name is rejected by the backend (422) and the offline
        // queue would then drop it as a permanent failure — silently losing it. Refuse
        // it here so the mistake is visible in debug. Use sendCustomEvent(name, …).
        if (isDroppableCustom(event, name)) {
            if (debug) println("[AppsTrack] CUSTOM event requires a non-empty name — event dropped")
            return
        }
        // SimpleDateFormat is NOT thread-safe and sendEvent runs on arbitrary caller
        // threads — synchronize access to the shared formatter.
        val eventTime = synchronized(iso) { iso.format(Date()) }
        val item = JSONObject()
            .put("apptrackId", apptrackId)
            .put("appId", packageName)
            .put("event", event.raw)
            .put("eid", UUID.randomUUID().toString()) // idempotency key; backend dedupes retries
            .put("eventTime", eventTime)
        if (name != null) item.put("name", name)
        if (parameters != null) item.put("parameters", JSONObject(parameters))
        enqueue(item)
        flushQueue()
    }

    /** Record a CUSTOM event. [name] is the custom event label and is required. */
    @JvmStatic
    @JvmOverloads
    fun sendCustomEvent(name: String, parameters: Map<String, Any?>? = null) =
        sendEvent(AppsTrackEvent.CUSTOM, name, parameters)

    /** A CUSTOM event with no usable name can never be ingested. Pure; for tests. */
    internal fun isDroppableCustom(event: AppsTrackEvent, name: String?): Boolean =
        event == AppsTrackEvent.CUSTOM && name.isNullOrEmpty()

    /** Append to the persisted queue (capped — oldest dropped first). Runs on `io`. */
    private fun enqueue(item: JSONObject) {
        io.execute {
            val arr = readQueue()
            arr.put(item)
            val out = cappedQueue(arr, MAX_QUEUE)
            if (debug && out.length() < arr.length()) {
                println("[AppsTrack] event queue full — dropping ${arr.length() - out.length()} oldest")
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
                println("[AppsTrack] dropping permanently-rejected event")
            }
            val cur = readQueue()
            val next = JSONArray()
            for (i in 1 until cur.length()) next.put(cur.get(i))
            prefs.edit().putString(QUEUE_KEY, next.toString()).apply()
            sendNextQueued() // keep draining
        }
    }

    // Note: `apptrackId` (a @JvmStatic property) already exposes `getApptrackId()` to
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

    // ── internals ──────────────────────────────────────────────────────────

    private fun fetchReferrerThenInstall(context: Context) {
        val client = InstallReferrerClient.newBuilder(context).build()
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
            .put("apptrackId", apptrackId)
            .put("appId", packageName)
            .put("device", device)
            .put("sdkVersion", SDK_VERSION)
            .put("installType", "new_install")
        if (!referrer.isNullOrEmpty()) body.put("installReferrer", referrer)
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
        if (apptrackId.isEmpty()) return
        if (attempt == 0 && !polling.compareAndSet(false, true)) return // a chain already runs
        val delays = longArrayOf(2000, 4000, 8000, 16000)
        io.execute {
            val enc = URLEncoder.encode(apptrackId, "UTF-8")
            val json = get("/v1/attribution?apptrackId=$enc")
            val status = json?.optString("status", "pending")
            if (json != null && status != "pending") {
                attribution = jsonToMap(json)
                prefs.edit().putString(ATTR_KEY, json.toString()).apply()
                polling.set(false)
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
        code in 400..499 -> Outcome.PERMANENT // 4xx → server will keep rejecting
        else -> Outcome.TRANSIENT // 5xx or unknown
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
