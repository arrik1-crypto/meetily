package com.meetily.mobile.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.util.Locale

/**
 * Reads what DeviceFit needs to know about this phone.
 *
 * The memory figures are read once per process: neither changes while the
 * app runs. CpuFeatures reads /proc/cpuinfo once and keeps the answer.
 * Nothing here touches storage; ModelSpaceCheck measures that on a worker
 * thread when a download is about to start.
 */
object DeviceProbe {

    /** totalMem and advertisedMem (-1 below API 34), once known. */
    @Volatile private var memory: LongArray? = null

    private fun memory(context: Context): LongArray? {
        memory?.let { return it }
        val read = try {
            val manager = context.applicationContext
                .getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return null
            val info = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            // A display hint only: DeviceFit decides from totalMem alone, so
            // the verdicts are the same on every Android version.
            val advertised = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                info.advertisedMem
            } else {
                -1L
            }
            if (info.totalMem <= 0L) return null
            longArrayOf(info.totalMem, advertised)
        } catch (_: Exception) {
            return null
        }
        memory = read
        return read
    }

    /**
     * This phone as DeviceFit sees it, or null when its memory cannot be
     * read (every badge is then left off). [allocatableBytes] is passed in by
     * a caller that has measured storage.
     */
    fun device(context: Context, allocatableBytes: Long? = null): DeviceFit.Device? {
        val mem = memory(context) ?: return null
        return DeviceFit.Device(
            totalMemBytes = mem[0],
            advertisedMemBytes = mem[1].takeIf { it > 0L },
            tier = DeviceFit.CpuTier.of(CpuFeatures.dotprodFp16),
            allocatableBytes = allocatableBytes,
            language = Locale.getDefault().language
        )
    }

    /** How [need] fits this phone; null when either is unknown. */
    fun verdict(context: Context, need: DeviceFit.Need?): DeviceFit.Verdict? {
        if (need == null) return null
        val device = device(context) ?: return null
        return DeviceFit.classify(need, device)
    }
}
