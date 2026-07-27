package com.example.usblogcapture.capture

import android.os.ParcelFileDescriptor
import java.io.OutputStream
import java.util.ArrayDeque

/**
 * The only piece of this subsystem that touches an actual file descriptor.
 *
 * The capture engine writes to this continuously and never knows it exists beyond
 * the [LogSink] interface. [attach]/[detach] are called by the orchestrator around
 * profile switches — writes that arrive with nothing attached are buffered (bounded,
 * oldest-dropped-on-overflow) and flushed the moment a new fd shows up, so a switch
 * never produces a gap in the file, just a short delay.
 */
class FileLogSink(private val maxBufferedBytes: Int = 2 * 1024 * 1024) : LogSink {

    private val lock = Any()
    private var stream: OutputStream? = null
    private val pending = ArrayDeque<ByteArray>()
    private var pendingBytes = 0

    fun attach(pfd: ParcelFileDescriptor) {
        synchronized(lock) {
            stream = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
            flushPendingLocked()
        }
    }

    fun detach() {
        synchronized(lock) {
            runCatching { stream?.close() }
            stream = null
        }
    }

    override fun write(bytes: ByteArray) {
        synchronized(lock) {
            val current = stream
            if (current == null) {
                bufferLocked(bytes)
                return
            }
            runCatching { current.write(bytes) }
                .onFailure {
                    stream = null
                    bufferLocked(bytes)
                }
        }
    }

    private fun bufferLocked(bytes: ByteArray) {
        pending.addLast(bytes)
        pendingBytes += bytes.size
        while (pendingBytes > maxBufferedBytes && pending.isNotEmpty()) {
            pendingBytes -= pending.removeFirst().size
        }
    }

    private fun flushPendingLocked() {
        val current = stream ?: return
        while (pending.isNotEmpty()) {
            val ok = runCatching { current.write(pending.first()) }.isSuccess
            if (!ok) return
            pendingBytes -= pending.removeFirst().size
        }
    }
}
