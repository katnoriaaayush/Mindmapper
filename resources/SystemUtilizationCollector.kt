package com.samsung.teachingboard.telemetry.collector

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.util.Log
import com.samsung.teachingboard.telemetry.model.CpuUtilization
import com.samsung.teachingboard.telemetry.model.GpuUtilization
import com.samsung.teachingboard.telemetry.model.RamUtilization
import com.samsung.teachingboard.telemetry.model.StorageUtilization
import com.samsung.teachingboard.telemetry.model.SystemUtilizationSnapshot
import java.io.File
import java.io.RandomAccessFile

/**
 * Collects point-in-time CPU / GPU / RAM / storage utilisation snapshots.
 *
 * CPU usage needs a delta between two /proc/stat reads, so this class is
 * stateful: the first call after construction returns overallUsagePercent = 0f
 * (no prior sample to diff against). Call [collectSnapshot] periodically
 * (e.g. every 5-30s from a telemetry loop) rather than once.
 *
 * Not thread-safe — confine all calls to a single background thread/coroutine.
 */
class SystemUtilizationCollector(private val context: Context) {

    private var lastCpuSample: CpuStatSample? = null

    // Known vendor sysfs nodes exposing GPU busy percentage, checked in order.
    // Path and availability vary by SoC/kernel; some require system-level
    // read permissions that a platform-signed app may have but a normal
    // third-party app would not. Verify against the target device/kernel.
    private val candidateGpuPaths = listOf(
        "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", // Adreno
        "/sys/class/misc/mali0/device/utilization",     // Mali, some vendor BSPs
        "/sys/kernel/gpu/gpu_busy"                       // Exynos, varies by BSP
    )

    fun collectSnapshot(): SystemUtilizationSnapshot {
        return SystemUtilizationSnapshot(
            timestampMillis = System.currentTimeMillis(),
            cpu = readCpuUtilization(),
            gpu = readGpuUtilization(),
            ram = readRamUtilization(),
            storage = readStorageUtilization()
        )
    }

    // ---------------------------------------------------------------------
    // CPU
    // ---------------------------------------------------------------------

    private data class CpuStatSample(
        val total: Long,
        val idle: Long,
        val perCoreTotal: LongArray,
        val perCoreIdle: LongArray
    )

    private fun readCpuUtilization(): CpuUtilization {
        val current = parseProcStat() ?: return CpuUtilization(0f, emptyList(), 0)
        val previous = lastCpuSample
        lastCpuSample = current

        if (previous == null) {
            return CpuUtilization(0f, List(current.perCoreTotal.size) { 0f }, current.perCoreTotal.size)
        }

        val overall = usagePercentFromDelta(
            totalDelta = current.total - previous.total,
            idleDelta = current.idle - previous.idle
        )

        val perCore = current.perCoreTotal.indices.map { i ->
            if (i >= previous.perCoreTotal.size) {
                0f
            } else {
                usagePercentFromDelta(
                    totalDelta = current.perCoreTotal[i] - previous.perCoreTotal[i],
                    idleDelta = current.perCoreIdle[i] - previous.perCoreIdle[i]
                )
            }
        }

        return CpuUtilization(
            overallUsagePercent = overall,
            perCoreUsagePercent = perCore,
            coreCount = current.perCoreTotal.size
        )
    }

    private fun usagePercentFromDelta(totalDelta: Long, idleDelta: Long): Float {
        if (totalDelta <= 0) return 0f
        val usedDelta = (totalDelta - idleDelta).coerceAtLeast(0)
        return (usedDelta.toFloat() / totalDelta.toFloat()) * 100f
    }

    /**
     * Parses /proc/stat. Fields per line (after the "cpu"/"cpuN" label) are:
     * user nice system idle iowait irq softirq steal guest guest_nice.
     * total = sum of all fields; idle = idle + iowait.
     * Note: guest/guest_nice are already counted within user/nice on most
     * kernels, so total is a close approximation rather than exact — fine
     * for a percentage-of-busy-time metric.
     */
    private fun parseProcStat(): CpuStatSample? {
        return try {
            val lines = File("/proc/stat").readLines()
            var total = 0L
            var idle = 0L
            val coreTotals = mutableListOf<Long>()
            val coreIdles = mutableListOf<Long>()

            for (line in lines) {
                if (!line.startsWith("cpu")) continue
                val fields = line.trim().split(Regex("\\s+"))
                val label = fields[0]
                val values = fields.drop(1).mapNotNull { it.toLongOrNull() }
                if (values.size < 4) continue

                val lineTotal = values.sum()
                val lineIdle = values[3] + (values.getOrNull(4) ?: 0L) // idle + iowait

                if (label == "cpu") {
                    total = lineTotal
                    idle = lineIdle
                } else {
                    coreTotals.add(lineTotal)
                    coreIdles.add(lineIdle)
                }
            }

            CpuStatSample(
                total = total,
                idle = idle,
                perCoreTotal = coreTotals.toLongArray(),
                perCoreIdle = coreIdles.toLongArray()
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read /proc/stat", e)
            null
        }
    }

    // ---------------------------------------------------------------------
    // GPU (best-effort)
    // ---------------------------------------------------------------------

    private fun readGpuUtilization(): GpuUtilization {
        for (path in candidateGpuPaths) {
            val file = File(path)
            if (!file.exists() || !file.canRead()) continue
            val raw = runCatching { RandomAccessFile(file, "r").use { it.readLine() } }.getOrNull()
                ?: continue
            val percent = raw.trim().trimEnd('%').toFloatOrNull() ?: continue
            return GpuUtilization(
                usagePercent = percent.coerceIn(0f, 100f),
                isAvailable = true,
                sourcePath = path
            )
        }
        return GpuUtilization(usagePercent = null, isAvailable = false)
    }

    // ---------------------------------------------------------------------
    // RAM
    // ---------------------------------------------------------------------

    private fun readRamUtilization(): RamUtilization {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)

        val used = info.totalMem - info.availMem
        val usagePercent = if (info.totalMem > 0) {
            (used.toFloat() / info.totalMem.toFloat()) * 100f
        } else {
            0f
        }

        return RamUtilization(
            totalBytes = info.totalMem,
            availableBytes = info.availMem,
            usedBytes = used,
            usagePercent = usagePercent,
            isLowMemory = info.lowMemory,
            lowMemoryThresholdBytes = info.threshold
        )
    }

    // ---------------------------------------------------------------------
    // Storage
    // ---------------------------------------------------------------------

    private fun readStorageUtilization(): StorageUtilization {
        val stat = StatFs(Environment.getDataDirectory().path)
        val total = stat.totalBytes
        val free = stat.availableBytes
        val used = total - free
        val usagePercent = if (total > 0) (used.toFloat() / total.toFloat()) * 100f else 0f

        return StorageUtilization(
            totalBytes = total,
            freeBytes = free,
            usedBytes = used,
            usagePercent = usagePercent
        )
    }

    companion object {
        private const val TAG = "SystemUtilCollector"
    }
}
