package com.example.usblogcapture.session

import com.example.usblogcapture.capture.FileLogSink
import com.example.usblogcapture.capture.LogCaptureEngine
import com.example.usblogcapture.crossuser.ProfileServiceConnector
import com.example.usblogcapture.crossuser.UserSwitchGate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns session lifecycle and switch handling — nothing else. Doesn't parse files,
 * doesn't touch Binder directly, doesn't know how to capture logs. Every one of
 * those concerns is delegated to a single-responsibility collaborator.
 */
class UsbLogSessionOrchestrator(
    private val repository: SessionRepository,
    private val connector: ProfileServiceConnector,
    private val switchGate: UserSwitchGate,
    private val sink: FileLogSink,
    private val captureEngine: LogCaptureEngine,
    private var currentForegroundUser: Int,
    private val staleThresholdMs: Long = 10 * 60 * 1000,
) {
    @Volatile private var session: SessionInfo? = null
    private val switchEpoch = AtomicInteger(0)

    fun start() {
        recoverPersistedSession()

        switchGate.setBeforeSwitchHandler { _, done ->
            switchEpoch.incrementAndGet()
            sink.detach()
            done()
        }
        switchGate.setSwitchCompleteHandler { newUserId ->
            currentForegroundUser = newUserId
            resumeOnCurrentProfile()
        }
    }

    fun onRealInsert(uuid: String) {
        connector.withHandoff(currentForegroundUser) { handoff ->
            val packages = handoff.readSessionConfig()
            if (packages.isEmpty()) return@withHandoff // no marker file, not a logging stick

            val newSession = SessionInfo(
                sessionId = UUID.randomUUID().toString(),
                fileName = "capture_${System.currentTimeMillis()}.log",
                volumeUuid = uuid,
                targetPackages = packages,
                lastWriteAtMs = System.currentTimeMillis(),
            )
            session = newSession
            repository.save(newSession)
            captureEngine.start(packages, sink)
            resumeOnCurrentProfile()
        }
    }

    fun onRealRemoval(uuid: String) {
        val active = session ?: return
        if (active.volumeUuid != uuid) return

        captureEngine.stop()
        sink.detach()
        repository.save(active.copy(lastWriteAtMs = System.currentTimeMillis(), ended = true))
        session = null // terminal — a later reinsert starts a new session, see README
    }

    private fun resumeOnCurrentProfile() {
        val active = session ?: return
        val epoch = switchEpoch.get()
        connector.withHandoff(currentForegroundUser) { handoff ->
            val pfd = handoff.openForAppend(active.fileName)
            if (epoch != switchEpoch.get()) {
                pfd.close() // a later switch already superseded this request
                return@withHandoff
            }
            sink.attach(pfd)
        }
    }

    private fun recoverPersistedSession() {
        val persisted = repository.load() ?: return
        if (persisted.ended || repository.isStale(persisted, staleThresholdMs)) {
            repository.clear()
            return
        }
        session = persisted
        captureEngine.start(persisted.targetPackages, sink)
        resumeOnCurrentProfile()
    }
}
