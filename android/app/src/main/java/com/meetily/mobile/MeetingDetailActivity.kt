package com.meetily.mobile

import android.content.Intent
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.ShapeAppearanceModel
import com.meetily.mobile.data.ActionItem
import com.meetily.mobile.data.AppSettings
import com.meetily.mobile.data.Meeting
import com.meetily.mobile.data.MeetingStore
import com.meetily.mobile.data.PhotoStore
import com.meetily.mobile.data.QaEntry
import com.meetily.mobile.export.MeetingExporter
import com.meetily.mobile.summarize.ActionItems
import com.meetily.mobile.summarize.ExtractiveSummarizer
import com.meetily.mobile.summarize.LlmClient
import com.meetily.mobile.summarize.SummaryTemplate
import com.meetily.mobile.summarize.SummaryTemplates
import java.io.File
import java.text.DateFormat
import java.util.Date

class MeetingDetailActivity : AppCompatActivity() {

    private lateinit var store: MeetingStore
    private lateinit var settings: AppSettings
    private var meeting: Meeting? = null

    private lateinit var titleView: TextView
    private lateinit var dateView: TextView
    private lateinit var metaView: TextView
    private lateinit var summaryView: TextView
    private lateinit var aiPanel: View
    private lateinit var transcriptList: LinearLayout
    private lateinit var notesInput: EditText
    private lateinit var attendeesInput: EditText
    private lateinit var progress: ProgressBar
    private lateinit var qaList: LinearLayout
    private lateinit var askInput: EditText
    private lateinit var askSend: MaterialButton
    private lateinit var askRow: View
    private lateinit var askDisabledHint: View

