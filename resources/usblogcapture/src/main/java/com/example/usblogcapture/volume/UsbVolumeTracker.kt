package com.example.usblogcapture.volume

import android.os.storage.StorageManager

/**
 * Single source of truth for "is this physical volume actually still present."
 *
 * A profile switch remounts *visibility* per foreground user; it never changes
 * whether the underlying StorageVolume itself is present system-wide. Checking
 * [StorageManager.getStorageVolumes] fresh on every event is what lets this class
 * tell a real removal apart from a switch-induced one, without needing to reason
 * about switch timing at all.
 */
class UsbVolumeTracker(private val storageManager: StorageManager) {

    private val insertedUuids = mutableSetOf<String>()

    @Synchronized
    fun onMount(uuid: String): UsbMountEvent =
        if (insertedUuids.add(uuid)) UsbMountEvent.REAL_INSERT else UsbMountEvent.VISIBILITY_ONLY

    @Synchronized
    fun onUnmount(uuid: String): UsbUnmountEvent {
        val stillKnown = storageManager.storageVolumes.any { it.uuid == uuid }
        if (stillKnown) return UsbUnmountEvent.SWITCH_INDUCED
        insertedUuids.remove(uuid)
        return UsbUnmountEvent.REAL_REMOVAL
    }

    @Synchronized
    fun isTracked(uuid: String): Boolean = uuid in insertedUuids
}
