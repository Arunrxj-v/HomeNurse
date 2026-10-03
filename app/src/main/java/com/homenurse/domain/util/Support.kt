package com.homenurse.domain.util

/** Injectable clock so tests can be deterministic. */
interface Clock {
    fun now(): Long
}

object SystemClock : Clock {
    override fun now(): Long = System.currentTimeMillis()
}

/** Injectable id source (UUIDs generated via the security layer). */
interface IdGenerator {
    fun newId(): String
}
