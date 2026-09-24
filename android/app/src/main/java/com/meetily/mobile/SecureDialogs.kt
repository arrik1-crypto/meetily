package com.meetily.mobile

import androidx.appcompat.app.AlertDialog
import com.meetily.mobile.security.AppLock

/**
 * Drop-in for [AlertDialog.Builder.show] on dialogs that carry meeting
 * content (titles, transcript lines, speaker names). A plain show() leaves
 * the dialog's own window out of "Hide content from screenshots".
 */
fun AlertDialog.Builder.showSecure(): AlertDialog {
    val dialog = create()
    AppLock.secure(dialog)
    dialog.show()
    return dialog
}
