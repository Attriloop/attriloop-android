package io.attriloop.sdk

import java.security.MessageDigest
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Normalization and SHA-256 hashing of customer match keys for the `user_attributes`
 * event (`docs/MMP_ROADMAP.md`, "SDK"). Plaintext never leaves this object: callers get
 * only 64-char lowercase hex. The rules are pinned by the shared vector file
 * `user-attributes.json` (also run by the iOS SDK), so a Meta/TikTok match never
 * depends on the platform.
 *
 * - email: trim, lowercase, exactly one `@` with non-empty parts.
 * - names: NFC, Unicode punctuation/symbols removed, whitespace collapsed, trimmed,
 *   lowercased. A full `name` is split on its first space; explicit first/last win.
 * - phone: ASCII digits only, one leading `00` removed, 7–15 digits.
 *   `ph_meta` = digits, `ph_tiktok` = `+` + digits. No country code is guessed.
 * - date of birth: `YYYY-MM-DD`, `YYYY/MM/DD` or `YYYYMMDD`, a real date from 1900
 *   up to today, hashed as `YYYYMMDD`.
 * - gender: `m`/`male` → `m`, `f`/`female` → `f`.
 * - a 64-hex value (except phone) is treated as pre-hashed and sent lowercased.
 */
internal object UserAttributes {
    /** Wire keys the edge accepts (64-hex each). */
    val KEYS = listOf("email", "first_name", "last_name", "ph_meta", "ph_tiktok", "date_of_birth", "gender")

    const val EVENT_NAME = "user_attributes"

    data class Input(
        val email: String? = null,
        val firstName: String? = null,
        val lastName: String? = null,
        val name: String? = null,
        val phoneNumber: String? = null,
        val dateOfBirth: String? = null,
        val gender: String? = null,
    )

    /** CUSTOM `user_attributes` parameter keys mapped onto [Input] fields. */
    private val PARAMETER_ALIASES = mapOf(
        "email" to "email",
        "name" to "name",
        "first_name" to "firstName",
        "firstName" to "firstName",
        "last_name" to "lastName",
        "lastName" to "lastName",
        "phone" to "phoneNumber",
        "phone_number" to "phoneNumber",
        "phoneNumber" to "phoneNumber",
        "date_of_birth" to "dateOfBirth",
        "dateOfBirth" to "dateOfBirth",
        "birthdate" to "dateOfBirth",
        "birthday" to "dateOfBirth",
        "gender" to "gender",
    )

    internal fun aliases(): Map<String, String> = PARAMETER_ALIASES

    /** Build an [Input] from Appstack-style CUSTOM parameters (non-strings ignored). */
    fun fromParameters(parameters: Map<String, Any?>): Input {
        val fields = HashMap<String, String>()
        for ((key, value) in parameters) {
            val field = PARAMETER_ALIASES[key] ?: continue
            val text = value as? String ?: continue
            fields[field] = text
        }
        return Input(
            email = fields["email"],
            firstName = fields["firstName"],
            lastName = fields["lastName"],
            name = fields["name"],
            phoneNumber = fields["phoneNumber"],
            dateOfBirth = fields["dateOfBirth"],
            gender = fields["gender"],
        )
    }

    /** Hashes for every usable field of [input]; unusable fields are omitted. */
    fun hashes(input: Input, today: String = todayUtc()): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        hashOrPass(input.email, ::email)?.let { out["email"] = it }

        var splitFirst: String? = null
        var splitLast: String? = null
        input.name?.let(::namePart)?.let { full ->
            val space = full.indexOf(' ')
            if (space < 0) {
                splitFirst = full
            } else {
                splitFirst = full.substring(0, space)
                splitLast = full.substring(space + 1)
            }
        }
        (hashOrPass(input.firstName, ::namePart) ?: splitFirst?.let(::sha256))?.let { out["first_name"] = it }
        (hashOrPass(input.lastName, ::namePart) ?: splitLast?.let(::sha256))?.let { out["last_name"] = it }

