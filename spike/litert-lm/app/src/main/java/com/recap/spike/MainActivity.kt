package com.recap.spike

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * Throwaway measurement tool. UI built in code so the whole spike is four
 * source files and no resource wrangling.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var output: TextView
    private lateinit var backends: RadioGroup
    private lateinit var runButton: Button
    private var busy = false

    /**
     * The picked model, held open for as long as it might be read.
     *
     * The path is `/proc/self/fd/N`, which is a real path the native loader
     * can open — so a 3.5 GB file picked out of Downloads is used where it
     * lies, with no copy. The descriptor must stay open the whole time: close
     * it and the path stops resolving mid-run.
     */
    private class Picked(val label: String, val pfd: ParcelFileDescriptor) {
        val path: String get() = "/proc/self/fd/${pfd.fd}"
    }

    private var picked: Picked? = null

    private val pickModel = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onPicked(uri) }

    private fun modelDir(): File =
        File(getExternalFilesDir(null), "models").apply { mkdirs() }

    private fun models(): List<File> =
        modelDir().listFiles { f -> f.isFile && f.length() > 1_000_000 }
            ?.sortedBy { it.name }
            .orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A tool whose entire job is reporting should not fail by vanishing.
        // If startup throws, put the trace on screen where it can be read and
        // copied, rather than leaving a launcher icon that does nothing.
        try {
            buildUi()
        } catch (t: Throwable) {
            setContentView(TextView(this).apply {
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(24, 48, 24, 24)
                text = "Startup failed\n\n" + java.io.StringWriter()
                    .also { t.printStackTrace(java.io.PrintWriter(it)) }
                    .toString()
            })
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }

        root.addView(TextView(this).apply {
            text = "LiteRT-LM spike"
            textSize = 22f
        })
        root.addView(TextView(this).apply {
            text = "Measures µAh per summary and speed, and reports which backend " +
                "the runtime actually selected.\n\nUNPLUG THE PHONE before running — " +
                "the battery reading is meaningless while charging."
            textSize = 13f
            setPadding(0, 16, 0, 24)
        })

        // GOOGLE_TENSOR is the one that matters on a Pixel — it is a distinct
        // backend from the generic NPU, which takes a nativeLibraryDir and so
        // expects vendor libraries this AAR does not ship.
        backends = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            listOf("CPU", "GPU", "GOOGLE_TENSOR", "NPU").forEachIndexed { i, name ->
                addView(RadioButton(this@MainActivity).apply {
                    id = 1000 + i
                    text = name
                })
            }
            check(1000)
        }
        root.addView(backends)

        // Pick the file wherever it already is — Downloads, most likely.
        // The alternative was adb-pushing gigabytes into an app-private
        // directory before anything could be measured.
        root.addView(Button(this).apply {
            text = "Pick model file (Downloads…)"
            setOnClickListener { pickModel.launch(arrayOf("*/*")) }
        })

        runButton = Button(this).apply {
            text = "Run measurement"
            setOnClickListener { start() }
        }
        root.addView(runButton)

        root.addView(Button(this).apply {
            text = "What loaded? (classpath dump)"
            setOnClickListener { show(LlmRuntime.describeClasspath()) }
        })

        root.addView(Button(this).apply {
            text = "Copy report"
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("spike", output.text))
                Toast.makeText(this@MainActivity, "Copied", Toast.LENGTH_SHORT).show()
            }
        })

        output = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, 24, 0, 0)
        }
        val scroll = ScrollView(this).apply {
            addView(output)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(scroll)

        // root already holds a weighted ScrollView for the output. Wrapping it
        // in a second one both crashed (root would already have a parent) and
        // would have collapsed that weight to nothing.
        setContentView(root)

        show(intro())
    }

    private fun onPicked(uri: Uri) {
        runCatching {
            picked?.pfd?.close()
            picked = null
            val name = displayName(uri)
            val pfd = contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("could not open that file")
            picked = Picked(name, pfd)
        }.fold(
            onSuccess = {
                val p = picked
                show(
                    buildString {
                        appendLine("Picked: ${p?.label}")
                        appendLine("Reading it in place via ${p?.path}")
                        appendLine("(no copy — the descriptor stays open for the run)")
                        appendLine()
                        appendLine("Pick a backend above, then Run measurement.")
                    }
                )
            },
            onFailure = { t ->
                show("Could not open that file: ${t.javaClass.simpleName}: ${t.message}")
            }
        )
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i)?.let { return it }
        }
        return uri.lastPathSegment ?: "model"
    }

    private fun intro(): String {
        val found = models()
        return buildString {
            appendLine("Two ways to give it a model:")
            appendLine()
            appendLine("1. Tap \"Pick model file\" and choose the .litertlm")
            appendLine("   wherever you downloaded it. Nothing is copied.")
            appendLine()
            appendLine("2. Or put one here and reopen the app:")
            appendLine("   ${modelDir().absolutePath}")
            appendLine()
            if (found.isEmpty()) {
                appendLine("No model in the folder yet.")
            } else {
                appendLine("Found in the folder:")
                found.forEach {
                    appendLine("  ${it.name}  (${it.length() / (1024 * 1024)} MB)")
                }
            }
            appendLine()
            appendLine("Prompt size: ${Harness.promptChars} chars (one map-reduce section).")
        }
    }

    private fun show(text: String) {
        output.text = text
    }

    private fun start() {
        if (busy) return

        val chosen = picked
        val path: String
        val label: String
        if (chosen != null) {
            path = chosen.path
            label = chosen.label
        } else {
            val fromFolder = models().firstOrNull()
            if (fromFolder == null) {
                show("No model chosen yet.\n\n" + intro())
                return
            }
            path = fromFolder.absolutePath
            label = fromFolder.name
        }

        val backend = when (backends.checkedRadioButtonId) {
            1001 -> "GPU"
            1002 -> "GOOGLE_TENSOR"
            1003 -> "NPU"
            else -> "CPU"
        }
        busy = true
        runButton.isEnabled = false
        show("Running on $backend with $label…\n")

        Thread {
            val result = Harness.measure(this, File(path), backend) { stage ->
                runOnUiThread { output.append("$stage\n") }
            }
            runOnUiThread {
                busy = false
                runButton.isEnabled = true
                result.fold(
                    onSuccess = { show(it.report()) },
                    onFailure = { t ->
                        show(
                            buildString {
                                appendLine("FAILED on $backend")
                                appendLine("model: $label")
                                appendLine("path:  $path")
                                appendLine()
                                appendLine("${t.javaClass.simpleName}: ${t.message}")
                                (t.cause)?.let {
                                    appendLine("cause: ${it.javaClass.simpleName}: ${it.message}")
                                }
                                if (chosen != null) {
                                    appendLine()
                                    appendLine(
                                        "This ran straight off the picked file. If the " +
                                            "runtime cannot read a /proc/self/fd path, " +
                                            "copy the model into the folder above and " +
                                            "run again — that rules the path out."
                                    )
                                }
                                appendLine()
                                // The useful half of a failure: what the
                                // runtime does expose, so the adapter can be
                                // written against it rather than guessed at.
                                append(LlmRuntime.describeClasspath())
                            }
                        )
                    }
                )
            }
        }.apply { name = "spike-measure" }.start()
    }

    override fun onDestroy() {
        runCatching { picked?.pfd?.close() }
        super.onDestroy()
    }
}
