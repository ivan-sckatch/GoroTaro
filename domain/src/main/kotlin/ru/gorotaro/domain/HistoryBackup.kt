package ru.gorotaro.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.ZoneId

@Serializable data class BackupReading(val reading: Reading, val hidden: Boolean = false)
@Serializable data class BackupBag(val comboKey: String, val contentVersion: String, val bag: Bag)
@Serializable data class HistoryBackup(
    val format: String = "gorotaro-history", val version: Int = 2, val createdAt: Long,
    val readings: List<BackupReading>, val bags: List<BackupBag>
)

object HistoryBackupCodec {
    const val maxBytes = 32 * 1024 * 1024
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(backup: HistoryBackup): ByteArray {
        validate(backup)
        return json.encodeToString(backup).toByteArray(Charsets.UTF_8).also {
            require(it.size <= maxBytes) { "Резервная копия превышает 32 МБ" }
        }
    }
    fun decode(bytes: ByteArray): HistoryBackup {
        require(bytes.size <= maxBytes) { "Резервная копия превышает 32 МБ" }
        return json.decodeFromString<HistoryBackup>(bytes.toString(Charsets.UTF_8)).also(::validate)
    }
    fun validate(backup: HistoryBackup) {
        require(backup.format == "gorotaro-history" && backup.version in 1..2) { "Неподдерживаемый формат резервной копии" }
        require(backup.createdAt >= 0 && backup.readings.size <= 10000 && backup.bags.size <= 30000) { "Слишком большая или некорректная резервная копия" }
        val ids = mutableSetOf<String>(); val periods = mutableSetOf<Pair<String, String>>(); val requests = mutableSetOf<String>()
        backup.readings.forEach { entry ->
            val r = entry.reading
            require(r.id.isNotBlank() && r.id.length <= 128 && ids.add(r.id)) { "Повтор или неверный идентификатор расклада" }
            val spread = Spread.fromId(r.spreadId)
            Topic.fromId(r.topic)
            require(backup.version >= 2 || (spread != Spread.SITUATION && r.topic == "general")) { "Новые ситуации требуют формат копии 2" }
            require(r.createdAt in 0..Long.MAX_VALUE - GeneralCooldown.durationMillis)
            ZoneId.of(r.zoneId)
            require(r.cards.size == spread.positions.size && r.cards.map { it.cardId }.distinct().size == r.cards.size)
            require(r.orientations.isEmpty() || r.orientations.size == r.cards.size)
            require(r.revealed.all { it in r.cards.indices })
            require(r.mode in listOf("COMPOSED", "EXACT"))
            require(r.comboKey.length <= 512 && r.contentVersion.isNotBlank() && r.contentVersion.length <= 128 && r.variantId.length <= 128)
            require(r.interpretation.positions.keys == spread.positions.toSet())
            require((r.interpretation.positions.values + listOf(r.interpretation.summary, r.interpretation.action, r.interpretation.reflection)).all { it.length <= 50000 })
            require(r.question.length <= 240 && r.optionA.length <= 80 && r.optionB.length <= 80)
            require(listOf(r.note, r.happened, r.useful).all { it.length <= 4000 })
            if (spread == Spread.DAY || spread == Spread.WEEK) {
                require(r.requestId == null && r.periodKey != null)
                val date = LocalDate.parse(r.periodKey)
                if (spread == Spread.WEEK) require(date.dayOfWeek == java.time.DayOfWeek.MONDAY)
                require(periods.add(r.spreadId to r.periodKey)) { "Повтор периода расклада" }
            } else {
                require(r.periodKey == null && !r.requestId.isNullOrBlank() && r.requestId.length <= 128)
                require(requests.add(r.requestId)) { "Повтор запроса расклада" }
            }
            r.cards.forEach { c ->
                require(c.cardId.matches(Regex("major_(0[0-9]|1[0-9]|2[01])")))
                require(c.imageAsset == "cards/${c.cardId}.webp" && c.aspectRatio.isFinite() && c.aspectRatio in 0.1f..10f)
                require(c.nameRu.isNotBlank() && c.nameRu.length <= 200)
                require(c.sources.all { it.startsWith("https://") && it.length <= 2048 })
            }
        }
        val bags = mutableSetOf<Pair<String, String>>()
        backup.bags.forEach {
            require(it.comboKey.isNotBlank() && it.comboKey.length <= 512 && it.contentVersion.isNotBlank() && it.contentVersion.length <= 128)
            require(bags.add(it.comboKey to it.contentVersion))
            require(it.bag.ids.size in 2..3 && it.bag.ids.distinct().size == it.bag.ids.size && it.bag.ids.all { id -> id.isNotBlank() && id.length <= 128 })
            require(it.bag.cursor in 0..it.bag.ids.size && (it.bag.last == null || it.bag.last in it.bag.ids))
        }
    }
}

fun Reading.canReveal(index: Int): Boolean = index in cards.indices && (index in revealed || (0 until index).all { it in revealed })
