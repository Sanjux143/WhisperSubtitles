package com.example.whispersubtitles

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter

class MainActivity : AppCompatActivity(), WhisperCallback {

    private val whisper = WhisperEngine()
    private var selectedMediaUri: Uri? = null
    private var selectedModelName: String? = null
    private var selectedMediaName: String = "transcription"

    private lateinit var modelSpinner: Spinner
    private lateinit var btnReload: Button
    private lateinit var btnPickMedia: Button
    private lateinit var btnTranscribe: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView
    private lateinit var tvConsole: TextView
    private lateinit var consoleScroll: ScrollView
    private lateinit var tvSrtResult: TextView

    private val mediaPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedMediaUri = uri
            selectedMediaName = getFileNameFromUri(uri)
            btnTranscribe.isEnabled = selectedModelName != null
            appendConsole("Selected: $selectedMediaName")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        modelSpinner = findViewById(R.id.modelSpinner)
        btnReload = findViewById(R.id.btnDownloadModel)
        btnPickMedia = findViewById(R.id.btnPickMedia)
        btnTranscribe = findViewById(R.id.btnTranscribe)
        progressBar = findViewById(R.id.progressBar)
        tvProgress = findViewById(R.id.tvProgress)
        tvConsole = findViewById(R.id.tvConsole)
        consoleScroll = findViewById(R.id.consoleScroll)
        tvSrtResult = findViewById(R.id.tvSrtResult)

        btnReload.text = "RELOAD"

        checkStoragePermission()
        loadSdcardModels()

        btnReload.setOnClickListener {
            checkStoragePermission()
            loadSdcardModels()
        }

        btnPickMedia.setOnClickListener {
            mediaPicker.launch("*/*")
        }

        btnTranscribe.setOnClickListener {
            startChunkedPipeline()
        }
    }

    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
    }

    private fun loadSdcardModels() {
        val dir = File("/sdcard/whisper")
        if (!dir.exists()) dir.mkdirs()

        val models = whisper.listSdcardModels()

        if (models.isEmpty()) {
            tvProgress.text = "No models found in /sdcard/whisper/"
            appendConsole("Put ggml-*.bin in /sdcard/whisper/ & press RELOAD")
            modelSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Empty: /sdcard/whisper"))
            selectedModelName = null
            btnTranscribe.isEnabled = false
            return
        }

        modelSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, models)
        modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedModelName = models[position]
                tvProgress.text = "Selected: $selectedModelName"
                btnTranscribe.isEnabled = selectedMediaUri != null
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        selectedModelName = models[0]
        tvProgress.text = "Found ${models.size} model(s)"
        btnTranscribe.isEnabled = selectedMediaUri != null
    }

    private fun getFileNameFromUri(uri: Uri): String {
        var name = "audio_subtitles"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                name = cursor.getString(nameIndex)
            }
        }
        return name.substringBeforeLast(".")
    }

    private fun startChunkedPipeline() {
        val uri = selectedMediaUri ?: return
        val modelName = selectedModelName ?: return

        btnTranscribe.isEnabled = false
        progressBar.progress = 0
        tvSrtResult.text = ""
        tvProgress.text = "Starting Stream Pipeline..."

        val srtFile = File("/sdcard/whisper", "$selectedMediaName.srt")
        if (srtFile.exists()) srtFile.delete()

        lifecycleScope.launch(Dispatchers.Default) {
            try {
                withContext(Dispatchers.Main) {
                    tvProgress.text = "Loading Model into RAM..."
                }

                val loaded = whisper.loadModelFromSdcard(modelName, this@MainActivity)
                if (!loaded) {
                    withContext(Dispatchers.Main) {
                        tvProgress.text = "Failed to load model /sdcard/whisper/$modelName"
                        btnTranscribe.isEnabled = true
                    }
                    return@launch
                }

                val threads = 4
                var subtitleIndex = 1

                AudioDecoder.decodeInChunks(applicationContext, uri) { chunkSamples, chunkIndex, totalEstimated ->
                    val chunkOffsetMs = chunkIndex * 30_000L

                    runOnUiThread {
                        val percent = if (totalEstimated > 0) ((chunkIndex + 1) * 100 / totalEstimated).coerceAtMost(100) else 0
                        progressBar.progress = percent
                        tvProgress.text = "Chunk ${chunkIndex + 1}/$totalEstimated ($percent%)"
                    }

                    val srtPart = whisper.transcribeToSrt(chunkSamples, threads)

                    if (srtPart.isNotBlank()) {
                        val adjustedSrt = adjustSrtTimeOffsets(srtPart, chunkOffsetMs, subtitleIndex)
                        subtitleIndex += countSrtSegments(srtPart)

                        FileWriter(srtFile, true).use { it.write(adjustedSrt) }

                        runOnUiThread {
                            tvSrtResult.append(adjustedSrt)
                            consoleScroll.post { consoleScroll.fullScroll(View.FOCUS_DOWN) }
                        }
                    }

                    // Anti-thermal cooldown delay
                    Thread.sleep(150)
                    true
                }

                whisper.freeModel()

                withContext(Dispatchers.Main) {
                    tvProgress.text = "Completed & Saved!"
                    progressBar.progress = 100
                    appendConsole("Full SRT saved at: ${srtFile.absolutePath}")
                    btnTranscribe.isEnabled = true
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvProgress.text = "Error: ${e.message}"
                    btnTranscribe.isEnabled = true
                }
            }
        }
    }

    private fun adjustSrtTimeOffsets(srtText: String, offsetMs: Long, startCounter: Int): String {
        val lines = srtText.trim().split("\n")
        val out = StringBuilder()
        var currentCounter = startCounter

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.matches(Regex("^\\d+$"))) {
                out.append(currentCounter++).append("\n")
                if (i + 1 < lines.size && lines[i + 1].contains("-->")) {
                    i++
                    val timeLine = lines[i]
                    val parts = timeLine.split("-->")
                    if (parts.size == 2) {
                        val start = parseTime(parts[0].trim()) + offsetMs
                        val end = parseTime(parts[1].trim()) + offsetMs
                        out.append(formatTime(start)).append(" --> ").append(formatTime(end)).append("\n")
                    } else {
                        out.append(timeLine).append("\n")
                    }
                }
            } else {
                out.append(lines[i]).append("\n")
            }
            i++
        }
        return out.toString()
    }

    private fun countSrtSegments(srtText: String): Int {
        return Regex("^\\d+$", RegexOption.MULTILINE).findAll(srtText).count()
    }

    private fun parseTime(time: String): Long {
        return try {
            val parts = time.split(":", ",")
            val h = parts[0].toLong()
            val m = parts[1].toLong()
            val s = parts[2].toLong()
            val ms = parts[3].toLong()
            (h * 3600 + m * 60 + s) * 1000 + ms
        } catch (e: Exception) { 0L }
    }

    private fun formatTime(timeMs: Long): String {
        var ms = timeMs
        val hr = ms / (3600 * 1000)
        ms %= (3600 * 1000)
        val min = ms / (60 * 1000)
        ms %= (60 * 1000)
        val sec = ms / 1000
        ms %= 1000
        return String.format("%02d:%02d:%02d,%03d", hr, min, sec, ms)
    }

    override fun onNativeLog(line: String) {
        runOnUiThread { appendConsole(line.trim()) }
    }

    override fun onProgress(progress: Int) {}

    private fun appendConsole(text: String) {
        if (text.isEmpty()) return
        tvConsole.append("$text\n")
        consoleScroll.post { consoleScroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        whisper.freeModel()
    }
}
