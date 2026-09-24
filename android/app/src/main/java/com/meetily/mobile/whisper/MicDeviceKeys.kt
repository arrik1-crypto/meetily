package com.meetily.mobile.whisper

/**
 * The string form of a preferred-microphone choice, and how a stored one is
 * matched back to a device. Pure Kotlin, so it is unit-tested.
 *
 * Keys are "type|productName|address". The address is what tells a phone's
 * built-in mics apart ("bottom", "back"): they share type and product name,
 * so the older "type|productName" key made every one of them the same choice
 * and picking the back mic silently selected the bottom one.
 *
 * Matching tries the exact key first, then falls back to type and product
 * name alone. That keeps keys saved by older versions working, and a USB mic
 * whose address changed with the port it was plugged into.
 */
object MicDeviceKeys {

    fun key(type: Int, productName: String, address: String): String {
        val base = "$type|$productName"
        return if (address.isBlank()) base else "$base|${address.trim()}"
    }

    /** Index into [keys] of the stored key, or -1 when nothing matches. */
    fun indexOf(keys: List<String>, stored: String): Int {
        val exact = keys.indexOf(stored)
        if (exact >= 0) return exact
        val base = baseOf(stored)
        return keys.indexOfFirst { baseOf(it) == base }
    }

    /** The product name inside a stored key, for "not connected" text. */
    fun nameOf(key: String): String = key.split('|').getOrNull(1) ?: key

    private fun baseOf(key: String): String = key.split('|').take(2).joinToString("|")
}
