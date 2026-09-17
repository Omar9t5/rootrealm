package com.faroquetech.rootrealm.core

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Shizuku privilege/status backend.
 *
 * This is deliberately separate from RootManager.
 * Root support remains handled by RootShell/RootManager.
 */
object ShizukuManager {

    const val REQUEST_PERMISSION_CODE = 2401

    fun isRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }
    }

    fun hasPermission(): Boolean {
        return try {
            isRunning() &&
                Shizuku.checkSelfPermission() ==
                PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    fun isAvailable(): Boolean {
        return isRunning()
    }

    fun requestPermission(): Boolean {
        return try {
            if (!isRunning() || hasPermission()) {
                false
            } else {
                Shizuku.requestPermission(REQUEST_PERMISSION_CODE)
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
