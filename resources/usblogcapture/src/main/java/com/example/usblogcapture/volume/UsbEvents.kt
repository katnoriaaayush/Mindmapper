package com.example.usblogcapture.volume

/** Result of classifying a mount broadcast against what we already know is inserted. */
enum class UsbMountEvent {
    REAL_INSERT,      // genuinely new physical volume
    VISIBILITY_ONLY,  // an already-known volume just became visible to a new foreground profile
}

/** Result of classifying an unmount broadcast against live StorageManager state. */
enum class UsbUnmountEvent {
    REAL_REMOVAL,     // volume is gone system-wide
    SWITCH_INDUCED,   // volume still exists elsewhere; this profile just lost visibility
}
