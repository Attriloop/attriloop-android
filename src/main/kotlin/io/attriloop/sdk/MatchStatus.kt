package io.attriloop.sdk

/**
 * Lifecycle of this install's attribution decision. Distinct from `matchMethod`,
 * which names the courier. The SDK never invents `organic`: silence from the
 * server is [pending]. The SDK does not know what is billable — that is server
 * policy, carried on the wire as `matchStatus` + `terminal` + `applyDeepLink`.
 */
enum class AttriloopMatchStatus(val raw: String) {
    /** The server says a courier named a click. */
    matched("matched"),
    /** First-open recorded, no click, and no upgrade path still open. */
    organic("organic"),
    /** More than one plausible candidate and no deterministic courier. */
    ambiguous("ambiguous"),
    /** The request or the decision errored. We could not decide. */
    failed("failed"),
    /** First-open recorded; an upgrade is still possible, or we have not heard yet. */
    pending("pending"),
    /** SDK not configured, consent withheld, or the platform path does not apply. */
    skipped("skipped");

    companion object {
        fun fromRaw(raw: String?): AttriloopMatchStatus? =
            entries.firstOrNull { it.raw == raw }

        /**
         * Interpret a `GET /v1/attribution` body. Never inspects `matchMethod`.
         * Second = keep polling. Empty / missing fields never become [organic].
         */
        fun interpret(
            matchStatus: String?,
            terminal: Boolean?,
            status: String?,
        ): Pair<AttriloopMatchStatus, Boolean> {
            fromRaw(matchStatus)?.let { parsed ->
                val keep = terminal == false || parsed == pending
                return parsed to keep
            }
            if (terminal == false) return pending to true
            return when (status) {
                "organic" -> organic to false
                "attributed", "matched" -> matched to false
                "ambiguous" -> ambiguous to false
                "failed" -> failed to false
                "skipped" -> skipped to false
                "pending", "unattributed" -> pending to true
                else -> pending to true
            }
        }

        /** Deferred deep link follows the wire, not a method-name list. */
        fun shouldApplyDeepLink(
            applyDeepLink: Boolean?,
            status: AttriloopMatchStatus,
        ): Boolean = applyDeepLink ?: (status == matched)

        fun restored(
            configured: Boolean,
            persisted: String?,
            params: Map<String, Any?>,
        ): AttriloopMatchStatus {
            if (!configured) return skipped
            fromRaw(persisted)?.takeIf { it != skipped }?.let { return it }
            val (status, keepPolling) = interpret(
                params["matchStatus"] as? String,
                params["terminal"] as? Boolean,
                params["status"] as? String,
            )
            return if (keepPolling) pending else status
        }
    }
}

/** Status plus the params map. `matched` with empty params is still `matched`. */
data class AttriloopAttribution(
    val status: AttriloopMatchStatus,
    val params: Map<String, Any?>,
)
