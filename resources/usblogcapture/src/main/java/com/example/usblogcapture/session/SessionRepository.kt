package com.example.usblogcapture.session

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists [SessionInfo] in the owner profile's own storage — not on the USB volume,
 * which may not be reliably accessible when this is read. This is what lets a
 * crashed/restarted orchestrator tell "resume this session" apart from "start fresh."
 */
class SessionRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(session: SessionInfo) {
        prefs.edit {
            putString(KEY_SESSION, JSONObject().apply {
                put("sessionId", session.sessionId)
                put("fileName", session.fileName)
                put("volumeUuid", session.volumeUuid)
                put("targetPackages", JSONArray(session.targetPackages))
                put("lastWriteAtMs", session.lastWriteAtMs)
                put("ended", session.ended)
            }.toString())
        }
    }

    fun load(): SessionInfo? {
        val raw = prefs.getString(KEY_SESSION, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            SessionInfo(
                sessionId = json.getString("sessionId"),
                fileName = json.getString("fileName"),
                volumeUuid = json.getString("volumeUuid"),
                targetPackages = json.getJSONArray("targetPackages").let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                },
                lastWriteAtMs = json.getLong("lastWriteAtMs"),
                ended = json.optBoolean("ended", false),
            )
        }.getOrNull()
    }

    fun clear() {
        prefs.edit { remove(KEY_SESSION) }
    }

    fun isStale(session: SessionInfo, thresholdMs: Long): Boolean =
        System.currentTimeMillis() - session.lastWriteAtMs > thresholdMs

    private companion object {
        const val PREFS_NAME = "usb_log_session"
        const val KEY_SESSION = "session"
    }
}
