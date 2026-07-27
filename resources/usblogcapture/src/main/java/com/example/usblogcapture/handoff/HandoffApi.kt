package com.example.usblogcapture.handoff

import android.os.ParcelFileDescriptor

/**
 * Plain Kotlin mirror of [com.example.usblogcapture.ILogFileHandoff], implemented
 * directly (no Binder) when the target profile is the owner itself, and adapted
 * from the AIDL stub otherwise. Callers depend only on this — never on the AIDL
 * type — so the owner-vs-remote distinction stays entirely inside
 * [com.example.usblogcapture.crossuser.ProfileServiceConnector].
 */
interface HandoffApi {
    fun readSessionConfig(): List<String>
    fun openForAppend(fileName: String): ParcelFileDescriptor
}
