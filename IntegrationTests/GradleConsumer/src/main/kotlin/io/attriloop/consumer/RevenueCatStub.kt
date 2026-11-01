package io.attriloop.consumer

/**
 * Compile-only stand-in for the RevenueCat `Purchases` API used by the README snippet.
 * The real SDK is `com.revenuecat.purchases:purchases`; Attriloop never depends on it,
 * so this smoke project stubs the one call the snippet makes.
 */
class Purchases private constructor() {
    fun setAttributes(attributes: Map<String, String?>) {
        check(attributes.isNotEmpty())
    }

    companion object {
        val sharedInstance = Purchases()
    }
}