        input.phoneNumber?.let(::phoneDigits)?.let { digits ->
            out["ph_meta"] = sha256(digits)
            out["ph_tiktok"] = sha256("+$digits")
        }
        hashOrPass(input.dateOfBirth) { dob(it, today) }?.let { out["date_of_birth"] = it }
        hashOrPass(input.gender, ::gender)?.let { out["gender"] = it }
        return out
    }

    // ── rules ────────────────────────────────────────────────────────────────

    private val HEX64 = Regex("^[0-9a-fA-F]{64}$")
    private val DOB_FORMAT = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$|^(\\d{4})/(\\d{2})/(\\d{2})$|^(\\d{4})(\\d{2})(\\d{2})$")

    private fun hashOrPass(value: String?, normalize: (String) -> String?): String? {
        if (value == null) return null
        val trimmed = trimWhiteSpace(value)
        if (HEX64.matches(trimmed)) return trimmed.lowercase(Locale.ROOT)
        return normalize(value)?.let(::sha256)
    }

    internal fun email(value: String): String? {
        val normalized = trimWhiteSpace(value).lowercase(Locale.ROOT)
        val parts = normalized.split("@")
        if (parts.size != 2 || parts[0].isEmpty() || parts[1].isEmpty()) return null
        return normalized
    }

    internal fun namePart(value: String): String? {
        val nfc = Normalizer.normalize(value, Normalizer.Form.NFC)
        val out = StringBuilder()
        var inSpace = false
        var i = 0
        while (i < nfc.length) {
            val cp = nfc.codePointAt(i)
            i += Character.charCount(cp)
            when {
                isPunctuationOrSymbol(cp) -> Unit
                isWhiteSpace(cp) -> inSpace = true
                else -> {
                    if (inSpace && out.isNotEmpty()) out.append(' ')
                    inSpace = false
                    out.appendCodePoint(cp)
                }
            }
        }
        val normalized = out.toString().lowercase(Locale.ROOT)
        return normalized.ifEmpty { null }
    }

    internal fun phoneDigits(value: String): String? {
        var digits = value.filter { it in '0'..'9' }
        if (digits.startsWith("00")) digits = digits.substring(2)
        return if (digits.length in 7..15) digits else null
    }

    internal fun dob(value: String, today: String): String? {
        val match = DOB_FORMAT.matchEntire(trimWhiteSpace(value)) ?: return null
        val groups = match.groupValues.drop(1).filter { it.isNotEmpty() }
        if (groups.size != 3) return null
        val (y, m, d) = groups
        val year = y.toInt()
        val month = m.toInt()
        val day = d.toInt()
        if (year < 1900 || month !in 1..12 || day < 1) return null
        val leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
        val days = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        if (day > days[month - 1]) return null
        val compact = "$y$m$d"
        return if (compact <= today) compact else null
    }

    internal fun gender(value: String): String? = when (trimWhiteSpace(value).lowercase(Locale.ROOT)) {
        "m", "male" -> "m"
        "f", "female" -> "f"
        else -> null
    }

    /** Today's UTC date as `YYYYMMDD`. */
    fun todayUtc(): String = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())

    internal fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * Unicode `White_Space` property, spelled out. Kotlin `trim()` also strips the
     * separators U+001C–U+001F (not White_Space), and `\s` differs between the JVM and
     * Android's ICU regex; either would break parity with the shared vectors and iOS.
     */
    internal fun isWhiteSpace(cp: Int): Boolean =
        cp in 0x09..0x0D || cp == 0x20 || cp == 0x85 || cp == 0xA0 || cp == 0x1680 ||
            cp in 0x2000..0x200A || cp == 0x2028 || cp == 0x2029 || cp == 0x202F ||
            cp == 0x205F || cp == 0x3000

    private fun isPunctuationOrSymbol(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.CONNECTOR_PUNCTUATION,
        Character.DASH_PUNCTUATION,
        Character.START_PUNCTUATION,
        Character.END_PUNCTUATION,
        Character.INITIAL_QUOTE_PUNCTUATION,
        Character.FINAL_QUOTE_PUNCTUATION,
        Character.OTHER_PUNCTUATION,
        Character.MATH_SYMBOL,
        Character.CURRENCY_SYMBOL,
        Character.MODIFIER_SYMBOL,
        Character.OTHER_SYMBOL,
        -> true
        else -> false
    }

    internal fun trimWhiteSpace(value: String): String {
        var start = 0
        var end = value.length
        while (start < end) {
            val cp = value.codePointAt(start)
            if (!isWhiteSpace(cp)) break
            start += Character.charCount(cp)
        }
        while (end > start) {
            val cp = value.codePointBefore(end)
            if (!isWhiteSpace(cp)) break
            end -= Character.charCount(cp)
        }
        return value.substring(start, end)
    }
}
