package ru.gorotaro.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import ru.gorotaro.app.data.Settings
import ru.gorotaro.domain.*

@Composable internal fun DebugScreen(vm: MainViewModel, settings: Settings, cards: List<Card>, busy: Boolean) {
    if (!BuildConfig.DEBUG) return
    val report by vm.debugReport.collectAsStateWithLifecycle()
    val preview by vm.debugPreview.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var spreadId by rememberSaveable { mutableStateOf(Spread.GENERAL.id) }
    var selectedIds by rememberSaveable { mutableStateOf(listOf("major_00", "major_01", "major_02")) }
    var reversed by rememberSaveable { mutableStateOf(listOf(false, false, false)) }
    var variant by rememberSaveable { mutableStateOf("c1") }
    val spread = Spread.fromId(spreadId)
    val ids = selectedIds.take(spread.positions.size)
    val valid = cards.isNotEmpty() && ids.distinct().size == ids.size && ids.all { id -> cards.any { it.cardId == id } }
    LazyColumn(contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.testTag("debug-list")) {
        item { Text("Инструменты для проверки прототипа · debug") }
        item { Panel {
            Heading("Диагностика")
            Text(report.ifBlank { "Загрузка…" }, style = MaterialTheme.typography.bodySmall)
            Text("Отчёт содержит технические сведения и счётчики, без вопросов, заметок и текстов раскладов.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = vm::refreshDebug, enabled = !busy) { Text("Обновить сведения") }
            OutlinedButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Диагностика ГороТаро", "$report\nРежим новых раскладов: ${settings.mode}"))
                vm.message.value = "Диагностика скопирована"
            }, enabled = report.isNotBlank()) { Text("Копировать диагностику") }
        } }
        item { Panel {
            Heading("Режим новых раскладов")
            Text("COMPOSED — составной тестовый текст. EXACT — текст установленного пакета; отсутствие комбинации даёт ошибку. Сохранённые результаты сохраняют свой режим.")
            listOf("COMPOSED", "EXACT").forEach { mode ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(settings.mode == mode, { vm.setSettings(settings.copy(mode = mode)) }, enabled = !busy)
                    Text(mode)
                }
            }
        } }
        item { Panel {
            Heading("Предпросмотр комбинации")
            Text("Всегда COMPOSED. Предпросмотр не сохраняется в историю, не расходует варианты и не меняет закреплённые расклады или 14-дневную паузу.")
            DebugChoice("Тип расклада", spread.title, Spread.entries.map { it.id to it.title }) { spreadId = it; vm.clearDebugPreview() }
            DebugChoice("Вариант текста", variant, listOf("c1" to "c1", "c2" to "c2", "c3" to "c3")) { variant = it; vm.clearDebugPreview() }
        } }
        items(spread.positions.size, key = { "selection-$spreadId-$it" }) { index ->
            Panel {
                Heading(spread.labels[index])
                DebugChoice("Карта", cards.find { it.cardId == selectedIds[index] }?.nameRu ?: "Выберите карту", cards.map { it.cardId to it.nameRu }) { id ->
                    selectedIds = selectedIds.toMutableList().also { it[index] = id }; vm.clearDebugPreview()
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Перевёрнутое положение", Modifier.weight(1f))
                    Switch(reversed[index], { value -> reversed = reversed.toMutableList().also { it[index] = value }; vm.clearDebugPreview() })
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!valid && cards.isNotEmpty()) Text("Выберите разные карты для каждой позиции.")
                PrimaryButton("Показать предпросмотр", valid && !busy) {
                    vm.previewDebug(spread, ids, reversed.take(ids.size).map { if (it) Orientation.REVERSED else Orientation.UP }, variant)
                }
            }
        }
        preview?.let { r ->
            item { Heading("Тестовый результат · ${r.variantId}"); Text(r.comboKey, style = MaterialTheme.typography.bodySmall) }
            items(r.cards.size, key = { "preview-$it" }) { index ->
                val card = r.cards[index]
                val orientation = r.orientationAt(index)
                Panel {
                    Heading(Spread.fromId(r.spreadId).labels[index])
                    AsyncImage("file:///android_asset/${card.imageAsset}", "${card.nameRu} · ${orientation.label}", contentScale = ContentScale.Fit,
                        modifier = Modifier.align(Alignment.CenterHorizontally).width(160.dp).aspectRatio(card.aspectRatio).graphicsLayer { rotationZ = if (orientation == Orientation.REVERSED) 180f else 0f })
                    Text("${card.nameRu} · ${orientation.label}")
                    Text(r.interpretation.positions.getValue(Spread.fromId(r.spreadId).positions[index]))
                }
            }
            item { Panel { Heading("Полезный шаг"); Text(r.interpretation.action); Heading("Вопрос для размышления"); Text(r.interpretation.reflection); Heading("Итог расклада"); Text(r.interpretation.summary) } }
        }
        item { OutlinedButton(onClick = { vm.error.value = "Тестовая ошибка: проверка диалога. Данные не изменены." }) { Text("Проверить диалог ошибки") } }
    }
}

@Composable private fun DebugChoice(label: String, selected: String, choices: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(selected) }
            DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                choices.forEach { (id, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { expanded = false; onSelect(id) }) }
            }
        }
    }
}
