package ru.gorotaro.app

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import ru.gorotaro.app.data.*
import ru.gorotaro.domain.*
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** major22 package importer. Validation precedes the atomic activation transaction. */
class ContentImporter(private val db: GoroDatabase, private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    suspend fun import(bytes: ByteArray): Coverage = withContext(Dispatchers.IO) {
        require(bytes.size <= 64 * 1024 * 1024) { "Пакет больше 64 МБ" }
        val files = mutableMapOf<String, ByteArray>()
        var total = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                require(entry.name in setOf("manifest.json", "cards.json", "spreads.json", "interpretations.jsonl") && entry.name !in files) { "Неизвестный или повторный файл пакета" }
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val n = zip.read(buffer); if (n < 0) break; total += n; require(total <= 128 * 1024 * 1024) { "Распакованный пакет больше 128 МБ" }; out.write(buffer, 0, n) }
                files[entry.name] = out.toByteArray()
            }
        }
        require(files.keys == setOf("manifest.json", "cards.json", "spreads.json", "interpretations.jsonl")) { "Неполный пакет" }
        val manifest = json.parseToJsonElement(files.getValue("manifest.json").toString(Charsets.UTF_8)).jsonObject
        fun field(name: String) = manifest.getValue(name).jsonPrimitive.content
        require(field("schemaVersion") == "1" && field("deckId") == "major22" && field("locale") == "ru" && field("orientationMode") == "on" && field("mode") == "EXACT") { "Нужен набор для 22 старших арканов с прямыми и перевёрнутыми положениями" }
        require(field("status") == "approved" && manifest["approvedForProduction"]?.jsonPrimitive?.boolean == true) { "Пакет не утверждён для публикации" }
        val version = field("contentVersion")
        require(version.matches(Regex("[A-Za-z0-9._-]{1,80}")) && !version.startsWith("demo") && !version.startsWith("composed")) { "Недопустимая версия" }
        val checksums = manifest.getValue("checksums").jsonObject
        require(checksums.keys == files.keys - "manifest.json") { "Неполный список контрольных сумм" }
        checksums.forEach { (name, checksum) ->
            val actual = MessageDigest.getInstance("SHA-256").digest(files.getValue(name)).joinToString("") { "%02x".format(it.toInt() and 255) }
            require(actual == checksum.jsonPrimitive.content) { "Повреждён файл $name" }
        }
        val packageCards = json.decodeFromString<List<Card>>(files.getValue("cards.json").toString(Charsets.UTF_8))
        val builtCards = json.decodeFromString<List<Card>>(context.assets.open("cards.json").bufferedReader().use { it.readText() })
        require(packageCards.map { it.cardId }.toSet() == builtCards.map { it.cardId }.toSet() && packageCards.size == 22) { "Пакет должен содержать исходные 22 карты" }
        require(packageCards.all { c -> builtCards.any { it.cardId == c.cardId && it.nameRu == c.nameRu && it.imageAsset == c.imageAsset && it.aspectRatio == c.aspectRatio } }) { "Идентичность карт нарушена" }
        packageCards.forEach { context.assets.open(it.imageAsset).close() }
        val spreadsArray = json.parseToJsonElement(files.getValue("spreads.json").toString(Charsets.UTF_8)).jsonArray
        val spreads = spreadsArray.map { element ->
            val o = element.jsonObject; val spread = Spread.fromId(o.getValue("spreadId").jsonPrimitive.content)
            require(o.getValue("positions").jsonArray.map { it.jsonPrimitive.content } == spread.positions) { "Неверные позиции" }; spread
        }
        require(spreads.isNotEmpty() && spreads.distinct().size == spreads.size)
        val records = files.getValue("interpretations.jsonl").inputStream().bufferedReader().useLines { lines -> lines.filter { it.isNotBlank() }.map { json.decodeFromString<ExactRecord>(it) }.toList() }
        val coverage = ExactValidator.validate(packageCards, spreads, records, production = true, orientationMode = "on")
        require(coverage.complete) { "Пакет отклонён: ${coverage.present}/${coverage.expected} комбинаций. ${coverage.errors.firstOrNull().orEmpty()}" }
        require(manifest.getValue("declaredCoverage").jsonPrimitive.int == coverage.expected) { "Заявленное покрытие не совпадает" }
        db.withTransaction {
            // Immutable versions prevent a bag from referring to silently rewritten variants.
            require(db.dao().meta("installed:$version") == null) { "Эта версия уже установлена. Используйте новый номер версии." }
            records.chunked(250).forEach { batch -> db.dao().saveExact(batch.map { ExactEntity(it.comboKey, version, json.encodeToString(it)) }) }
            db.dao().saveMeta(MetaEntity("installed:$version", "true"))
            db.dao().saveMeta(MetaEntity("activeVersion", version))
            db.dao().saveMeta(MetaEntity("activeOrientationMode", "on"))
        }
        coverage
    }
}
