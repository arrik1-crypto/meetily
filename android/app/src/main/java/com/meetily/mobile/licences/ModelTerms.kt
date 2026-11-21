package com.meetily.mobile.licences

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.StyleSpan
import android.text.util.Linkify
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.text.util.LinkifyCompat
import com.meetily.mobile.R
import com.meetily.mobile.TermsActivity

/**
 * The one helper every model download goes through for licence terms.
 *
 * Gemma, MedGemma (Health AI Developer Foundations) and Llama licences pass
 * their use restrictions on to whoever uses the model, so the first download
 * under each [TermsFamily] waits for the user to accept that licence. The
 * answer is remembered per family, with the family's version, in the app's
 * settings file. SettingsActivity.pickModel asks through [confirm], and
 * ModelDownloadService refuses through [requireAccepted] anything that
 * reached it without an answer, so a new download path cannot skip the
 * question by accident.
 */
object ModelTerms {

    /** AppSettings' file: the acceptance is a setting like any other. */
    private const val PREFS = "meetily_settings"
    private const val KEY_PREFIX = "model_terms_accepted_"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isAccepted(context: Context, family: TermsFamily): Boolean =
        !family.needsAcceptance(prefs(context).getInt(KEY_PREFIX + family.id, 0))

    fun markAccepted(context: Context, family: TermsFamily) {
        prefs(context).edit().putInt(KEY_PREFIX + family.id, family.version).apply()
    }

    /** True when [key] may download now: no such licence, or it was accepted. */
    fun mayDownload(context: Context, key: String): Boolean {
        val family = ModelLicences.familyFor(key) ?: return true
        return isAccepted(context, family)
    }

    /**
     * Last line of defence, for the download service: throws (with a message
     * for the download's error report) when [key] needs an acceptance that
     * was never given.
     */
    fun requireAccepted(context: Context, key: String) {
        check(mayDownload(context, key)) {
            context.getString(R.string.licences_download_needs_acceptance)
        }
    }

    /**
     * Asks the user to accept [key]'s licence, naming the model as
     * [modelName], and runs [onAccepted] once they do (after the answer is
     * stored, so a caller that simply retries goes through). Cancelling runs
     * nothing. A model with no such licence runs [onAccepted] at once.
     */
    fun confirm(activity: Activity, key: String, modelName: String, onAccepted: () -> Unit) {
        val family = ModelLicences.familyFor(key)
        if (family == null || isAccepted(activity, family)) {
            onAccepted()
            return
        }
        val licenceName = activity.getString(nameRes(family))
        val policyName = activity.getString(policyRes(family))
        val message = SpannableStringBuilder()
        if (family.builtWithLlama) {
            message.append(
                activity.getString(R.string.licences_built_with_llama),
                StyleSpan(Typeface.BOLD),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            message.append("\n\n")
        }
        message.append(
            activity.getString(
                R.string.licences_accept_body,
                modelName, licenceName, policyName, family.termsUrl, family.policyUrl
            )
        )
        if (family == TermsFamily.HAI_DEF) {
            message.append("\n\n")
            message.append(activity.getString(R.string.licences_accept_hai_def_note))
        }
        LinkifyCompat.addLinks(message, Linkify.WEB_URLS)

        val dialog = AlertDialog.Builder(activity)
            .setTitle(licenceName)
            .setMessage(message)
            .setPositiveButton(R.string.licences_accept_download) { _, _ ->
                markAccepted(activity, family)
                onAccepted()
            }
            .setNegativeButton(android.R.string.cancel, null)
            // Null here, and the real handler attached after show(), so that
            // reading the terms leaves the question open behind them.
            .setNeutralButton(R.string.licences_terms_of_use, null)
            .show()
        dialog.findViewById<TextView>(android.R.id.message)?.movementMethod =
            LinkMovementMethod.getInstance()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            activity.startActivity(Intent(activity, TermsActivity::class.java))
        }
    }

    /**
     * The licence line for [key]'s model-picker card, or null for a key no
     * catalogue defines. Llama models lead with "Built with Llama" on a line
     * of its own, as their licence asks it to be shown prominently.
     */
    fun pickerLine(context: Context, key: String): CharSequence? {
        val licence = ModelLicences.forKey(key) ?: return null
        val label = context.getString(
            R.string.licences_picker_label, context.getString(nameRes(licence))
        )
        if (licence.family?.builtWithLlama != true) return label
        return SpannableStringBuilder()
            .append(
                context.getString(R.string.licences_built_with_llama),
                StyleSpan(Typeface.BOLD),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            .append("\n")
            .append(label)
    }

    @StringRes
    private fun nameRes(licence: Licence): Int = when (licence) {
        Licence.MIT -> R.string.licences_name_mit
        Licence.APACHE_2 -> R.string.licences_name_apache_2
        Licence.CC_BY_4 -> R.string.licences_name_cc_by_4
        Licence.GEMMA -> R.string.licences_name_gemma
        Licence.HAI_DEF -> R.string.licences_name_hai_def
        Licence.LLAMA_3_2 -> R.string.licences_name_llama_3_2
        Licence.LLAMA_3_1 -> R.string.licences_name_llama_3_1
        Licence.SEE_MODEL_PAGE -> R.string.licences_see_model_page
    }

    @StringRes
    private fun nameRes(family: TermsFamily): Int = when (family) {
        TermsFamily.GEMMA -> R.string.licences_name_gemma
        TermsFamily.HAI_DEF -> R.string.licences_name_hai_def
        TermsFamily.LLAMA_3_2 -> R.string.licences_name_llama_3_2
        TermsFamily.LLAMA_3_1 -> R.string.licences_name_llama_3_1
    }

    @StringRes
    private fun policyRes(family: TermsFamily): Int = when (family) {
        TermsFamily.GEMMA -> R.string.licences_policy_gemma
        TermsFamily.HAI_DEF -> R.string.licences_policy_hai_def
        TermsFamily.LLAMA_3_2 -> R.string.licences_policy_llama_3_2
        TermsFamily.LLAMA_3_1 -> R.string.licences_policy_llama_3_1
    }
}
