package com.samsung.teachingboard.telemetry.model

/**
 * Aggregated launch/usage stats for a single app package over a query window.
 */
data class AppUsageStat(
    val packageName: String,
    val launchCount: Int,
    val totalForegroundTimeMillis: Long,
    val lastLaunchTimestampMillis: Long?,
    val lastForegroundTimestampMillis: Long?
)

/**
 * A single resume->pause session as observed from UsageEvents. Kept for
 * callers that want raw session-level detail rather than just aggregates.
 */
data class AppUsageSession(
    val packageName: String,
    val startTimestampMillis: Long,
    val endTimestampMillis: Long
) {
    val durationMillis: Long get() = (endTimestampMillis - startTimestampMillis).coerceAtLeast(0)
}

/**
 * Full result of a usage query over [windowStartMillis, windowEndMillis]:
 * per-app aggregates plus the raw sessions they were built from.
 */
data class AppUsageSnapshot(
    val windowStartMillis: Long,
    val windowEndMillis: Long,
    val perApp: List<AppUsageStat>,
    val sessions: List<AppUsageSession>
)
