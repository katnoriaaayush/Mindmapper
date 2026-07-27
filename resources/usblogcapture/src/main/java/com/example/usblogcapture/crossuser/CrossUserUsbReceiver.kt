package com.example.usblogcapture.crossuser

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import com.example.usblogcapture.volume.UsbMountEvent
import com.example.usblogcapture.volume.UsbUnmountEvent
import com.example.usblogcapture.volume.UsbVolumeTracker

/**
 * Wraps the cross-user broadcast registration in one place so nothing else in the
 * codebase touches hidden APIs directly. Translates raw storage broadcasts into
 * [UsbVolumeTracker] calls and only forwards the classified, real-world-meaningful
 * outcome — callers never see raw mount/unmount noise.
 *
 * [register] delegates to your existing reflection-based cross-user registration
 * (the one already receiving mount/unmount events from every profile into the owner
 * profile) — swap [CrossUserBroadcasts] for that call.
 */
class CrossUserUsbReceiver(
    private val tracker: UsbVolumeTracker,
    private val onRealInsert: (uuid: String) -> Unit,
    private val onRealRemoval: (uuid: String) -> Unit,
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val volume = intent.getParcelableExtra<StorageVolume>(StorageManager.EXTRA_STORAGE_VOLUME)
        val uuid = volume?.uuid ?: return

        when (intent.action) {
            Intent.ACTION_MEDIA_MOUNTED -> {
                if (tracker.onMount(uuid) == UsbMountEvent.REAL_INSERT) onRealInsert(uuid)
                // VISIBILITY_ONLY is intentionally dropped: the switch-complete callback
                // (see UserSwitchGate) is what drives the handoff, not this broadcast.
            }
            Intent.ACTION_MEDIA_UNMOUNTED, Intent.ACTION_MEDIA_EJECT -> {
                if (tracker.onUnmount(uuid) == UsbUnmountEvent.REAL_REMOVAL) onRealRemoval(uuid)
            }
        }
    }

    fun register(context: Context) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addDataScheme("file")
        }
        // TODO: replace with your existing cross-user receiver registration —
        // this is the same reflection call you already have working.
        CrossUserBroadcasts.registerAcrossAllUsers(context, this, filter)
    }

    fun unregister(context: Context) {
        context.unregisterReceiver(this)
    }
}
