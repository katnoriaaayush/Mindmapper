package com.samsung.teachingboard.telemetry.usbsink

import android.os.IRemoteCallback
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * High-throughput, user-switch-aware log sink writing to a raw
 * /mnt/media_rw/<volume>/ USB path.
 *
 * Design:
 *  - Producers call [offer] from any thread; entries land in a bounded queue.
 *    Producers NEVER block and NEVER touch the file.
 *  - A single dedicated writer thread owns the FileOutputStream exclusively
 *    and drains the queue in batches (one write syscall per batch).
 *  - Before a user switch, [suspendForUserSwitch] drains + fsyncs + closes the
 *    file ON THE WRITER THREAD, and only then fires the IRemoteCallback reply,
 *    so ActivityManager (and therefore vold) cannot proceed while we hold an
 *    open fd on the volume.
 *  - While suspended, the queue itself is the buffer. No second buffer, no
 *    re-enqueue logic. [resumeAfterUserSwitch] reopens the file and the loop
 *    drains everything accumulated during the switch.
 *
 * Throughput: batched writes through a 128 KB BufferedOutputStream, flush on a
 * time/size policy, fsync only at rotation / suspend / eject. Comfortably
 * handles hundreds of entries per second on slow USB flash.
 *
 * Thread-safety: all file I/O happens on the internal writer thread. State
 * transitions are requested from binder/broadcast threads via [ReentrantLock]
 * + condition and executed by the writer thread.
 */
