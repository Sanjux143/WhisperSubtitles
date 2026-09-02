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
            appendConsole("Media selected: $selectedMediaName")
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
            startTranscription()
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
            appendConsole("Put ggml-*.bin inside /sdcard/whisper/ and tap RELOAD")
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
        tvProgress.text = "Found ${models.size} model(s) in /sdcard/whisper"
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

    private fun startTranscription() {
        val uri = selectedMediaUri ?: return
        val modelName = selectedModelName ?: return

        btnTranscribe.isEnabled = false
        progressBar.progress = 0
        tvProgress.text = "Extracting Audio (16kHz PCM)..."

        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val samples = AudioDecoder.decodeTo16kHzFloatPcm(applicationContext, uri)

                withContext(Dispatchers.Main) {
                    tvProgress.text = "Loading $modelName via Native C++..."
                }

                val loaded = whisper.loadModelFromSdcard(modelName, this@MainActivity)
                if (!loaded) {
                    withContext(Dispatchers.Main) {
                        tvProgress.text = "Failed to load model from /sdcard/whisper/$modelName"
                        btnTranscribe.isEnabled = true
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    tvProgress.text = "Transcribing with whisper.cpp..."
                }

                val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
                val srtContent = whisper.transcribeToSrt(samples, threads)

                whisper.freeModel()

                // Save SRT inside /sdcard/whisper/
                val srtFile = File("/sdcard/whisper", "$selectedMediaName.srt")
                FileWriter(srtFile).use { writer ->
                    writer.write(srtContent)
                }

                withContext(Dispatchers.Main) {
                    tvProgress.text = "Completed & Saved!"
                    tvSrtResult.text = srtContent
                    appendConsole("SRT File Saved: ${srtFile.absolutePath}")
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

    override fun onNativeLog(line: String) {
        runOnUiThread { appendConsole(line.trim()) }
    }

    override fun onProgress(progress: Int) {
        runOnUiThread {
            progressBar.progress = progress
            tvProgress.text = "Transcribing: $progress%"
        }
    }

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
