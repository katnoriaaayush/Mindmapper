package com.example.usblogcapture.capture

/**
 * Placeholder so this module compiles and runs standalone before your real capture
 * mechanism is wired in. Replace with your implementation in UsbLogCaptureService —
 * that's the only line that needs to change.
 */
class NoOpLogCaptureEngine : LogCaptureEngine {
    override fun start(targetPackages: List<String>, sink: LogSink) = Unit
    override fun stop() = Unit
}
