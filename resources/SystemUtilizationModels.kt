package com.samsung.teachingboard.telemetry.model

/**
 * Immutable snapshot of system resource utilisation at a single point in time.
 * Shape is intentionally flat so it maps directly onto a JSON telemetry payload.
 */
data class SystemUtilizationSnapshot(
    val timestampMillis: Long,
    val cpu: CpuUtilization,
    val gpu: GpuUtilization,
    val ram: RamUtilization,
    val storage: StorageUtilization
)

/**
 * CPU utilisation derived from a delta between two /proc/stat reads.
 * [perCoreUsagePercent] is empty only if per-core lines couldn't be parsed.
 */
data class CpuUtilization(
    val overallUsagePercent: Float,
    val perCoreUsagePercent: List<Float>,
    val coreCount: Int
)

/**
 * GPU utilisation is best-effort: Android has no stable public API for it.
 * [isAvailable] is false when no known vendor sysfs node could be read; in
 * that case [usagePercent] is null and callers should omit the field from
 * the upload payload rather than send a misleading 0.
 */
data class GpuUtilization(
    val usagePercent: Float?,
    val isAvailable: Boolean,
    val sourcePath: String? = null
)

/** RAM utilisation sourced from ActivityManager.MemoryInfo. */
data class RamUtilization(
    val totalBytes: Long,
    val availableBytes: Long,
    val usedBytes: Long,
    val usagePercent: Float,
    val isLowMemory: Boolean,
    val lowMemoryThresholdBytes: Long
)

/** Internal storage utilisation sourced from StatFs against the data partition. */
data class StorageUtilization(
    val totalBytes: Long,
    val freeBytes: Long,
    val usedBytes: Long,
    val usagePercent: Float
)