class UsbLogSink(
    private val config: Config = Config()
) {

    data class Config(
        /** Bounded queue capacity. 100k ≈ 3+ minutes of headroom at 500 logs/s. */
        val queueCapacity: Int = 100_000,
        /** Max entries pulled per drain batch (one write syscall per batch). */
        val batchSize: Int = 512,
        /** BufferedOutputStream size. */
        val streamBufferBytes: Int = 128 * 1024,
        /** Flush at least this often while data is flowing. */
        val flushIntervalMs: Long = 500L,
        /** Flush when this many bytes written since last flush. */
        val flushThresholdBytes: Long = 64L * 1024,
        /** Rotate current.log when it exceeds this size. */
        val rotateAtBytes: Long = 10L * 1024 * 1024,
        /** Poll timeout — also bounds latency of noticing a suspend request. */
        val pollTimeoutMs: Long = 200L,
        /** Active file name. Rotated files get timestamped names. */
        val currentFileName: String = "current.log",
        val logSubDir: String = "logs"
    )

    private enum class State {
        /** No media / not started. Writer parks; queue still absorbs. */
        NO_MEDIA,
        /** Normal operation: draining queue to file. */
        WRITING,
        /** Suspend requested (user switch incoming). Writer must close file, then ack. */
        SUSPEND_REQUESTED,
        /** File closed, switch in progress. Writer parks; queue absorbs. */
        SUSPENDED,
        /** Terminal. */
        STOPPED
    }

    private val queue = ArrayBlockingQueue<String>(config.queueCapacity)
    private val droppedCount = AtomicLong(0)

    private val lock = ReentrantLock()
    private val stateChanged = lock.newCondition()

    // Guarded by [lock]:
    private var state = State.NO_MEDIA
    private var pendingSwitchReply: IRemoteCallback? = null
    private var pendingNewUserId: Int = -1
    private var mediaRoot: File? = null   // e.g. /mnt/media_rw/XXXX-XXXX

    // Writer-thread-only (no locking needed):
    private var out: BufferedOutputStream? = null
    private var rawFd: FileDescriptor? = null
    private var bytesInCurrentFile: Long = 0
    private var bytesSinceFlush: Long = 0
    private var lastFlushUptime: Long = 0

    @Volatile
    private var writerThread: Thread? = null

    // ---------------------------------------------------------------------
    // Public API — producers
    // ---------------------------------------------------------------------

    /**
     * Enqueue one log line (without trailing newline). Non-blocking; from any
     * thread. If the queue is full the entry is dropped and counted — a drop
     * marker is written on the next resume/recovery.
     */
    fun offer(line: String) {
        if (!queue.offer(line)) {
            droppedCount.incrementAndGet()
        }
    }

    // ---------------------------------------------------------------------
    // Public API — lifecycle & media events
    // ---------------------------------------------------------------------

    /** Start the writer thread. Call once. Media may attach later. */
    fun start() {
        lock.withLock {
            check(writerThread == null) { "already started" }
            val t = Thread(::writerLoop, "UsbLogSink-writer").apply {
                priority = Thread.NORM_PRIORITY - 1
                isDaemon = false
            }
            writerThread = t
            t.start()
        }
    }

    /**
     * Media is available. [root] is the raw path, e.g. File("/mnt/media_rw/1234-ABCD").
     * Safe to call from ACTION_MEDIA_MOUNTED receiver or a startup scan.
     */
    fun onMediaMounted(root: File) {
        lock.withLock {
            if (state == State.STOPPED) return
            mediaRoot = root
            if (state == State.NO_MEDIA) {
                state = State.WRITING            // writer opens lazily on first batch
                stateChanged.signalAll()
            }
        }
    }

    /**
     * Media ejected/unmounted. Treated like a suspend with no reply: close on
     * writer thread ASAP; subsequent write errors are handled defensively too.
     */
    fun onMediaEjected() {
        lock.withLock {
            if (state == State.STOPPED) return
            mediaRoot = null
            when (state) {
                State.WRITING, State.SUSPEND_REQUESTED, State.SUSPENDED -> {
                    // If a switch reply is pending we still must ack it —
                    // repurpose SUSPEND_REQUESTED path; writer closes then acks.
                    if (pendingSwitchReply == null) {
                        state = State.SUSPEND_REQUESTED  // close path, no reply
                    }
                    // note: writer transitions to NO_MEDIA (mediaRoot == null)
                    stateChanged.signalAll()
                }
                else -> {}
            }
        }
    }

    // ---------------------------------------------------------------------
    // Public API — user switch hooks (call from IUserSwitchObserver)
    // ---------------------------------------------------------------------

    /**
     * Call from IUserSwitchObserver.onUserSwitching(newUserId, reply).
     * The [reply] is fired on the writer thread AFTER flush+fsync+close.
     * ActivityManager waits on this reply, so vold cannot remount while we
     * hold the fd. Total cost is milliseconds.
     *
     * If we're already NO_MEDIA/SUSPENDED (nothing open), the reply is sent
     * immediately — never leave AMS hanging.
     */
    fun suspendForUserSwitch(newUserId: Int, reply: IRemoteCallback?) {
        lock.withLock {
            when (state) {
                State.WRITING -> {
                    pendingSwitchReply = reply
                    pendingNewUserId = newUserId
                    state = State.SUSPEND_REQUESTED
                    stateChanged.signalAll()
                }
                else -> {
                    // Nothing open — ack right away.
                    sendReplySafely(reply)
                    if (state != State.STOPPED && state != State.NO_MEDIA) {
                        state = State.SUSPENDED
                    }
                }
            }
        }
    }

    /**
     * Call from IUserSwitchObserver.onUserSwitchComplete(newUserId).
     * Reopens (lazily) and drains everything buffered during the switch.
     */
    fun resumeAfterUserSwitch(newUserId: Int) {
        lock.withLock {
            if (state != State.SUSPENDED) return
            val root = mediaRoot
            state = if (root != null && root.canWrite()) {
                State.WRITING
            } else {
                State.NO_MEDIA
            }
            if (state == State.WRITING) {
                // Marker so post-switch logs are attributable in the file.
                queueMarkerLocked("=== resumed after switch to user $newUserId ===")
            }
            stateChanged.signalAll()
        }
    }

    /** Flush, fsync, close, and stop the writer thread. Blocks briefly. */
    fun stop() {
        val t: Thread?
        lock.withLock {
            if (state == State.STOPPED) return
            state = State.STOPPED
            t = writerThread
            writerThread = null
            stateChanged.signalAll()
        }
        try {
            t?.join(5_000)
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // ---------------------------------------------------------------------
    // Writer thread
    // ---------------------------------------------------------------------

    private fun writerLoop() {
        val batch = ArrayList<String>(config.batchSize)
        val sb = StringBuilder(config.batchSize * 128)

        while (true) {
            // --- 1. Resolve state; park while there's nothing to do. -------
            val localState: State = lock.withLock {
                while (state == State.SUSPENDED || state == State.NO_MEDIA) {
                    stateChanged.await()
                }
                state
            }

            when (localState) {
                State.STOPPED -> {
                    closeFileQuietly(fsync = true)
                    return
                }

                State.SUSPEND_REQUESTED -> {
                    // Drain what's already queued, then close, then ack.
                    drainAllToFileBestEffort(batch, sb)
                    closeFileQuietly(fsync = true)
                    val reply: IRemoteCallback?
                    lock.withLock {
                        reply = pendingSwitchReply
                        pendingSwitchReply = null
                        // Eject path sets mediaRoot = null → park as NO_MEDIA.
                        state = if (mediaRoot == null) State.NO_MEDIA else State.SUSPENDED
                    }
                    sendReplySafely(reply)   // AMS proceeds with the switch now
                }

                State.WRITING -> {
                    if (!ensureFileOpen()) {
                        // Open failed (media gone / permission). Demote.
                        lock.withLock {
                            if (state == State.WRITING) state = State.NO_MEDIA
                        }
                        continue
                    }
                    // --- 2. Batch drain: block briefly for first entry. ----
                    val first = try {
                        queue.poll(config.pollTimeoutMs, TimeUnit.MILLISECONDS)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt(); null
                    }
                    if (first == null) {
                        maybeFlush(force = false)   // idle tick: honor flush timer
                        continue
                    }
                    batch.add(first)
                    queue.drainTo(batch, config.batchSize - 1)
                    writeBatch(batch, sb)
                    batch.clear()
                    maybeRotate()
                }

                else -> { /* unreachable */ }
            }
        }
    }

    /** One write syscall for the whole batch. On IOException → NO_MEDIA. */
    private fun writeBatch(batch: List<String>, sb: StringBuilder) {
        val stream = out ?: return
        sb.setLength(0)
        for (line in batch) sb.append(line).append('\n')
        val bytes = sb.toString().toByteArray(StandardCharsets.UTF_8)
        try {
            stream.write(bytes)
            bytesInCurrentFile += bytes.size
            bytesSinceFlush += bytes.size
            maybeFlush(force = false)
        } catch (ioe: IOException) {
            Log.w(TAG, "write failed (${batch.size} entries lost), demoting to NO_MEDIA", ioe)
            droppedCount.addAndGet(batch.size.toLong())
            closeFileQuietly(fsync = false)   // fd is likely dead; don't fsync
            lock.withLock {
                if (state == State.WRITING) state = State.NO_MEDIA
            }
        }
    }

    private fun drainAllToFileBestEffort(batch: ArrayList<String>, sb: StringBuilder) {
        while (out != null) {
            batch.clear()
            if (queue.drainTo(batch, config.batchSize) == 0) break
            writeBatch(batch, sb)
        }
        batch.clear()
    }

    // ---------------------------------------------------------------------
    // File management (writer-thread only)
    // ---------------------------------------------------------------------

    private fun ensureFileOpen(): Boolean {
        if (out != null) return true
        val root = lock.withLock { mediaRoot } ?: return false
        return try {
            val dir = File(root, config.logSubDir)
            if (!dir.isDirectory && !dir.mkdirs()) {
                Log.w(TAG, "cannot create ${dir.absolutePath}")
                return false
            }
            val file = File(dir, config.currentFileName)
            val fos = FileOutputStream(file, /* append = */ true)
            rawFd = fos.fd
            out = BufferedOutputStream(fos, config.streamBufferBytes)
            bytesInCurrentFile = file.length()
            bytesSinceFlush = 0
            lastFlushUptime = android.os.SystemClock.uptimeMillis()
            // Surface any drops that happened while we had no sink.
            val dropped = droppedCount.getAndSet(0)
            if (dropped > 0) {
                writeMarkerNow("=== $dropped entries dropped while sink unavailable ===")
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "open failed", e)
            out = null; rawFd = null
            false
        }
    }

    private fun maybeFlush(force: Boolean) {
        val stream = out ?: return
        val now = android.os.SystemClock.uptimeMillis()
        val due = force ||
            bytesSinceFlush >= config.flushThresholdBytes ||
            (bytesSinceFlush > 0 && now - lastFlushUptime >= config.flushIntervalMs)
        if (!due) return
        try {
            stream.flush()
            bytesSinceFlush = 0
            lastFlushUptime = now
        } catch (ioe: IOException) {
            Log.w(TAG, "flush failed, demoting to NO_MEDIA", ioe)
            closeFileQuietly(fsync = false)
            lock.withLock { if (state == State.WRITING) state = State.NO_MEDIA }
        }
    }

    private fun maybeRotate() {
        if (bytesInCurrentFile < config.rotateAtBytes || out == null) return
        val root = lock.withLock { mediaRoot } ?: return
        val dir = File(root, config.logSubDir)
        val current = File(dir, config.currentFileName)
        try {
            out?.flush()
            rawFd?.let { Os.fsync(it) }          // data durable before rename
            out?.close()
        } catch (e: Exception) {
            Log.w(TAG, "pre-rotate flush/close failed", e)
        } finally {
            out = null; rawFd = null
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val rotated = File(dir, "log-$stamp.log")
        if (!current.renameTo(rotated)) {
            Log.w(TAG, "rotate rename failed; continuing to append to current")
        }
        fsyncDirQuietly(dir)                      // make the rename durable
        // Next writeBatch reopens a fresh current.log via ensureFileOpen().
    }

    private fun closeFileQuietly(fsync: Boolean) {
        try {
            out?.flush()
            if (fsync) rawFd?.let { Os.fsync(it) }
        } catch (e: Exception) {
            Log.w(TAG, "flush/fsync on close failed", e)
        }
        try {
            out?.close()
        } catch (e: Exception) {
            Log.w(TAG, "close failed", e)
        } finally {
            out = null
            rawFd = null
        }
    }

    private fun fsyncDirQuietly(dir: File) {
        try {
            val dirFd = Os.open(dir.absolutePath, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(dirFd)
            } finally {
                Os.close(dirFd)
            }
        } catch (e: Exception) {
            Log.w(TAG, "dir fsync failed", e)
        }
    }

    /** Writer-thread-only immediate marker write (bypasses queue ordering). */
    private fun writeMarkerNow(marker: String) {
        try {
            out?.write((marker + "\n").toByteArray(StandardCharsets.UTF_8))
            bytesInCurrentFile += marker.length + 1
            bytesSinceFlush += marker.length + 1
        } catch (ioe: IOException) {
            Log.w(TAG, "marker write failed", ioe)
        }
    }

    /** Called under [lock]: enqueue a marker preserving queue order. */
    private fun queueMarkerLocked(marker: String) {
        val dropped = droppedCount.getAndSet(0)
        val text = if (dropped > 0) "$marker (dropped=$dropped during switch)" else marker
        if (!queue.offer(text)) droppedCount.addAndGet(dropped + 1)
    }

    private fun sendReplySafely(reply: IRemoteCallback?) {
        try {
            reply?.sendResult(null)
        } catch (e: Exception) {
            Log.w(TAG, "switch reply failed", e)
        }
    }

    companion object {
        private const val TAG = "UsbLogSink"
    }
}
