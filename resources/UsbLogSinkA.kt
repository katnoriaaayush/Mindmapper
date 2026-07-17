/**
 * User-switch-aware USB log sink.
 *
 * Contract:
 *  - onBeforeUserSwitching(newUserId, reply): quiesce writer, fsync + close USB FD,
 *    THEN reply.sendResult(null). System (AMS) blocks the switch until reply or
 *    its internal timeout (~3s total budget for all observers), so the quiesce
 *    must be fast and bounded.
 *  - onUserSwitchComplete(newUserId): re-resolve USB path (volume may have
 *    remounted), reopen in append mode, drain the in-memory buffer, resume.
 *
 * Threading model:
 *  - Binder callbacks NEVER touch the FD. They post control messages to a single
 *    HandlerThread ("usb-log-writer") that exclusively owns the stream, and wait
 *    on a latch with a timeout.
 *  - Producers call [write] from any thread; it only enqueues.
 */

import android.app.ActivityManager
import android.app.IUserSwitchObserver
import android.os.Handler
import android.os.HandlerThread
import android.os.IRemoteCallback
import android.os.RemoteException
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class UsbLogSink(
    private val resolveUsbLogFile: () -> File?   // re-run on every (re)open; mount point can change
) {
    enum class State { RUNNING, DRAINING, SUSPENDED, RESUMING, STOPPED }

    private val state = AtomicReference(State.SUSPENDED)

    /** Bounded ring buffer. Producers never block; oldest entries drop under pressure. */
    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_CAPACITY)
    private val dropped = AtomicInteger(0)

    private val thread = HandlerThread("usb-log-writer").apply { start() }
    private val handler = Handler(thread.looper)

    // FD is owned exclusively by `thread`. Never touched from binder threads.
    private var out: FileOutputStream? = null

    // ---------------------------------------------------------------- producers

    fun write(line: ByteArray) {
        if (!queue.offer(line)) {
            queue.poll()                       // drop-oldest policy
            queue.offer(line)
            dropped.incrementAndGet()
        }
        if (state.get() == State.RUNNING) {
            handler.post(::drainLocked)        // cheap; drain is idempotent
        }
        // If SUSPENDED/DRAINING: entries just accumulate in the ring buffer.
    }

    // ---------------------------------------------------- quiesce (pre-switch)

    /**
     * Called from the binder thread. Returns only after the FD is closed
     * (or the deadline passed, in which case the FD is force-closed anyway).
     * Bounded: never exceeds [QUIESCE_BUDGET_MS].
     */
    fun quiesceBlocking(): Boolean {
        if (!state.compareAndSet(State.RUNNING, State.DRAINING)) {
            return true                        // already suspended/stopped — nothing holds the FD
        }
        val done = CountDownLatch(1)
        handler.post {
            try {
                drainLocked(deadlineMs = System.currentTimeMillis() + DRAIN_BUDGET_MS)
                out?.let {
                    it.flush()
                    it.fd.sync()               // fsync BEFORE replying — vold may yank the volume
                    it.close()
                }
            } catch (_: Exception) {
                // Best effort: FD must not survive past the reply.
                runCatching { out?.close() }
            } finally {
                out = null
                state.set(State.SUSPENDED)
                done.countDown()
            }
        }
        val ok = done.await(QUIESCE_BUDGET_MS, TimeUnit.MILLISECONDS)
        if (!ok) {
            // Writer thread is wedged (e.g. blocked in a write to a dying FUSE mount).
            // Last resort: close the FD out from under it. An IOException on the
            // writer thread is recoverable; being SIGKILLed by vold is not.
            runCatching { out?.close() }
            out = null
            state.set(State.SUSPENDED)
        }
        return ok
    }

    // ---------------------------------------------------- resume (post-switch)

    fun resume(fromUser: Int, toUser: Int) {
        if (!state.compareAndSet(State.SUSPENDED, State.RESUMING)) return
        handler.post { openWithRetry(attempt = 0, fromUser, toUser) }
    }

    private fun openWithRetry(attempt: Int, fromUser: Int, toUser: Int) {
        if (state.get() != State.RESUMING) return
        val file = resolveUsbLogFile()
        val stream = file?.let { runCatching { FileOutputStream(it, /*append=*/true) }.getOrNull() }
        if (stream == null) {
            // USB volume often remounts asynchronously after a user switch.
            // Prefer also listening for ACTION_MEDIA_MOUNTED / StorageVolumeCallback
            // and calling resume() again from there; this backoff is the fallback.
            if (attempt < MAX_OPEN_RETRIES) {
                handler.postDelayed(
                    { openWithRetry(attempt + 1, fromUser, toUser) },
                    RETRY_BASE_MS shl attempt
                )
            } else {
                state.set(State.SUSPENDED)     // give up; a later mount event can retry
            }
            return
        }
        out = stream
        val d = dropped.getAndSet(0)
        runCatching {
            stream.write("=== user switch $fromUser -> $toUser (dropped=$d) ===\n".toByteArray())
        }
        state.set(State.RUNNING)
        drainLocked()
    }

    // ------------------------------------------------------------ writer-owned

    private fun drainLocked(deadlineMs: Long = Long.MAX_VALUE) {
        val o = out ?: return
        var entry = queue.poll()
        while (entry != null) {
            runCatching { o.write(entry) }.onFailure { return }   // quiesce will handle cleanup
            if (System.currentTimeMillis() >= deadlineMs) return  // respect the switch budget
            entry = queue.poll()
        }
    }

    companion object {
        const val QUEUE_CAPACITY = 8192       // ~ a few MB of log lines; tune to your rate
        const val DRAIN_BUDGET_MS = 800L      // portion of budget spent flushing backlog
        const val QUIESCE_BUDGET_MS = 1800L   // total; must stay well under AMS's ~3s
        const val RETRY_BASE_MS = 250L
        const val MAX_OPEN_RETRIES = 6
    }
}

