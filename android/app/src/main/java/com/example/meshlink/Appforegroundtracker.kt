package com.example.meshlink


object AppForegroundTracker {

    @Volatile
    private var foreground: Boolean = false

    fun setForeground(isForeground: Boolean) {
        foreground = isForeground
    }

    fun isInForeground(): Boolean = foreground
}