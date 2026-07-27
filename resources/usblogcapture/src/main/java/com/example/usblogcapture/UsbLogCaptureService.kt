package com.example.usblogcapture

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.storage.StorageManager
import com.example.usblogcapture.capture.FileLogSink
import com.example.usblogcapture.capture.NoOpLogCaptureEngine
import com.example.usblogcapture.crossuser.CrossUserUsbReceiver
import com.example.usblogcapture.crossuser.ProfileServiceConnector
import com.example.usblogcapture.crossuser.UserSwitchGate
import com.example.usblogcapture.handoff.LogFileHandoffImpl
import com.example.usblogcapture.session.SessionRepository
import com.example.usblogcapture.session.UsbLogSessionOrchestrator
import com.example.usblogcapture.volume.UsbVolumeTracker

/**
 * Owner-profile foreground service. Composition root only: wires the collaborators
 * together and forwards platform callbacks (IUserSwitchObserver, broadcasts). No
 * USB, session, or capture logic lives here directly — see UsbLogSessionOrchestrator.
 */
class UsbLogCaptureService : Service() {

    private val switchGate = UserSwitchGate()
    private lateinit var tracker: UsbVolumeTracker
    private lateinit var receiver: CrossUserUsbReceiver
    private lateinit var orchestrator: UsbLogSessionOrchestrator

    override fun onCreate() {
        super.onCreate()

        val storageManager = getSystemService(StorageManager::class.java)
        tracker = UsbVolumeTracker(storageManager)

        val localHandoff = LogFileHandoffImpl(com.example.usblogcapture.volume.UsbVolumeResolver(storageManager))
        val connector = ProfileServiceConnector(this, OWNER_USER_ID, localHandoff)

        orchestrator = UsbLogSessionOrchestrator(
            repository = SessionRepository(this),
            connector = connector,
            switchGate = switchGate,
            sink = FileLogSink(),
            // Swap point: replace with your existing log capture mechanism.
            // Nothing else in this module needs to change.
            captureEngine = NoOpLogCaptureEngine(),
            currentForegroundUser = resolveCurrentForegroundUserId(),
        )
        orchestrator.start()

        receiver = CrossUserUsbReceiver(
            tracker = tracker,
            onRealInsert = { uuid -> orchestrator.onRealInsert(uuid) },
            onRealRemoval = { uuid -> orchestrator.onRealRemoval(uuid) },
        )
        receiver.register(this)

        // Wire your existing IUserSwitchObserver registration to call:
        //   switchGate.dispatchBeforeSwitch(newUserId) { reply.sendResult(null) }
        //     from onUserSwitching(newUserId, reply)
        //   switchGate.dispatchSwitchComplete(newUserId)
        //     from onUserSwitchComplete(newUserId)
    }

    override fun onDestroy() {
        receiver.unregister(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = null

    private fun resolveCurrentForegroundUserId(): Int {
        // Replace with whatever you already use to read ActivityManager.getCurrentUser() —
        // the same access your IUserSwitchObserver registration already depends on.
        TODO("wire to your existing current-user resolution")
    }

    private companion object {
        const val OWNER_USER_ID = 0 // the system/owner profile is always user 0
    }
}
