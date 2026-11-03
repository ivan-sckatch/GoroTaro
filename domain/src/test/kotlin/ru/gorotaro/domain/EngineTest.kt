package ru.gorotaro.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.util.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

class EngineTest {
    private val cards = (0..21).map { Card("major_%02d".format(it), "Карта $it", "x", .6f, "Смысл $it", "Шаг $it", "Вопрос $it") }
    private fun engine(at: String = "2026-10-02T12:00:00Z", zone: String = "Europe/Moscow", seed: Long = 10): DrawEngine {
        val source = Random(seed)
        return DrawEngine(RandomSource { source.nextInt(it) }, Clock.fixed(Instant.parse(at), ZoneOffset.UTC), ZoneProvider { ZoneId.of(zone) })
    }
    @Test fun drawsHaveNoRepeatsAndKeepPositionOrder() {
        val source = Random(42); val e = DrawEngine(RandomSource { source.nextInt(it) }, Clock.systemUTC(), ZoneProvider { ZoneOffset.UTC })
        listOf(1, 3, 7).forEach { size -> repeat(1000) { val draw = e.draw(cards, size); assertEquals(size, draw.distinct().size); assertTrue(cards.containsAll(draw)) } }
        val first = DrawEngine(RandomSource { 0 }, Clock.systemUTC(), ZoneProvider { ZoneOffset.UTC }).draw(cards.reversed(), 3)
        assertEquals(cards.take(3), first)
    }
    @Test fun shuffledOrderChangesKey() { assertNotEquals(comboKey(Spread.WEEK, cards.take(3)), comboKey(Spread.WEEK, cards.take(3).reversed())) }
    @Test fun invalidConfigurationFails() {
        listOf(0, 23).forEach { count -> assertThrows(IllegalArgumentException::class.java) { engine().draw(cards, count) } }
        assertThrows(IllegalArgumentException::class.java) { engine().draw(cards + cards[0], 3) }
    }
    @Test fun boundariesUseLocalDates() {
        assertEquals("2026-10-02", engine("2026-10-02T20:59:59Z").period(Spread.DAY))
        assertEquals("2026-10-03", engine("2026-10-02T21:00:00Z").period(Spread.DAY))
        assertEquals("2026-09-28", engine("2026-10-04T20:59:59Z").period(Spread.WEEK))
        assertEquals("2026-10-05", engine("2026-10-04T21:00:00Z").period(Spread.WEEK))
        assertEquals("2025-12-29", engine("2026-01-01T12:00:00Z").period(Spread.WEEK))
        assertEquals("2026-10-01", engine("2026-10-02T01:00:00Z", "America/New_York").period(Spread.DAY))
        assertEquals("2026-10-02", engine("2026-10-02T01:00:00Z", "Europe/Moscow").period(Spread.DAY))
        assertEquals("2026-03-08", engine("2026-03-08T06:59:59Z", "America/New_York").period(Spread.DAY))
        assertEquals("2026-03-08", engine("2026-03-08T07:00:00Z", "America/New_York").period(Spread.DAY))
        assertEquals("2026-11-01", engine("2026-11-01T05:59:59Z", "America/New_York").period(Spread.DAY))
        assertEquals("2026-11-01", engine("2026-11-01T06:00:00Z", "America/New_York").period(Spread.DAY))
    }
    @Test fun bagsUseEveryVariantAndAvoidCycleBoundaryRepeats() {
        val source = Random(9); val e = DrawEngine(RandomSource { source.nextInt(it) }, Clock.systemUTC(), ZoneProvider { ZoneOffset.UTC })
        listOf(listOf("a", "b"), listOf("a", "b", "c")).forEach { ids ->
            var bag = Bag(); var last: String? = null
            repeat(100) { val cycle = mutableListOf<String>(); repeat(ids.size) { val (chosen, next) = e.nextVariant(ids, bag); if (cycle.isEmpty()) assertNotEquals(last, chosen); cycle += chosen; last = chosen; bag = next }; assertEquals(ids.toSet(), cycle.toSet()) }
            assertEquals(bag, Json.decodeFromString<Bag>(Json.encodeToString(bag)))
        }
    }
    @Test fun coverageRequiresAllOrderedKeysAndApproval() {
        assertEquals(9240, ExactValidator.keys(cards, Spread.WEEK).count())
        val record = ExactRecord(comboKey(Spread.DAY, cards.take(1)), listOf(Interpretation("a", "Summary A", mapOf("theme" to "Meaning"), "Action", "Question"), Interpretation("b", "Summary B", mapOf("theme" to "Meaning"), "Action", "Question")))
        val report = ExactValidator.validate(cards, listOf(Spread.DAY), listOf(record), true)
        assertEquals(22, report.expected); assertEquals(1, report.present); assertFalse(report.complete); assertTrue(report.errors.contains("Unapproved variant"))
        assertEquals(36982, Spread.entries.sumOf { ExactValidator.keys(cards, it).count() })
    }
    @Test fun duplicateFullTextAndUnknownCardsFail() {
        val v = Interpretation("a", "Same", mapOf("theme" to "M"), "A", "R", "approved")
        val record = ExactRecord(comboKey(Spread.DAY, cards.take(1)), listOf(v, v.copy(id = "b")))
        assertTrue(ExactValidator.validate(cards, listOf(Spread.DAY), listOf(record), false).errors.contains("Duplicate full text"))
        assertTrue(ExactValidator.validate(cards, listOf(Spread.DAY), listOf(record.copy(comboKey = record.comboKey.replace("major_00", "unknown"))), false).errors.any { it.startsWith("Unknown key") })
    }
    @Test fun userPlaceholdersAreSubstitutedOnlyOnce() {
        val v = Interpretation("a", "{optionA} {optionB}", mapOf("a" to "{optionA}"), "Step", "Question")
        assertEquals("{optionB} X", v.render("{optionB}", "X").summary)
    }
    @Test fun decisionsEnforceLimitsAndTrim() {
        validateDecision("  Вопрос  ", "  Сделать  ", "Подождать")
        assertThrows(IllegalArgumentException::class.java) { validateDecision(" ", "a", "b") }
        assertThrows(IllegalArgumentException::class.java) { validateDecision("q".repeat(241), "a", "b") }
        assertThrows(IllegalArgumentException::class.java) { validateDecision("q", "a".repeat(81), "b") }
    }
    @Test fun orientationsAreIndependentBinaryDraws() {
        var calls = 0
        val e = DrawEngine(RandomSource { bound -> assertEquals(2, bound); calls++ % 2 }, Clock.systemUTC(), ZoneProvider { ZoneOffset.UTC })
        assertEquals(listOf(Orientation.UP, Orientation.REVERSED, Orientation.UP), e.drawOrientations(3))
        assertEquals(3, calls)
        assertThrows(IllegalArgumentException::class.java) { e.drawOrientations(0) }
    }
    @Test fun orientationKeysCoverAllEightPatternsAndKeepLegacyKeys() {
        val small = cards.take(3)
        val keys = ExactValidator.keys(small, Spread.GENERAL, "on").toList()
        assertEquals(48, keys.size); assertEquals(48, keys.toSet().size)
        assertEquals(8, keys.count { it.contains("past:major_00:") && it.contains("present:major_01:") })
        assertTrue(comboKey(Spread.DAY, cards.take(1)).contains("|off|theme:major_00:up"))
        assertEquals(44, ExactValidator.keys(cards, Spread.DAY, "on").count())
        assertEquals(73920, ExactValidator.keys(cards, Spread.GENERAL, "on").count())
        assertThrows(IllegalArgumentException::class.java) { comboKey(Spread.DAY, cards.take(1), listOf(Orientation.REVERSED)) }
        assertThrows(IllegalArgumentException::class.java) { comboKey(Spread.GENERAL, small, emptyList(), "on") }
    }
    @Test fun allSpreadsResolveReversedMeaningActionAndReflection() {
        val deck = cards.map { it.copy(reversedMeaning = "Перевёрнутый смысл ${it.cardId}", reversedAction = "Перевёрнутый шаг ${it.cardId}", reversedReflection = "Перевёрнутый вопрос ${it.cardId}", reversedNarrative = Narrative("проверить условия", "деталь может остаться неясной", NarrativePace.PAUSE, List(3) { n -> "Перевёрнутый смысл ${it.cardId} $n" })) }
        Spread.entries.forEach { spread ->
            val selected = deck.take(spread.positions.size)
            val result = ComposedResolver.resolve(spread, selected, "c1", List(selected.size) { Orientation.REVERSED })
            result.positions.values.forEach { assertTrue(it.contains("Перевёрнутый смысл")); assertTrue(it.contains("Перевёрнутое положение")) }
            assertTrue(result.action.contains(selected.last().reversedAction))
            assertEquals(selected.last().reversedReflection, result.reflection)
        }
        assertNull(engine().period(Spread.GENERAL))
        assertEquals(listOf("past", "present", "future"), Spread.GENERAL.positions)
    }
    @Test fun mixedOrientationsSurviveSerializationAndLegacySnapshotsStayUpright() {
        val reading = Reading(id = "r", requestId = "request", periodKey = null, createdAt = 0, zoneId = "UTC", spreadId = "general3", cards = cards.take(3), comboKey = "key", variantId = "c1", contentVersion = "test", mode = "COMPOSED", interpretation = Interpretation("c1", "Summary", emptyMap(), "Step", "Question"))
        val legacy = Json.decodeFromString<Reading>(Json.encodeToString(reading))
        assertEquals(Orientation.UP, legacy.orientationAt(2))
        val updated = reading.copy(orientations = listOf(Orientation.REVERSED, Orientation.UP, Orientation.REVERSED), engineVersion = "2")
        assertEquals(updated, Json.decodeFromString<Reading>(Json.encodeToString(updated)))
        assertTrue(updated.exportText().contains("Прошлое: Карта 0 · Перевёрнутое положение"))
    }
    @Test fun reversedCoverageRejectsUprightOnlyPackages() {
        val variants = listOf("a", "b").map { Interpretation(it, "Summary $it", mapOf("theme" to "Theme"), "Step", "Question", "approved") }
        val record = ExactRecord(comboKey(Spread.DAY, cards.take(1)), variants)
        val report = ExactValidator.validate(cards, listOf(Spread.DAY), listOf(record), true, "on")
        assertEquals(44, report.expected); assertEquals(0, report.present)
        assertTrue(report.errors.any { it.startsWith("Unknown key") })
    }
    @Test fun fixedSeedDistributionDiagnostic() {
        val e = engine(seed = 20261002)
        val counts = IntArray(22)
        repeat(110_000) { counts[cards.indexOf(e.draw(cards, 1).single())]++ }
        val chiSquared = counts.sumOf { (it - 5000.0) * (it - 5000.0) / 5000.0 }
        // Predetermined broad diagnostic threshold; algorithmic correctness is tested separately.
        assertTrue("Chi squared: $chiSquared", chiSquared < 60)
        println("Distribution diagnostic: seed=20261002 draws=110000 chiSquared=$chiSquared threshold=60")
    }
}
