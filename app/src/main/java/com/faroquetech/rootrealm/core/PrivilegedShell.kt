package com.faroquetech.rootrealm.core

/**
 * Unified privileged execution layer.
 *
 * Priority:
 *   1. Traditional root through RootShell
 *   2. Shizuku through ShizukuShell
 *
 * Existing RootShell/RootManager code is not modified.
 */
object PrivilegedShell {

    enum class Backend {
        ROOT,
        SHIZUKU,
        NONE
    }

    fun backend(): Backend {
        return when {
            RootManager.isGranted() -> Backend.ROOT
            ShizukuManager.hasPermission() -> Backend.SHIZUKU
            else -> Backend.NONE
        }
    }

    fun isAvailable(): Boolean {
        return backend() != Backend.NONE
    }

    fun execute(
        command: String,
        timeoutSeconds: Long = 10L
    ): ShellResult {

        return when (backend()) {

            Backend.ROOT ->
                RootShell.execute(
                    command = command,
                    useRoot = true,
                    timeoutSeconds = timeoutSeconds
                )

            Backend.SHIZUKU ->
                ShizukuShell.execute(
                    command = command,
                    timeoutSeconds = timeoutSeconds
                )

            Backend.NONE ->
                ShellResult(
                    exitCode = -1,
                    output = "",
                    error = "Neither root nor Shizuku is available."
                )
        }
    }
}
