package ru.gorotaro.app.data

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import ru.gorotaro.app.BuildConfig
import ru.gorotaro.domain.*
import java.util.UUID

class ReadingRepository(val db: GoroDatabase, val engine: DrawEngine, private val context: Context) {
    val json = Json { ignoreUnknownKeys = true }
    private val dao = db.dao()
    fun decode(entity: ReadingEntity) = json.decodeFromString<Reading>(entity.snapshot)
    private fun entity(r: Reading) = ReadingEntity(r.id, r.requestId, r.periodKey, r.spreadId, r.comboKey, r.createdAt, json.encodeToString(r),
        searchText = listOf(r.question, if (r.spreadId == Spread.SITUATION.id) Topic.fromId(r.topic).label else "", r.note, r.happened, r.useful).joinToString("\n").lowercase(java.util.Locale.ROOT), favorite = r.favorite)
    suspend fun initialize(): List<Card> = withContext(Dispatchers.IO) {
        db.withTransaction {
            if (dao.meta("historySearchVersion")?.value != "2") {
                var offset = 0
                while (true) {
                    val page = dao.readingPage(offset, 250)
                    if (page.isEmpty()) break
                    page.forEach { old -> dao.updateReading(entity(decode(old)).copy(hidden = old.hidden)) }
                    offset += page.size
                }
                dao.saveMeta(MetaEntity("historySearchVersion", "2"))
            }
            if (dao.meta("bundledCardsVersion")?.value != "major22-narratives-3") {
                val cards = json.decodeFromString<List<Card>>(context.assets.open("cards.json").bufferedReader().use { it.readText() })
                dao.saveCards(cards.map { CardEntity(it.cardId, json.encodeToString(it)) })
                dao.saveMeta(MetaEntity("bundledCardsVersion", "major22-narratives-3"))
            }
            if (BuildConfig.DEBUG && dao.meta("demoInstalled") == null) {
                val root = json.parseToJsonElement(context.assets.open("sample_content.json").bufferedReader().use { it.readText() }).jsonObject
                val records = json.decodeFromJsonElement<List<ExactRecord>>(root.getValue("records"))
                dao.saveExact(records.map { ExactEntity(it.comboKey, "demo-1", json.encodeToString(it)) })
                dao.saveMeta(MetaEntity("demoInstalled", "true"))
            }
            dao.cards().map { json.decodeFromString<Card>(it.snapshot) }
        }
    }
    suspend fun current(spread: Spread): Reading? = withContext(Dispatchers.IO) {
        if (spread == Spread.SITUATION) dao.latest(spread.id)?.takeUnless { it.hidden }?.let(::decode)
        else if (spread == Spread.GENERAL) dao.latest(spread.id)?.takeIf { GeneralCooldown.active(it.createdAt, engine.clock.millis()) }?.let(::decode)
        else engine.period(spread)?.let { dao.period(spread.id, it)?.let(::decode) }
    }
    suspend fun get(id: String) = withContext(Dispatchers.IO) { dao.reading(id)?.let(::decode) }
    fun history(spread: String?, limit: Int, query: String = "", favorites: Boolean = false) = dao.searchHistory(spread, query.trim().lowercase(java.util.Locale.ROOT), favorites, limit).map { rows -> rows.map(::decode) }
    suspend fun create(spread: Spread, mode: String, requestId: String? = null, question: String = "", a: String = "", b: String = "", topic: String = "general"): Reading = withContext(Dispatchers.IO) {
        if (spread == Spread.DECISION) { require(!requestId.isNullOrBlank()); validateDecision(question, a, b) }
        val selectedTopic = Topic.fromId(topic)
        require(question.length <= 240) { "Вопрос не должен превышать 240 символов" }
        db.withTransaction {
            val period = engine.period(spread)
            val existing = if (period != null) dao.period(spread.id, period) else dao.request(requireNotNull(requestId))
            if (existing != null) return@withTransaction decode(existing)
            if (spread == Spread.GENERAL) {
                val latest = dao.latest(spread.id)
                if (latest != null && GeneralCooldown.active(latest.createdAt, engine.clock.millis())) return@withTransaction decode(latest)
            }
            require(mode in listOf("EXACT", "COMPOSED")) { "Неизвестный режим толкования" }
            val active = dao.meta("activeVersion")?.value
            if (mode == "EXACT") require(active != null && dao.meta("activeOrientationMode")?.value == "on") { "Для нового расклада установите набор толкований с обоими положениями карт в настройках." }
            val deck = dao.cards().map { json.decodeFromString<Card>(it.snapshot) }
            val cards = engine.draw(deck, spread.positions.size)
            val orientations = engine.drawOrientations(cards.size)
            val key = comboKey(spread, cards, orientations, "on")
            val version = if (mode == "COMPOSED") "composed-local-5" else requireNotNull(active)
            val exact = if (mode == "EXACT") dao.exact(key, version)?.let { json.decodeFromString<ExactRecord>(it.snapshot) } else null
            if (mode == "EXACT" && exact == null) error("Толкование этой комбинации пока недоступно в установленном пакете")
            val ids = exact?.variants?.map { it.id } ?: listOf("c1", "c2", "c3")
            val previous = dao.bag(key, version)?.let { json.decodeFromString<Bag>(it.state) } ?: Bag()
            val (variant, bag) = engine.nextVariant(ids, previous)
            val optionA = if (spread == Spread.DECISION) a.trim() else ""
            val optionB = if (spread == Spread.DECISION) b.trim() else ""
            val savedQuestion = if (spread == Spread.DECISION || spread == Spread.SITUATION) question.trim() else ""
            val interpretation = (exact?.variants?.single { it.id == variant } ?: ComposedResolver.resolve(spread, cards, variant, orientations, if (spread == Spread.SITUATION) selectedTopic else Topic.GENERAL)).render(optionA, optionB)
            val reading = Reading(
                id = UUID.randomUUID().toString(),
                requestId = if (period == null) requestId else null,
                periodKey = period, createdAt = engine.clock.millis(), zoneId = engine.zones.zone().id,
                spreadId = spread.id, cards = cards, comboKey = key, variantId = variant,
                contentVersion = version, mode = mode, question = savedQuestion,
                optionA = optionA, optionB = optionB, interpretation = interpretation,
                engineVersion = "5", orientations = orientations, topic = if (spread == Spread.SITUATION) selectedTopic.id else "general"
            )
            dao.saveBag(VariantEntity(key, version, json.encodeToString(bag)))
            dao.insertReading(entity(reading))
            reading
        }
    }
    suspend fun update(id: String, change: (Reading) -> Reading): Reading = withContext(Dispatchers.IO) {
        db.withTransaction {
            val old = requireNotNull(dao.reading(id)); val updated = change(decode(old))
            dao.updateReading(entity(updated).copy(hidden = old.hidden)); updated
        }
    }
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val old = dao.reading(id) ?: return@withTransaction
            // Retain locked snapshots even when hidden; history deletion never bypasses a cooldown.
            if (old.periodKey != null || old.spreadId == Spread.GENERAL.id) dao.updateReading(entity(decode(old).copy(note = "", happened = "", useful = "", favorite = false)).copy(hidden = true)) else dao.deleteReading(id)
        }
    }
    suspend fun reset() = withContext(Dispatchers.IO) { db.withTransaction { dao.clearReadings(); dao.clearBags() } }

    suspend fun backup(): ByteArray = withContext(Dispatchers.IO) {
        db.withTransaction {
            require(dao.readingCount() <= 10000 && dao.bagCount() <= 30000) { "Слишком много записей для одной резервной копии" }
            require(dao.readingBytes() + dao.bagBytes() <= HistoryBackupCodec.maxBytes) { "Резервная копия превышает 32 МБ" }
            HistoryBackupCodec.encode(HistoryBackup(createdAt = engine.clock.millis(),
                readings = dao.allReadings().map { BackupReading(decode(it), it.hidden) },
                bags = dao.allBags().map { BackupBag(it.comboKey, it.contentVersion, json.decodeFromString<Bag>(it.state)) }))
        }
    }
    suspend fun restore(bytes: ByteArray): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val backup = HistoryBackupCodec.decode(bytes) // Validate the entire file before touching the database.
        db.withTransaction {
            var added = 0; var skipped = 0
            backup.readings.forEach { entry ->
                val r = entry.reading
                val collision = dao.reading(r.id) != null || r.requestId?.let { dao.request(it) != null } == true || r.periodKey?.let { dao.period(r.spreadId, it) != null } == true
                if (collision) skipped++ else { dao.insertReading(entity(r).copy(hidden = entry.hidden)); added++ }
            }
            // Preserve locally advanced variant cycles when merging with an older backup.
            backup.bags.forEach { b -> if (dao.bag(b.comboKey, b.contentVersion) == null) dao.saveBag(VariantEntity(b.comboKey, b.contentVersion, json.encodeToString(b.bag))) }
            added to skipped
        }
    }

    suspend fun debugReport(): String = withContext(Dispatchers.IO) {
        check(BuildConfig.DEBUG) { "Отладка доступна только в debug-сборке" }
        db.withTransaction {
            val active = dao.meta("activeVersion")?.value
            val latest = dao.latest(Spread.GENERAL.id)
            val now = engine.clock.millis()
            val zone = engine.zones.zone()
            buildString {
                appendLine("ГороТаро ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · debug")
                appendLine("Android ${android.os.Build.VERSION.RELEASE} · API ${android.os.Build.VERSION.SDK_INT}")
                appendLine("Время: ${java.time.Instant.ofEpochMilli(now).atZone(zone)}")
                appendLine("Часовой пояс: ${zone.id}")
                appendLine("Период дня: ${engine.period(Spread.DAY)}")
                appendLine("Период недели: ${engine.period(Spread.WEEK)}")
                appendLine("Карты: ${dao.cards().size}")
                appendLine("EXACT-пакет: ${active ?: "не установлен"}")
                appendLine("Положения EXACT: ${dao.meta("activeOrientationMode")?.value ?: "—"}")
                appendLine("Комбинации в активном EXACT: ${active?.let { dao.exactCount(it) } ?: 0}")
                appendLine("Расклады в истории: ${dao.visibleReadingCount()}")
                appendLine("Скрытые снимки: ${dao.hiddenReadingCount()}")
                appendLine("Мешки вариантов: ${dao.bagCount()}")
                appendLine("Общий расклад: ${if (latest != null && GeneralCooldown.active(latest.createdAt, now)) "пауза до ${java.time.Instant.ofEpochMilli(GeneralCooldown.availableAt(latest.createdAt)).atZone(zone)}" else "доступен"}")
            }
        }
    }

    // Read-only preview: does not draw randomly, advance bags or unlock saved periods.
    suspend fun debugPreview(spread: Spread, cardIds: List<String>, orientations: List<Orientation>, variant: String): Reading = withContext(Dispatchers.IO) {
        check(BuildConfig.DEBUG) { "Отладка доступна только в debug-сборке" }
        require(cardIds.size == spread.positions.size && orientations.size == cardIds.size)
        require(cardIds.distinct().size == cardIds.size) { "Выберите разные карты для каждой позиции" }
        require(variant in listOf("c1", "c2", "c3"))
        val deck = dao.cards().map { json.decodeFromString<Card>(it.snapshot) }.associateBy { it.cardId }
        val selected = cardIds.map { requireNotNull(deck[it]) { "Карта не найдена" } }
        Reading(
            id = "debug-preview", requestId = null, periodKey = null,
            createdAt = engine.clock.millis(), zoneId = engine.zones.zone().id,
            spreadId = spread.id, cards = selected, comboKey = comboKey(spread, selected, orientations, "on"),
            variantId = variant, contentVersion = "composed-local-5", engineVersion = "5", mode = "COMPOSED",
            optionA = if (spread == Spread.DECISION) "Сделать" else "",
            optionB = if (spread == Spread.DECISION) "Подождать" else "",
            interpretation = ComposedResolver.resolve(spread, selected, variant, orientations).render("Сделать", "Подождать"),
            revealed = selected.indices.toSet(), orientations = orientations
        )
    }
}
