package com.example.usblogcapture.handoff

import android.os.ParcelFileDescriptor
import com.example.usblogcapture.volume.UsbVolumeResolver
import java.io.File

/**
 * The actual file-access logic, kept separate from both the AIDL Binder plumbing
 * (LogFileHandoffService) and the in-process shortcut used when the owner profile
 * is itself foreground — both wrap this one implementation instead of duplicating it.
 */
class LogFileHandoffImpl(private val resolver: UsbVolumeResolver) : HandoffApi {

    override fun readSessionConfig(): List<String> {
        val root = resolver.resolveFirstAvailableRoot() ?: return emptyList()
        return SessionConfigParser.parse(root)
    }

    override fun openForAppend(fileName: String): ParcelFileDescriptor {
        val root = resolver.resolveFirstAvailableRoot()
            ?: error("No USB volume visible in this profile")
        return ParcelFileDescriptor.open(
            File(root, fileName),
            ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_APPEND or
                ParcelFileDescriptor.MODE_WRITE_ONLY,
        )
    }
}
