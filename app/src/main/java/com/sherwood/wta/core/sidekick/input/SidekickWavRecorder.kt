package com.sherwood.wta.core.sidekick.input

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.sherwood.wta.core.logging.AppLogger
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Records one dictation to a 16 kHz mono WAV file for a Whisper-compatible server.
 *
 * Chosen over the platform recognizer when the user runs their own server: it is the
 * only path that guarantees Serbian, Swedish and English regardless of what speech
 * packs the phone has, and the audio can stay on the local network.
 *
 * Memory: the PCM stream goes straight to disk in 8 KB chunks — a two-minute clip is
 * 3.8 MB on disk and a few kilobytes in RAM, never a `ByteArray` of the whole take. The
 * [MAX_DURATION_MS] cap is enforced by the writer loop rather than by a timer, so a
 * stalled reader cannot accumulate past it.
 *
 * Lifetime: one recording per instance. [stop] joins the capture thread so the WAV is
 * complete (header patched) before the caller uploads it; [cancel] discards the file.
 */
class SidekickWavRecorder(private val context: Context) {

    private val lock = Any()

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var output: File? = null

    @Volatile
    private var running = false

    @Volatile
    private var pcmBytes = 0L

    @Volatile
    private var finalized = false

    @Volatile
    private var limitNotified = false

    /**
     * Starts capturing.
     *
     * @param onLimitReached invoked once, on the capture thread, when [MAX_DURATION_MS]
     *   is hit. The caller finalizes and uploads; the recorder stops on its own.
     * @return `null` when recording started, otherwise why it did not.
     */
    fun start(onLimitReached: () -> Unit): SidekickSpeechFailure? {
        if (running) return SidekickSpeechFailure.BUSY
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return SidekickSpeechFailure.PERMISSION
        }
        val minBuffer = runCatching {
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        }.getOrDefault(0)
        if (minBuffer <= 0) {
            AppLogger.w(TAG, "AudioRecord min buffer unavailable for 16 kHz mono PCM")
            return SidekickSpeechFailure.AUDIO
        }
        // A quarter second per read: small enough for a responsive stop, large enough
        // that the loop is not a busy spin.
        val bufferSize = maxOf(minBuffer, SAMPLE_RATE / 4 * BYTES_PER_SAMPLE)

