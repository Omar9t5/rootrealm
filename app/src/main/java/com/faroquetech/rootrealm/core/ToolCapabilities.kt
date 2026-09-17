package com.faroquetech.rootrealm.core

object ToolCapabilities {

    fun requiredFor(
        toolName: String
    ): Capability {

        return when (
            toolName
                .replace("\n", " ")
                .trim()
        ) {

            "Device Info" ->
                Capability.BASIC

            "ADB Tools" ->
                Capability.ADB

            "Backup & Restore" ->
                Capability.ROOT_OR_ADB

            "Debloater" ->
                Capability.ROOT_OR_ADB

            "Bootloader & Fastboot" ->
                Capability.ROOT_OR_FASTBOOT

            "Root Manager" ->
                Capability.ROOT

            "Integrity Checker" ->
                Capability.BASIC

            "CPU / GPU Manager" ->
                Capability.ROOT

            "RAM Manager" ->
                Capability.ROOT

            "Battery Manager" ->
                Capability.BASIC

            "Kernel Manager" ->
                Capability.ROOT_OR_FASTBOOT

            "Module Manager" ->
                Capability.ROOT

            "APK Tools" ->
                Capability.BASIC

            "System Tools" ->
                Capability.ROOT_OR_ADB

            else ->
                Capability.BASIC
        }
    }

    fun isAvailable(
        toolName: String
    ): Boolean {

        return Capabilities.has(
            requiredFor(toolName)
        )
    }
}