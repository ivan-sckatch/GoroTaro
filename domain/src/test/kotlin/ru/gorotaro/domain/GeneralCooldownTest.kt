package ru.gorotaro.domain

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class GeneralCooldownTest {
    private val created = Instant.parse("2026-10-02T12:00:00Z").toEpochMilli()
    @Test fun unlocksAfterFourteenCompleteDaysAtExactMillisecond() {
        val next = Instant.parse("2026-10-16T12:00:00Z").toEpochMilli()
        assertEquals(next, GeneralCooldown.availableAt(created))
        assertTrue(GeneralCooldown.active(created,next-1))
        assertFalse(GeneralCooldown.active(created,next))
        assertFalse(GeneralCooldown.active(created,next+1))
    }
    @Test fun midnightAndCalendarWeekDoNotResetCooldown() {
        assertTrue(GeneralCooldown.active(created,Instant.parse("2026-10-02T21:00:00Z").toEpochMilli()))
        assertTrue(GeneralCooldown.active(created,Instant.parse("2026-10-12T00:00:00Z").toEpochMilli()))
    }
    @Test fun movingClockBackKeepsLastReadingLocked() {
        assertTrue(GeneralCooldown.active(created,created-86400000))
        assertEquals(GeneralCooldown.availableAt(created),GeneralCooldown.availableAt(created))
    }
    @Test fun timezoneAndDaylightSavingDoNotChangeElapsedInterval() {
        val start = Instant.parse("2026-03-01T15:00:00Z")
        val next = Instant.ofEpochMilli(GeneralCooldown.availableAt(start.toEpochMilli()))
        assertEquals(336,java.time.Duration.between(start,next).toHours())
        val newYork = ZoneId.of("America/New_York")
        assertEquals(10,start.atZone(newYork).hour)
        assertEquals(11,next.atZone(newYork).hour)
    }
}
