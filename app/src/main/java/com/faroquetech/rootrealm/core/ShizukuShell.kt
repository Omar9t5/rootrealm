package com.faroquetech.rootrealm.core

import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Executes shell commands through Shizuku.
 *
 * This does NOT replace RootShell.
 * RootShell remains the traditional `su -c` backend.
 *
 * Shizuku API 12.2.0 still exposes newProcess(), which is used here
 * for compatibility with the current Root Realm dependency.
 */
object ShizukuShell {

    private const val DEFAULT_TIMEOUT_SECONDS = 10L

    fun isAvailable(): Boolean {
        return ShizukuManager.hasPermission()
    }

    fun execute(
        command: String,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS
    ): ShellResult {

        if (!isAvailable()) {
            return ShellResult(
                exitCode = -1,
                output = "",
                error = "Shizuku is not running or Root Realm has no Shizuku permission."
            )
        }

        return try {
            val process = Shizuku.newProcess(
                arrayOf("sh", "-c", command),
                null,
                null
            )

            val output = StringBuilder()
            val error = StringBuilder()

            val stdoutThread = Thread {
                try {
                    BufferedReader(
                        InputStreamReader(process.inputStream)
                    ).useLines { lines ->
                        lines.forEach {
                            output.appendLine(it)
                        }
                    }
                } catch (_: Exception) {
                }
            }

            val stderrThread = Thread {
                try {
                    BufferedReader(
                        InputStreamReader(process.errorStream)
                    ).useLines { lines ->
                        lines.forEach {
                            error.appendLine(it)
                        }
                    }
                } catch (_: Exception) {
                }
            }

            stdoutThread.start()
            stderrThread.start()

            val finished = process.waitFor(
                timeoutSeconds,
                TimeUnit.SECONDS
            )

            if (!finished) {
                process.destroy()
                return ShellResult(
                    exitCode = -1,
                    output = output.toString().trim(),
                    error = "Shizuku command timed out after ${timeoutSeconds}s."
                )
            }

            stdoutThread.join(1000)
            stderrThread.join(1000)

            ShellResult(
                exitCode = process.exitValue(),
                output = output.toString().trim(),
                error = error.toString().trim()
            )
        } catch (e: Exception) {
            ShellResult(
                exitCode = -1,
                output = "",
                error = e.message ?: e.javaClass.simpleName
            )
        }
    }

    fun id(): ShellResult {
        return execute("id")
    }
}
