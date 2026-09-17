package com.faroquetech.rootrealm.core

object FastbootManager {

    enum class FastbootState {
        AVAILABLE,
        NOT_AVAILABLE
    }

    fun getState(): FastbootState {

        return if (isFastbootAvailable()) {
            FastbootState.AVAILABLE
        } else {
            FastbootState.NOT_AVAILABLE
        }
    }

    fun isFastbootAvailable(): Boolean {

        val paths = arrayOf(
            "/system/bin/fastboot",
            "/system/xbin/fastboot",
            "/data/adb/fastboot"
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
                    "command -v fastboot",
                    useRoot = false
                )

            result.success &&
                    result.output.isNotBlank()

        } catch (_: Exception) {
            false
        }
    }
}