package ru.gorotaro.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import java.security.SecureRandom
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek

fun interface RandomSource { fun nextInt(bound: Int): Int }
class SecureRandomSource : RandomSource {
    private val source = SecureRandom()
    override fun nextInt(bound: Int) = source.nextInt(bound)
}
fun interface ZoneProvider { fun zone(): ZoneId }

enum class Spread(val id: String, val title: String, val positions: List<String>, val labels: List<String>) {
    DAY("day1", "На сегодня", listOf("theme"), listOf("Тема дня")),
    WEEK("week3", "На эту неделю", listOf("focus", "obstacle", "action"), listOf("Фокус", "Препятствие", "Полезное действие")),
    DECISION("decision3", "На решение", listOf("a", "b", "check"), listOf("Что поддерживает вариант А", "Что поддерживает вариант Б", "Что проверить перед выбором")),
    GENERAL("general3", "Общий расклад", listOf("past", "present", "future"), listOf("Прошлое", "Настоящее", "Будущее")),
    SITUATION("situation3", "Разобрать ситуацию", listOf("context", "tension", "support"), listOf("Что происходит", "Что осложняет", "На что опереться"));
    companion object { fun fromId(id: String) = requireNotNull(entries.find { it.id == id }) { "Неизвестный тип расклада: $id" } }
}

enum class Topic(val id: String, val label: String) {
    GENERAL("general", "Общая ситуация"), COMMUNICATION("communication", "Общение"), WORK("work", "Работа и проекты");
    companion object { fun fromId(id: String) = requireNotNull(entries.find { it.id == id }) { "Неизвестная тема: $id" } }
}

@Serializable enum class Orientation(val id: String, val label: String) {
    UP("up", "Прямое положение"), REVERSED("rev", "Перевёрнутое положение")
}
@Serializable enum class NarrativePace {
    @SerialName("move") MOVE,
    @SerialName("pause") PAUSE,
    @SerialName("structure") STRUCTURE,
    @SerialName("connect") CONNECT,
    @SerialName("change") CHANGE
}
@Serializable data class Narrative(val goal: String, val risk: String, val pace: NarrativePace, val variants: List<String>)
@Serializable data class Card(
    val cardId: String, val nameRu: String, val imageAsset: String, val aspectRatio: Float,
    val meaning: String, val action: String, val reflection: String,
    val reversedMeaning: String = "", val reversedAction: String = "", val reversedReflection: String = "",
    val sources: List<String> = emptyList(),
    val narrative: Narrative? = null, val reversedNarrative: Narrative? = null
) {
    fun meaning(o: Orientation) = if (o == Orientation.UP) meaning else reversedMeaning
    fun action(o: Orientation) = if (o == Orientation.UP) action else reversedAction
    fun reflection(o: Orientation) = if (o == Orientation.UP) reflection else reversedReflection
    fun narrative(o: Orientation) = if (o == Orientation.UP) narrative else reversedNarrative
}
@Serializable data class Interpretation(val id: String, val summary: String, val positions: Map<String, String>, val action: String, val reflection: String, val status: String = "draft")
@Serializable data class ExactRecord(val comboKey: String, val variants: List<Interpretation>)
@Serializable data class Bag(val ids: List<String> = emptyList(), val cursor: Int = 0, val last: String? = null)
@Serializable data class Reading(
    val id: String, val requestId: String?, val periodKey: String?, val createdAt: Long,
    val zoneId: String, val spreadId: String, val cards: List<Card>, val comboKey: String,
    val variantId: String, val contentVersion: String, val engineVersion: String = "1",
    val mode: String, val question: String = "", val optionA: String = "", val optionB: String = "",
    val interpretation: Interpretation, val note: String = "", val revealed: Set<Int> = emptySet(),
    val orientations: List<Orientation> = emptyList(),
    val happened: String = "", val useful: String = "", val favorite: Boolean = false,
    val topic: String = "general"
) {
    // Snapshots from engine v1 have no orientations and remain upright.
    fun orientationAt(index: Int) = orientations.getOrElse(index) { Orientation.UP }
    fun exportText(): String = buildString {
        appendLine("ГороТаро · ${Spread.fromId(spreadId).title}")
        appendLine("${java.time.Instant.ofEpochMilli(createdAt).atZone(ZoneId.of(zoneId))}")
        if (spreadId == Spread.SITUATION.id) appendLine("Тема: ${Topic.fromId(topic).label}")
        if (question.isNotBlank()) appendLine(question)
        if (spreadId == Spread.DECISION.id) appendLine("А: $optionA\nБ: $optionB")
        appendLine(if (mode == "COMPOSED") "Толкование по значениям карт" else "Подготовленное толкование")
        cards.forEachIndexed { i, c -> appendLine("${Spread.fromId(spreadId).labels[i]}: ${c.nameRu} · ${orientationAt(i).label}") }
        val spread = Spread.fromId(spreadId)
        spread.positions.forEachIndexed { i, p -> appendLine("${spread.labels[i]}: ${interpretation.positions[p].orEmpty()}") }
        appendLine("Полезный шаг: ${interpretation.action}")
        appendLine("Вопрос: ${interpretation.reflection}")
        appendLine("Итог расклада:\n${interpretation.summary}")
        if (note.isNotEmpty()) appendLine("Заметка: $note")
        if (happened.isNotEmpty()) appendLine("Что произошло: $happened")
        if (useful.isNotEmpty()) appendLine("Что оказалось полезным: $useful")
    }
}

