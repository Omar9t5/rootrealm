package com.faroquetech.rootrealm.core

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

data class ShellResult(
    val exitCode: Int,
    val output: String,
    val error: String
) {
    val success: Boolean
        get() = exitCode == 0
}

object RootShell {

    private const val DEFAULT_TIMEOUT_SECONDS = 10L

    fun isSuAvailable(): Boolean {

        val paths = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/data/adb/magisk/su"
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
            val process = Runtime.getRuntime().exec(
                arrayOf("sh", "-c", "command -v su")
            )

            process.waitFor(
                DEFAULT_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            )

            process.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    fun requestRoot(): ShellResult {
        return execute(
            "id",
            useRoot = true
        )
    }

    fun isRootGranted(): Boolean {

        val result = execute(
            "id",
            useRoot = true
        )

        return result.success &&
                (
                    result.output.contains("uid=0") ||
                    result.output.contains("euid=0")
                )
    }

    fun execute(
        command: String,
        useRoot: Boolean = false,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS
    ): ShellResult {

        return try {

            val shellCommand =
                if (useRoot) {
                    arrayOf(
                        "su",
                        "-c",
                        command
                    )
                } else {
                    arrayOf(
                        "sh",
                        "-c",
                        command
                    )
                }

            val process =
                Runtime.getRuntime().exec(shellCommand)

            val outputBuilder =
                StringBuilder()

            val errorBuilder =
                StringBuilder()

            val stdout =
                BufferedReader(
                    InputStreamReader(
                        process.inputStream
                    )
                )

            val stderr =
                BufferedReader(
                    InputStreamReader(
                        process.errorStream
                    )
                )

            val outputThread =
                Thread {
                    try {
                        stdout.forEachLine {
                            outputBuilder
                                .append(it)
                                .append('\n')
                        }
                    } catch (_: Exception) {
                    }
                }

            val errorThread =
                Thread {
                    try {
                        stderr.forEachLine {
                            errorBuilder
                                .append(it)
                                .append('\n')
                        }
                    } catch (_: Exception) {
                    }
                }

            outputThread.start()
            errorThread.start()

            val finished =
                process.waitFor(
                    timeoutSeconds,
                    TimeUnit.SECONDS
                )

            if (!finished) {

                process.destroyForcibly()

                return ShellResult(
                    exitCode = -1,
                    output = outputBuilder.toString().trim(),
                    error = "Command timed out"
                )
            }

            outputThread.join(1000)
            errorThread.join(1000)

            ShellResult(
                exitCode = process.exitValue(),
                output = outputBuilder.toString().trim(),
                error = errorBuilder.toString().trim()
            )

        } catch (e: Exception) {

            ShellResult(
                exitCode = -1,
                output = "",
                error = e.message ?: "Unknown shell error"
            )
        }
    }
}