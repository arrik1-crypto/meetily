package com.meetily.mobile.llm

/**
 * Whether a chat call is served on this device, as a pure function of the
 * engine setting.
 *
 * Extracted so it can be tested. The decision otherwise lives inside a
 * SharedPreferences-backed `isSelected()` that a JVM unit test cannot reach,
 * and it is the predicate six places in the app lean on to mean "nothing
 * leaves the phone" — the privacy indicator, llmConfigured, the unattended
 * summary gate and the endpoint-consent prompt among them.
 *
 * This briefly returned a three-valued Target, while LiteRT-LM was a second
 * on-device runtime and "who answers" and "does it stay here" were genuinely
 * different questions. With that withdrawn they are one question again, and
 * two names for one boolean is how they drift apart.
 */
object EngineRouting {

    /**
     * True when [engine] (`AppSettings.llmEngine`) is served in-process by
     * llama.cpp, and therefore that nothing is sent off the device.
     *
     * Anything unrecognised answers false. Erring the other way would claim
     * on-device privacy for a configuration that has not been established as
     * such — the one direction of this decision that cannot be walked back.
     */
    fun staysOnDevice(engine: String): Boolean = engine == LOCAL

    private const val LOCAL = "local"
}
