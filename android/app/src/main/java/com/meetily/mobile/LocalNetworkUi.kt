package com.meetily.mobile

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.meetily.mobile.security.LocalNetworkAccess
import java.net.InetAddress
import java.net.URI

/**
 * The Android half of [LocalNetworkAccess]: the permission's state on this
 * phone, the words for a local AI server that could not be reached, and the
 * way to the app's settings page.
 *
 * While the build targets 36, [applies] is false everywhere, so the only
 * part that shows is [failureText]'s plain "could not reach" hint for a
 * local server, which holds whatever the permission does.
 */
object LocalNetworkUi {

    /** The switch: Android 17 or later with a build targeting it. */
    fun applies(context: Context): Boolean = LocalNetworkAccess.applies(
        Build.VERSION.SDK_INT, context.applicationInfo.targetSdkVersion
    )

    fun granted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, LocalNetworkAccess.PERMISSION) ==
            PackageManager.PERMISSION_GRANTED

    fun hint(context: Context, error: Throwable): LocalNetworkAccess.Hint {
        val applies = applies(context)
        return LocalNetworkAccess.hintFor(error, applies, applies && granted(context))
    }

    /** What to say about [error] when the local network explains it; null otherwise. */
    fun failureText(context: Context, error: Throwable): String? {
        val host = LocalNetworkAccess.unreachableIn(error)?.host ?: return null
        return when (hint(context, error)) {
            LocalNetworkAccess.Hint.ALLOW_ACCESS ->
                context.getString(R.string.platform_lan_needs_permission, host)
            LocalNetworkAccess.Hint.CHECK_NETWORK ->
                context.getString(R.string.platform_lan_unreachable, host)
            LocalNetworkAccess.Hint.NONE -> null
        }
    }

    fun openAppSettings(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:" + context.packageName))
            )
        } catch (_: Exception) {
        }
    }

    /**
     * [message] with a button to the app's settings page, where Nearby
     * devices (the permission's group) is allowed. [onClose] runs once the
     * dialog is gone, whichever way it went.
     */
    fun showPermissionDialog(activity: Activity, message: String, onClose: (() -> Unit)? = null) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.platform_lan_permission_title)
            .setMessage(message)
            .setPositiveButton(R.string.platform_lan_open_settings) { _, _ ->
                openAppSettings(activity)
            }
            .setNegativeButton(R.string.platform_lan_not_now, null)
            .setOnDismissListener { onClose?.invoke() }
            .show()
    }

    /** [url]'s host, brackets and all for an IPv6 literal; null when it has none. */
    fun hostOf(url: String): String? = try {
        URI(url.trim()).host
    } catch (_: Exception) {
        null
    }

    /**
     * Where [url]'s host leads. A plain name is looked up, as EndpointGuard
     * does before every request, so this runs off the main thread, and only
     * once [applies] (never while the build targets 36).
     */
    fun endpointKind(url: String): LocalNetworkAccess.Kind {
        val host = hostOf(url) ?: return LocalNetworkAccess.Kind.OTHER
        val known = LocalNetworkAccess.hostKind(host)
        if (known != LocalNetworkAccess.Kind.OTHER || LocalNetworkAccess.literal(host) != null) {
            return known
        }
        val resolved = try {
            InetAddress.getAllByName(host).toList()
        } catch (_: Exception) {
            emptyList()
        }
        return LocalNetworkAccess.hostKind(host, resolved)
    }
}
