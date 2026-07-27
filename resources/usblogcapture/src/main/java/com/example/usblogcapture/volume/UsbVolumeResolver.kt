package com.example.usblogcapture.volume

import android.os.storage.StorageManager
import java.io.File

/**
 * Resolves the on-disk root for a tracked volume *in the current process's namespace*.
 *
 * Must only be called from a process that currently has the volume mounted — i.e. the
 * foreground profile. That constraint is exactly why file access is routed through the
 * per-profile handoff service rather than resolved once in the owner profile.
 */
class UsbVolumeResolver(private val storageManager: StorageManager) {

    fun resolveRoot(uuid: String): File? =
        storageManager.storageVolumes.firstOrNull { it.uuid == uuid }?.directory

    /** Convenience for callers that only ever track a single active removable volume. */
    fun resolveFirstAvailableRoot(): File? =
        storageManager.storageVolumes.firstOrNull { !it.isPrimary }?.directory
}
