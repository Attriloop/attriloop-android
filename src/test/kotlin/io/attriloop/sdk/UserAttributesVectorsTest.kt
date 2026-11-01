package io.attriloop.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the shared `user-attributes.json` vectors (source of truth:
 * attriloop-saas `packages/core/test-vectors/`, synced by `scripts/sync-test-vectors.sh`).
 * The iOS SDK runs the same file, so both platforms must produce identical hashes.
 */
class UserAttributesVectorsTest {
    private val vectors: JSONObject by lazy {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("user-attributes.json")) {
            "user-attributes.json missing from src/test/resources"
        }
        JSONObject(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
    }

    private fun input(json: JSONObject) = UserAttributes.Input(
        email = json.optString("email").takeIf { json.has("email") },
        firstName = json.optString("firstName").takeIf { json.has("firstName") },
        lastName = json.optString("lastName").takeIf { json.has("lastName") },
        name = json.optString("name").takeIf { json.has("name") },
        phoneNumber = json.optString("phoneNumber").takeIf { json.has("phoneNumber") },
        dateOfBirth = json.optString("dateOfBirth").takeIf { json.has("dateOfBirth") },
        gender = json.optString("gender").takeIf { json.has("gender") },
    )

    private fun JSONObject.toStringMap(): Map<String, String> =
        keys().asSequence().associateWith { getString(it) }

    @Test fun everySharedVectorProducesTheExpectedHashes() {
        val today = vectors.getString("today")
        val cases = vectors.getJSONArray("cases")
        assertTrue(cases.length() > 40)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val actual = UserAttributes.hashes(input(case.getJSONObject("input")), today)
            assertEquals(case.getString("name"), case.getJSONObject("expected").toStringMap(), actual)
        }
    }

    @Test fun everySharedVectorAlsoPassesThroughTheCustomEventAliases() {
        val today = vectors.getString("today")
        val reverse = mapOf(
            "email" to "email", "firstName" to "first_name", "lastName" to "last_name", "name" to "name",
            "phoneNumber" to "phone_number", "dateOfBirth" to "date_of_birth", "gender" to "gender",
        )
        val cases = vectors.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val raw = case.getJSONObject("input")
            val params = raw.keys().asSequence().associate { reverse.getValue(it) to raw.getString(it) as Any? }
            val actual = UserAttributes.hashes(UserAttributes.fromParameters(params), today)
            assertEquals(case.getString("name"), case.getJSONObject("expected").toStringMap(), actual)
        }
    }

    @Test fun parameterAliasesMatchTheSharedFile() {
        assertEquals(vectors.getJSONObject("parameterAliases").toStringMap(), UserAttributes.aliases())
    }

    @Test fun appstackStyleCustomPhoneSendsBothNetworkHashes() {
        val hashes = UserAttributes.hashes(
            UserAttributes.fromParameters(mapOf("phoneNumber" to "+34 600 000 000")),
            "20261101",
        )
        assertEquals(setOf("ph_meta", "ph_tiktok"), hashes.keys)
        assertEquals(UserAttributes.sha256("34600000000"), hashes["ph_meta"])
        assertEquals(UserAttributes.sha256("+34600000000"), hashes["ph_tiktok"])
    }

    @Test fun birthdateAndBirthdayAliasesMapToDateOfBirth() {
        for (key in listOf("birthdate", "birthday", "dateOfBirth", "date_of_birth")) {
            val hashes = UserAttributes.hashes(UserAttributes.fromParameters(mapOf(key to "1990-04-07")), "20261101")
            assertEquals(key, mapOf("date_of_birth" to UserAttributes.sha256("19900407")), hashes)
        }
    }

    @Test fun trimFollowsUnicodeWhiteSpaceNotKotlinTrim() {
        assertEquals("a", UserAttributes.trimWhiteSpace("\u00A0\u3000a\u2009\u0085"))
        assertEquals("\u001Fa", UserAttributes.trimWhiteSpace("\u001Fa"))
        assertEquals("a", "\u001Fa".trim()) // what Kotlin trim() would do
    }

    @Test fun productionTodayIsCompactUtcDate() {
        assertTrue(Regex("^\\d{8}$").matches(UserAttributes.todayUtc()))
    }
}
