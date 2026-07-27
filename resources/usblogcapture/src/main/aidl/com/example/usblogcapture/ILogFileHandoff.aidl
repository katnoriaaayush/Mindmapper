package com.example.usblogcapture;

interface ILogFileHandoff {
    // Parses log.sinfo at the volume root visible in this profile's namespace.
    // Empty list means no marker file is present — not a logging stick.
    List<String> readSessionConfig();

    // Opens (creating if absent) the named file in append mode and returns a
    // ParcelFileDescriptor. Binder transfer dup()s the fd, so the caller's copy
    // stays valid after this service is torn down.
    ParcelFileDescriptor openForAppend(String fileName);
}
