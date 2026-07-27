package com.example.usblogcapture.session

data class SessionInfo(
    val sessionId: String,
    val fileName: String,
    val volumeUuid: String,
    val targetPackages: List<String>,
    val lastWriteAtMs: Long,
    val ended: Boolean = false,
)
