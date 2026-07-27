package com.example.usblogcapture.crossuser

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.UserHandle
import com.example.usblogcapture.ILogFileHandoff
import com.example.usblogcapture.handoff.HandoffApi
import com.example.usblogcapture.handoff.LogFileHandoffService

/**
 * Resolves a [HandoffApi] for an arbitrary profile. When the target is the owner
 * profile itself, calls [localHandoff] directly instead of binding to itself over
 * Binder — the handoff service only ever needs to run in *other* profiles.
 */
class ProfileServiceConnector(
    private val context: Context,
    private val ownerUserId: Int,
    private val localHandoff: HandoffApi,
) {
    fun withHandoff(targetUserId: Int, action: (HandoffApi) -> Unit) {
        if (targetUserId == ownerUserId) {
            action(localHandoff)
            return
        }

        val intent = Intent(context, LogFileHandoffService::class.java)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                action(ILogFileHandoff.Stub.asInterface(binder).asApi())
                context.unbindService(this)
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }

        // TODO: replace with your existing bindServiceAsUser reflection call.
        CrossUserBind.bindServiceAsUser(context, intent, connection, UserHandle.of(targetUserId))
    }

    // Wrap RemoteException at this boundary in production — AIDL calls can throw it
    // if the bound process dies mid-call (e.g. a fast second switch).
    private fun ILogFileHandoff.asApi(): HandoffApi = object : HandoffApi {
        override fun readSessionConfig() = this@asApi.readSessionConfig()
        override fun openForAppend(fileName: String) = this@asApi.openForAppend(fileName)
    }
}
