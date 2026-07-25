package com.meetily.mobile

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors

/**
 * A plain list of actions in a bottom sheet, sharing the card-picker's
 * manners: tapping an item dismisses, an unavailable item stays visible but
 * greyed with a reason, and a destructive item is set apart at the end so it
 * is never the thing a stray tap lands on.
 */
object ActionSheet {

    data class Item(
        val id: String,
        val title: String,
        /** Shown under the title — usually why an item is unavailable. */
        val subtitle: String? = null,
        val enabled: Boolean = true,
        val destructive: Boolean = false,
        /** Draws a hairline above this item. */
        val separated: Boolean = false
    )

    fun show(
        context: Context,
        title: String,
        items: List<Item>,
        onPick: (String) -> Unit
    ) {
        val sheet = BottomSheetDialog(context)
        val content = LayoutInflater.from(context).inflate(R.layout.sheet_actions, null)
        sheet.setContentView(content)
        content.findViewById<TextView>(R.id.actionSheetTitle).text = title
        val list = content.findViewById<LinearLayout>(R.id.actionSheetList)
        val inflater = LayoutInflater.from(context)
        val density = context.resources.displayMetrics.density
        val error = MaterialColors.getColor(
            content, com.google.android.material.R.attr.colorError
        )
        val outline = MaterialColors.getColor(
            content, com.google.android.material.R.attr.colorOutlineVariant
        )
        for (item in items) {
            if (item.separated && list.childCount > 0) {
                list.addView(
                    View(context).apply {
                        setBackgroundColor(outline)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            (1 * density).toInt()
                        ).apply {
                            topMargin = (6 * density).toInt()
                            bottomMargin = (6 * density).toInt()
                        }
                    }
                )
            }
            val row = inflater.inflate(R.layout.item_action_row, list, false)
            val titleView = row.findViewById<TextView>(R.id.actionRowTitle)
            titleView.text = item.title
            if (item.destructive) titleView.setTextColor(error)
            val subtitle = row.findViewById<TextView>(R.id.actionRowSubtitle)
            if (item.subtitle.isNullOrBlank()) {
                subtitle.visibility = View.GONE
            } else {
                subtitle.visibility = View.VISIBLE
                subtitle.text = item.subtitle
            }
            if (item.enabled) {
                row.setOnClickListener {
                    sheet.dismiss()
                    onPick(item.id)
                }
            } else {
                row.isEnabled = false
                row.alpha = 0.45f
            }
            list.addView(row)
        }
        sheet.show()
    }
}
