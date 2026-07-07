package com.samsung.teachingboard.telemetry.model

/**
 * Result of a single ping-based reachability probe against [target].
 * Sourced by shelling out to the device's ping binary and parsing its
 * summary output, rather than reimplementing ICMP.
 */
data class NetworkPingSnapshot(
    val timestampMillis: Long,
    val target: String,
    val packetsTransmitted: Int,
    val packetsReceived: Int,
    val packetLossPercent: Float,
    val isReachable: Boolean,
    val rttMinMs: Float?,
    val rttAvgMs: Float?,
    val rttMaxMs: Float?,
    val rttMdevMs: Float?
)
