package com.gits.compositioncompass

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.gits.compositioncompass.StuffJavaIsTooConvolutedFor.SafStorage

//Minimal settings editor for config.ini. Reads/writes it directly through SafStorage,
//independent of CompositionCompassOptions - it doesn't know or care what fields that
//class expects, it just shows whatever key=value lines are actually in the file. Built
//entirely programmatically (no XML layout) so it has no dependency on res/ resources
//elsewhere in the project.
//
//Launch with (both extras optional - default to config.ini at the SAF tree root, i.e.
//exactly SafStorage.getOrCreateFile("", "config.ini")):
//
//  startActivity(Intent(this, SettingsActivity::class.java)
//      .putExtra(SettingsActivity.EXTRA_RELATIVE_PATH, "")
//      .putExtra(SettingsActivity.EXTRA_FILE_NAME, "config.ini"))
class SettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RELATIVE_PATH = "relativePath"
        const val EXTRA_FILE_NAME = "fileName"
        private const val EXCEPTIONS_KEY = "exceptions"
    }

    private lateinit var storage: SafStorage
    private var relativePath: String = ""
    private var fileName: String = "config.ini"

    //full parsed ini, insertion order preserved - single source of truth for both screens
    private val values = LinkedHashMap<String, String>()
    private var exceptions = mutableListOf<String>()

    private lateinit var root: FrameLayout
    private val fieldInputs = mutableMapOf<String, EditText>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        relativePath = intent.getStringExtra(EXTRA_RELATIVE_PATH) ?: ""
        fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "config.ini"
        storage = SafStorage(this)

        root = FrameLayout(this)
        setContentView(root)

        if (!storage.isReady) {
            showError("Storage access not granted yet - open Composition Compass first so it can ask for a folder, then come back here.")
            return
        }

        loadValues()
        showMainScreen()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    //standalone, full-width action button with reliably centered text - width alone
    //(MATCH_PARENT) isn't enough, since the platform's default button background can
    //still pad asymmetrically around wrap-content text; forcing gravity fixes both
    private fun actionButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        setOnClickListener { onClick() }
    }

    private fun showError(message: String) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        layout.addView(TextView(this).apply { text = message })
        layout.addView(actionButton("Close") { finish() })
        root.removeAllViews()
        root.addView(layout)
    }

    //parsing mirrors CompositionCompassOptions.load() exactly (split on first "=", keep
    //any further "=" characters as part of the value) so round-tripping through this
    //screen can't corrupt values like URLs or base64 secrets that contain "="
    private fun loadValues() {
        values.clear()

        storage.readLines(relativePath, fileName).forEach { line ->
            if (line.contains("=")) {
                val splitted = line.split("=")
                val key = splitted.first()
                val value = splitted.drop(1).joinToString("=")
                values[key] = value
            }
        }

        exceptions = (values[EXCEPTIONS_KEY] ?: "")
            .split("|")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()
    }

    //writes the whole map back in the same format CompositionCompassOptions.save() uses
    private fun persist() {
        values[EXCEPTIONS_KEY] = exceptions.joinToString("|")

        val content = values.entries.joinToString("") { "${it.key}=${it.value}${System.lineSeparator()}" }
        storage.writeText(relativePath, fileName, content)
    }

    // ─── Main screen: generic key/value editor ─────────────────────────────

    private fun showMainScreen() {
        fieldInputs.clear()

        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        scroll.addView(layout)

        layout.addView(TextView(this).apply {
            text = "Composition Compass Settings"
            textSize = 20f
            setPadding(0, 0, 0, dp(16))
        })

        values.keys.filter { it != EXCEPTIONS_KEY }.forEach { key ->
            layout.addView(TextView(this).apply {
                text = key
                setPadding(0, dp(12), 0, dp(4))
            })

            val input = EditText(this).apply {
                setText(values[key] ?: "")
            }
            fieldInputs[key] = input
            layout.addView(input)
        }

        layout.addView(actionButton("Exceptions (${exceptions.size})") {
            fieldInputs.forEach { (key, input) -> values[key] = input.text.toString() }
            showExceptionsScreen()
        }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(24) })

        layout.addView(actionButton("Save") {
            fieldInputs.forEach { (key, input) -> values[key] = input.text.toString() }
            persist()
            Toast.makeText(this@SettingsActivity, "Saved", Toast.LENGTH_SHORT).show()
        }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(24) })

        root.removeAllViews()
        root.addView(scroll)
    }

    // ─── Sub-page: exceptions regex entries ────────────────────────────────
    // Each entry is a raw fragment; the ini's "exceptions" value is always the current
    // entries rebuilt as entry1|entry2|entry3... (matches how YoutubeDownloader already
    // consumes it: --match-title "^((?!(${options.exceptions})).)*$").

    private fun showExceptionsScreen() {
        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        scroll.addView(layout)

        layout.addView(TextView(this).apply {
            text = "Exceptions"
            textSize = 20f
        })

        layout.addView(TextView(this).apply {
            text = "Titles matching any entry below are skipped during download. The " +
                    "final regex is these entries joined with \"|\"."
            setPadding(0, dp(4), 0, dp(16))
        })

        val entriesLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(entriesLayout)

        fun renderEntries() {
            entriesLayout.removeAllViews()

            exceptions.forEachIndexed { index, entry ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(4), 0, dp(4))
                }

                val input = EditText(this).apply {
                    setText(entry)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addTextChangedListener(object : TextWatcher {
                        override fun afterTextChanged(s: Editable?) {
                            if (index < exceptions.size) exceptions[index] = s.toString()
                        }
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    })
                }
                row.addView(input)

                row.addView(Button(this).apply {
                    text = "Remove"
                    gravity = Gravity.CENTER
                    setOnClickListener {
                        exceptions.removeAt(index)
                        renderEntries()
                    }
                })

                entriesLayout.addView(row)
            }
        }
        renderEntries()

        layout.addView(actionButton("Add entry") {
            exceptions.add("")
            renderEntries()
        }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(16) })

        layout.addView(actionButton("Save & back") {
            exceptions = exceptions.map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            values[EXCEPTIONS_KEY] = exceptions.joinToString("|")
            persist()
            showMainScreen()
        }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(24) })

        layout.addView(actionButton("Back without saving") {
            loadValues() //discard in-memory edits since the last persist()
            showMainScreen()
        })

        root.removeAllViews()
        root.addView(scroll)
    }
}