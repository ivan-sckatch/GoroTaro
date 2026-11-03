package ru.gorotaro.domain

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class NarrativeTest {
    private val cards = Json.decodeFromString<List<Card>>(File("../app/src/main/assets/cards.json").readText())
    @Test fun corpusKeepsOrientationSpecificGrammarAndVariants() {
        assertEquals(22, cards.size)
        assertEquals(132, cards.sumOf { c -> Orientation.entries.sumOf { c.narrative(it)!!.variants.size } })
        cards.forEach { c -> Orientation.entries.forEach { o ->
            val n = c.narrative(o)!!
            assertEquals(3, n.variants.toSet().size)
            assertTrue(n.goal.first().isLowerCase()); assertTrue(n.risk.first().isLowerCase())
            assertFalse(n.goal.endsWith('.')); assertFalse(n.risk.endsWith('.'))
        } }
    }
    @Test fun allProfilesKeepDetailsButDoNotCopyThemIntoSynthesis() {
        Spread.entries.forEach { spread -> repeat(22) { offset ->
            val selected = List(spread.positions.size) { cards[(offset + it) % cards.size] }
            repeat(1 shl selected.size) { mask ->
                val orientations = selected.indices.map { if (mask and (1 shl it) == 0) Orientation.UP else Orientation.REVERSED }
                val results = (0..2).map { profile ->
                    val r = ComposedResolver.resolve(spread, selected, "c${profile + 1}", orientations)
                    selected.forEachIndexed { i, c ->
                        val fragment = c.narrative(orientations[i])!!.variants[(profile + i) % 3]
                        assertTrue(r.positions.getValue(spread.positions[i]).contains(fragment))
                        if (spread != Spread.DAY) {
                            assertTrue(r.summary.contains(c.nameRu))
                            assertFalse(r.summary.contains(fragment))
                        }
                    }
                    assertTrue("Too long: ${r.summary.length}", r.summary.length < 1100)
                    assertTrue("Too long: ${r.action.length}", r.action.length < 450)
                    val sentences = listOf(r.summary, r.action, r.reflection).flatMap { it.split(Regex("[.!?]")) }
                        .map(String::trim).filter { it.length >= 65 }
                    assertEquals("Repeated sentence", sentences.size, sentences.distinct().size)
                    r.summary
                }
                assertEquals(3, results.toSet().size)
            }
        } }
    }
    @Test fun synthesisDependsOnEachPositionAndOrientation() {
        Spread.entries.filter { it != Spread.DAY }.forEach { spread ->
            val selected = cards.take(3)
            val initial = ComposedResolver.resolve(spread, selected, "c1").summary
            repeat(3) { i ->
                val changed = selected.toMutableList().apply { this[i] = cards[10] }
                assertNotEquals(initial, ComposedResolver.resolve(spread, changed, "c1").summary)
                val reversed = List(3) { if (it == i) Orientation.REVERSED else Orientation.UP }
                assertNotEquals(initial, ComposedResolver.resolve(spread, selected, "c1", reversed).summary)
            }
        }
    }
    @Test fun actionUsesResourceAndObstacleWithoutRetellingAllGoals() {
        val selected = listOf(cards[1], cards[9], cards[19])
        val r = ComposedResolver.resolve(Spread.SITUATION, selected, "c1")
        assertTrue(r.action.startsWith(selected.last().action))
        assertEquals(selected.last().reflection, r.reflection)
        assertFalse(r.action.contains(selected.first().narrative!!.goal))
        val changedResource = ComposedResolver.resolve(Spread.SITUATION, selected.take(2) + cards[10], "c1")
        assertNotEquals(r.action, changedResource.action)
        val changedObstacle = selected.toMutableList().apply { this[1] = this[1].copy(narrative = this[1].narrative!!.copy(pace = NarrativePace.MOVE)) }
        assertNotEquals(r.action, ComposedResolver.resolve(Spread.SITUATION, changedObstacle, "c1").action)
    }
    @Test fun decisionKeepsChoiceWithUserAndLegacyGeneralKeepsTimeRoles() {
        val selected = cards.take(3)
        val r = ComposedResolver.resolve(Spread.DECISION, selected, "c1").render("Учиться", "Работать")
        assertTrue(r.summary.contains("Учиться")); assertTrue(r.summary.contains("Работать"))
        assertFalse((r.summary + r.action + r.reflection).contains("{option"))
        assertTrue(r.summary.contains("Общая проверка"))
        val legacy = ComposedResolver.resolve(Spread.GENERAL, selected, "c1")
        assertTrue(legacy.summary.contains("в прошлом")); assertTrue(legacy.summary.contains("В настоящем"))
        assertEquals(listOf("past", "present", "future"), Spread.GENERAL.positions)
        assertEquals(listOf("context", "tension", "support"), Spread.SITUATION.positions)
    }
    @Test fun topicsChangeFramingWithoutInterpretingFreeText() {
        val summaries = Topic.entries.map { topic -> ComposedResolver.resolve(Spread.SITUATION, cards.take(3), "c1", topic = topic).summary }
        assertEquals(3, summaries.toSet().size)
        assertTrue(summaries[1].contains("потребности обеих сторон"))
        assertTrue(summaries[2].contains("временем и силами"))
    }
    @Test fun incompleteCorpusFailsAndEveryPacePairHasConnection() {
        assertThrows(IllegalArgumentException::class.java) { ComposedResolver.resolve(Spread.DAY, listOf(cards[0].copy(narrative = null)), "c1") }
        assertThrows(IllegalArgumentException::class.java) { ComposedResolver.resolve(Spread.WEEK, cards.take(1), "c1") }
        NarrativePace.entries.forEach { a -> NarrativePace.entries.forEach { b ->
            val selected = cards.take(3).mapIndexed { i, c -> c.copy(narrative = c.narrative!!.copy(pace = if (i == 0) a else b)) }
            val summary = ComposedResolver.resolve(Spread.WEEK, selected, "c1").summary
            selected.forEach { assertTrue(summary.contains(it.nameRu)) }
            assertTrue(summary.length > 200)
        } }
    }
    @Test fun twelveDeterministicExamplesForEditorialReview() {
        val examples = (0..11).map { index ->
            val spread = listOf(Spread.SITUATION, Spread.WEEK, Spread.DECISION, Spread.GENERAL)[index % 4]
            val topic = Topic.entries[index / 4]
            val indices = listOf(listOf(1,7,19), listOf(2,9,12), listOf(4,10,21), listOf(3,6,8), listOf(1,9,19), listOf(11,13,4), listOf(17,15,6), listOf(9,12,2), listOf(4,11,21), listOf(8,16,0), listOf(10,13,16), listOf(1,9,19))[index]
            val selected = indices.map { cards[it] }
            val orientations = if (index in listOf(0,7,8,10)) List(3) { Orientation.UP }
                else if (index == 11) listOf(Orientation.UP, Orientation.REVERSED, Orientation.UP)
                else List(3) { if (index and (1 shl it) == 0) Orientation.UP else Orientation.REVERSED }
            val r = ComposedResolver.resolve(spread, selected, "c${index % 3 + 1}", orientations, topic).render("Учиться", "Работать")
            "## ${index + 1}. ${spread.title} · ${topic.label}\n\n" + selected.indices.joinToString(" · ") { "${selected[it].nameRu}: ${orientations[it].label}" } +
                "\n\n${r.summary}\n\n**Шаг:** ${r.action}\n\n**Вопрос:** ${r.reflection}\n\n" + r.positions.values.joinToString("\n\n")
        }
        File("../reports/NARRATIVE-EXAMPLES.md").writeText("# Контрольные примеры движка 5\n\nФиксированные входные данные для редакторской проверки, не результаты пользовательского пилота.\n\n" + examples.joinToString("\n\n"))
    }
}