class DrawEngine(val random: RandomSource, val clock: Clock, val zones: ZoneProvider) {
    fun period(spread: Spread): String? {
        val date = LocalDate.now(clock.withZone(zones.zone()))
        return when (spread) {
            Spread.DAY -> date.toString()
            Spread.WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()
            Spread.DECISION, Spread.GENERAL, Spread.SITUATION -> null
        }
    }
    fun draw(cards: List<Card>, count: Int): List<Card> {
        require(cards.map { it.cardId }.distinct().size == cards.size)
        require(count in 1..cards.size)
        val pool = cards.sortedBy { it.cardId }.toMutableList()
        repeat(count) { i -> val j = i + random.nextInt(pool.size - i); val c = pool[i]; pool[i] = pool[j]; pool[j] = c }
        return pool.take(count)
    }
    fun drawOrientations(count: Int): List<Orientation> {
        require(count > 0)
        return List(count) { if (random.nextInt(2) == 0) Orientation.UP else Orientation.REVERSED }
    }
    fun nextVariant(ids: List<String>, previous: Bag): Pair<String, Bag> {
        require(ids.size in 2..3 && ids.distinct().size == ids.size)
        var bag = previous
        if (bag.cursor >= bag.ids.size || bag.ids.toSet() != ids.toSet()) {
            val shuffled = ids.toMutableList()
            for (i in shuffled.lastIndex downTo 1) { val j = random.nextInt(i + 1); val a = shuffled[i]; shuffled[i] = shuffled[j]; shuffled[j] = a }
            if (shuffled.first() == bag.last) { val a = shuffled[0]; shuffled[0] = shuffled[1]; shuffled[1] = a }
            bag = Bag(shuffled, 0, bag.last)
        }
        val chosen = bag.ids[bag.cursor]
        return chosen to bag.copy(cursor = bag.cursor + 1, last = chosen)
    }
}

fun comboKey(spread: Spread, cards: List<Card>, orientations: List<Orientation> = List(cards.size) { Orientation.UP }, orientationMode: String = "off"): String {
    require(cards.size == spread.positions.size && cards.map { it.cardId }.distinct().size == cards.size)
    require(orientations.size == cards.size && orientationMode in listOf("off", "on"))
    require(orientationMode == "on" || orientations.all { it == Orientation.UP })
    return "v1|major22|${spread.id}|ru|$orientationMode|" + cards.indices.joinToString("|") { i -> "${spread.positions[i]}:${cards[i].cardId}:${orientations[i].id}" }
}

fun validateDecision(question: String, a: String, b: String) {
    require(question.trim().isNotEmpty() && question.trim().length <= 240) { "Введите вопрос до 240 символов" }
    require(a.trim().isNotEmpty() && a.trim().length <= 80 && b.trim().isNotEmpty() && b.trim().length <= 80) { "Заполните два варианта до 80 символов" }
}

fun Interpretation.render(a: String, b: String): Interpretation {
    // One pass: user-supplied braces must not become template instructions.
    fun sub(text: String) = Regex("\\{option[AB]\\}").replace(text) { if (it.value == "{optionA}") a else b }
    return copy(summary = sub(summary), positions = positions.mapValues { sub(it.value) }, action = sub(action), reflection = sub(reflection))
}

