package com.samsung.teachingboard.telemetry.collector

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import com.samsung.teachingboard.telemetry.model.AppUsageSession
import com.samsung.teachingboard.telemetry.model.AppUsageSnapshot
import com.samsung.teachingboard.telemetry.model.AppUsageStat
import java.util.concurrent.TimeUnit

/**
 * Collects app launch counts and per-app foreground time from the platform's
 * UsageEvents log (UsageStatsManager), rather than self-instrumenting every
 * app with lifecycle hooks — this captures any foreground app, including
 * ones you don't control the code of.
 *
 * Requires PACKAGE_USAGE_STATS, which is not a normal runtime permission.
 * On a platform-signed system app this is typically handled by:
 *   1. A privapp-permissions allowlist XML entry (auto-granted, no user
 *      interaction), or
 *   2. `adb shell appops set <pkg> GET_USAGE_STATS allow` during dev, or
 *   3. User grant via Settings > Apps > Special access > Usage access
 *      (Settings.ACTION_USAGE_ACCESS_SETTINGS) as a fallback.
 * [hasUsageAccessPermission] checks the live AppOps state rather than
 * assuming the manifest declaration alone is sufficient.
 *
 * UsageEvents includes every foreground component on the device — system
 * UI, IME, permission dialogs, etc. — not just user-facing apps. Filter
 * [AppUsageSnapshot.perApp] against your known app allow-list if you only
 * want launches of apps installed on the teaching board.
 *
 * Each call re-queries the full window fresh; it does not keep a
 * high-water mark between calls. Have the caller track the last query's
 * windowEndMillis and pass it as the next windowStartMillis if you want
 * non-overlapping incremental windows.
 */
class AppUsageCollector(private val context: Context) {

    private val usageStatsManager: UsageStatsManager by lazy {
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    }

    fun hasUsageAccessPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Queries [windowStartMillis, windowEndMillis) and builds per-app
     * launch counts + foreground time from the raw resume/pause events.
     * Defaults to the last 24 hours if no window is given. This is a
     * blocking binder call to the system service — call off the main
     * thread for large windows.
     */
    fun collectSnapshot(
        windowStartMillis: Long = System.currentTimeMillis() - DEFAULT_LOOKBACK_MILLIS,
        windowEndMillis: Long = System.currentTimeMillis()
    ): AppUsageSnapshot {
        if (!hasUsageAccessPermission()) {
            Log.w(TAG, "PACKAGE_USAGE_STATS not granted; returning empty snapshot")
            return AppUsageSnapshot(windowStartMillis, windowEndMillis, emptyList(), emptyList())
        }

        val events = usageStatsManager.queryEvents(windowStartMillis, windowEndMillis)
        val sessions = mutableListOf<AppUsageSession>()
        val openSessionStart = mutableMapOf<String, Long>() // package -> resume timestamp
        val launchCounts = mutableMapOf<String, Int>()
        val lastLaunchTs = mutableMapOf<String, Long>()
        val lastForegroundTs = mutableMapOf<String, Long>()

        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue

            when (event.eventType) {
                RESUMED_EVENT_TYPE -> {
                    // Only count as a new launch if the package wasn't
                    // already mid-session — avoids double counting when
                    // multiple activities in the same app resume in turn.
                    if (!openSessionStart.containsKey(pkg)) {
                        openSessionStart[pkg] = event.timeStamp
                        launchCounts[pkg] = (launchCounts[pkg] ?: 0) + 1
                        lastLaunchTs[pkg] = event.timeStamp
                    }
                    lastForegroundTs[pkg] = event.timeStamp
                }
                PAUSED_EVENT_TYPE -> {
                    val start = openSessionStart.remove(pkg)
                    if (start != null) {
                        sessions.add(AppUsageSession(pkg, start, event.timeStamp))
                    }
                }
            }
        }

        // Anything still resumed at window end (app still in foreground
        // when we queried) is closed at windowEndMillis so its time counts.
        for ((pkg, start) in openSessionStart) {
            sessions.add(AppUsageSession(pkg, start, windowEndMillis))
        }

        val perApp = sessions.groupBy { it.packageName }
            .map { (pkg, pkgSessions) ->
                AppUsageStat(
                    packageName = pkg,
                    launchCount = launchCounts[pkg] ?: 0,
                    totalForegroundTimeMillis = pkgSessions.sumOf { it.durationMillis },
                    lastLaunchTimestampMillis = lastLaunchTs[pkg],
                    lastForegroundTimestampMillis = lastForegroundTs[pkg]
                )
            }
            .sortedByDescending { it.totalForegroundTimeMillis }

        return AppUsageSnapshot(windowStartMillis, windowEndMillis, perApp, sessions)
    }

    companion object {
        private const val TAG = "AppUsageCollector"
        private val DEFAULT_LOOKBACK_MILLIS = TimeUnit.HOURS.toMillis(24)

        // ACTIVITY_RESUMED / ACTIVITY_PAUSED (API 29+) are the modern,
        // per-activity replacements for the deprecated MOVE_TO_FOREGROUND /
        // MOVE_TO_BACKGROUND constants. Falling back keeps this working on
        // older API levels too.
        private val RESUMED_EVENT_TYPE: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            UsageEvents.Event.ACTIVITY_RESUMED
        } else {
            @Suppress("DEPRECATION")
            UsageEvents.Event.MOVE_TO_FOREGROUND
        }

        private val PAUSED_EVENT_TYPE: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            UsageEvents.Event.ACTIVITY_PAUSED
        } else {
            @Suppress("DEPRECATION")
            UsageEvents.Event.MOVE_TO_BACKGROUND
        }
    }
}
