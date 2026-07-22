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
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
import com.meetily.mobile.summarize.CustomTemplates
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

    // The document layout above the transcript lives in one pre-inflated
    // header view inside a ConcatAdapter; transcript lines are recycled so
    // multi-hour meetings scroll smoothly.
    private lateinit var headerView: View
    private lateinit var transcriptAdapter: TranscriptLinesAdapter

    private lateinit var titleView: TextView
    private lateinit var dateView: TextView
    private lateinit var metaView: TextView
    private lateinit var summaryView: TextView
    private lateinit var aiPanel: View
    private lateinit var tagHint: TextView
    private lateinit var notesInput: EditText
    private lateinit var attendeesInput: EditText
    private lateinit var progress: ProgressBar
    private lateinit var qaList: LinearLayout
    private lateinit var askInput: EditText
    private lateinit var askSend: MaterialButton
    private lateinit var askRow: View
    private lateinit var askDisabledHint: View

    private var suggestDialog: AlertDialog? = null
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

        val recycler = findViewById<RecyclerView>(R.id.detailRecycler)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.itemAnimator = null
        headerView = LayoutInflater.from(this)
            .inflate(R.layout.detail_header, recycler, false)
        transcriptAdapter = TranscriptLinesAdapter(
            onClick = { index -> assignSpeaker(index) },
            onLongClick = { index -> toggleHighlight(index) }
        )
        recycler.adapter = ConcatAdapter(StaticViewAdapter(headerView), transcriptAdapter)

        titleView = headerView.findViewById(R.id.detailTitle)
        dateView = headerView.findViewById(R.id.detailDate)
        metaView = headerView.findViewById(R.id.detailMeta)
        summaryView = headerView.findViewById(R.id.detailSummary)
        aiPanel = headerView.findViewById(R.id.aiPanel)
        tagHint = headerView.findViewById(R.id.tagHint)
        notesInput = headerView.findViewById(R.id.detailNotes)
        attendeesInput = headerView.findViewById(R.id.detailAttendees)
        progress = headerView.findViewById(R.id.summaryProgress)
        qaList = headerView.findViewById(R.id.qaList)
        askInput = headerView.findViewById(R.id.askInput)
        askSend = headerView.findViewById(R.id.askSend)
        askRow = headerView.findViewById(R.id.askRow)
        askDisabledHint = headerView.findViewById(R.id.askDisabledHint)

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

        headerView.findViewById<View>(R.id.generateButton).setOnClickListener {
            chooseTemplateAndSummarize()
        }
        askSend.setOnClickListener { sendQuestion() }
        headerView.findViewById<View>(R.id.addPhotoCamera).setOnClickListener { capturePhoto() }
        headerView.findViewById<View>(R.id.addPhotoGallery).setOnClickListener {
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
        tagHint.text = getString(
            if (m.segments.isEmpty()) R.string.no_transcript else R.string.tap_to_tag_hint
        )
        transcriptAdapter.submit(m.segments)
    }

    private fun toggleHighlight(index: Int) {
        val m = meeting ?: return
        if (index !in m.segments.indices) return
        m.segments[index] = m.segments[index].copy(
            highlighted = !m.segments[index].highlighted
        )
        store.save(m)
        transcriptAdapter.update(index, m.segments[index])
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
            transcriptAdapter.update(index, m.segments[index])
        }
    }

    override fun onPause() {
        super.onPause()
        saveEdits()
    }

    override fun onDestroy() {
        // Avoid a WindowLeaked crash if a config change lands mid-analysis.
        suggestDialog?.dismiss()
        suggestDialog = null
        super.onDestroy()
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
        val header = headerView.findViewById<View>(R.id.actionsHeader)
        val list = headerView.findViewById<LinearLayout>(R.id.actionList)
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
        val strip = headerView.findViewById<LinearLayout>(R.id.photoStrip)
        val scroll = headerView.findViewById<View>(R.id.photoScroll)
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

    /**
     * LLM-based speaker attribution from conversational context. Applies only
     * to untagged lines, never overwriting manual tags, and always behind an
     * explicit confirmation with a preview.
     */
    private fun suggestSpeakers() {
        val m = meeting ?: return
        if (!settings.useLlm || settings.llmBaseUrl.isBlank()) {
            Toast.makeText(this, R.string.suggest_requires_llm, Toast.LENGTH_LONG).show()
            return
        }
        if (m.segments.isEmpty()) {
            Toast.makeText(this, R.string.no_transcript, Toast.LENGTH_SHORT).show()
            return
        }
        saveEdits()

        // Cancelable: a hung endpoint (up to ~200s of timeouts) must not trap
        // the screen. Cancel abandons the in-flight result.
        val progressDialog = AlertDialog.Builder(this)
            .setMessage(R.string.suggest_analyzing)
            .setCancelable(true)
            .create()
        suggestDialog = progressDialog
        progressDialog.show()

        val baseUrl = settings.llmBaseUrl
        val apiKey = settings.llmApiKey
        val model = settings.llmModel
        val lines = m.segments.map { it.text to it.speaker }
        val attendees = m.attendees.toList()

        Thread {
            var error: String? = null
            val suggestions = try {
                LlmClient.suggestSpeakers(baseUrl, apiKey, model, lines, attendees)
            } catch (e: Exception) {
                error = e.message ?: "unknown error"
                emptyList()
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val canceled = !progressDialog.isShowing
                progressDialog.dismiss()
                suggestDialog = null
                if (canceled) return@runOnUiThread
                if (error != null) {
                    Toast.makeText(
                        this, getString(R.string.ask_failed, error), Toast.LENGTH_LONG
                    ).show()
                    return@runOnUiThread
                }
                val applicable = suggestions
                    .distinctBy { it.first }
                    .filter { (index, _) ->
                        index in m.segments.indices &&
                            m.segments[index].speaker.isNullOrBlank()
                    }
                if (applicable.isEmpty()) {
                    Toast.makeText(this, R.string.suggest_none, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                confirmSpeakerSuggestions(m, applicable)
            }
        }.start()
    }

    private fun confirmSpeakerSuggestions(m: Meeting, applicable: List<Pair<Int, String>>) {
        val preview = buildString {
            for ((index, name) in applicable.take(5)) {
                val text = m.segments[index].text
                append("“")
                append(text.take(40))
                if (text.length > 40) append("…")
                append("” → ").append(name).append('\n')
            }
            if (applicable.size > 5) {
                append("…")
            }
        }.trimEnd()
        AlertDialog.Builder(this)
            .setTitle(R.string.suggest_apply_title)
            .setMessage(
                resources.getQuantityString(
                    R.plurals.suggest_apply_message,
                    applicable.size, applicable.size, preview
                )
            )
            .setPositiveButton(R.string.suggest_apply) { _, _ ->
                for ((index, suggested) in applicable) {
                    if (index !in m.segments.indices) continue
                    // Canonicalize to the existing attendee's casing so the
                    // transcript never mixes "Bob" and "bob".
                    val name = m.attendees
                        .firstOrNull { it.equals(suggested, ignoreCase = true) }
                        ?: suggested
                    m.segments[index] = m.segments[index].copy(speaker = name)
                    if (m.attendees.none { it.equals(name, ignoreCase = true) }) {
                        m.attendees.add(name)
                    }
                }
                attendeesInput.setText(m.attendeesText())
                store.save(m)
                renderTranscript(m)
                Toast.makeText(
                    this,
                    resources.getQuantityString(
                        R.plurals.suggest_applied, applicable.size, applicable.size
                    ),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseTemplateAndSummarize() {
        val templates = SummaryTemplates.allWithCustom(this)
        val labels = templates.map { it.label(this) }.toTypedArray()
        val current = templates
            .indexOfFirst { it.key == settings.summaryTemplate }
            .coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_template)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                dialog.dismiss()
                val template = templates[which]
                settings.summaryTemplate = template.key
                generateSummary(template)
            }
            .setNeutralButton(R.string.template_custom_button) { _, _ ->
                showCustomTemplateMenu()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCustomTemplateMenu() {
        val customs = CustomTemplates.load(this)
        val items = mutableListOf(getString(R.string.template_new))
        items.addAll(customs.map { getString(R.string.template_delete_fmt, it.name) })
        AlertDialog.Builder(this)
            .setTitle(R.string.template_custom_title)
            .setItems(items.toTypedArray()) { _, which ->
                if (which == 0) {
                    showNewTemplateDialog()
                } else {
                    val doomed = customs[which - 1]
                    CustomTemplates.delete(this, doomed.key)
                    if (settings.summaryTemplate == doomed.key) {
                        settings.summaryTemplate = "general"
                    }
                    Toast.makeText(this, R.string.template_deleted, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showNewTemplateDialog() {
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val nameInput = EditText(this).apply {
            hint = getString(R.string.template_name_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val instructionsInput = EditText(this).apply {
            hint = getString(R.string.template_instructions_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
            gravity = android.view.Gravity.TOP
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(nameInput)
            addView(instructionsInput)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.template_new)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = nameInput.text.toString().trim()
                val instructions = instructionsInput.text.toString().trim()
                if (name.isBlank() || instructions.isBlank()) {
                    Toast.makeText(this, R.string.template_fields_required, Toast.LENGTH_SHORT)
                        .show()
                    return@setPositiveButton
                }
                val custom = CustomTemplates.add(this, name, instructions)
                settings.summaryTemplate = custom.key
                Toast.makeText(this, R.string.template_saved, Toast.LENGTH_SHORT).show()
                generateSummary(SummaryTemplates.byKey(this, custom.key))
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
            R.id.action_suggest_speakers -> {
                suggestSpeakers()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    companion object {
        const val EXTRA_MEETING_ID = "meeting_id"
    }
}
