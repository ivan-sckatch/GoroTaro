package ru.gorotaro.domain

import java.time.Duration

/** Rolling interval: fourteen complete days after the last saved general reading. */
object GeneralCooldown {
    val durationMillis: Long = Duration.ofDays(14).toMillis()
    fun availableAt(createdAt: Long): Long = Math.addExact(createdAt, durationMillis)
    fun active(createdAt: Long, now: Long): Boolean = now < availableAt(createdAt)
}
