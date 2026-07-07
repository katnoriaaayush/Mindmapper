package com.samsung.teachingboard.telemetry.collector

import android.util.Log
import com.samsung.teachingboard.telemetry.model.NetworkPingSnapshot
import java.util.concurrent.TimeUnit

/**
 * Collects packet loss and RTT latency by shelling out to the device's
 * built-in ping binary (ICMP echo per RFC 792) and parsing its summary
 * output, rather than opening a raw socket directly.
 *
 * Third-party app UIDs generally can't create raw ICMP sockets on Android.
 * Eligibility for the "ping" socket type (SOCK_DGRAM + IPPROTO_ICMP) is
 * gated by the kernel's net.ipv4.ping_group_range sysctl, which varies by
 * OEM/kernel config. A platform-signed system app is far more likely to
 * fall inside that range than a normal app, but this isn't guaranteed on
 * every device/build — treat exec or permission failures as "unreachable"
 * via [NetworkPingSnapshot.isReachable] rather than assuming no network.
 *
 * This makes a blocking call (spawns a process, waits up to
 * [timeoutSeconds]) — invoke from a background thread/coroutine, never
 * from the main thread.
 */
class NetworkPingCollector(
    private val target: String = "8.8.8.8",
    private val pingCount: Int = 10,
    private val timeoutSeconds: Long = 15L
) {

    fun collectSnapshot(): NetworkPingSnapshot {
        val rawOutput = runPing()
        return rawOutput?.let { parsePingOutput(it) } ?: unreachableSnapshot()
    }

    private fun runPing(): String? {
        return try {
            val process = ProcessBuilder("/system/bin/ping", "-c", pingCount.toString(), target)
                .redirectErrorStream(true)
                .start()

            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                Log.w(TAG, "ping to $target timed out after ${timeoutSeconds}s")
                return null
            }
            process.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to exec ping to $target", e)
            null
        }
    }

    /**
     * Parses summary lines such as:
     *   10 packets transmitted, 9 packets received, 10% packet loss
     *   rtt min/avg/max/mdev = 12.1/23.4/45.2/8.9 ms
     * Tolerates both iputils ("rtt"/"mdev") and toybox ("round-trip"/
     * "stddev") wording, since the binary varies by AOSP version/vendor.
     */
    private fun parsePingOutput(output: String): NetworkPingSnapshot {
        val statsMatch = STATS_REGEX.find(output)
        val transmitted = statsMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val received = statsMatch?.groupValues?.get(2)?.toIntOrNull() ?: 0
        val lossPercent = statsMatch?.groupValues?.get(3)?.toFloatOrNull()
            ?: if (transmitted > 0) {
                ((transmitted - received).toFloat() / transmitted.toFloat()) * 100f
            } else {
                100f
            }

        val rttMatch = RTT_REGEX.find(output)

        return NetworkPingSnapshot(
            timestampMillis = System.currentTimeMillis(),
            target = target,
            packetsTransmitted = transmitted,
            packetsReceived = received,
            packetLossPercent = lossPercent,
            isReachable = received > 0,
            rttMinMs = rttMatch?.groupValues?.get(1)?.toFloatOrNull(),
            rttAvgMs = rttMatch?.groupValues?.get(2)?.toFloatOrNull(),
            rttMaxMs = rttMatch?.groupValues?.get(3)?.toFloatOrNull(),
            rttMdevMs = rttMatch?.groupValues?.get(4)?.toFloatOrNull()
        )
    }

    private fun unreachableSnapshot(): NetworkPingSnapshot {
        return NetworkPingSnapshot(
            timestampMillis = System.currentTimeMillis(),
            target = target,
            packetsTransmitted = pingCount,
            packetsReceived = 0,
            packetLossPercent = 100f,
            isReachable = false,
            rttMinMs = null,
            rttAvgMs = null,
            rttMaxMs = null,
            rttMdevMs = null
        )
    }

    companion object {
        private const val TAG = "NetworkPingCollector"

        private val STATS_REGEX = Regex(
            """(\d+)\s+packets transmitted,\s*(\d+)\s*(?:packets)?\s*received,.*?(\d+)%\s*packet loss"""
        )
        private val RTT_REGEX = Regex(
            """(?:round-trip|rtt)\s+min/avg/max/(?:mdev|stddev)\s*=\s*([\d.]+)/([\d.]+)/([\d.]+)/([\d.]+)"""
        )
    }
}
