package com.example.usblogcapture.handoff

import java.io.File

object SessionConfigParser {
    private const val MARKER_FILE_NAME = "log.sinfo"

    /** Empty result means "no marker file" — the caller treats that as no session to start. */
    fun parse(volumeRoot: File): List<String> {
        val marker = File(volumeRoot, MARKER_FILE_NAME)
        if (!marker.exists()) return emptyList()
        return marker.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
    }
}
