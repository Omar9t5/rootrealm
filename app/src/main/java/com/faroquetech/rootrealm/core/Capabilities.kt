package com.faroquetech.rootrealm.core

enum class Capability {
    BASIC,

    ROOT,
    SHIZUKU,
    PRIVILEGED,
    ROOT_OR_SHIZUKU,

    ADB,
    FASTBOOT,

    ROOT_OR_ADB,

    ROOT_AND_ADB,

    ROOT_OR_FASTBOOT,

    ROOT_AND_FASTBOOT
}

object Capabilities {

    fun has(
        capability: Capability
    ): Boolean {

        return when (capability) {

            Capability.BASIC ->
                true

            Capability.ROOT ->
                RootManager.isGranted()

            Capability.SHIZUKU ->
                ShizukuManager.hasPermission()

            Capability.PRIVILEGED,
            Capability.ROOT_OR_SHIZUKU ->
                RootManager.isGranted() ||
                    ShizukuManager.hasPermission()

            Capability.ADB ->
                AdbManager.isUsbDebuggingEnabled()

            Capability.FASTBOOT ->
                FastbootManager.isFastbootAvailable()

            Capability.ROOT_OR_ADB ->
                RootManager.isGranted() ||
                    AdbManager.isUsbDebuggingEnabled()

            Capability.ROOT_AND_ADB ->
                RootManager.isGranted() &&
                    AdbManager.isUsbDebuggingEnabled()

            Capability.ROOT_OR_FASTBOOT ->
                RootManager.isGranted() ||
                    FastbootManager.isFastbootAvailable()

            Capability.ROOT_AND_FASTBOOT ->
                RootManager.isGranted() &&
                    FastbootManager.isFastbootAvailable()
        }
    }

    fun privilegedBackend(): PrivilegedShell.Backend {
        return PrivilegedShell.backend()
    }
}