        val created = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
        } catch (t: Throwable) {
            AppLogger.w(TAG, "AudioRecord creation failed: ${t.javaClass.simpleName}")
            null
        }
        if (created == null || created.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { created?.release() }
            AppLogger.w(TAG, "AudioRecord is not initialized")
            return SidekickSpeechFailure.AUDIO
        }

        val file = try {
            val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
            File.createTempFile("voice-", ".wav", dir)
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Voice file could not be created", t)
            runCatching { created.release() }
            return SidekickSpeechFailure.AUDIO
        }
        val stream = try {
            FileOutputStream(file).also { it.write(ByteArray(WavFormat.HEADER_BYTES)) }
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Voice file could not be opened", t)
            runCatching { created.release() }
            runCatching { file.delete() }
            return SidekickSpeechFailure.AUDIO
        }

        record = created
        output = file
        pcmBytes = 0L
        finalized = false
        limitNotified = false
        running = true

        val started = try {
            created.startRecording()
            created.recordingState == AudioRecord.RECORDSTATE_RECORDING
        } catch (t: Throwable) {
            AppLogger.w(TAG, "startRecording failed: ${t.javaClass.simpleName}")
            false
        }
        if (!started) {
            closeQuietly(created, stream)
            runCatching { file.delete() }
            output = null
            record = null
            return SidekickSpeechFailure.AUDIO
        }

        thread = Thread(
            { capture(created, stream, bufferSize, onLimitReached) },
            "sidekick-voice"
        ).apply {
            isDaemon = true
            start()
        }
        return null
    }

    /**
     * Stops capturing and returns the finished WAV, or `null` when nothing was captured.
     *
     * Blocks until the capture thread has patched the header, so the returned file is
     * always a complete WAV.
     */
    fun stop(): File? {
        val current = synchronized(lock) {
            running = false
            record
        }
        runCatching { current?.stop() }
        val worker = thread
        if (worker != null && worker !== Thread.currentThread()) {
            runCatching { worker.join(JOIN_TIMEOUT_MS) }
        }
        // The take is handed to the caller exactly once: a second stop() must not return
        // the same file while an upload is reading it.
        val file = output
        output = null
        thread = null
        if (pcmBytes <= 0L || file == null || file.length() <= WavFormat.HEADER_BYTES) {
            if (file != null) runCatching { file.delete() }
            return null
        }
        return file
    }

    /**
     * Stops capturing and unlinks the file without waiting for the capture thread.
     *
     * Used by teardown paths that run on the main thread: on Android an open file can be
     * unlinked, so the thread keeps writing into an inode nobody can reach and the space
     * is released when it closes. [cancel] is the blocking variant for IO callers.
     */
    fun cancelAsync() {
        val current = synchronized(lock) {
            running = false
            record
        }
        runCatching { current?.stop() }
        val file = output
        output = null
        thread = null
        if (file != null) runCatching { file.delete() }
        pcmBytes = 0L
    }

    /** Stops capturing and deletes the recording. Safe to call more than once. */
    fun cancel() {
        val current = synchronized(lock) {
            running = false
            record
        }
        runCatching { current?.stop() }
        val worker = thread
        if (worker != null && worker !== Thread.currentThread()) {
            runCatching { worker.join(JOIN_TIMEOUT_MS) }
        }
        val file = output
        output = null
        thread = null
        if (file != null) runCatching { file.delete() }
        pcmBytes = 0L
    }

    private fun capture(
        audio: AudioRecord,
        stream: FileOutputStream,
        bufferSize: Int,
        onLimitReached: () -> Unit
    ) {
        val chunk = ByteArray(bufferSize)
        var failed = false
        try {
            while (running && pcmBytes < MAX_PCM_BYTES) {
                val wanted = minOf(chunk.size.toLong(), MAX_PCM_BYTES - pcmBytes).toInt()
                val read = audio.read(chunk, 0, wanted)
                if (read < 0) {
                    // The mic was lost (another app took it, device removed). Keep what
                    // was captured: a shorter transcript beats a lost dictation.
                    AppLogger.w(TAG, "Audio read failed with $read")
                    failed = true
                    break
                }
                if (read == 0) continue
                stream.write(chunk, 0, read)
                pcmBytes += read
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Voice capture stopped: ${t.javaClass.simpleName}")
            failed = true
        } finally {
            val reachedLimit = !failed && running && pcmBytes >= MAX_PCM_BYTES
            running = false
            finalizeFile(stream)
            closeQuietly(audio, null)
            if (reachedLimit && !limitNotified) {
                limitNotified = true
                // Called last so the caller always observes a finalized file.
                runCatching { onLimitReached() }
            }
        }
    }

    private fun finalizeFile(stream: FileOutputStream) {
        if (finalized) return
        finalized = true
        runCatching { stream.close() }
        val file = output ?: return
        val dataBytes = pcmBytes
        try {
            RandomAccessFile(file, "rw").use { raf ->
                WavFormat.writeHeader(raf, dataBytes)
            }
        } catch (t: Throwable) {
            AppLogger.e(TAG, "WAV header could not be written", t)
        }
    }

    private fun closeQuietly(audio: AudioRecord?, stream: FileOutputStream?) {
        runCatching { stream?.close() }
        runCatching { audio?.stop() }
        runCatching { audio?.release() }
    }

    companion object {
        private const val TAG = "SidekickWavRecorder"

        /** Cache subdirectory for the take; the maintenance pruner cleans stale files. */
        const val CACHE_DIR = "sidekick"

        const val SAMPLE_RATE = 16_000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val BYTES_PER_SAMPLE = 2

        /** Hard cap per recording: 120 s, i.e. 3.84 MB of PCM. */
        const val MAX_DURATION_MS = 120_000L

        val MAX_PCM_BYTES: Long =
            MAX_DURATION_MS * SAMPLE_RATE * BYTES_PER_SAMPLE / 1000L

        private const val JOIN_TIMEOUT_MS = 3_000L
    }
}

/**
 * The 44-byte canonical PCM WAV header.
 *
 * Extracted so the byte layout is unit-testable without an `AudioRecord` — a wrong
 * little-endian field produces a file every server rejects with an unhelpful 400.
 */
internal object WavFormat {

    const val HEADER_BYTES = 44

    fun headerBytes(
        dataBytes: Long,
        sampleRate: Int = SidekickWavRecorder.SAMPLE_RATE,
        channels: Int = 1,
        bitsPerSample: Int = 16
    ): ByteArray {
        val blockAlign = channels * bitsPerSample / 8
        val byteRate = sampleRate * blockAlign
        val header = ByteArray(HEADER_BYTES)
        writeAscii(header, 0, "RIFF")
        writeIntLe(header, 4, (36L + dataBytes).coerceAtMost(0xFFFF_FFFFL))
        writeAscii(header, 8, "WAVE")
        writeAscii(header, 12, "fmt ")
        writeIntLe(header, 16, 16L)
        writeShortLe(header, 20, 1) // PCM
        writeShortLe(header, 22, channels)
        writeIntLe(header, 24, sampleRate.toLong())
        writeIntLe(header, 28, byteRate.toLong())
        writeShortLe(header, 32, blockAlign)
        writeShortLe(header, 34, bitsPerSample)
        writeAscii(header, 36, "data")
        writeIntLe(header, 40, dataBytes.coerceAtMost(0xFFFF_FFFFL))
        return header
    }

    fun writeHeader(raf: RandomAccessFile, dataBytes: Long) {
        raf.seek(0)
        raf.write(headerBytes(dataBytes))
    }

    private fun writeAscii(target: ByteArray, offset: Int, value: String) {
        for (i in value.indices) target[offset + i] = value[i].code.toByte()
    }

    private fun writeIntLe(target: ByteArray, offset: Int, value: Long) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
        target[offset + 2] = ((value shr 16) and 0xFF).toByte()
        target[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeShortLe(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }
}
