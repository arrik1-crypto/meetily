package com.meetily.mobile.notes

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.view.Gravity
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView

/** Builds the read-mode view of Markdown notes into a vertical container. */
object NotesRenderer {

    /**
     * Renders [source] into [container] (cleared first). Checklist rows get
     * live checkboxes; toggling one calls [onToggleCheck] with the source
     * line index.
     */
    fun render(
        container: LinearLayout,
        source: String,
        onToggleCheck: (Int) -> Unit
    ) {
        container.removeAllViews()
        val context = container.context
        for (block in NotesMarkdown.parse(source)) {
            when (block) {
                is NotesMarkdown.Block.Heading -> container.addView(
                    textView(context, block.text, bold = true).apply {
                        setTextSize(
                            TypedValue.COMPLEX_UNIT_SP,
                            when (block.level) {
                                1 -> 19f
                                2 -> 17f
                                else -> 15.5f
                            }
                        )
                        setPadding(0, dp(context, 10), 0, dp(context, 2))
                    }
                )
                is NotesMarkdown.Block.Bullet -> container.addView(
                    textView(context, null).apply {
                        text = SpannableStringBuilder("•  ").append(inline(block.text))
                        setPadding(dp(context, 6), dp(context, 2), 0, dp(context, 2))
                    }
                )
                is NotesMarkdown.Block.Check -> container.addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(
                            CheckBox(context).apply {
                                isChecked = block.done
                                setOnCheckedChangeListener { _, _ ->
                                    onToggleCheck(block.line)
                                }
                            }
                        )
                        addView(
                            textView(context, null).apply {
                                text = inline(block.text)
                                if (block.done) {
                                    paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                                    alpha = 0.55f
                                }
                            }
                        )
                    }
                )
                is NotesMarkdown.Block.Para -> container.addView(
                    textView(context, null).apply {
                        text = inline(block.text)
                        setPadding(0, dp(context, 2), 0, dp(context, 2))
                    }
                )
                NotesMarkdown.Block.Gap -> container.addView(
                    TextView(context).apply { height = dp(context, 8) }
                )
            }
        }
    }

    private fun inline(text: String): CharSequence {
        val out = SpannableStringBuilder()
        for (segment in NotesMarkdown.inlineSegments(text)) {
            val start = out.length
            out.append(segment.text)
            val end = out.length
            if (segment.bold) {
                out.setSpan(
                    StyleSpan(Typeface.BOLD), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            if (segment.italic) {
                out.setSpan(
                    StyleSpan(Typeface.ITALIC), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            if (segment.code) {
                out.setSpan(
                    TypefaceSpan("monospace"), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        return out
    }

    private fun textView(context: Context, text: String?, bold: Boolean = false): TextView =
        TextView(context).apply {
            if (text != null) this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            val color = TypedValue()
            context.theme.resolveAttribute(
                com.google.android.material.R.attr.colorOnSurface, color, true
            )
            setTextColor(color.data)
        }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
