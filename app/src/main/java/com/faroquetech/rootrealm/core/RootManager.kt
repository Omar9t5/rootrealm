package com.faroquetech.rootrealm.core

object RootManager {

    enum class RootState {
        AVAILABLE,
        GRANTED,
        NOT_AVAILABLE,
        DENIED
    }

    fun getState(): RootState {

        if (!RootShell.isSuAvailable()) {
            return RootState.NOT_AVAILABLE
        }

        return if (RootShell.isRootGranted()) {
            RootState.GRANTED
        } else {
            RootState.DENIED
        }
    }

    fun isAvailable(): Boolean {
        return RootShell.isSuAvailable()
    }

    fun isGranted(): Boolean {
        return RootShell.isRootGranted()
    }

    fun request(): ShellResult {
        return RootShell.requestRoot()
    }

    fun run(command: String): ShellResult {
        return RootShell.execute(
            command = command,
            useRoot = true
        )
    }
}