/**
 * Binder-side glue. Registered via IActivityManager.registerUserSwitchObserver
 * (needs platform signature / INTERACT_ACROSS_USERS_FULL).
 *
 * NOTE (Samsung): extend the Stub and override EVERY method of the interface —
 * you've already hit AbstractMethodError from Samsung's framework diverging
 * from AOSP method ordering, so leave no method unimplemented.
 */
class LogSinkUserSwitchObserver(private val sink: UsbLogSink) : IUserSwitchObserver.Stub() {

    @Volatile private var lastForegroundUser = ActivityManager.getCurrentUser()

    /** Android 15+: earliest hook, fires before AMS starts tearing down the old user. */
    override fun onBeforeUserSwitching(newUserId: Int, reply: IRemoteCallback?) {
        quiesceThenReply(reply)
    }

    /** Pre-15 fallback: also carries a reply the system waits on. Quiesce is idempotent. */
    override fun onUserSwitching(newUserId: Int, reply: IRemoteCallback?) {
        quiesceThenReply(reply)
    }

    override fun onUserSwitchComplete(newUserId: Int) {
        val from = lastForegroundUser
        lastForegroundUser = newUserId
        sink.resume(from, newUserId)
    }

    override fun onForegroundProfileSwitch(newProfileId: Int) { /* no-op */ }
    override fun onLockedBootComplete(newUserId: Int) { /* no-op */ }

    private fun quiesceThenReply(reply: IRemoteCallback?) {
        // We ARE allowed to do bounded work here — AMS dispatches observer callbacks
        // expecting a deferred reply — but quiesceBlocking() is hard-capped, so the
        // reply always goes out. Send it exactly once, in `finally`.
        try {
            sink.quiesceBlocking()
        } finally {
            try {
                reply?.sendResult(null)        // ONLY after the FD is provably closed
            } catch (_: RemoteException) { }
        }
    }
}
