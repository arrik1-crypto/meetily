package com.meetily.mobile

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.meetily.mobile.data.AppSettings
import com.meetily.mobile.diag.AiReportFormat
import com.meetily.mobile.llm.EngineRouting
import com.meetily.mobile.summarize.AiProvenance

/**
 * "Report AI output": the one flow every screen showing generated text uses
 * to let the user flag it to the developer (Play's AI-generated content
 * policy).
 *
 * The user picks a reason, may add a comment, and may tick "include the
 * generated text" (off by default: meetings are private). Choosing to send
 * opens an email draft to @string/support_email in the user's own email app,
 * so nothing leaves the phone until they send it there. With no email app,
 * the report goes to the clipboard instead and a toast says where to paste
 * it. Nothing is stored about the report.
 */
object AiReport {

    /** One piece of generated text that can be reported. */
    class Subject(
        /** Shown when a screen offers several things to report. */
        val label: String,
        /** Stable code for where it was shown ("meeting_summary", …). */
        val surface: String,
        /** The generated text; only sent if the user opts in. */
        val text: String,
        val templateKey: String? = null,
        /**
         * The engine and model that wrote [text] (AiProvenance), when known.
         * Null reports the current setting and says so.
         */
        val modelKey: String? = null
    )

    /**
     * Adds a "Report AI output" button straight after [anchor], inside the
     * same parent, so no existing layout has to change. [anchor]'s parent
     * must be a LinearLayout or similar flow container.
     */
    fun addButton(
        anchor: View,
        textRes: Int = R.string.aisafety_report_action,
        onClick: () -> Unit
    ): MaterialButton {
        val parent = anchor.parent as ViewGroup
        val button = LayoutInflater.from(anchor.context)
            .inflate(R.layout.aisafety_report_button, parent, false) as MaterialButton
        button.setText(textRes)
        button.setOnClickListener { onClick() }
        parent.addView(button, parent.indexOfChild(anchor) + 1)
        return button
    }

    /** The current engine and model as AiProvenance records them, for live generations. */
    fun currentModelKey(context: Context): String {
        val settings = AppSettings(context)
        return AiProvenance.modelKey(
            useLlm = true,
            onDevice = EngineRouting.staysOnDevice(settings.llmEngine),
            localModel = settings.localLlmModel,
            endpointModel = settings.llmModel
        )
    }

    /** Asks which of [subjects] to report, or goes straight on when there is one. */
    fun choose(activity: Activity, subjects: List<Subject>) {
        val usable = subjects.filter { it.text.isNotBlank() }
        when (usable.size) {
            0 -> Toast.makeText(
                activity, R.string.aisafety_nothing_to_report, Toast.LENGTH_SHORT
            ).show()
            1 -> show(activity, usable.first())
            else -> AlertDialog.Builder(activity)
                .setTitle(R.string.aisafety_report_pick_title)
                .setItems(usable.map { it.label }.toTypedArray()) { _, which ->
                    show(activity, usable[which])
                }
                .setNegativeButton(android.R.string.cancel, null)
                .showSecure()
        }
    }

    /** The report dialog for [subject]. */
    fun show(activity: Activity, subject: Subject) {
        if (subject.text.isBlank()) {
            Toast.makeText(activity, R.string.aisafety_nothing_to_report, Toast.LENGTH_SHORT)
                .show()
            return
        }
        val view = LayoutInflater.from(activity).inflate(R.layout.aisafety_dialog_report, null)
        val reasons = view.findViewById<RadioGroup>(R.id.aisafety_reason_group)
        val comment = view.findViewById<EditText>(R.id.aisafety_report_comment)
        val include = view.findViewById<CheckBox>(R.id.aisafety_report_include)
        include.isChecked = false
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.aisafety_report_title)
            .setView(view)
            .setPositiveButton(R.string.aisafety_report_send) { _, _ ->
                val reason = reasonFor(reasons.checkedRadioButtonId)
                if (reason != null) {
                    send(activity, subject, reason, comment.text.toString(), include.isChecked)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSecure()
        // A reason is required: the positive button waits for one.
        val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        positive?.isEnabled = false
        reasons.setOnCheckedChangeListener { _, checkedId ->
            positive?.isEnabled = reasonFor(checkedId) != null
        }
    }

    private fun reasonFor(checkedId: Int): AiReportFormat.Reason? = when (checkedId) {
        R.id.aisafety_reason_offensive -> AiReportFormat.Reason.OFFENSIVE
        R.id.aisafety_reason_inaccurate -> AiReportFormat.Reason.INACCURATE
        R.id.aisafety_reason_privacy -> AiReportFormat.Reason.PRIVACY
        R.id.aisafety_reason_other -> AiReportFormat.Reason.OTHER
        else -> null
    }

    private fun send(
        activity: Activity,
        subject: Subject,
        reason: AiReportFormat.Reason,
        comment: String,
        includeText: Boolean
    ) {
        val address = activity.getString(R.string.support_email)
        val body = AiReportFormat.body(
            AiReportFormat.Report(
                surface = subject.surface,
                reason = reason,
                comment = comment,
                appVersion = appVersion(activity),
                androidVersion = "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
                modelKey = subject.modelKey ?: currentModelKey(activity),
                modelRecorded = subject.modelKey != null,
                templateKey = subject.templateKey,
                generatedText = if (includeText) subject.text else null
            )
        )
        val mailSubject = activity.getString(R.string.aisafety_report_subject, reason.code)
        // The link carries subject and body for apps that only read the
        // URI; the extras serve the ones that only read those.
        val intent = Intent(
            Intent.ACTION_SENDTO,
            Uri.parse(AiReportFormat.mailtoUri(address, mailSubject, body))
        ).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(address))
            putExtra(Intent.EXTRA_SUBJECT, mailSubject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            copyInstead(activity, address, mailSubject, body)
        } catch (_: RuntimeException) {
            // A draft too large for the binder, or a broken mail handler.
            copyInstead(activity, address, mailSubject, body)
        }
    }

    /** No email app: the whole report goes to the clipboard, ready to paste. */
    private fun copyInstead(activity: Activity, address: String, subject: String, body: String) {
        val text = "To: $address\nSubject: $subject\n\n$body"
        val copied = try {
            val clipboard = activity.getSystemService(ClipboardManager::class.java)
            val clip = ClipData.newPlainText(
                activity.getString(R.string.aisafety_report_clip_label), text
            )
            // May hold meeting text: keep it out of clipboard previews.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                clip.description.extras = PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
            clipboard?.setPrimaryClip(clip)
            clipboard != null
        } catch (_: Exception) {
            false
        }
        Toast.makeText(
            activity,
            activity.getString(
                if (copied) R.string.aisafety_report_no_email
                else R.string.aisafety_report_copy_failed,
                address
            ),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun appVersion(context: Context): String = try {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        "${info.versionName ?: "?"} ($code)"
    } catch (_: Exception) {
        "?"
    }
}
