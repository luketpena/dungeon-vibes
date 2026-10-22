package com.dungeonvibes.data

/** Current time in epoch millis. Injected so tests can control timestamps. */
fun interface TimeSource {
    fun nowMillis(): Long
}