    private var pendingPhotoFile: File? = null
    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val file = pendingPhotoFile
            pendingPhotoFile = null
            val m = meeting
            if (success && file != null && file.exists() && file.length() > 0 && m != null) {
                m.photos.add(file.name)
                store.save(m)
                renderPhotos(m)
            } else {
                file?.delete()
            }
        }
    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { importPhoto(it) }
        }
    private val exportMd =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
            uri?.let { writeExport(it, isPdf = false) }
        }
    private val exportPdf =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            uri?.let { writeExport(it, isPdf = true) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.apply(this)
        setContentView(R.layout.activity_detail)

        store = MeetingStore(this)
        settings = AppSettings(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.detailToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = ""
        toolbar.setNavigationOnClickListener { finish() }

        titleView = findViewById(R.id.detailTitle)
        dateView = findViewById(R.id.detailDate)
        metaView = findViewById(R.id.detailMeta)
        summaryView = findViewById(R.id.detailSummary)
        aiPanel = findViewById(R.id.aiPanel)
        transcriptList = findViewById(R.id.transcriptList)
        notesInput = findViewById(R.id.detailNotes)
        attendeesInput = findViewById(R.id.detailAttendees)
        progress = findViewById(R.id.summaryProgress)
        qaList = findViewById(R.id.qaList)
        askInput = findViewById(R.id.askInput)
        askSend = findViewById(R.id.askSend)
        askRow = findViewById(R.id.askRow)
        askDisabledHint = findViewById(R.id.askDisabledHint)

        val id = intent.getStringExtra(EXTRA_MEETING_ID)
        meeting = id?.let { store.load(it) }
        val m = meeting
        if (m == null) {
            Toast.makeText(this, R.string.meeting_not_found, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        titleView.text = m.title
        dateView.text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(m.createdAtMs))
        val wordCount = m.transcriptText()
            .split(Regex("\\s+"))
            .count { it.isNotBlank() }
        metaView.text = getString(R.string.detail_meta, m.segments.size, wordCount)

        findViewById<View>(R.id.generateButton).setOnClickListener {
            chooseTemplateAndSummarize()
        }
        askSend.setOnClickListener { sendQuestion() }
        findViewById<View>(R.id.addPhotoCamera).setOnClickListener { capturePhoto() }
        findViewById<View>(R.id.addPhotoGallery).setOnClickListener {
            try {
                pickImage.launch("image/*")
            } catch (_: Exception) {
                Toast.makeText(this, R.string.photo_attach_failed, Toast.LENGTH_SHORT).show()
            }
        }

        renderSummary(m.summary)
        renderActionItems(m)
        renderTranscript(m)
        renderQaHistory(m)
        renderPhotos(m)
        notesInput.setText(m.notes)
        attendeesInput.setText(m.attendeesText())
        maybeAutoTitle(m)
    }

    override fun onResume() {
        super.onResume()
        val llmReady = settings.useLlm && settings.llmBaseUrl.isNotBlank()
        askRow.visibility = if (llmReady) View.VISIBLE else View.GONE
        askDisabledHint.visibility = if (llmReady) View.GONE else View.VISIBLE
    }

    private fun renderSummary(summary: String) {
        if (summary.isBlank()) {
            summaryView.visibility = View.GONE
            aiPanel.visibility = View.VISIBLE
        } else {
            summaryView.text = summary
            summaryView.visibility = View.VISIBLE
            aiPanel.visibility = View.GONE
        }
    }

    private fun renderTranscript(m: Meeting) {
        transcriptList.removeAllViews()
        findViewById<View>(R.id.tagHint).visibility =
            if (m.segments.isEmpty()) View.GONE else View.VISIBLE
        if (m.segments.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.no_transcript)
                setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_BodyMedium
                )
                setTextColor(dateView.currentTextColor)
            }
            transcriptList.addView(empty)
            return
        }
        val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
        val inflater = LayoutInflater.from(this)
        for ((index, segment) in m.segments.withIndex()) {
            val line = inflater.inflate(R.layout.item_transcript_line, transcriptList, false)
            val timeView = line.findViewById<TextView>(R.id.lineTime)
            val time = timeFormat.format(Date(segment.timestampMs))
            timeView.text = if (segment.highlighted) "★ $time" else time
            line.findViewById<TextView>(R.id.lineText).text = segment.text
            val speakerView = line.findViewById<TextView>(R.id.lineSpeaker)
            if (segment.speaker.isNullOrBlank()) {
                speakerView.visibility = View.GONE
            } else {
                speakerView.text = segment.speaker
                speakerView.visibility = View.VISIBLE
            }
            if (segment.highlighted) {
                line.setBackgroundResource(R.drawable.bg_line_highlight)
            }
            line.setOnClickListener { assignSpeaker(index) }
            line.setOnLongClickListener {
                toggleHighlight(index)
                true
            }
            transcriptList.addView(line)
        }
    }

    private fun toggleHighlight(index: Int) {
        val m = meeting ?: return
        if (index !in m.segments.indices) return
        m.segments[index] = m.segments[index].copy(
            highlighted = !m.segments[index].highlighted
        )
        store.save(m)
        renderTranscript(m)
    }

    private fun assignSpeaker(index: Int) {
        val m = meeting ?: return
        if (index !in m.segments.indices) return
        saveEdits()
        SpeakerPicker.show(this, m.attendees, m.segments[index].speaker) { name ->
            if (index !in m.segments.indices) return@show
            m.segments[index] = m.segments[index].copy(speaker = name)
            if (!name.isNullOrBlank() &&
                m.attendees.none { it.equals(name, ignoreCase = true) }
            ) {
                m.attendees.add(name)
                attendeesInput.setText(m.attendeesText())
            }
            store.save(m)
            renderTranscript(m)
        }
    }

    override fun onPause() {
        super.onPause()
        saveEdits()
    }

    private fun saveEdits() {
        val m = meeting ?: return
        val newNotes = notesInput.text.toString()
        val newAttendees = Meeting.parseAttendees(attendeesInput.text.toString())
        if (newNotes != m.notes || newAttendees != m.attendees) {
            m.notes = newNotes
            m.attendees = newAttendees
            store.save(m)
        }
    }

    private fun maybeAutoTitle(m: Meeting) {
        val defaultTitle = getString(
            R.string.default_meeting_title,
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(m.createdAtMs))
        )
        if (m.title != defaultTitle || m.transcriptText().isBlank()) return

        val offline = ExtractiveSummarizer.titleFor(m.transcriptText())
        if (offline.isNotBlank()) {
            m.title = offline
            titleView.text = offline
            store.save(m)
        }
        if (settings.useLlm && settings.llmBaseUrl.isNotBlank()) {
            val baseUrl = settings.llmBaseUrl
            val apiKey = settings.llmApiKey
            val model = settings.llmModel
            val transcript = m.transcriptTextWithSpeakers()
            val notes = m.notes
            Thread {
                try {
                    val generated = LlmClient.title(baseUrl, apiKey, model, transcript, notes)
                    if (generated.isNotBlank()) {
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            m.title = generated
                            titleView.text = generated
                            store.save(m)
                        }
                    }
                } catch (_: Exception) {
                    // Offline title already applied; a failed refinement is fine.
                }
            }.start()
        }
    }

    private fun renderActionItems(m: Meeting) {
        val header = findViewById<View>(R.id.actionsHeader)
        val list = findViewById<LinearLayout>(R.id.actionList)
        list.removeAllViews()
        val visible = m.actionItems.isNotEmpty()
        header.visibility = if (visible) View.VISIBLE else View.GONE
        list.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return
        val inflater = LayoutInflater.from(this)
        for ((index, item) in m.actionItems.withIndex()) {
            val row = inflater.inflate(R.layout.item_action, list, false)
            val check = row.findViewById<CheckBox>(R.id.actionCheck)
            val text = row.findViewById<TextView>(R.id.actionText)
            val owner = row.findViewById<TextView>(R.id.actionOwner)
            text.text = item.task
            applyStrike(text, item.done)
            check.isChecked = item.done
            if (item.owner.isNullOrBlank()) {
                owner.visibility = View.GONE
            } else {
                owner.text = item.owner
                owner.visibility = View.VISIBLE
            }
            check.setOnCheckedChangeListener { _, checked ->
                if (index in m.actionItems.indices) {
                    m.actionItems[index] = m.actionItems[index].copy(done = checked)
                    applyStrike(text, checked)
                    store.save(m)
                }
            }
            row.setOnLongClickListener {
                confirmRemoveAction(index)
                true
            }
            list.addView(row)
        }
    }

    private fun applyStrike(view: TextView, done: Boolean) {
        view.paintFlags = if (done) {
            view.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        } else {
            view.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }
    }

    private fun confirmRemoveAction(index: Int) {
        val m = meeting ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.remove_action_item)
            .setPositiveButton(R.string.delete) { _, _ ->
                if (index in m.actionItems.indices) {
                    m.actionItems.removeAt(index)
                    store.save(m)
                    renderActionItems(m)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun renderPhotos(m: Meeting) {
        val strip = findViewById<LinearLayout>(R.id.photoStrip)
        val scroll = findViewById<View>(R.id.photoScroll)
        strip.removeAllViews()
        scroll.visibility = if (m.photos.isEmpty()) View.GONE else View.VISIBLE
        if (m.photos.isEmpty()) return
        val density = resources.displayMetrics.density
        val sizePx = (84 * density).toInt()
        val marginPx = (8 * density).toInt()
        val radiusPx = 14 * density
        for (name in m.photos.toList()) {
            val file = PhotoStore.fileFor(this, name)
            val thumb = PhotoStore.decodeSampled(file, 256) ?: continue
            val image = ShapeableImageView(this).apply {
                shapeAppearanceModel = ShapeAppearanceModel.builder()
                    .setAllCornerSizes(radiusPx)
                    .build()
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(thumb)
                layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                    marginEnd = marginPx
                }
                contentDescription = getString(R.string.section_photos)
                setOnClickListener { showPhoto(file) }
                setOnLongClickListener {
                    confirmDeletePhoto(name)
                    true
                }
            }
            strip.addView(image)
        }
    }

    private fun showPhoto(file: File) {
        val bitmap = PhotoStore.decodeSampled(file, 1400) ?: return
        val image = ImageView(this).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
        }
        AlertDialog.Builder(this)
            .setView(image)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun confirmDeletePhoto(name: String) {
        val m = meeting ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_photo_title)
            .setPositiveButton(R.string.delete) { _, _ ->
                m.photos.remove(name)
                PhotoStore.delete(this, name)
                store.save(m)
                renderPhotos(m)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun capturePhoto() {
        val m = meeting ?: return
        val file = PhotoStore.newPhotoFile(this, m.id)
        pendingPhotoFile = file
        try {
            takePicture.launch(PhotoStore.uriFor(this, file))
        } catch (_: Exception) {
            pendingPhotoFile = null
            file.delete()
            Toast.makeText(this, R.string.no_camera_app, Toast.LENGTH_SHORT).show()
        }
    }

    private fun importPhoto(uri: Uri) {
        val m = meeting ?: return
        try {
            val file = PhotoStore.newPhotoFile(this, m.id)
            contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            } ?: throw RuntimeException("cannot open image")
            m.photos.add(file.name)
            store.save(m)
            renderPhotos(m)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.photo_attach_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showExportDialog() {
        val m = meeting ?: return
        val options = arrayOf(getString(R.string.export_markdown), getString(R.string.export_pdf))
        AlertDialog.Builder(this)
            .setTitle(R.string.export)
            .setItems(options) { _, which ->
                saveEdits()
                try {
                    if (which == 0) {
                        exportMd.launch(MeetingExporter.suggestedFileName(m, "md"))
                    } else {
                        exportPdf.launch(MeetingExporter.suggestedFileName(m, "pdf"))
                    }
                } catch (e: Exception) {
                    Toast.makeText(
                        this, getString(R.string.export_failed, e.message ?: "no file picker"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun writeExport(uri: Uri, isPdf: Boolean) {
        val m = meeting ?: return
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                if (isPdf) {
                    MeetingExporter.writePdf(m, out)
                } else {
                    out.write(MeetingExporter.markdown(m).toByteArray(Charsets.UTF_8))
                }
            } ?: throw RuntimeException("could not open destination")
            Toast.makeText(this, R.string.export_done, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(
                this, getString(R.string.export_failed, e.message ?: "unknown error"),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun renderQaHistory(m: Meeting) {
        qaList.removeAllViews()
        for (entry in m.qa) {
            addQaView(entry.question, entry.answer)
        }
    }

    /** Adds a question/answer pair to the thread; returns the answer view for updating. */
    private fun addQaView(question: String, answer: String): TextView {
        val item = LayoutInflater.from(this).inflate(R.layout.item_qa, qaList, false)
        item.findViewById<TextView>(R.id.qaQuestion).text = question
        val answerView = item.findViewById<TextView>(R.id.qaAnswer)
        answerView.text = answer
        qaList.addView(item)
        return answerView
    }

    private fun sendQuestion() {
        val m = meeting ?: return
        val question = askInput.text.toString().trim()
        if (question.isBlank()) return
        saveEdits()

        askInput.setText("")
        askSend.isEnabled = false
        val answerView = addQaView(question, getString(R.string.ask_thinking))

        val baseUrl = settings.llmBaseUrl
        val apiKey = settings.llmApiKey
        val model = settings.llmModel
        val transcript = m.transcriptTextWithSpeakers()
        val notes = m.notes
        val summary = m.summary
        val attendees = m.attendees.toList()
        val history = m.qa.map { it.question to it.answer }

        Thread {
            val answer = try {
                LlmClient.ask(
                    baseUrl, apiKey, model, transcript, notes, summary,
                    attendees, history, question
                )
            } catch (e: Exception) {
                getString(R.string.ask_failed, e.message ?: "unknown error")
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                answerView.text = answer
                askSend.isEnabled = true
                m.qa.add(QaEntry(question, answer))
                store.save(m)
            }
        }.start()
    }

    private fun chooseTemplateAndSummarize() {
        val labels = SummaryTemplates.ALL.map { getString(it.labelRes) }.toTypedArray()
        val current = SummaryTemplates.ALL
            .indexOfFirst { it.key == settings.summaryTemplate }
            .coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_template)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                dialog.dismiss()
                val template = SummaryTemplates.ALL[which]
                settings.summaryTemplate = template.key
                generateSummary(template)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun generateSummary(template: SummaryTemplate) {
        val m = meeting ?: return
        saveEdits()

        if (m.transcriptText().isBlank() && m.notes.isBlank()) {
            Toast.makeText(this, R.string.nothing_to_summarize, Toast.LENGTH_SHORT).show()
            return
        }

        aiPanel.visibility = View.GONE
        progress.visibility = View.VISIBLE
        summaryView.visibility = View.VISIBLE
        summaryView.text = getString(R.string.summarizing)

        val useLlm = settings.useLlm
        val baseUrl = settings.llmBaseUrl
        val apiKey = settings.llmApiKey
        val model = settings.llmModel
        val rawTranscript = m.transcriptText()
        val speakerTranscript = m.transcriptTextWithSpeakers()
        val notes = m.notes
        val attendees = m.attendees.toList()
        val highlights = m.highlightedTexts()
        val segmentsSnapshot = m.segments.toList()

        Thread {
            var parsedItems: List<ActionItem>? = null
            val result = try {
                if (useLlm && baseUrl.isNotBlank()) {
                    val raw = LlmClient.summarize(
                        baseUrl, apiKey, model, speakerTranscript, notes,
                        attendees, highlights, template
                    )
                    val (clean, items) = ActionItems.splitLlmOutput(raw)
                    parsedItems = items
                    clean
                } else {
                    ExtractiveSummarizer.summarize(
                        rawTranscript, notes, highlights, template.extractiveActionsOnly
                    )
                }
            } catch (e: Exception) {
                val fallback = ExtractiveSummarizer.summarize(
                    rawTranscript, notes, highlights, template.extractiveActionsOnly
                )
                getString(R.string.llm_failed_fallback, e.message ?: "unknown error") +
                    "\n\n" + fallback
            }
            val finalItems = parsedItems
                ?: ActionItems.fromMeetingContent(segmentsSnapshot, notes)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                progress.visibility = View.GONE
                summaryView.text = result
                m.summary = result
                m.actionItems = finalItems.toMutableList()
                renderActionItems(m)
                store.save(m)
            }
        }.start()
    }

    private fun shareMeeting() {
        val m = meeting ?: return
        saveEdits()
        val text = buildString {
            append(m.title).append('\n')
            append(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(m.createdAtMs))
            ).append("\n\n")
            if (m.attendees.isNotEmpty()) {
                append("ATTENDEES\n").append(m.attendeesText()).append("\n\n")
            }
            if (m.summary.isNotBlank()) {
                append("SUMMARY\n").append(m.summary).append("\n\n")
            }
            if (m.notes.isNotBlank()) {
                append("NOTES\n").append(m.notes).append("\n\n")
            }
            append("TRANSCRIPT\n").append(m.transcriptTextWithSpeakers())
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, m.title)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_meeting)))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_detail, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_summarize -> {
                chooseTemplateAndSummarize()
                true
            }
            R.id.action_share -> {
                shareMeeting()
                true
            }
            R.id.action_export -> {
                showExportDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    companion object {
        const val EXTRA_MEETING_ID = "meeting_id"
    }
}
