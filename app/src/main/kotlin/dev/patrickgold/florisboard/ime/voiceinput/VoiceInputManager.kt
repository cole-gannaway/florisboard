/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.voiceinput

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.lib.devtools.flogError
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody

enum class VoiceInputState {
    IDLE, RECORDING, TRANSCRIBING,
}

/**
 * Records microphone audio to a local WAV file and uploads it to a
 * user-configured OpenAI-compatible transcription endpoint via a single
 * (non-streaming) multipart POST request.
 */
class VoiceInputManager(private val appContext: Context) {
    private val prefs by FlorisPreferenceStore
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(VoiceInputState.IDLE)
    val state: StateFlow<VoiceInputState> = _state

    private var recordingThread: Thread? = null
    @Volatile
    private var isRecording = false
    private var audioFile: File? = null

    fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun startRecording() {
        if (_state.value != VoiceInputState.IDLE || !hasRecordAudioPermission()) return

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize <= 0) return
        val bufferSize = minBufferSize * 2

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize,
        )
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            return
        }

        val file = File(appContext.cacheDir, "voice_input_${System.currentTimeMillis()}.wav")
        audioFile = file
        val writer = WavFileWriter(file, SAMPLE_RATE, CHANNEL_COUNT)

        isRecording = true
        _state.value = VoiceInputState.RECORDING
        audioRecord.startRecording()

        recordingThread = Thread {
            val buffer = ByteArray(bufferSize)
            while (isRecording) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0) {
                    writer.write(buffer, read)
                }
            }
            audioRecord.stop()
            audioRecord.release()
            writer.close()
        }.apply { start() }
    }

    /**
     * Stops the active recording and uploads it for transcription. [onResult] is always
     * invoked on the main thread so callers can safely commit text to the editor from it.
     */
    fun stopRecordingAndTranscribe(onResult: (Result<String>) -> Unit) {
        if (_state.value != VoiceInputState.RECORDING) return
        isRecording = false
        _state.value = VoiceInputState.TRANSCRIBING
        val file = audioFile
        audioFile = null
        val threadToJoin = recordingThread
        recordingThread = null

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                threadToJoin?.join()
                if (file != null) transcribe(file) else Result.failure(IllegalStateException("No recording found"))
            }
            _state.value = VoiceInputState.IDLE
            withContext(Dispatchers.Main) {
                onResult(result)
            }
        }
    }

    fun cancelRecording() {
        if (_state.value != VoiceInputState.RECORDING) return
        isRecording = false
        recordingThread?.join()
        recordingThread = null
        audioFile?.delete()
        audioFile = null
        _state.value = VoiceInputState.IDLE
    }

    private fun transcribe(file: File): Result<String> {
        return try {
            val endpointUrl = prefs.voiceInput.endpointUrl.get()
            if (endpointUrl.isBlank()) {
                return Result.failure(IllegalStateException("No transcription endpoint configured"))
            }
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", file.name, file.asRequestBody("audio/wav".toMediaType()))
                .build()
            val request = Request.Builder()
                .url(endpointUrl)
                .post(requestBody)
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(IllegalStateException("Server returned HTTP ${response.code}"))
                }
                val body = response.body.string()
                val transcription = Json.decodeFromString<TranscriptionResponse>(body)
                Result.success(transcription.text)
            }
        } catch (e: Exception) {
            flogError { "Voice input transcription request failed: ${e.javaClass.name}: ${e.message}" }
            Result.failure(e)
        } finally {
            file.delete()
        }
    }

    @Serializable
    private data class TranscriptionResponse(val text: String)

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_COUNT = 1
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }
}