object ComposedResolver {
    fun resolve(spread: Spread, cards: List<Card>, variant: String, orientations: List<Orientation> = List(cards.size) { Orientation.UP }, topic: Topic = Topic.GENERAL): Interpretation {
        require(cards.size == spread.positions.size && orientations.size == cards.size)
        val variantIndex = when (variant) { "c1" -> 0; "c2" -> 1; "c3" -> 2; else -> error("Неизвестный вариант составного толкования") }
        val narratives = cards.indices.map { i -> requireNotNull(cards[i].narrative(orientations[i])) { "Для положения карты отсутствует база формулировок" } }
        narratives.forEach { require(it.variants.size in 2..3 && it.variants.all(String::isNotBlank) && it.goal.isNotBlank() && it.risk.isNotBlank()) { "Неполная база формулировок" } }
        val meanings = narratives.mapIndexed { i, n -> n.variants[(variantIndex + i) % n.variants.size] }
        val positions = spread.positions.zip(cards).mapIndexed { index, (p, c) ->
            val framing = when (spread) {
                Spread.DAY -> "Тема дня"
                Spread.WEEK -> listOf("Опора для внимания", "Возможное затруднение — проверьте, не упускаете ли вы эту сторону", "Ресурс для следующего шага")[index]
                Spread.DECISION -> listOf("Для пути «{optionA}» исследуйте этот ресурс", "Для пути «{optionB}» исследуйте этот ресурс", "Перед выбором проверьте эту сторону обоих путей")[index]
                Spread.GENERAL -> listOf("Прошлое: опыт и привычки, влияющие на ситуацию", "Настоящее: тема, которой сейчас полезно уделить внимание", "Будущее: возможное направление при сохранении текущих условий")[index]
                Spread.SITUATION -> listOf("Текущая сторона ситуации", "Возможное затруднение, которое стоит проверить", "Ресурс, на который можно опереться")[index]
            }
            p to "$framing. ${c.nameRu} · ${orientations[index].label}. ${meanings[index]}"
        }.toMap()
        val summary = NarrativeComposer.compose(spread, cards, orientations, narratives, meanings, variantIndex, topic)
        val action = NarrativeComposer.action(spread, cards, orientations, narratives)
        return Interpretation(variant, summary, positions, action, cards.last().reflection(orientations.last()), "draft")
    }
}

data class Coverage(val expected: Int, val present: Int, val errors: List<String>, val missing: List<String>) {
    val complete get() = errors.isEmpty() && expected == present
}
object ExactValidator {
    fun keys(cards: List<Card>, spread: Spread, orientationMode: String = "off"): Sequence<String> = sequence {
        require(orientationMode in listOf("on", "off"))
        suspend fun SequenceScope<String>.walk(selected: List<Card>) {
            if (selected.size == spread.positions.size) {
                repeat(if (orientationMode == "on") 1 shl selected.size else 1) { mask ->
                    val orientations = selected.indices.map { if (mask and (1 shl it) == 0) Orientation.UP else Orientation.REVERSED }
                    yield(comboKey(spread, selected, orientations, orientationMode))
                }
            }
            else cards.filter { c -> selected.none { it.cardId == c.cardId } }.forEach { walk(selected + it) }
        }
        walk(emptyList())
    }
    fun validate(cards: List<Card>, spreads: List<Spread>, records: List<ExactRecord>, production: Boolean, orientationMode: String = "off"): Coverage {
        val expected = spreads.flatMap { keys(cards, it, orientationMode).toList() }.toSet()
        val present = records.map { it.comboKey }.toSet()
        val errors = mutableListOf<String>()
        if (cards.isEmpty() || cards.map { it.cardId }.distinct().size != cards.size) errors += "Invalid deck"
        if (present.size != records.size) errors += "Duplicate keys"
        val normalized = mutableSetOf<String>()
        records.forEach { record ->
            if (record.comboKey !in expected) errors += "Unknown key: ${record.comboKey}"
            val spread = spreads.find { it.id == record.comboKey.split('|').getOrNull(2) }
            if (record.variants.size !in 2..3 || record.variants.map { it.id }.distinct().size != record.variants.size) errors += "Invalid variants: ${record.comboKey}"
            record.variants.forEach { v ->
                if (v.id.isBlank() || v.summary.isBlank() || v.action.isBlank() || v.reflection.isBlank() || v.positions.keys != spread?.positions?.toSet() || v.positions.values.any { it.isBlank() }) errors += "Invalid fields: ${record.comboKey}"
                if (listOf(v.summary, v.action, v.reflection).any { it.length > 4000 } || v.positions.values.any { it.length > 4000 }) errors += "Text too long"
                if (production && v.status != "approved") errors += "Unapproved variant"
                val text = (listOf(v.summary) + v.positions.values + v.action + v.reflection).joinToString(" ").lowercase().replace(Regex("\\s+"), " ").trim()
                if (!normalized.add(text)) errors += "Duplicate full text"
            }
        }
        val missing = (expected - present).take(5)
        if (production && missing.isNotEmpty()) errors += "Incomplete EXACT coverage"
        return Coverage(expected.size, present.count { it in expected }, errors, missing)
    }
}
