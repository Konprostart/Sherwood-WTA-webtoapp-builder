package com.sherwood.wta.core.sidekick.input

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test

/**
 * A wrong little-endian field makes every Whisper server answer 400 with a message that
 * says nothing about the header, so the byte layout is pinned here rather than discovered
 * on a phone.
 */
class WavFormatTest {

    private fun le(buffer: ByteBuffer, offset: Int, length: Int): Long = when (length) {
        2 -> buffer.getShort(offset).toLong() and 0xFFFF
        else -> buffer.getInt(offset).toLong() and 0xFFFFFFFFL
    }

    @Test
    fun `header is a canonical 16 kHz mono pcm wav`() {
        val dataBytes = 32_000L
        val header = WavFormat.headerBytes(dataBytes)
        assertThat(header.size).isEqualTo(WavFormat.HEADER_BYTES)

        val text = String(header.copyOfRange(0, 4), Charsets.US_ASCII)
        assertThat(text).isEqualTo("RIFF")
        assertThat(String(header.copyOfRange(8, 12), Charsets.US_ASCII)).isEqualTo("WAVE")
        assertThat(String(header.copyOfRange(12, 16), Charsets.US_ASCII)).isEqualTo("fmt ")
        assertThat(String(header.copyOfRange(36, 40), Charsets.US_ASCII)).isEqualTo("data")

        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        assertThat(le(buffer, 4, 4)).isEqualTo(36 + dataBytes)
        assertThat(le(buffer, 16, 4)).isEqualTo(16) // fmt chunk size
        assertThat(le(buffer, 20, 2)).isEqualTo(1) // PCM
        assertThat(le(buffer, 22, 2)).isEqualTo(1) // mono
        assertThat(le(buffer, 24, 4)).isEqualTo(16_000)
        assertThat(le(buffer, 28, 4)).isEqualTo(32_000) // byte rate
        assertThat(le(buffer, 32, 2)).isEqualTo(2) // block align
        assertThat(le(buffer, 34, 2)).isEqualTo(16) // bits per sample
        assertThat(le(buffer, 40, 4)).isEqualTo(dataBytes)
    }

    @Test
    fun `writeHeader patches the first bytes of a file`() {
        val file = File.createTempFile("wav-format-test", ".wav")
        try {
            RandomAccessFile(file, "rw").use { raf ->
                raf.write(ByteArray(WavFormat.HEADER_BYTES))
                raf.seek(raf.length())
                raf.write(ByteArray(1_000))
                WavFormat.writeHeader(raf, 1_000)
            }
            val bytes = file.readBytes()
            assertThat(bytes.size).isEqualTo(WavFormat.HEADER_BYTES + 1_000)
            assertThat(String(bytes.copyOfRange(0, 4), Charsets.US_ASCII)).isEqualTo("RIFF")
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertThat(le(buffer, 40, 4)).isEqualTo(1_000)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `the recording cap is two minutes of pcm`() {
        assertThat(SidekickWavRecorder.MAX_DURATION_MS).isEqualTo(120_000L)
        // 120 s * 16 000 Hz * 2 bytes: the ceiling the writer loop enforces.
        assertThat(SidekickWavRecorder.MAX_PCM_BYTES).isEqualTo(3_840_000L)
        assertThat(SidekickWavRecorder.SAMPLE_RATE).isEqualTo(16_000)
    }
}
