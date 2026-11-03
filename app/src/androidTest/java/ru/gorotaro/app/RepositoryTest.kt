package ru.gorotaro.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import ru.gorotaro.app.data.*
import ru.gorotaro.domain.*
import java.time.*
import java.io.ByteArrayOutputStream
import java.util.zip.*
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class RepositoryTest {
    private lateinit var db: GoroDatabase
    private lateinit var repo: ReadingRepository
    private val context get() = ApplicationProvider.getApplicationContext<GoroApplication>()
    private val json = Json { ignoreUnknownKeys = true }
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, GoroDatabase::class.java).build()
        repo = ReadingRepository(db, DrawEngine(RandomSource { 0 }, Clock.fixed(Instant.parse("2026-10-02T12:00:00Z"), ZoneOffset.UTC), ZoneProvider { ZoneId.of("Europe/Moscow") }), context)
        repo.initialize(); Unit
    }
    @After fun close() { db.close() }
    @Test fun situationsKeepContextAllowDistinctRequestsAndRoundTrip() = runBlocking {
        val first = repo.create(Spread.SITUATION, "COMPOSED", "situation-one", "Как обсудить сроки?", topic = "work")
        assertEquals("work", first.topic); assertEquals("Как обсудить сроки?", first.question)
        assertEquals("5", first.engineVersion)
        assertEquals(first, repo.create(Spread.SITUATION, "COMPOSED", "situation-one", "Другой вопрос"))
        val second = repo.create(Spread.SITUATION, "COMPOSED", "situation-two")
        assertNotEquals(first.id, second.id)
        assertEquals(2, repo.history(null, 10).first().size)
        assertEquals(first.id, repo.history(null, 10, "РАБОТА").first().single().id)
        val backup = repo.backup()
        repo.reset(); assertEquals(2 to 0, repo.restore(backup))
        assertEquals(first, repo.get(first.id))
        assertEquals(0 to 2, repo.restore(backup))
    }
    @Test fun debugPreviewDoesNotChangeHistoryLocksOrVariantBags() = runBlocking {
        val day = repo.create(Spread.DAY, "COMPOSED")
        val general = repo.create(Spread.GENERAL, "COMPOSED", "general-before-preview")
        val history = repo.history(null, 100).first()
        val state = db.dao().bag(general.comboKey, general.contentVersion)
        val ids = general.cards.map { it.cardId }
        val orientations = general.cards.map { Orientation.REVERSED }
        val preview = repo.debugPreview(Spread.GENERAL, ids, orientations, "c3")
        assertEquals("c3", preview.variantId)
        assertEquals(orientations, preview.orientations)
        assertEquals(preview.cards.indices.toSet(), preview.revealed)
        assertEquals(history, repo.history(null, 100).first())
        assertEquals(state, db.dao().bag(general.comboKey, general.contentVersion))
        assertNull(repo.get(preview.id))
        assertEquals(day, repo.current(Spread.DAY))
        assertEquals(general, repo.current(Spread.GENERAL))
        assertEquals(general, repo.create(Spread.GENERAL, "COMPOSED", "general-after-preview"))
    }
    @Test fun debugPreviewRejectsDuplicateCardsWithoutSaving() = runBlocking {
        val failure = runCatching { repo.debugPreview(Spread.WEEK, listOf("major_00", "major_00", "major_01"), List(3) { Orientation.UP }, "c1") }
        assertTrue(failure.isFailure)
        assertTrue(repo.history(null, 100).first().isEmpty())
        assertEquals(0, db.dao().bagCount())
    }
    @Test fun debugReportOmitsPersonalTextsAndCountsHiddenSnapshots() = runBlocking {
        val reading = repo.create(Spread.DECISION, "COMPOSED", "private-request", "СЕКРЕТНЫЙ ВОПРОС", "ЛИЧНЫЙ А", "ЛИЧНЫЙ Б")
        repo.update(reading.id) { it.copy(note = "ЛИЧНАЯ ЗАМЕТКА") }
        val day = repo.create(Spread.DAY, "COMPOSED")
        repo.delete(day.id)
        val report = repo.debugReport()
        assertTrue(report.contains("Расклады в истории: 1"))
        assertTrue(report.contains("Скрытые снимки: 1"))
        listOf(reading.id, reading.question, reading.optionA, reading.optionB, "ЛИЧНАЯ ЗАМЕТКА", reading.interpretation.summary).forEach { assertFalse(report.contains(it)) }
    }
    @Test fun simultaneousRequestsReturnOnePeriodicSnapshot() = runBlocking {
        val readings = (1..20).map { async(Dispatchers.IO) { repo.create(Spread.WEEK, "COMPOSED") } }.awaitAll()
        assertEquals(1, readings.map { it.id }.distinct().size)
        assertEquals(1, repo.history(null, 100).first().size)
        assertEquals(1, json.decodeFromString<Bag>(db.dao().bag(readings[0].comboKey, readings[0].contentVersion)!!.state).cursor)
    }
    @Test fun duplicateRequestDoesNotSpendAnotherVariant() = runBlocking {
        val first = repo.create(Spread.DECISION, "COMPOSED", "request-1", "Вопрос", "А", "Б")
        assertEquals("Вопрос", first.question); assertEquals("А", first.optionA); assertEquals("Б", first.optionB)
        assertEquals("COMPOSED", first.mode); assertEquals("5", first.engineVersion)
        val state = db.dao().bag(first.comboKey, first.contentVersion)
        assertEquals(first, repo.create(Spread.DECISION, "COMPOSED", "request-1", "Другой", "В", "Г"))
        assertEquals(state, db.dao().bag(first.comboKey, first.contentVersion))
        val next = repo.create(Spread.DECISION, "COMPOSED", "request-2", "Вопрос", "А", "Б")
        assertNotEquals(first.variantId, next.variantId)
    }
    @Test fun missingExactDoesNotSaveOrRedraw() = runBlocking {
        val failure = runCatching { repo.create(Spread.WEEK, "EXACT") }
        assertTrue(failure.isFailure); assertTrue(repo.history(null, 100).first().isEmpty())
        val cards = repo.initialize().take(3)
        assertNull(db.dao().bag(comboKey(Spread.WEEK, cards), "demo-1"))
    }
    @Test fun backupRestoreIsIdempotentAndPreservesHiddenCooldownAndNotes() = runBlocking {
        val general = repo.create(Spread.GENERAL, "COMPOSED", "backup-general")
        val saved = repo.update(general.id) { it.copy(note = "Заметка", happened = "Событие", useful = "Договорились", favorite = true) }
        val day = repo.create(Spread.DAY, "COMPOSED"); repo.delete(day.id)
        val bags = db.dao().allBags().toSet()
        val backup = repo.backup()
        repo.reset()
        assertEquals(2 to 0, repo.restore(backup))
        assertEquals(saved, repo.get(general.id))
        assertEquals(1, repo.history(null, 100).first().size)
        assertEquals(day, repo.current(Spread.DAY))
        assertEquals(saved, repo.current(Spread.GENERAL))
        assertEquals(bags, db.dao().allBags().toSet())
        repo.update(saved.id) { it.copy(note = "Новая заметка") }
        assertEquals(0 to 2, repo.restore(backup))
        assertEquals("Новая заметка", repo.get(saved.id)!!.note)
    }
    @Test fun restoreSkipsSamePeriodEvenWithAnotherIdAndRejectsInvalidFileAtomically() = runBlocking {
        val day = repo.create(Spread.DAY, "COMPOSED")
        val backup = HistoryBackup(createdAt = 1000, readings = listOf(BackupReading(day.copy(id = "different"))), bags = emptyList())
        assertEquals(0 to 1, repo.restore(HistoryBackupCodec.encode(backup)))
        assertEquals(day, repo.current(Spread.DAY))
        val invalid = json.encodeToString(backup.copy(readings = backup.readings + BackupReading(day.copy(id = "broken", revealed = setOf(20))))).toByteArray()
        assertTrue(runCatching { repo.restore(invalid) }.isFailure)
        assertEquals(listOf(day), repo.history(null, 100).first())
    }
    @Test fun searchFindsRussianNotesAcrossAllPagesAndFiltersFavorites() = runBlocking {
        val first = repo.create(Spread.DECISION, "COMPOSED", "search-1", "Учиться или работать?", "Учиться", "Работать")
        repo.update(first.id) { it.copy(happened = "Получилось ДОГОВОРИТЬСЯ", favorite = true) }
        repo.create(Spread.DECISION, "COMPOSED", "search-2", "Другой вопрос", "А", "Б")
        assertEquals(first.id, repo.history(null, 1, "договориться", true).first().single().id)
        assertEquals(first.id, repo.history(null, 1, "УЧИТЬСЯ").first().single().id)
        assertTrue(repo.history(Spread.DAY.id, 50, "", true).first().isEmpty())
    }
    @Test fun versionOneMigrationPreservesSnapshotsAndBackfillsRussianSearch() = runBlocking {
        val name = "gorotaro-migration-v1-test.db"
        context.deleteDatabase(name)
        val saved = repo.create(Spread.DAY, "COMPOSED").copy(note = "Старая русская заметка")
        val schema = Json.parseToJsonElement(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
            .open("ru.gorotaro.app.data.GoroDatabase/1.json").bufferedReader().use { it.readText() }).jsonObject.getValue("database").jsonObject
        context.openOrCreateDatabase(name, android.content.Context.MODE_PRIVATE, null).use { raw ->
            schema.getValue("entities").jsonArray.forEach { element ->
                val table = element.jsonObject
                val tableName = table.getValue("tableName").jsonPrimitive.content
                raw.execSQL(table.getValue("createSql").jsonPrimitive.content.replace("\u0024{TABLE_NAME}", tableName))
                table["indices"]?.jsonArray?.forEach { index -> raw.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\u0024{TABLE_NAME}", tableName)) }
            }
            raw.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            raw.execSQL("INSERT INTO room_master_table (id,identity_hash) VALUES (42,?)", arrayOf(schema.getValue("identityHash").jsonPrimitive.content))
            raw.execSQL("INSERT INTO readings (id,requestId,periodKey,spreadId,comboKey,createdAt,snapshot,hidden) VALUES (?,NULL,?,?,?,?,?,0)",
                arrayOf<Any?>(saved.id, saved.periodKey, saved.spreadId, saved.comboKey, saved.createdAt, json.encodeToString(saved)))
            raw.version = 1
        }
        val migrated = Room.databaseBuilder(context, GoroDatabase::class.java, name).addMigrations(MIGRATION_1_2).build()
        try {
            val migratedRepo = ReadingRepository(migrated, repo.engine, context)
            migratedRepo.initialize()
            assertEquals(saved, migratedRepo.get(saved.id))
            assertEquals(saved.id, migratedRepo.history(null, 50, "РУССКАЯ").first().single().id)
            assertEquals(saved, migratedRepo.current(Spread.DAY))
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
    @Test fun rollbackRemovesReadingAndVariantAdvance() = runBlocking {
        var created: Reading? = null
        runCatching { db.withTransaction { created = repo.create(Spread.WEEK, "COMPOSED"); error("Injected failure before commit") } }
        assertNull(db.dao().reading(created!!.id))
        assertNull(db.dao().bag(created!!.comboKey, created!!.contentVersion))
    }
    @Test fun deletingHistoryPreservesPeriodButResetAllowsNewReading() = runBlocking {
        val first = repo.create(Spread.DAY, "COMPOSED"); repo.update(first.id) { it.copy(note = "Личная заметка") }; repo.delete(first.id)
        assertTrue(repo.history(null, 50).first().isEmpty())
        val reopened = repo.create(Spread.DAY, "EXACT")
        assertEquals(first.id, reopened.id); assertEquals(first.interpretation, reopened.interpretation); assertEquals("", reopened.note)
        repo.reset(); assertNotEquals(first.id, repo.create(Spread.DAY, "COMPOSED").id)
    }
    @Test fun reconstructedRepositoryKeepsRevealTextQuestionAndNotes() = runBlocking {
        val first = repo.create(Spread.DECISION, "COMPOSED", "persist", "Что выбрать?", "Сделать", "Подождать")
        val updated = repo.update(first.id) { it.copy(revealed = setOf(0, 2), note = "Моя заметка") }
        val newRepo = ReadingRepository(db, DrawEngine(SecureRandomSource(), Clock.systemUTC(), ZoneProvider { ZoneOffset.UTC }), context)
        assertEquals(updated, newRepo.get(first.id))
        assertEquals(updated, newRepo.create(Spread.DECISION, "EXACT", "persist", "Что выбрать?", "Сделать", "Подождать"))
    }
    @Test fun updatedContentDoesNotRewriteExistingPeriod() = runBlocking {
        val first = repo.create(Spread.WEEK, "COMPOSED")
        db.dao().saveMeta(MetaEntity("activeVersion", "new-content"))
        assertEquals(first, repo.create(Spread.WEEK, "EXACT"))
    }
    @Test fun periodicReadingsDoNotKeepDecisionDraftFields() = runBlocking {
        val r = repo.create(Spread.DAY, "COMPOSED", "stray-request", "Личный вопрос", "А", "Б")
        assertEquals("", r.question); assertEquals("", r.optionA); assertEquals("", r.optionB); assertNull(r.requestId)
    }
    @Test fun diskDatabaseReopenPreservesCompleteSnapshot() = runBlocking {
        val name = "gorotaro-persistence-test.db"
        context.deleteDatabase(name)
        var disk = Room.databaseBuilder(context, GoroDatabase::class.java, name).build()
        try {
            val diskRepo = ReadingRepository(disk, repo.engine, context)
            diskRepo.initialize()
            val first = diskRepo.create(Spread.WEEK, "COMPOSED")
            val updated = diskRepo.update(first.id) { it.copy(note = "Сохранено на диск", revealed = setOf(0, 1)) }
            disk.close()
            disk = Room.databaseBuilder(context, GoroDatabase::class.java, name).build()
            val reopened = ReadingRepository(disk, repo.engine, context)
            reopened.initialize()
            assertEquals(updated, reopened.get(first.id))
            assertEquals(updated, reopened.create(Spread.WEEK, "EXACT"))
        } finally { disk.close(); context.deleteDatabase(name) }
    }
    @Test fun crossingMidnightAndMovingClockBackReturnsOriginalPeriod() = runBlocking {
        fun at(instant: String) = ReadingRepository(db, DrawEngine(RandomSource { 0 }, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC), ZoneProvider { ZoneId.of("Europe/Moscow") }), context)
        val before = at("2026-10-02T20:59:59Z").create(Spread.DAY, "COMPOSED")
        val after = at("2026-10-02T21:00:00Z").create(Spread.DAY, "COMPOSED")
        assertNotEquals(before.id, after.id)
        assertEquals(before, at("2026-10-02T20:00:00Z").create(Spread.DAY, "EXACT"))
    }
    @Test fun tenThousandHistoryRecordsRemainOrderedAndPaged() = runBlocking {
        val reading = repo.create(Spread.DECISION, "COMPOSED", "base", "Вопрос", "А", "Б")
        val start = System.nanoTime()
        db.withTransaction { repeat(10_000) { i ->
            val r = reading.copy(id = "history-%05d".format(i), requestId = "r-$i", createdAt = reading.createdAt + i)
            db.dao().insertReading(ReadingEntity(r.id, r.requestId, null, r.spreadId, r.comboKey, r.createdAt, json.encodeToString(r)))
        } }
        val written = (System.nanoTime()-start)/1_000_000
        val t = System.nanoTime(); val page = repo.history(null, 50).first(); val read = (System.nanoTime()-t)/1_000_000
        assertEquals(50, page.size); assertEquals("history-09999", page.first().id)
        println("GOROTARO_BENCH history10000 insertMs=$written page50Ms=$read")
    }
    private suspend fun fixture(version: String, corrupt: Boolean = false): ByteArray {
        val cards = repo.initialize()
        val records = cards.flatMap { c -> Orientation.entries.map { orientation -> ExactRecord(comboKey(Spread.DAY, listOf(c), listOf(orientation), "on"), listOf("v1", "v2").map { id -> Interpretation(id, "Test fixture ${c.cardId} ${orientation.id} $id", mapOf("theme" to "Test ${c.nameRu} ${orientation.id}"), "Test action $id", "Test question", "approved") }) } }
        val files = linkedMapOf("cards.json" to json.encodeToString(cards), "spreads.json" to "[{\"spreadId\":\"day1\",\"positions\":[\"theme\"]}]", "interpretations.jsonl" to records.joinToString("\n") { json.encodeToString(it) })
        val checksums = files.mapValues { MessageDigest.getInstance("SHA-256").digest(it.value.toByteArray()).joinToString("") { byte -> "%02x".format(byte.toInt() and 255) } }
        val manifest = buildJsonObject {
            put("schemaVersion", 1); put("contentVersion", version); put("deckId", "major22"); put("locale", "ru"); put("orientationMode", "on"); put("mode", "EXACT"); put("status", "approved"); put("approvedForProduction", true); put("declaredCoverage", 44)
            put("checksums", buildJsonObject { checksums.forEach { (k, v) -> put(k, v) } })
        }
        files["manifest.json"] = manifest.toString()
        if (corrupt) files["interpretations.jsonl"] = "{}"
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip -> files.forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() } }
        return out.toByteArray()
    }
    @Test fun generalRequestsKeepOrientationsAndDoNotKeepDecisionFields() = runBlocking {
        val reversedRepo = ReadingRepository(db, DrawEngine(RandomSource { 1 }, repo.engine.clock, repo.engine.zones), context)
        val first = reversedRepo.create(Spread.GENERAL, "COMPOSED", "general-1", "Черновик", "А", "Б")
        assertNull(first.periodKey); assertEquals("general-1",first.requestId); assertEquals("", first.question)
        assertEquals(List(3) { Orientation.REVERSED },first.orientations)
        assertEquals(first, reversedRepo.create(Spread.GENERAL, "COMPOSED", "general-1"))
        assertEquals(first, reversedRepo.create(Spread.GENERAL, "COMPOSED", "general-2"))
        assertEquals(first, repo.get(first.id))
    }
    @Test fun concurrentGeneralRequestsDoNotDrawAgainDuringCooldown() = runBlocking {
        val readings = (1..20).map { n -> async(Dispatchers.IO) { repo.create(Spread.GENERAL,"COMPOSED","general-$n") } }.awaitAll()
        assertEquals(1,readings.map { it.id }.distinct().size)
        assertEquals(1,repo.history(Spread.GENERAL.id,50).first().size)
        val first = readings.first()
        assertEquals(1,json.decodeFromString<Bag>(db.dao().bag(first.comboKey,first.contentVersion)!!.state).cursor)
    }
    @Test fun generalCooldownSurvivesDeletionAndEndsAtExactBoundary() = runBlocking {
        fun at(time: String) = ReadingRepository(db,DrawEngine(RandomSource { 0 },Clock.fixed(Instant.parse(time),ZoneOffset.UTC),ZoneProvider { ZoneId.of("Europe/Moscow") }),context)
        val first = repo.create(Spread.GENERAL,"COMPOSED","general-1")
        repo.update(first.id) { it.copy(note = "Заметка") }; repo.delete(first.id)
        assertTrue(repo.history(Spread.GENERAL.id,50).first().isEmpty())
        val before = at("2026-10-16T11:59:59.999Z")
        assertEquals(first,before.create(Spread.GENERAL,"COMPOSED","general-2"))
        assertEquals(first,before.current(Spread.GENERAL))
        val bag = db.dao().bag(first.comboKey,first.contentVersion)
        assertEquals(first,at("2026-10-01T12:00:00Z").create(Spread.GENERAL,"COMPOSED","clock-back"))
        assertEquals(bag,db.dao().bag(first.comboKey,first.contentVersion))
        val boundary = at("2026-10-16T12:00:00Z")
        assertNull(boundary.current(Spread.GENERAL))
        val next = boundary.create(Spread.GENERAL,"COMPOSED","general-3")
        assertNotEquals(first.id,next.id)
        assertEquals(next,boundary.current(Spread.GENERAL))
        assertEquals(first, boundary.create(Spread.GENERAL,"COMPOSED","general-1"))
    }
    @Test fun fullResetClearsGeneralCooldownAlongWithPersonalData() = runBlocking {
        val first = repo.create(Spread.GENERAL,"COMPOSED","before-reset")
        repo.reset()
        assertNull(repo.current(Spread.GENERAL))
        assertNotEquals(first.id,repo.create(Spread.GENERAL,"COMPOSED","after-reset").id)
    }
    @Test fun importsActivateAtomicallyAndRejectCorruption() = runBlocking {
        val importer = ContentImporter(db, context)
        assertTrue(importer.import(fixture("fixture-1")).complete)
        assertEquals("fixture-1", db.dao().meta("activeVersion")!!.value)
        assertTrue(runCatching { importer.import(fixture("fixture-2", corrupt = true)) }.isFailure)
        assertEquals("fixture-1", db.dao().meta("activeVersion")!!.value)
        val reading = repo.create(Spread.DAY, "EXACT")
        assertEquals("fixture-1", reading.contentVersion)
        assertTrue(runCatching { importer.import(fixture("fixture-1")) }.isFailure)
    }
}
