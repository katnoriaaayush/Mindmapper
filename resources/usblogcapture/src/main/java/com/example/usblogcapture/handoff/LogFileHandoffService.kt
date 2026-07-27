package com.example.usblogcapture.handoff

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.storage.StorageManager
import com.example.usblogcapture.ILogFileHandoff
import com.example.usblogcapture.volume.UsbVolumeResolver

/**
 * Runs transiently in whichever profile is currently foreground. Deliberately thin —
 * stateless beyond [impl], and safe to let the system tear down between calls, since
 * ParcelFileDescriptor transfer over Binder dups the fd before this service exits.
 */
class LogFileHandoffService : Service() {

    private val impl by lazy {
        LogFileHandoffImpl(UsbVolumeResolver(getSystemService(StorageManager::class.java)))
    }

    private val binder = object : ILogFileHandoff.Stub() {
        override fun readSessionConfig(): List<String> = impl.readSessionConfig()
        override fun openForAppend(fileName: String) = impl.openForAppend(fileName)
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onUnbind(intent: Intent): Boolean = true
}
