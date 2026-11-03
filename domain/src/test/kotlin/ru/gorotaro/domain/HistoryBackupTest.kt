package ru.gorotaro.domain

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class HistoryBackupTest {
    private val cards = Json.decodeFromString<List<Card>>(File("../app/src/main/assets/cards.json").readText())
    private fun reading() = Reading("reading-1", "request-1", null, 1000, "Europe/Moscow", Spread.GENERAL.id,
        cards.take(3), comboKey(Spread.GENERAL, cards.take(3)), "c1", "composed-demo-3", mode = "COMPOSED",
        interpretation = ComposedResolver.resolve(Spread.GENERAL, cards.take(3), "c1"), note = "Заметка", revealed = setOf(0),
        happened = "Состоялся разговор", useful = "Удалось договориться", favorite = true)
    private fun backup(r: Reading = reading()) = HistoryBackup(createdAt = 2000,
        readings = listOf(BackupReading(r, true)), bags = listOf(BackupBag(r.comboKey, r.contentVersion, Bag(listOf("c2", "c1", "c3"), 1, "c2"))))

    @Test fun roundTripKeepsFullSnapshotHiddenLocksAndVariantState() {
        val original = backup()
        assertEquals(original, HistoryBackupCodec.decode(HistoryBackupCodec.encode(original)))
    }
    @Test fun legacySnapshotDefaultsDoNotLoseExistingText() {
        val encoded = Json.encodeToString(Reading.serializer(), reading().copy(happened = "", useful = "", favorite = false))
        val decoded = Json.decodeFromString<Reading>(encoded)
        assertEquals("", decoded.happened); assertEquals("", decoded.useful); assertFalse(decoded.favorite)
        assertEquals(reading().interpretation, decoded.interpretation)
    }
    @Test fun versionOneCopiesRestoreUnchangedAndSituationsRequireVersionTwo() {
        val old = backup().copy(version = 1)
        assertEquals(old, HistoryBackupCodec.decode(HistoryBackupCodec.encode(old)))
        val situation = reading().copy(spreadId = Spread.SITUATION.id, topic = "work",
            interpretation = ComposedResolver.resolve(Spread.SITUATION, cards.take(3), "c1", topic = Topic.WORK))
        val current = backup(situation)
        assertEquals(current, HistoryBackupCodec.decode(HistoryBackupCodec.encode(current)))
        assertThrows(IllegalArgumentException::class.java) { HistoryBackupCodec.encode(current.copy(version = 1)) }
        assertThrows(IllegalArgumentException::class.java) { HistoryBackupCodec.encode(backup(situation.copy(topic = "unknown"))) }
        assertFalse(situation.exportText().contains("А: "))
        assertTrue(situation.exportText().contains("Работа и проекты"))
    }
    @Test fun invalidVersionsDuplicatesAndBrokenPositionsAreRejected() {
        val b = backup()
        listOf(b.copy(version = 3), b.copy(format = "other"), b.copy(readings = b.readings + b.readings),
            backup(reading().copy(revealed = setOf(3))), backup(reading().copy(orientations = listOf(Orientation.UP))),
            backup(reading().copy(interpretation = reading().interpretation.copy(positions = emptyMap()))),
            backup(reading().copy(cards = listOf(cards[0], cards[0], cards[1]))),
            b.copy(bags = listOf(b.bags[0].copy(bag = Bag(listOf("c1", "c2"), -1, "c1"))))).forEach {
            assertThrows(IllegalArgumentException::class.java) { HistoryBackupCodec.validate(it) }
        }
        assertThrows(IllegalArgumentException::class.java) { HistoryBackupCodec.decode(ByteArray(HistoryBackupCodec.maxBytes + 1)) }
    }
    @Test fun periodAndRequestCollisionsWithinFileAreRejected() {
        val r = reading()
        assertThrows(IllegalArgumentException::class.java) {
            HistoryBackupCodec.validate(backup().copy(readings = listOf(BackupReading(r), BackupReading(r.copy(id = "other")))))
        }
        val day = r.copy(requestId = null, periodKey = "2026-10-07", spreadId = Spread.DAY.id, cards = cards.take(1),
            revealed = emptySet(), interpretation = ComposedResolver.resolve(Spread.DAY, cards.take(1), "c1"))
        assertThrows(IllegalArgumentException::class.java) {
            HistoryBackupCodec.validate(HistoryBackup(createdAt = 2000, readings = listOf(BackupReading(day), BackupReading(day.copy(id = "other"))), bags = emptyList()))
        }
    }
    @Test fun revealOrderRequiresPreviousCardsAndKeepsLegacyRevealsAccessible() {
        val closed = reading().copy(revealed = emptySet())
        assertTrue(closed.canReveal(0)); assertFalse(closed.canReveal(1)); assertFalse(closed.canReveal(2)); assertFalse(closed.canReveal(-1))
        assertTrue(closed.copy(revealed = setOf(0)).canReveal(1))
        assertFalse(closed.copy(revealed = setOf(0)).canReveal(2))
        assertTrue(closed.copy(revealed = setOf(2)).canReveal(2))
    }
    @Test fun userExportContainsRetrospectiveWithoutTechnicalModeNames() {
        val text = reading().exportText()
        assertTrue(text.contains(reading().happened)); assertTrue(text.contains(reading().useful))
        assertFalse(text.contains("COMPOSED")); assertFalse(text.contains("EXACT"))
    }
}
