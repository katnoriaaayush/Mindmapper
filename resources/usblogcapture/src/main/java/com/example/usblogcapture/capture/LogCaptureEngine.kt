package com.example.usblogcapture.capture

/**
 * Everything your existing log capture mechanism needs to implement to plug into
 * this subsystem. It never sees USB volumes, profiles, switches, or session
 * lifecycle — just a package filter to start with, and a sink to write bytes to.
 * That's the entire modularity boundary: replace [NoOpLogCaptureEngine] with a real
 * implementation and nothing else in this module changes.
 */
interface LogCaptureEngine {
    fun start(targetPackages: List<String>, sink: LogSink)
    fun stop()
}

/** Minimal write surface — deliberately not tied to files, streams, or fds. */
interface LogSink {
    fun write(bytes: ByteArray)
}
