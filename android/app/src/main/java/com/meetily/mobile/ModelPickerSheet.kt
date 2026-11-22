package com.meetily.mobile

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.meetily.mobile.data.DeviceFit

/**
 * Card-based model chooser used by every model surface (transcription,
 * speaker ID, on-device AI, import): one card per model with name, meta
 * line, and status, a per-model delete button on downloaded entries, and
 * an optional delete-all footer. Replaces the old dense text-list dialogs.
 */
object ModelPickerSheet {

    data class Entry(
        val key: String,
        val title: String,
        val meta: String,
        val downloaded: Boolean,
        val selected: Boolean,
        /** The model's licence line (see licences.ModelTerms.pickerLine); null hides it. */
        val licence: CharSequence? = null,
        /**
         * How the model fits this phone's memory (see FitBadge); null hides
         * the badge. Choosing a tight or too-big model asks first.
         */
        val fit: DeviceFit.Verdict? = null,
        /**
         * The model already in use, so choosing it again changes nothing and
         * skips the fit warning. That is what [selected] on a downloaded
         * entry means in Settings and Import; a picker whose [selected]
         * marks something else, such as a recommendation, passes its own.
         */
        val active: Boolean = selected && downloaded
    )

    /**
     * [entriesProvider] is re-queried after every delete so the sheet
     * refreshes in place. [onPick] dismisses the sheet; delete callbacks
     * keep it open. [onDismissed] fires exactly once when the sheet goes
     * away without a pick (cancel, back, outside tap). [header], when given,
     * sits above the cards and scrolls with them (Import's spoken language).
     */
    fun show(
        context: Context,
        title: String,
        entriesProvider: () -> List<Entry>,
        onPick: (String) -> Unit,
        onDelete: ((Entry) -> Unit)? = null,
        onDeleteAll: (() -> Unit)? = null,
        onDismissed: (() -> Unit)? = null,
        header: View? = null
    ) {
        val sheet = BottomSheetDialog(context)
        val content = LayoutInflater.from(context)
            .inflate(R.layout.sheet_model_picker, null)
        sheet.setContentView(content)
        content.findViewById<TextView>(R.id.modelPickerTitle).text = title
        val list = content.findViewById<LinearLayout>(R.id.modelPickerList)
        val deleteAll = content.findViewById<TextView>(R.id.modelPickerDeleteAll)
        if (header != null) (list.parent as? LinearLayout)?.addView(header, 0)
        var picked = false

        fun render() {
            list.removeAllViews()
            val entries = entriesProvider()
            val inflater = LayoutInflater.from(context)
            val primary = MaterialColors.getColor(
                content, com.google.android.material.R.attr.colorPrimary
            )
            val variant = MaterialColors.getColor(
                content, com.google.android.material.R.attr.colorOnSurfaceVariant
            )
            val outline = MaterialColors.getColor(
                content, com.google.android.material.R.attr.colorOutlineVariant
            )
            for (entry in entries) {
                val card = inflater.inflate(R.layout.item_model_card, list, false)
                    as MaterialCardView
                card.findViewById<TextView>(R.id.modelCardName).text = entry.title
                card.findViewById<TextView>(R.id.modelCardMeta).text = entry.meta
                card.findViewById<TextView>(R.id.modelCardLicence).apply {
                    text = entry.licence
                    visibility = if (entry.licence == null) View.GONE else View.VISIBLE
                }
                FitBadge.bind(card.findViewById(R.id.modelCardFit), entry.fit)
                val status = card.findViewById<TextView>(R.id.modelCardStatus)
                status.text = context.getString(
                    when {
                        entry.selected && entry.downloaded -> R.string.model_status_active
                        entry.selected -> R.string.model_status_selected_missing
                        entry.downloaded -> R.string.model_downloaded_label
                        else -> R.string.model_tap_download
                    }
                )
                status.setTextColor(if (entry.selected) primary else variant)
                if (entry.selected) {
                    card.strokeColor = primary
                    card.strokeWidth =
                        (2 * context.resources.displayMetrics.density).toInt()
                } else {
                    card.strokeColor = outline
                }
                card.setOnClickListener {
                    // A tight or too-big model is a warning, never a refusal;
                    // cancelling it leaves the sheet open to choose again.
                    FitBadge.confirmChoice(
                        context, entry.title, entry.fit, alreadyActive = entry.active
                    ) {
                        picked = true
                        sheet.dismiss()
                        onPick(entry.key)
                    }
                }
                val trash = card.findViewById<ImageButton>(R.id.modelCardDelete)
                if (entry.downloaded && onDelete != null) {
                    trash.visibility = View.VISIBLE
                    trash.setOnClickListener {
                        AlertDialog.Builder(context)
                            .setTitle(R.string.delete_model_title)
                            .setMessage(
                                context.getString(R.string.delete_model_body, entry.title)
                            )
                            .setPositiveButton(R.string.model_delete) { _, _ ->
                                onDelete(entry)
                                render()
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                }
                list.addView(card)
            }
            val anyDownloaded = entries.any { it.downloaded }
            deleteAll.visibility =
                if (onDeleteAll != null && anyDownloaded) View.VISIBLE else View.GONE
        }

        if (onDeleteAll != null) {
            deleteAll.setOnClickListener {
                AlertDialog.Builder(context)
                    .setTitle(R.string.delete_model_title)
                    .setMessage(R.string.delete_all_models_body)
                    .setPositiveButton(R.string.model_delete) { _, _ ->
                        onDeleteAll()
                        render()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }

        sheet.setOnDismissListener {
            if (!picked) onDismissed?.invoke()
        }
        render()
        sheet.show()
    }
}
