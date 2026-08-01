package com.recap.spike

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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

    private fun modelDir(): File =
        File(getExternalFilesDir(null), "models").apply { mkdirs() }

    private fun models(): List<File> =
        modelDir().listFiles { f -> f.isFile && f.length() > 1_000_000 }
            ?.sortedBy { it.name }
            .orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        backends = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            listOf("CPU", "GPU", "NPU").forEachIndexed { i, name ->
                addView(RadioButton(this@MainActivity).apply {
                    id = 1000 + i
                    text = name
                })
            }
            check(1000)
        }
        root.addView(backends)

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

        setContentView(ScrollView(this).apply {
            addView(root, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            ))
        }.let { root })

        show(intro())
    }

    private fun intro(): String {
        val found = models()
        return buildString {
            appendLine("Put a .litertlm model here:")
            appendLine()
            appendLine(modelDir().absolutePath)
            appendLine()
            appendLine("  adb push gemma-4-E4B-it.litertlm \\")
            appendLine("    ${modelDir().absolutePath}/")
            appendLine()
            if (found.isEmpty()) {
                appendLine("No model found yet.")
            } else {
                appendLine("Found:")
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
        val model = models().firstOrNull()
        if (model == null) {
            show("No model in ${modelDir().absolutePath}\n\n" + intro())
            return
        }
        val backend = when (backends.checkedRadioButtonId) {
            1001 -> "GPU"
            1002 -> "NPU"
            else -> "CPU"
        }
        busy = true
        runButton.isEnabled = false
        show("Running on $backend with ${model.name}…\n")

        Thread {
            val result = Harness.measure(this, model, backend) { stage ->
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
                                appendLine()
                                appendLine("${t.javaClass.simpleName}: ${t.message}")
                                (t.cause)?.let {
                                    appendLine("cause: ${it.javaClass.simpleName}: ${it.message}")
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
}
