package ru.ashcrm.app.web

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The page compresses or uploads media (docs/ANDROID_CONTRACT.md §7.6, since 1.13): `{t: "busy", on: true}` every 30 s
 * while it lasts, `on: false` when done. Without a repeat for [timeoutMs] the page is taken as not busy any more (it
 * crashed, reloaded, its renderer died). Only the logic: the activity keeps the screen on and the WebView running.
 */
class Busy(private val timeoutMs: Long = TIMEOUT_MS) {
    private var lastOn: Long? = null

    /** A message of the page at [now]. */
    fun message(on: Boolean, now: Long) {
        lastOn = if (on) now else null
    }

    fun active(now: Long): Boolean = lastOn?.let { now - it < timeoutMs } == true

    /** When to look again: the moment the busy time runs out without a repeat; null — not busy. */
    fun expiresAt(): Long? = lastOn?.plus(timeoutMs)

    /** The WebView was made anew: its page is not the one that was busy. */
    fun reset() {
        lastOn = null
    }

    companion object {
        const val TIMEOUT_MS = 90_000L
    }
}

/** `on` of `{t: "busy", on: …}`: anything but a JSON `true` is «not busy». */
fun busyOn(msg: JsonObject): Boolean = runCatching { msg["on"]?.jsonPrimitive?.booleanOrNull }.getOrNull() == true
