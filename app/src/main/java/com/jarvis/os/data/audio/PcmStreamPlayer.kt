package com.jarvis.os.data.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class PcmStreamPlayer(private val sampleRate: Int) {

    suspend fun play(chunks: Flow<ByteArray>): Long = withContext(Dispatchers.IO) {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        check(minBuffer > 0) { "Unsupported audio configuration." }

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuffer * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        var framesWritten = 0L
        var carry = -1

        try {
            track.play()
            chunks.collect { chunk ->
                val data = if (carry >= 0) {
                    ByteArray(chunk.size + 1).also {
                        it[0] = carry.toByte()
                        chunk.copyInto(it, destinationOffset = 1)
                    }
                } else chunk
                val usable = data.size and 1.inv()
                carry = if (usable != data.size) data[data.size - 1].toInt() and 0xFF else -1

                var offset = 0
                while (offset < usable) {
                    val written = track.write(data, offset, usable - offset)
                    check(written >= 0) { "AudioTrack write error: $written" }
                    offset += written
                }
                framesWritten += usable / 2
            }

            check(framesWritten > 0) { "No audio received." }
            track.stop()
            awaitDrain(track, framesWritten)
            framesWritten
        } finally {
            runCatching {
                track.pause()
                track.flush()
            }
            track.release()
        }
    }

    private suspend fun awaitDrain(track: AudioTrack, frames: Long) {
        val deadline = SystemClock.uptimeMillis() + DRAIN_TIMEOUT_MS
        while ((track.playbackHeadPosition.toLong() and 0xFFFFFFFFL) < frames &&
            SystemClock.uptimeMillis() < deadline
        ) {
            delay(40L)
        }
    }

    private companion object {
        const val DRAIN_TIMEOUT_MS = 3_000L
    }
}
