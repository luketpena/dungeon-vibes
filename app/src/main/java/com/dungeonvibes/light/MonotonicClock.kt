package com.dungeonvibes.light

/**
 * Milliseconds that only ever move forward (unlike wall-clock time, which can jump when the phone
 * syncs its clock). Injected so effect timing can run on virtual time in tests.
 */
fun interface MonotonicClock {
    fun nowMs(): Long
}
