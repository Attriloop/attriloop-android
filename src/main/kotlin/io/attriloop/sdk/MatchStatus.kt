package io.attriloop.sdk

import org.json.JSONObject

/**
 * Lifecycle of this install's attribution decision. Distinct from `matchMethod`,
 * which names the courier. The SDK does not know what is billable or attributed —
 * that is server policy, carried on the wire as `attriloop_match_status` (edge ≥
 * 2026-11) or the legacy `matchStatus` + `terminal`.
 *
 * Values in `attriloop_match_status` of the params map: [matched], [matchedNoParams],
 * [organic], [skipped], [failed], [notConfigured]. Only [failed] and [notConfigured]
 * are retryable. [pending] and [ambiguous] remain for [Attriloop.getMatchStatus]
 * source compatibility and never appear in the params map.
 *
 * 0.3.0 migration: before `configure` the status is now [notConfigured] (it was
 * [skipped]); [skipped] now means "the server will not attempt a match" (erased subject).
 */
enum class AttriloopMatchStatus(val raw: String) {
    /** Attributed, with at least one of network, campaign, adset or ad. */
    matched("matched"),
    /** Attributed, with none of network, campaign, adset or ad. */
    matchedNoParams("matched_no_params"),
    /** Initial match done, not attributed. */
    organic("organic"),
    /** Legacy: more than one plausible candidate. Never in the params map. */
    ambiguous("ambiguous"),
    /** Attempted, unresolved (timeout or transport failure). Retryable. */
    failed("failed"),
    /** Waiting for the server. Never in the params map. */
    pending("pending"),
    /** No match will be attempted (erased subject). */
    skipped("skipped"),
    /** Read before `configure`. Resolves once configured. */
    notConfigured("not_configured");

    companion object {
        fun fromRaw(raw: String?): AttriloopMatchStatus? =
            entries.firstOrNull { it.raw == raw }

        /**
         * Interpret the legacy fields of a `GET /v1/attribution` body. Never inspects
         * `matchMethod`. Second = keep polling. Empty / missing fields never become [organic].
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
        ): Boolean = applyDeepLink ?: (status == matched || status == matchedNoParams)

        /** Restore the status from a persisted params map (see [AttributionDecision.decide]). */
        fun restored(
            configured: Boolean,
            persisted: String?,
            params: Map<String, Any?>,
        ): AttriloopMatchStatus {
            if (!configured) return notConfigured
            val decision = AttributionDecision.decide(params)
            if (decision.settled) return decision.status
            val stored = fromRaw(persisted)
            return if (stored == failed) failed else pending
        }
    }
}

/** Status plus the params map. `matched` with empty params is still `matched`. */
data class AttriloopAttribution(
    val status: AttriloopMatchStatus,
    val params: Map<String, Any?>,
)

/**
 * What one `GET /v1/attribution` body means for the SDK. Pure, shared by the poll
 * chain, the restore on launch and the parity fixture tests.
 *
 * - The edge's `attriloop_match_status` wins: `pending` keeps waiting, anything else
 *   settles. The contract keys come from `attribution_params` (or, for a persisted
 *   map, its own top-level `attriloop_*` keys).
 * - An older edge without that field falls back to the legacy fields: pending keeps
 *   waiting; matched becomes `matched`/`matched_no_params` from the legacy campaign
 *   fields; organic/ambiguous become `organic`.
 * - [repollNextLaunch] is the legacy "an upgrade is still possible" bit (`terminal`
 *   false): the SDK polls once on the next launch so a late upgrade reaches the cache.
 */
