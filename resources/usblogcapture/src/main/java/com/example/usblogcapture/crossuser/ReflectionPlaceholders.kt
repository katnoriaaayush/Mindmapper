package com.example.usblogcapture.crossuser

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.UserHandle

/**
 * Placeholders for the two hidden-API calls this module needs but doesn't reimplement,
 * because you already have working versions of both. Delete this file once
 * [CrossUserUsbReceiver] and [ProfileServiceConnector] point at your real ones.
 */
object CrossUserBroadcasts {
    /** Your existing reflection-based registerReceiverAsUser-across-all-profiles call. */
    fun registerAcrossAllUsers(context: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
        TODO("wire to your existing cross-user receiver registration")
    }
}

object CrossUserBind {
    /** Your existing reflection-based bindServiceAsUser call. */
    fun bindServiceAsUser(
        context: Context,
        intent: Intent,
        connection: ServiceConnection,
        user: UserHandle,
    ) {
        TODO("wire to your existing bindServiceAsUser reflection call")
    }
}
