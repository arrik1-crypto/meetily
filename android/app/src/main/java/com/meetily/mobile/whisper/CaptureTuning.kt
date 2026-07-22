package com.meetily.mobile.whisper

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build

/**
 * Microphone capture tuning for the Whisper engine: audio-source selection
 * (how the phone's firmware pre-processes the mic signal) and preferred
 * input device (built-in vs USB vs wired headset).
 *
 * The system SpeechRecognizer engine manages its own capture, so none of
 * this applies there.
 */
object CaptureTuning {

    const val SOURCE_RECOGNITION = "recognition"
    const val SOURCE_FARFIELD = "camcorder"
    const val SOURCE_RAW = "unprocessed"

    const val DEVICE_AUTO = "auto"

    val SOURCE_KEYS = listOf(SOURCE_RECOGNITION, SOURCE_FARFIELD, SOURCE_RAW)

    fun unprocessedSupported(context: Context): Boolean = try {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        "true".equals(
            am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED),
            ignoreCase = true
        )
    } catch (_: Throwable) {
        false
    }

    /** Resolves a stored tuning key to a MediaRecorder.AudioSource constant. */
    fun audioSourceFor(context: Context, key: String): Int = when (key) {
        SOURCE_FARFIELD -> MediaRecorder.AudioSource.CAMCORDER
        SOURCE_RAW ->
            if (unprocessedSupported(context)) MediaRecorder.AudioSource.UNPROCESSED
            else MediaRecorder.AudioSource.VOICE_RECOGNITION
        else -> MediaRecorder.AudioSource.VOICE_RECOGNITION
    }

    /**
     * Selectable capture devices. Bluetooth SCO is intentionally excluded:
     * forcing it needs an extra routing dance and its narrowband voice link
     * degrades transcription; USB and wired mics just work.
     */
    fun inputDevices(context: Context): List<AudioDeviceInfo> = try {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.getDevices(AudioManager.GET_DEVICES_INPUTS).filter {
            it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC ||
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
        }
    } catch (_: Throwable) {
        emptyList()
    }

    /** Stable-ish identity for persistence (device ids change across reboots). */
    fun deviceKey(device: AudioDeviceInfo): String =
        "${device.type}|${device.productName}"

    fun deviceLabel(device: AudioDeviceInfo): String {
        val name = device.productName?.toString()?.trim().orEmpty()
        val kind = when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "built-in"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
            else -> ""
        }
        val address = if (Build.VERSION.SDK_INT >= 28) {
            device.address?.trim().orEmpty()
        } else {
            ""
        }
        val detail = listOf(kind, address).filter { it.isNotBlank() }.joinToString(", ")
        return if (detail.isBlank()) name else "$name ($detail)"
    }

    /** Currently-connected device matching a stored key, or null for auto. */
    fun findPreferred(context: Context, key: String): AudioDeviceInfo? {
        if (key == DEVICE_AUTO || key.isBlank()) return null
        return inputDevices(context).firstOrNull { deviceKey(it) == key }
    }

    /** Human-readable name embedded in a stored key (for "not connected" UI). */
    fun nameFromKey(key: String): String = key.substringAfter('|', key)
}
