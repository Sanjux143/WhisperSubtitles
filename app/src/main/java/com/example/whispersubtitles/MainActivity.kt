package com.example.whispersubtitles

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL

class MainActivity : AppCompatActivity(), WhisperCallback {

    private val whisper = WhisperEngine()
    private var selectedMediaUri: Uri? = null
    private var activeModelFile: File? = null

    private lateinit var modelSpinner: Spinner
    private lateinit var btnDownload: Button
    private lateinit var btnPickMedia: Button
    private lateinit var btnTranscribe: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView
    private lateinit var tvConsole: TextView
    private lateinit var consoleScroll: ScrollView
    private lateinit var tvSrtResult: TextView

    private val models = mapOf(
        "Tiny (75MB)" to "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin",
        "Base (142MB)" to "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin",
        "Small (466MB)" to "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin"
    )

    private val mediaPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedMediaUri = uri
            btnTranscribe.isEnabled = activeModelFile?.exists() == true
            appendConsole("Media selected: $uri")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        modelSpinner = findViewById(R.id.modelSpinner)
        btnDownload = findViewById(R.id.btnDownloadModel)
        btnPickMedia = findViewById(R.id.btnPickMedia)
        btnTranscribe = findViewById(R.id.btnTranscribe)
        progressBar = findViewById(R.id.progressBar)
        tvProgress = findViewById(R.id.tvProgress)
        tvConsole = findViewById(R.id.tvConsole)
        consoleScroll = findViewById(R.id.consoleScroll)
        tvSrtResult = findViewById(R.id.tvSrtResult)

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, models.keys.toList())
        modelSpinner.adapter = adapter

        btnPickMedia.setOnClickListener { mediaPicker.launch("*/*") }
        btnDownload.setOnClickListener { downloadSelectedModel() }
        btnTranscribe.setOnClickListener { startTranscriptionPipeline() }
    }

    private fun downloadSelectedModel() {
        val selectedKey = modelSpinner.selectedItem.toString()
        val url = models[selectedKey] ?: return
        val filename = url.substringAfterLast("/")
        val targetFile = File(filesDir, filename)

        btnDownload.isEnabled = false
        tvProgress.text = "Downloading $filename..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                URL(url).openStream().use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
                activeModelFile = targetFile
                withContext(Dispatchers.Main) {
                    tvProgress.text = "Downloaded: $filename"
                    btnDownload.isEnabled = true
                    btnTranscribe.isEnabled = selectedMediaUri != null
                    appendConsole("Model ready: ${targetFile.absolutePath}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvProgress.text = "Download Failed: ${e.message}"
                    btnDownload.isEnabled = true
                }
            }
        }
    }

    private fun startTranscriptionPipeline() {
        val uri = selectedMediaUri ?: return
        val model = activeModelFile ?: return

        btnTranscribe.isEnabled = false
        progressBar.progress = 0
        tvProgress.text = "Extracting & decoding audio (16kHz PCM)..."

        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val samples = AudioDecoder.decodeTo16kHzFloatPcm(applicationContext, uri)
                withContext(Dispatchers.Main) {
                    tvProgress.text = "Initializing whisper context..."
                }

                val ok = whisper.initModel(model.absolutePath, this@MainActivity)
                if (!ok) {
                    withContext(Dispatchers.Main) {
                        tvProgress.text = "Failed to load GGML model!"
                        btnTranscribe.isEnabled = true
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    tvProgress.text = "Transcribing segments..."
                }

                val threads = Runtime.getRuntime().availableProcessors().coerceAtMost(4)
                val srt = whisper.transcribeToSrt(samples, threads)

                whisper.freeModel()

                withContext(Dispatchers.Main) {
                    tvProgress.text = "Completed!"
                    tvSrtResult.text = srt
                    btnTranscribe.isEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvProgress.text = "Pipeline Error: ${e.message}"
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
