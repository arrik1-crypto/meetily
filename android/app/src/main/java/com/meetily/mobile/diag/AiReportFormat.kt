package com.meetily.mobile.diag

/**
 * The "Report AI output" email: what it says, and the mailto: link that
 * opens it as a draft. Pure Kotlin, unit-tested; the dialog and the intent
 * live in AiReport.
 *
 * The body is plain English with stable codes rather than localised labels,
 * so reports read the same whatever the reporter's language. It carries the
 * reason, the comment, the app version, the model and the template, and the
 * generated text only when the user opted in: meetings are private, and the
 * default must send none of their content.
 */
object AiReportFormat {

    enum class Reason(val code: String) {
        OFFENSIVE("offensive_or_harmful"),
        INACCURATE("inaccurate"),
        PRIVACY("privacy_concern"),
        OTHER("other")
    }

    data class Report(
        /** Where the text was shown, e.g. "meeting_summary". */
        val surface: String,
        val reason: Reason,
        val comment: String,
        val appVersion: String,
        val androidVersion: String,
        val modelKey: String,
        /**
         * False when the model was not stored with the text and [modelKey]
         * is only the current setting, which may have changed since.
         */
        val modelRecorded: Boolean,
        val templateKey: String?,
        /** Null unless the user ticked "include the generated text". */
        val generatedText: String?
    )

    /** Generated text beyond this is cut, to keep the draft well inside an intent. */
    const val TEXT_CAP = 6_000

    const val COMMENT_CAP = 2_000

    fun body(report: Report): String = buildString {
        append("Recap AI output report\n")
        append("======================\n")
        append("Reason: ").append(report.reason.code).append('\n')
        append("Where: ").append(report.surface).append('\n')
        append("Comment: ")
        append(report.comment.trim().take(COMMENT_CAP).ifBlank { "(none)" })
        append("\n\n")
        append("App: Recap ").append(report.appVersion).append('\n')
        append("Android: ").append(report.androidVersion).append('\n')
        append("Model: ").append(report.modelKey.ifBlank { "unknown" })
        if (!report.modelRecorded) append(" (current setting, not recorded with this text)")
        append('\n')
        append("Template: ").append(report.templateKey?.ifBlank { null } ?: "none")
        append("\n\n")
        val text = report.generatedText?.trim().orEmpty()
        if (text.isEmpty()) {
            append("Generated text: not included.\n")
        } else {
            append("--- Generated text (included by the user) ---\n")
            append(capped(text))
            append('\n')
        }
    }

    fun capped(text: String): String =
        if (text.length <= TEXT_CAP) text
        else text.take(TEXT_CAP) + "\n[cut at $TEXT_CAP characters]"

    /**
     * An RFC 6068 mailto: link with the subject and body filled in. Line
     * breaks in the body go as CRLF, which the RFC requires; the address is
     * a fixed resource and is used as it stands.
     */
    fun mailtoUri(address: String, subject: String, body: String): String {
        val crlf = body.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\r\n")
        return "mailto:" + address + "?subject=" + encode(subject) + "&body=" + encode(crlf)
    }

    /** Percent-encodes the UTF-8 bytes of [value], leaving only RFC 3986 unreserved characters. */
    fun encode(value: String): String {
        val out = StringBuilder(value.length * 3)
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 0xff
            val unreserved = c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code ||
                c in '0'.code..'9'.code || c == '-'.code || c == '.'.code ||
                c == '_'.code || c == '~'.code
            if (unreserved) {
                out.append(c.toChar())
            } else {
                out.append('%').append(HEX[c shr 4]).append(HEX[c and 0x0f])
            }
        }
        return out.toString()
    }

    private const val HEX = "0123456789ABCDEF"
}