internal data class AttributionDecision(
    val settled: Boolean,
    val repollNextLaunch: Boolean,
    val status: AttriloopMatchStatus,
    /** Contract keys without `attriloop_id`; empty when not settled. */
    val contract: Map<String, String>,
) {
    companion object {
        const val STATUS_KEY = "attriloop_match_status"
        const val ID_KEY = "attriloop_id"
        const val PARAMS_KEY = "attribution_params"

        /** Optional contract keys, in contract order. */
        val OPTIONAL_KEYS = listOf(
            "attriloop_adnetwork",
            "attriloop_campaign",
            "attriloop_adset",
            "attriloop_ad",
            "attriloop_matching_type",
            "attriloop_confidence",
        )

        private val SETTLED_STATUSES = setOf(
            AttriloopMatchStatus.matched,
            AttriloopMatchStatus.matchedNoParams,
            AttriloopMatchStatus.organic,
            AttriloopMatchStatus.skipped,
            AttriloopMatchStatus.failed,
            AttriloopMatchStatus.notConfigured,
        )

        fun decide(body: Map<String, Any?>): AttributionDecision {
            val (legacyStatus, legacyKeep) = AttriloopMatchStatus.interpret(
                body["matchStatus"] as? String,
                body["terminal"] as? Boolean,
                body["status"] as? String,
            )
            val wire = body[STATUS_KEY] as? String
            if (wire != null) {
                val status = AttriloopMatchStatus.fromRaw(wire)
                if (status == null || status !in SETTLED_STATUSES) {
                    return AttributionDecision(false, legacyKeep, AttriloopMatchStatus.pending, emptyMap())
                }
                val source = asMap(body[PARAMS_KEY]) ?: body
                val contract = LinkedHashMap<String, String>()
                contract[STATUS_KEY] = wire
                for (key in OPTIONAL_KEYS) nonEmpty(source[key])?.let { contract[key] = it }
                return AttributionDecision(true, legacyKeep, status, contract)
            }
            if (legacyKeep) {
                return AttributionDecision(false, true, AttriloopMatchStatus.pending, emptyMap())
            }
            return when (legacyStatus) {
                AttriloopMatchStatus.matched -> {
                    val keys = LinkedHashMap<String, String>()
                    nonEmpty(body["adNetwork"])?.let { keys["attriloop_adnetwork"] = it }
                    (nonEmpty(body["campaignName"]) ?: nonEmpty(body["campaignId"]))?.let { keys["attriloop_campaign"] = it }
                    (nonEmpty(body["adsetName"]) ?: nonEmpty(body["adsetId"]))?.let { keys["attriloop_adset"] = it }
                    (nonEmpty(body["adName"]) ?: nonEmpty(body["adId"]))?.let { keys["attriloop_ad"] = it }
                    val status = if (keys.isEmpty()) AttriloopMatchStatus.matchedNoParams else AttriloopMatchStatus.matched
                    val contract = LinkedHashMap<String, String>()
                    contract[STATUS_KEY] = status.raw
                    contract.putAll(keys)
                    nonEmpty(body["matchingType"])?.let { contract["attriloop_matching_type"] = it }
                    nonEmpty(body["confidence"])?.let { contract["attriloop_confidence"] = it }
                    AttributionDecision(true, false, status, contract)
                }
                AttriloopMatchStatus.organic, AttriloopMatchStatus.ambiguous ->
                    settledOnly(AttriloopMatchStatus.organic)
                AttriloopMatchStatus.failed -> settledOnly(AttriloopMatchStatus.failed)
                AttriloopMatchStatus.skipped -> settledOnly(AttriloopMatchStatus.skipped)
                else -> AttributionDecision(false, true, AttriloopMatchStatus.pending, emptyMap())
            }
        }

        /**
         * The params map handed to the host: legacy server fields (minus the nested
         * `attribution_params`), the contract keys and the local `attriloop_id`.
         */
        fun merge(body: Map<String, Any?>, decision: AttributionDecision, attriloopId: String): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            for ((key, value) in body) {
                if (key == PARAMS_KEY || key == ID_KEY || key.startsWith("attriloop_")) continue
                out[key] = value
            }
            out.putAll(decision.contract)
            if (attriloopId.isNotEmpty()) out[ID_KEY] = attriloopId
            return out
        }

        /** A 0.2.x persisted cache: the raw legacy poll body, without contract keys. */
        fun isLegacyCache(saved: Map<String, Any?>): Boolean = saved[STATUS_KEY] == null

        /**
         * The params map restored at configure. A 0.3.0+ cache is used as stored. A
         * 0.2.x cache gets its contract keys rebuilt from the legacy fields, the same
         * mapping as a legacy edge response; an unsettled one is kept as is.
         */
        fun restoredParams(saved: Map<String, Any?>, attriloopId: String): Map<String, Any?> {
            if (!isLegacyCache(saved)) return saved
            val decision = decide(saved)
            return if (decision.settled) merge(saved, decision, attriloopId) else saved
        }

        private fun settledOnly(status: AttriloopMatchStatus) =
            AttributionDecision(true, false, status, mapOf(STATUS_KEY to status.raw))

        private fun nonEmpty(value: Any?): String? =
            (value as? String)?.takeIf { it.isNotBlank() }

        private fun asMap(value: Any?): Map<String, Any?>? = when (value) {
            is JSONObject -> value.keys().asSequence().associateWith { key ->
                value.opt(key).takeUnless { it == JSONObject.NULL }
            }
            is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to v }
            else -> null
        }
    }
}
