package com.meetily.mobile.data

import java.io.File

/**
 * The one check every "file named by stored data" goes through.
 *
 * Meeting JSON names other files — its own `<id>.json`, the audio file,
 * photos, attachments — and every store joins those names onto its directory.
 * A backup restored from someone else can carry any string there, and
 * "../voice_profiles.json" joined onto filesDir/audio is filesDir itself:
 * deleting that meeting would then delete the enrolled voiceprints, and
 * saving one with such an id would overwrite them with meeting JSON.
 *
 * Plain names only: no separators, no NUL, not "." or "..". A name that
 * passes cannot leave the directory it is joined onto. Pure — unit-tested.
 */
object SafeFiles {

    /**
     * Stands in for a rejected name. Never created by anything, so reads find
     * nothing and deletes delete nothing, and callers that expect a File keep
     * their signatures.
     */
    private const val REJECTED = ".rejected-reference"

    fun isPlainName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        if (name == "." || name == "..") return false
        return name.none { it == '/' || it == '\\' || it == '\u0000' }
    }

    /** [name] inside [dir], or a never-existing placeholder when it is not a plain name. */
    fun child(dir: File, name: String): File =
        File(dir, if (isPlainName(name)) name else REJECTED)
}
