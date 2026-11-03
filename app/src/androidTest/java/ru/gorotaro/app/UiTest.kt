package ru.gorotaro.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.runner.RunWith
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModelProvider

@RunWith(AndroidJUnit4::class)
class UiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun reset() {
        val app = compose.activity.application as GoroApplication
        runBlocking { app.repository.reset(); app.settings.reset() }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("home-list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("practice-toggle"))
        compose.onNodeWithTag("practice-toggle").performClick()
        compose.onNodeWithTag("home-list").performScrollToNode(hasText("Вытянуть карту"))
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Вытянуть карту").fetchSemanticsNodes().any { !it.config.contains(SemanticsProperties.Disabled) } }
    }
    @Test fun dayOpensInOneActionAndSurvivesActivityRecreation() {
        compose.onNodeWithText("Вытянуть карту").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("reading-list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("reading-list").performScrollToNode(hasText("Итог расклада"))
        compose.onNodeWithText("Итог расклада").assertExists()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("reading-list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("reading-list").performScrollToNode(hasText("Итог расклада"))
        compose.onNodeWithText("Итог расклада").assertExists()
        screenshot("day-result")
    }
    @Test fun debugMenuOpensPreviewAndReturnsToSettings() {
        compose.onNodeWithContentDescription("Настройки").performClick()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("debug-menu-entry"))
        compose.onNodeWithTag("debug-menu-entry").performClick()
        compose.onNodeWithTag("debug-list").performScrollToNode(hasText("Показать предпросмотр"))
        compose.onNodeWithText("Показать предпросмотр").performClick()
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        compose.waitUntil(15_000) { vm.debugPreview.value != null }
        compose.onNodeWithTag("debug-list").performScrollToNode(hasText("Итог расклада"))
        compose.onNodeWithText("Итог расклада").assertExists()
        val repo = (compose.activity.application as GoroApplication).repository
        runBlocking { Assert.assertTrue(repo.history(null, 100).first().isEmpty()) }
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithTag("settings-list").assertExists()
    }
    @Test fun closedWeekCardDoesNotExposeItsNameToAccessibility() {
        compose.onNodeWithTag("home-list").performScrollToNode(hasText("На эту неделю"))
        compose.onNodeWithTag("week3-start").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("reading-list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("reading-list").performScrollToNode(hasContentDescription("Позиция Фокус, карта закрыта"))
        compose.onNodeWithContentDescription("Позиция Фокус, карта закрыта").assertExists()
        screenshot("week-closed")
        compose.onNodeWithTag("reading-list").performScrollToNode(hasText("Открыть все"))
        compose.onNodeWithText("Открыть все").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Открыть все").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("reading-list").performScrollToNode(hasText("Итог расклада"))
        compose.onNodeWithText("Итог расклада").assertExists()
    }
    @Test fun decisionRequiresQuestionAndKeepsBothOptions() {
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("decision3-start"))
        compose.onNodeWithTag("decision3-start").performScrollTo().performClick()
        compose.onNodeWithTag("decision-list").performScrollToNode(hasText("Вытянуть три карты"))
        compose.onNodeWithText("Вытянуть три карты").assertIsNotEnabled()
        compose.onNodeWithTag("decision-list").performScrollToNode(hasText("Ваш вопрос"))
        compose.onNodeWithTag("question-input").performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("Как выбрать следующий шаг?")) }
        compose.onNodeWithTag("question-input").assertTextContains("Как выбрать следующий шаг?")
        compose.onNodeWithTag("decision-list").performScrollToNode(hasText("Вариант А"))
        compose.onNodeWithTag("option-a-input").performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("Учиться")) }
        compose.onNodeWithTag("option-a-input").assertTextContains("Учиться")
        compose.onNodeWithTag("decision-list").performScrollToNode(hasText("Вариант Б"))
        compose.onNodeWithTag("option-b-input").performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("Работать")) }
        compose.onNodeWithTag("option-b-input").assertTextContains("Работать")
        compose.onNodeWithTag("decision-list").performScrollToNode(hasText("Вытянуть три карты"))
        compose.onNodeWithText("Вытянуть три карты").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("reading-list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("reading-list").performScrollToNode(hasText("А: Учиться"))
        compose.onNodeWithText("А: Учиться").assertExists(); compose.onNodeWithText("Б: Работать").assertExists()
        screenshot("decision-result")
    }
    @Test fun situationAcceptsEmptyQuestionAndUsesNewPositions() {
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("situation3-start"))
        compose.onNodeWithTag("situation3-start").performClick()
        compose.onNodeWithTag("situation-list").performScrollToNode(hasText("Вытянуть три карты"))
        compose.onNodeWithText("Вытянуть три карты").assertIsEnabled().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("reading-list").fetchSemanticsNodes().isNotEmpty() }
        listOf("Что происходит", "Что осложняет", "На что опереться").forEach { label ->
            compose.onNodeWithTag("reading-list").performScrollToNode(hasContentDescription("Позиция $label, карта закрыта"))
            compose.onNodeWithContentDescription("Позиция $label, карта закрыта").assertExists()
        }
        compose.onAllNodesWithTag("decision-list").assertCountEquals(0)
    }
    @Test fun revealKeepsExplanationsHiddenUntilExplicitlyExpandedAfterAllCards() {
        compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("week3-start"))
        compose.onNodeWithTag("week3-start").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("reading-list").fetchSemanticsNodes().isNotEmpty() }
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        compose.onNodeWithTag("reading-list").performScrollToNode(hasContentDescription("Позиция Препятствие, карта закрыта"))
        compose.onNodeWithContentDescription("Позиция Препятствие, карта закрыта").assertIsNotEnabled()
        repeat(3) { index ->
            compose.onNodeWithTag("reading-list").performScrollToNode(hasText("Открыть следующую карту"))
            compose.onNodeWithText("Открыть следующую карту").performClick()
            compose.waitUntil(15_000) { vm.reading.value?.revealed?.size == index + 1 }
            compose.onAllNodesWithTag("position-$index-explanation").assertCountEquals(0)
            if (index < 2) compose.onAllNodesWithText("Итог расклада").assertCountEquals(0)
        }
        compose.onNodeWithTag("reading-list").performScrollToNode(hasText("Итог расклада"))
        compose.onNodeWithText("Итог расклада").assertExists()
        compose.onNodeWithTag("reading-list").performScrollToNode(hasTestTag("position-0-toggle"))
        compose.onAllNodesWithTag("position-0-explanation").assertCountEquals(0)
        compose.onNodeWithTag("position-0-toggle").performClick()
        compose.onNodeWithTag("reading-list").performScrollToNode(hasTestTag("position-0-explanation"))
        compose.onNodeWithTag("position-0-explanation").assertExists()
    }
    @Test fun retrospectiveNotesAndFavoriteSurviveRecreationAndAppearInSearch() {
        compose.onNodeWithText("Вытянуть карту").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("reading-list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("reading-list").performScrollToNode(hasTestTag("reading-favorite"))
        compose.onNodeWithTag("reading-favorite").performClick()
        compose.onNodeWithTag("reading-list").performScrollToNode(hasTestTag("retrospective-toggle"))
        compose.onNodeWithTag("retrospective-toggle").performClick()
        compose.onNodeWithTag("reading-list").performScrollToNode(hasTestTag("happened-input"))
        compose.onNodeWithTag("happened-input").performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("Состоялся разговор")) }
        compose.onNodeWithTag("reading-list").performScrollToNode(hasTestTag("useful-input"))
        compose.onNodeWithTag("useful-input").performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("Удалось договориться")) }
        compose.onNodeWithTag("reading-list").performScrollToNode(hasText("Сохранить заметки"))
        compose.onNodeWithText("Сохранить заметки").performClick()
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        compose.waitUntil(15_000) { vm.reading.value?.useful == "Удалось договориться" }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("reading-list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("reading-list").performScrollToNode(hasTestTag("useful-input"))
        compose.onNodeWithTag("useful-input").assertTextContains("Удалось договориться")
        compose.onNodeWithText("История").performClick()
        compose.onNodeWithTag("history-list").performScrollToNode(hasTestTag("history-search"))
        compose.onNodeWithTag("history-search").performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("ДОГОВОРИТЬСЯ")) }
        compose.onNodeWithTag("history-list").performScrollToNode(hasText("Только избранное"))
        compose.onNodeWithText("Только избранное").performClick()
        compose.onNodeWithTag("history-list").performScrollToNode(hasText("★ Убрать из избранного"))
        compose.onNodeWithText("★ Убрать из избранного").assertExists()
    }
    @Test fun noteAutosavesBeforeNavigationWithoutSaveButton() {
        compose.onNodeWithText("Вытянуть карту").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("reading-list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("reading-list").performScrollToNode(hasTestTag("note-input"))
        compose.onNodeWithTag("note-input").performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("Проверка черновика")) }
        compose.onNodeWithText("История").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("history-list").fetchSemanticsNodes().isNotEmpty() }
        val repo = (compose.activity.application as GoroApplication).repository
        runBlocking { Assert.assertEquals("Проверка черновика", repo.history(null, 1).first().single().note) }
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val app = compose.activity.application
        val target = File(app.getExternalFilesDir(null), "$name.png")
        target.outputStream().use { InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
