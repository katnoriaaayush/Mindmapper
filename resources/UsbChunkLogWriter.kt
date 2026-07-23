package com.samsung.teachingboard.usbsync

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Writes a continuous log stream to a removable USB volume as a series of
 * finalized MediaStore entries ("chunks"), rotating by size or elapsed time.
 *
 * Bypasses direct /mnt/media_rw path access (blocked at the SELinux/firmware
 * level) by routing every write through MediaProvider via ContentResolver,
 * which owns media_rw group membership independently of the caller's SELinux
 * domain.
 *
 * Must be constructed and used from the foreground-user process — the USB
 * volume is only visible in that user's StorageVolume/mount namespace.
 *
 * All mutable state is only ever touched on the single background thread
 * this class owns, so write()/flush()/close() are safe to call from any
 * caller thread. Nothing is held open indefinitely: a background task
 * force-rotates a stale chunk even if no new data arrives, so a chunk is
 * never pending for longer than chunkIntervalMs.
 */
class UsbChunkLogWriter(
    private val context: Context,
    private val chunkSizeBytes: Long = DEFAULT_CHUNK_SIZE_BYTES,
    private val chunkIntervalMs: Long = DEFAULT_CHUNK_INTERVAL_MS,
    private val relativeDir: String = "Documents/DeviceLogs",
    private val filePrefix: String = "log",
    private val listener: Listener? = null
) {

    interface Listener {
        /** Called after a chunk is written and finalized (IS_PENDING cleared). */
        fun onChunkFinalized(uri: Uri, bytesWritten: Long) {}
        /** Called when an open/write/finalize attempt fails. Writer keeps running. */
        fun onError(e: Exception) {}
    }

    private val resolver = context.contentResolver
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val sequence = AtomicInteger(0)

    private var currentUri: Uri? = null
    private var currentStream: OutputStream? = null
    private var currentChunkBytes: Long = 0
    private var chunkOpenedAt: Long = 0

    init {
        // Guarantees time-based rotation even during idle periods with no write() calls.
        executor.scheduleWithFixedDelay(
            ::rotateIfStale, chunkIntervalMs, chunkIntervalMs, TimeUnit.MILLISECONDS
        )
    }

    /** Queue a write. Safe to call from any thread; actual IO is serialized. */
    fun write(data: ByteArray) {
        executor.execute {
            try {
                ensureOpenChunk()
                currentStream?.write(data)
                currentChunkBytes += data.size
                if (shouldRotate()) finalizeCurrentChunk()
            } catch (e: IOException) {
                // Most likely the volume went away mid-write (unplug, user switch).
                // Drop the broken chunk rather than leaving a corrupt pending row behind.
                Log.w(TAG, "write failed, abandoning current chunk", e)
                abandonCurrentChunk()
                listener?.onError(e)
            }
        }
    }

    fun write(line: String) = write((line + "\n").toByteArray(Charsets.UTF_8))

    /** Force-finalize the current chunk without waiting for size/time thresholds. */
    fun flush() {
        executor.execute {
            try {
                finalizeCurrentChunk()
            } catch (e: IOException) {
                listener?.onError(e)
            }
        }
    }

    /** Finalize any in-progress chunk and stop accepting further writes. */
    fun close() {
        executor.execute {
            try {
                finalizeCurrentChunk()
            } catch (e: IOException) {
                listener?.onError(e)
            }
        }
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    // --- internal, only ever runs on `executor`'s single thread ---

    private fun rotateIfStale() {
        try {
            if (currentStream != null && shouldRotate()) finalizeCurrentChunk()
        } catch (e: IOException) {
            listener?.onError(e)
        }
    }

    private fun ensureOpenChunk() {
        if (currentStream != null) return

        val volumeName = resolveUsbVolumeName()
            ?: throw IOException("No removable USB volume currently mounted")

        val name = "${filePrefix}_${System.currentTimeMillis()}_${sequence.incrementAndGet()}.log"
        val values = ContentValues().apply {
            put(MediaStore.Files.FileColumns.DISPLAY_NAME, name)
            put(MediaStore.Files.FileColumns.MIME_TYPE, "text/plain")
            put(MediaStore.Files.FileColumns.RELATIVE_PATH, relativeDir)
            put(MediaStore.Files.FileColumns.IS_PENDING, 1)
        }

        val collection = MediaStore.Files.getContentUri(volumeName)
        val uri = resolver.insert(collection, values)
            ?: throw IOException("MediaStore insert returned null for volume $volumeName")

        val pfd = resolver.openFileDescriptor(uri, "w")
            ?: throw IOException("Could not open ParcelFileDescriptor for $uri")

        // AutoCloseOutputStream closes the underlying pfd when the stream closes.
        currentUri = uri
        currentStream = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
        currentChunkBytes = 0
        chunkOpenedAt = System.currentTimeMillis()
    }

    private fun shouldRotate(): Boolean {
        val ageMs = System.currentTimeMillis() - chunkOpenedAt
        return currentChunkBytes >= chunkSizeBytes || ageMs >= chunkIntervalMs
    }

    private fun finalizeCurrentChunk() {
        val uri = currentUri ?: return
        val stream = currentStream
        val bytes = currentChunkBytes
        try {
            stream?.flush()
            stream?.close()
        } finally {
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Files.FileColumns.IS_PENDING, 0) },
                null, null
            )
            currentUri = null
            currentStream = null
            currentChunkBytes = 0
        }
        listener?.onChunkFinalized(uri, bytes)
    }

    private fun abandonCurrentChunk() {
        val uri = currentUri
        try {
            currentStream?.close()
        } catch (_: IOException) {
            // already broken; nothing more to do with the stream
        } finally {
            currentUri = null
            currentStream = null
            currentChunkBytes = 0
        }
        // Clean up the orphaned pending row instead of leaving it stuck forever.
        uri?.let { resolver.delete(it, null, null) }
    }

    private fun resolveUsbVolumeName(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        return sm.storageVolumes
            .firstOrNull { it.isRemovable && !it.isPrimary }
            ?.mediaStoreVolumeName
    }

    companion object {
        private const val TAG = "UsbChunkLogWriter"
        const val DEFAULT_CHUNK_SIZE_BYTES = 5L * 1024 * 1024
        const val DEFAULT_CHUNK_INTERVAL_MS = 5 * 60 * 1000L
    }
}
