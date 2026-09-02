package com.example.whispersubtitles

interface WhisperCallback {
    fun onNativeLog(line: String)
    fun onProgress(progress: Int)
}

class WhisperEngine {
    companion object {
        init {
            System.loadLibrary("whispersubtitles")
        }
    }

    external fun initModel(modelPath: String, callback: WhisperCallback): Boolean
    external fun transcribeToSrt(audioData: FloatArray, numThreads: Int): String
    external fun freeModel()
}
