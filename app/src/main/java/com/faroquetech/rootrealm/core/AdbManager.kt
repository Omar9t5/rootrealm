package com.faroquetech.rootrealm.core

import android.os.Build

object AdbManager {

    enum class AdbState {
        AVAILABLE,
        NOT_AVAILABLE
    }

    fun getState(): AdbState {

        return if (isAdbAvailable()) {
            AdbState.AVAILABLE
        } else {
            AdbState.NOT_AVAILABLE
        }
    }

    fun isAdbAvailable(): Boolean {

        val paths = arrayOf(
            "/system/bin/adb",
            "/system/xbin/adb",
            "/data/adb/adb"
        )

        for (path in paths) {
            try {
                if (java.io.File(path).exists()) {
                    return true
                }
            } catch (_: Exception) {
            }
        }

        return try {

            val result =
                RootShell.execute(
                    "command -v adb",
                    useRoot = false
                )

            result.success &&
                    result.output.isNotBlank()

        } catch (_: Exception) {
            false
        }
    }

    fun isUsbDebuggingEnabled(): Boolean {

        return try {

            val result =
                RootShell.execute(
                    "settings get global adb_enabled",
                    useRoot = false
                )

            result.output.trim() == "1"

        } catch (_: Exception) {
            false
        }
    }

    fun getDeviceInfo(): String {

        return buildString {

            append("Manufacturer: ")
            append(Build.MANUFACTURER)
            append('\n')

            append("Model: ")
            append(Build.MODEL)
            append('\n')

            append("Android: ")
            append(Build.VERSION.RELEASE)
            append('\n')

            append("ADB Debugging: ")
            append(
                if (isUsbDebuggingEnabled()) {
                    "Enabled"
                } else {
                    "Disabled"
                }
            )
        }
    }
}