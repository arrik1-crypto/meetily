package com.meetily.mobile.data

/**
 * The example AI endpoint address that used to be the stored default.
 *
 * Settings put the stored address into the field as text, so the example
 * read like a value already chosen, and Save wrote it back. With "Use AI"
 * on, meetings then went over plain http to whatever machine held that
 * private address on the current network. The default is blank now (the
 * example stays as the field's hint); this decides which upgraded installs
 * have the example cleared. Pure, so it is unit-tested.
 */
object EndpointExample {

    const val OLD_DEFAULT = "http://192.168.1.100:11434/v1"

    /** Set once [AppSettings.migrateEndpointExample] has run. */
    const val KEY_MIGRATED = "llm_base_url_example_cleared"

    /**
     * Whether the stored address [stored] is the old example and nothing
     * uses it: AI off, or the on-device engine selected. An endpoint in use
     * at that address is kept, since it may be the user's own server, and
     * clearing it would quietly switch their AI features off.
     */
    fun shouldClear(stored: String?, useLlm: Boolean, engine: String): Boolean =
        stored?.trim() == OLD_DEFAULT && (!useLlm || engine == "local")
}
