package ru.gorotaro.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import ru.gorotaro.app.data.Settings
import ru.gorotaro.domain.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); enableEdgeToEdge(); setContent { GoroApp() } }
}

private val light = lightColorScheme(primary = Color(0xFF52365F), background = Color(0xFFF7F2EB), surface = Color.White, onSurface = Color(0xFF271E31), onBackground = Color(0xFF271E31), secondary = Color(0xFF685A70), surfaceVariant = Color(0xFFF0E8F2), onSurfaceVariant = Color(0xFF685A70))
private val dark = darkColorScheme(primary = Color(0xFFD8B4EC), onPrimary = Color(0xFF271E31), background = Color(0xFF17131D), surface = Color(0xFF251E2E), onSurface = Color(0xFFF6F0E9), onBackground = Color(0xFFF6F0E9), secondary = Color(0xFFC9BDCF), onSurfaceVariant = Color(0xFFC9BDCF))
private val dateFormat = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru"))
private fun date(r: Reading): String {
    if (r.spreadId == "week3" && r.periodKey != null) { val d = LocalDate.parse(r.periodKey); return "${d.format(dateFormat)} — ${d.plusDays(6).format(dateFormat)}" }
    return Instant.ofEpochMilli(r.createdAt).atZone(ZoneId.of(r.zoneId)).format(dateFormat)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun GoroApp(vm: MainViewModel = viewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val route by vm.route.collectAsStateWithLifecycle()
    val reading by vm.reading.collectAsStateWithLifecycle()
    val cards by vm.cards.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val pendingRestore by vm.pendingRestore.collectAsStateWithLifecycle()
    val restoreCount by vm.restoreCount.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var reset by rememberSaveable { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val text = reading?.exportText()
        if (uri != null && text != null) scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }; vm.message.value = "Файл сохранён" } catch (e: Exception) { vm.error.value = "Не удалось сохранить файл" }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size() + n <= 64 * 1024 * 1024) { "Пакет больше 64 МБ" }; out.write(buffer, 0, n) }; out.toByteArray()
                } ?: error("Не удалось открыть пакет")
                vm.importPackage(bytes)
            } catch (e: Exception) { vm.error.value = e.message ?: "Ошибка чтения пакета" }
        }
    }
    val backupExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            vm.busy.value = true
            try {
                vm.flushNotes()
                val bytes = vm.repo.backup()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openOutputStream(uri)) { "Не удалось открыть файл" }.use { it.write(bytes) }
                }
                vm.message.value = "Резервная копия сохранена"
            } catch (e: Exception) { vm.error.value = "Не удалось сохранить резервную копию: ${e.message}" }
            finally { vm.busy.value = false }
        }
    }
    val backupImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            vm.busy.value = true
            try {
                val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openInputStream(uri)) { "Не удалось открыть файл" }.use { input ->
                        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer); if (count < 0) break
                            require(out.size() + count <= HistoryBackupCodec.maxBytes) { "Резервная копия превышает 32 МБ" }
                            out.write(buffer, 0, count)
                        }
                        out.toByteArray()
                    }
                }
                vm.prepareRestore(bytes).join()
            } catch (e: Exception) { vm.error.value = "Не удалось прочитать резервную копию: ${e.message}" }
            finally { vm.busy.value = false }
        }
    }
    LaunchedEffect(message) { message?.let { snack.showSnackbar(it); vm.message.value = null } }
    LaunchedEffect(route) { while (route == "home") { vm.refresh(); delay(60_000) } }
    BackHandler(enabled = detailId != null || route !in listOf("home", "history", "deck")) {
        if (detailId != null) detailId = null else vm.navigate(if (route == "debug") "settings" else "home")
    }
    val isDark = settings.theme == "dark" || (settings.theme == "system" && isSystemInDarkTheme())
    SideEffect {
        (context as? ComponentActivity)?.let { activity ->
            activity.window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(if (isDark) 0xFF17131D.toInt() else 0xFFF7F2EB.toInt()))
            val bars = if (isDark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
            activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        }
    }
    MaterialTheme(colorScheme = if (isDark) dark else light, typography = Typography(bodyLarge = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, lineHeight = 24.sp), titleLarge = androidx.compose.ui.text.TextStyle(fontSize = 26.sp), titleMedium = androidx.compose.ui.text.TextStyle(fontSize = 20.sp), bodySmall = androidx.compose.ui.text.TextStyle(fontSize = 14.sp))) {
        Scaffold(
            topBar = { TopAppBar(title = { Text(if (route == "reading") reading?.let { Spread.fromId(it.spreadId).title } ?: "Расклад" else when (route) { "history" -> "История"; "deck" -> "Колода"; "settings" -> "Настройки"; "debug" -> "Отладочное меню"; "decision" -> "Сравнить варианты"; "situation" -> "Разобрать ситуацию"; else -> "ГороТаро" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { if (route !in listOf("home", "history", "deck")) IconButton(onClick = { vm.navigate(if (route == "debug") "settings" else "home") }, modifier = Modifier.semantics { contentDescription = "Назад" }) { Text("‹", fontSize = 32.sp, modifier = Modifier.clearAndSetSemantics { }) } },
                actions = { if (route != "settings") IconButton(onClick = { vm.navigate("settings") }, modifier = Modifier.semantics { contentDescription = "Настройки" }) { Text("⚙", fontSize = 24.sp, modifier = Modifier.clearAndSetSemantics { }) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)) },
            bottomBar = {
                val tabs = listOf("home" to "Главная", "history" to "История", "deck" to "Колода")
                if (LocalDensity.current.fontScale >= 1.5f) {
                    Surface(color = MaterialTheme.colorScheme.surface) {
                        Row(Modifier.fillMaxWidth().navigationBarsPadding()) {
                            tabs.forEach { (id, label) -> TextButton(onClick = { vm.navigate(id) }, contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.weight(1f).heightIn(min = 52.dp).semantics { selected = route == id }) { Text(label, style = MaterialTheme.typography.bodySmall, color = if (route == id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary) } }
                        }
                    }
                } else NavigationBar { tabs.forEach { (id, label) -> NavigationBarItem(selected = route == id, onClick = { vm.navigate(id) }, icon = { Text(when(id) { "home" -> "◇"; "history" -> "◷"; else -> "▤" }, modifier = Modifier.clearAndSetSemantics { }) }, label = { Text(label) }) } }
            },
            snackbarHost = { SnackbarHost(snack) }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = 600.dp).fillMaxWidth()) {
                    when (route) {
                        "home" -> Home(vm, busy)
                        "decision" -> Decision(vm, busy)
                        "situation" -> Situation(vm, busy)
                        "reading" -> reading?.let { r -> ReadingScreen(r, settings, vm, onCard = { detailId = it }, onDelete = { deleteId = r.id }, onExport = { scope.launch { vm.flushNotes(); export.launch("ГороТаро-${r.id.take(8)}.txt") } }) }
                        "history" -> History(vm, onDelete = { deleteId = it })
                        "deck" -> Deck(cards) { detailId = it }
                        "settings" -> SettingsScreen(settings, vm, busy, { reset = true }, { importer.launch(arrayOf("application/zip", "application/octet-stream")) },
                            { backupExport.launch("ГороТаро-история-${LocalDate.now()}.json") }, { backupImport.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) })
                        "debug" -> if (BuildConfig.DEBUG) DebugScreen(vm, settings, cards, busy)
                    }
                    if (cards.isEmpty() && route == "home") CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
            }
        }
        error?.let { text -> AlertDialog(onDismissRequest = { vm.error.value = null }, title = { Text("Не удалось выполнить действие") }, text = { Text(text) }, confirmButton = { TextButton(onClick = { vm.error.value = null }) { Text("Понятно") } }) }
        detailId?.let { id -> ((if (route == "reading") reading?.cards?.find { it.cardId == id } else null) ?: cards.find { it.cardId == id })?.let { card ->
            val actualIndex = reading?.cards?.indexOfFirst { it.cardId == id } ?: -1
            var reversed by rememberSaveable(id, route, reading?.id) { mutableStateOf(route == "reading" && actualIndex >= 0 && reading?.orientationAt(actualIndex) == Orientation.REVERSED) }
            val orientation = if (reversed) Orientation.REVERSED else Orientation.UP
            AlertDialog(onDismissRequest = { detailId = null }, title = { Text(card.nameRu) }, text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    AsyncImage("file:///android_asset/${card.imageAsset}", contentDescription = "${card.nameRu} · ${orientation.label}", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp).graphicsLayer { rotationZ = if (reversed) 180f else 0f })
                    Column {
                        FilterChip(selected = !reversed, onClick = { reversed = false }, label = { Text("Прямое положение") })
                        FilterChip(selected = reversed, onClick = { reversed = true }, label = { Text("Перевёрнутое положение") })
                    }
                    reading?.let { r ->
                        val i = r.cards.indexOfFirst { it.cardId == card.cardId }
                        if (route == "reading" && i >= 0 && i in r.revealed && orientation == r.orientationAt(i)) {
                            val spread = Spread.fromId(r.spreadId)
                            Text(spread.labels[i], style = MaterialTheme.typography.titleMedium)
                            Text(r.interpretation.positions.getValue(spread.positions[i]))
                            if (r.revealed.size == r.cards.size) Text(r.interpretation.summary)
                        }
                    }
                    if (card.meaning(orientation).isBlank()) Text("В старом сохранённом раскладе нет этого толкования. Откройте карту в разделе «Колода».")
                    else { Text(card.meaning(orientation)); Text(card.action(orientation)); Text(card.reflection(orientation)) }
                    card.sources.forEach { url ->
                        TextButton(onClick = { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }) { Text("Источник значений · Labyrinthos") }
                    }
                }
            }, confirmButton = { TextButton(onClick = { detailId = null }) { Text("Закрыть") } })
        } }
        deleteId?.let { id -> AlertDialog(onDismissRequest = { deleteId = null }, title = { Text("Удалить из истории?") }, text = { Text("Расклад исчезнет из истории вместе с заметками. Закреплённые результаты дня и недели и ранее установленные паузы сохраняются.") }, confirmButton = { TextButton(onClick = { vm.delete(id); deleteId = null }) { Text("Удалить") } }, dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Отмена") } }) }
        if (reset) AlertDialog(onDismissRequest = { reset = false }, title = { Text("Сбросить все личные данные?") }, text = { Text("История, заметки, настройки и закреплённые расклады будут удалены. Действие необратимо.") }, confirmButton = { TextButton(onClick = { vm.reset(); reset = false }) { Text("Сбросить") } }, dismissButton = { TextButton(onClick = { reset = false }) { Text("Отмена") } })
        if (pendingRestore != null) AlertDialog(onDismissRequest = vm::cancelRestore, title = { Text("Восстановить историю?") },
            text = { Text("В копии $restoreCount раскладов, включая скрытые снимки. Будут добавлены отсутствующие записи. Существующие расклады и заметки останутся прежними; совпадения по дню, неделе или запросу будут пропущены. Закреплённые результаты и пауза общего расклада сохраняются.") },
            confirmButton = { TextButton(onClick = vm::restoreBackup, enabled = !busy) { Text("Восстановить") } },
            dismissButton = { TextButton(onClick = vm::cancelRestore) { Text("Отмена") } })
    }
}

@Composable internal fun Heading(text: String) { Text(text, style = MaterialTheme.typography.titleMedium) }
@Composable internal fun PrimaryButton(text: String, enabled: Boolean = true, action: () -> Unit) { Button(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp)) { Text(text) } }
@Composable internal fun Panel(content: @Composable ColumnScope.() -> Unit) { Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) } }
internal val contentPadding = PaddingValues(20.dp)

@Composable private fun Home(vm: MainViewModel, busy: Boolean) {
    val periods by vm.periodic.collectAsStateWithLifecycle()
    val cards by vm.cards.collectAsStateWithLifecycle()
    val latest by vm.latest.collectAsStateWithLifecycle()
    var practice by rememberSaveable { mutableStateOf(false) }
    LazyColumn(modifier = Modifier.testTag("home-list"), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { Text("Время прислушаться к себе", style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif) }
        item { Panel {
            Heading("Разобрать ситуацию")
            Text("Три карты: что происходит, что осложняет ситуацию и на что можно опереться.")
            Button(onClick = vm::situation, enabled = !busy && cards.isNotEmpty(), modifier = Modifier.fillMaxWidth().testTag("situation3-start")) { Text("Новая ситуация") }
            periods[Spread.SITUATION]?.let { r -> TextButton(onClick = { vm.open(r.id) }) { Text("Продолжить предыдущую ситуацию") } }
        } }
        item { Panel {
            Heading("Сравнить два варианта")
            Text("Ресурсы двух путей и общая проверка перед выбором.")
            OutlinedButton(onClick = vm::decision, enabled = !busy && cards.isNotEmpty(), modifier = Modifier.fillMaxWidth().testTag("decision3-start")) { Text("Сравнить варианты") }
        } }
        latest.firstOrNull()?.let { r -> item { Panel {
            Heading("Вернуться к последней записи")
            Text(r.question.ifBlank { Spread.fromId(r.spreadId).title })
            Text(date(r), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { vm.open(r.id) }, modifier = Modifier.testTag("latest-reading")) { Text("Открыть запись") }
        } } }
        item { TextButton(onClick = { practice = !practice }, modifier = Modifier.testTag("practice-toggle").semantics { stateDescription = if (practice) "Развёрнуто" else "Свёрнуто" }) { Text("Моя практика ${if (practice) "−" else "+"}") } }
        if (practice) items(listOf(Spread.DAY, Spread.WEEK), key = { it.id }) { spread -> Panel {
            Heading(spread.title)
            Text(if (spread == Spread.DAY) "Одна карта для наблюдения за днём." else "Фокус, препятствие и действие на неделю. Можно вернуться позже и сравнить впечатления.")
            Button(onClick = { vm.draw(spread) }, enabled = !busy && cards.isNotEmpty(), modifier = Modifier.fillMaxWidth().testTag("${spread.id}-start")) {
                Text(if (periods[spread] != null) "Открыть расклад" else if (spread == Spread.DAY) "Вытянуть карту" else "Начать расклад")
            }
        } }
        item { Text("Толкования помогают размышлять; окончательный выбор остаётся за вами.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
    }
}

@Composable private fun Situation(vm: MainViewModel, busy: Boolean) {
    val question by vm.question.collectAsStateWithLifecycle()
    val topic by vm.topic.collectAsStateWithLifecycle()
    LazyColumn(contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.imePadding().testTag("situation-list")) {
        item { Text("Выберите тему или продолжите без уточнений. Вопрос необязателен.") }
        item { Column { Topic.entries.forEach { t -> FilterChip(selected = topic == t.id, onClick = { vm.edit("topic", t.id) }, label = { Text(t.label) }) } } }
        item { OutlinedTextField(question, { vm.edit("question", it.take(240)) }, label = { Text("Что хотите прояснить?") }, supportingText = { Text("${question.length}/240 · необязательно") }, modifier = Modifier.fillMaxWidth().testTag("situation-question"), minLines = 3) }
        item { Text("Тема задаёт ракурс толкования. Ваш вопрос сохранится в истории как контекст; приложение не анализирует его текст.", style = MaterialTheme.typography.bodySmall) }
        item { PrimaryButton(if (busy) "Сохраняем расклад…" else "Вытянуть три карты", !busy) { vm.draw(Spread.SITUATION) } }
    }
}

@Composable private fun Decision(vm: MainViewModel, busy: Boolean) {
    val question by vm.question.collectAsStateWithLifecycle(); val a by vm.optionA.collectAsStateWithLifecycle(); val b by vm.optionB.collectAsStateWithLifecycle()
    LazyColumn(contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.imePadding().testTag("decision-list")) {
        item { Text("Назовите вопрос и два возможных пути. Расклад поможет сравнить их, сохраняя выбор за вами.") }
        item { OutlinedTextField(question, { vm.edit("question", it.take(240)) }, label = { Text("Ваш вопрос") }, supportingText = { Text("${question.length}/240") }, modifier = Modifier.fillMaxWidth().testTag("question-input"), minLines = 3) }
        item { OutlinedTextField(a, { vm.edit("a", it.take(80)) }, label = { Text("Вариант А") }, supportingText = { Text("${a.length}/80") }, modifier = Modifier.fillMaxWidth().testTag("option-a-input")) }
        item { OutlinedTextField(b, { vm.edit("b", it.take(80)) }, label = { Text("Вариант Б") }, supportingText = { Text("${b.length}/80") }, modifier = Modifier.fillMaxWidth().testTag("option-b-input")) }
        item { PrimaryButton(if (busy) "Сохраняем расклад…" else "Вытянуть три карты", !busy && question.isNotBlank() && a.isNotBlank() && b.isNotBlank()) { vm.draw(Spread.DECISION) } }
    }
}

@Composable private fun ReadingScreen(r: Reading, settings: Settings, vm: MainViewModel, onCard: (String) -> Unit, onDelete: () -> Unit, onExport: () -> Unit) {
    val spread = Spread.fromId(r.spreadId)
    val draft = vm.noteDraft(r)
    var note by rememberSaveable(r.id) { mutableStateOf(draft.first) }
    var happened by rememberSaveable(r.id) { mutableStateOf(draft.second) }
    var useful by rememberSaveable(r.id) { mutableStateOf(draft.third) }
    var retrospective by rememberSaveable(r.id) { mutableStateOf(happened.isNotBlank() || useful.isNotBlank()) }
    var showReflection by rememberSaveable(r.id) { mutableStateOf(false) }
    val noteStatus by vm.noteStatus.collectAsStateWithLifecycle()
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) { vm.saveDraft() }
    val haptic = LocalHapticFeedback.current
    val all = r.revealed.size == r.cards.size
    val listState = rememberLazyListState()
    var previousRevealCount by rememberSaveable(r.id) { mutableIntStateOf(r.revealed.size) }
    LaunchedEffect(r.id, r.revealed) {
        if (r.revealed.size > previousRevealCount) {
            val index = r.revealed.maxOrNull() ?: 0
            val item = 3 + (if (r.question.isNotBlank() || spread == Spread.SITUATION) 1 else 0) + index
            if (settings.reduceMotion) listState.scrollToItem(item) else listState.animateScrollToItem(item)
        }
        previousRevealCount = r.revealed.size
    }
    LazyColumn(modifier = Modifier.testTag("reading-list"), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(20.dp), state = listState) {
        item { Text(date(r), color = MaterialTheme.colorScheme.secondary) }
        item { Text(if (r.mode == "COMPOSED") "Толкование по значениям карт" else if (r.contentVersion.startsWith("demo")) "Демонстрационное толкование" else "Подготовленное толкование", style = MaterialTheme.typography.bodySmall) }
        item { OutlinedButton(onClick = { vm.favorite(r.id) }, modifier = Modifier.testTag("reading-favorite")) { Text(if (r.favorite) "★ В избранном" else "☆ Добавить в избранное") } }
        if (r.question.isNotBlank() || spread == Spread.SITUATION) item { Panel {
            if (spread == Spread.SITUATION) Text(Topic.fromId(r.topic).label)
            if (r.question.isNotBlank()) Text(r.question)
            if (spread == Spread.DECISION) { Text("А: ${r.optionA}"); Text("Б: ${r.optionB}") }
        } }
        // Vertical cards preserve readability at 320dp and 200% font scaling.
        items(r.cards.size, key = { "${r.id}-$it" }) { index ->
            val card = r.cards[index]; val open = index in r.revealed
            val canOpen = r.canReveal(index)
            val alpha by animateFloatAsState(if (open) 1f else 0.86f, tween(if (settings.reduceMotion) 0 else 250), label = "reveal")
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Heading(spread.labels[index])
                Box(Modifier.widthIn(max = if (r.cards.size == 1) 260.dp else 210.dp).fillMaxWidth().aspectRatio(card.aspectRatio).graphicsLayer { this.alpha = alpha }
                    .clickable(enabled = open || canOpen) { if (open) onCard(card.cardId) else { vm.reveal(index); if (settings.haptics) haptic.performHapticFeedback(HapticFeedbackType.LongPress) } }
                    .clearAndSetSemantics {
                        contentDescription = if (open) "${spread.labels[index]}, ${card.nameRu}, ${r.orientationAt(index).label}" else "Позиция ${spread.labels[index]}, карта закрыта"
                        role = Role.Button
                        if (!open && !canOpen) { disabled(); stateDescription = "Сначала откройте предыдущую карту" }
                        else onClick(if (open) "Подробнее о карте" else "Открыть") { if (open) onCard(card.cardId) else vm.reveal(index); true }
                    }) {
                    AsyncImage(if (open) "file:///android_asset/${card.imageAsset}" else "file:///android_asset/card_back.webp", contentDescription = null, contentScale = ContentScale.Fit, error = painterResource(R.drawable.card_placeholder), modifier = Modifier.fillMaxSize().graphicsLayer { rotationZ = if (open && r.orientationAt(index) == Orientation.REVERSED) 180f else 0f })
                }
                Text(if (open) card.nameRu else if (canOpen) "Нажмите, чтобы открыть" else "Сначала откройте предыдущую карту", style = MaterialTheme.typography.bodySmall)
                if (open) Text(r.orientationAt(index).label, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (!all) item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val next = r.cards.indices.first { it !in r.revealed }
            PrimaryButton("Открыть следующую карту") { vm.reveal(next) }
            TextButton(onClick = vm::revealAll) { Text("Открыть все") }
            Text("Общий итог появится после раскрытия всех карт.", style = MaterialTheme.typography.bodySmall)
        } }
        if (all) {
            item { Panel { Heading("Итог расклада"); Text(r.interpretation.summary) } }
            item { Panel { Heading("Полезный шаг"); Text(r.interpretation.action)
                TextButton(onClick = { showReflection = !showReflection }) { Text(if (showReflection) "Скрыть вопрос себе" else "Вопрос себе") }
                if (showReflection) Text(r.interpretation.reflection)
            } }
            items(spread.positions.size, key = { "explanation-$it" }) { index ->
                var expanded by rememberSaveable(r.id, index) { mutableStateOf(false) }
                Panel {
                    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("position-$index-toggle").semantics { stateDescription = if (expanded) "Развёрнуто" else "Свёрнуто" }) {
                        Text("${spread.labels[index]} · ${r.cards[index].nameRu} ${if (expanded) "−" else "+"}")
                    }
                    if (expanded) Text(r.interpretation.positions.getValue(spread.positions[index]), modifier = Modifier.testTag("position-$index-explanation"))
                }
            }
            item { OutlinedTextField(note, { note = it.take(4000); vm.stageNotes(r.id, note, happened, useful) }, label = { Text("Личная заметка") }, modifier = Modifier.fillMaxWidth().testTag("note-input"), minLines = 3) }
            item { TextButton(onClick = { retrospective = !retrospective }, modifier = Modifier.testTag("retrospective-toggle")) { Text(if (retrospective) "Свернуть дополнения" else "Вернуться и дополнить") } }
            if (retrospective) {
            item { OutlinedTextField(happened, { happened = it.take(4000); vm.stageNotes(r.id, note, happened, useful) }, label = { Text("Что произошло?") }, modifier = Modifier.fillMaxWidth().testTag("happened-input"), minLines = 3) }
            item { OutlinedTextField(useful, { useful = it.take(4000); vm.stageNotes(r.id, note, happened, useful) }, label = { Text("Что оказалось полезным?") }, modifier = Modifier.fillMaxWidth().testTag("useful-input"), minLines = 3) }
            }
            item { Text(noteStatus.ifBlank { "Записи необязательны. Изменения сохраняются автоматически." }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("note-status")) }
            item { PrimaryButton("Сохранить заметки") { vm.note(r.id, note, happened, useful) } }
            item { OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Экспортировать текст") } }
            item { TextButton(onClick = onDelete) { Text("Удалить из истории") } }
        }
    }
}

@Composable private fun History(vm: MainViewModel, onDelete: (String) -> Unit) {
    val history by vm.history.collectAsStateWithLifecycle(); val filter by vm.filter.collectAsStateWithLifecycle(); val limit by vm.limit.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle(); val favorites by vm.favorites.collectAsStateWithLifecycle()
    LazyColumn(contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.testTag("history-list")) {
        item { OutlinedTextField(search, vm::searchHistory, label = { Text("Поиск по вопросу, теме и заметкам") }, modifier = Modifier.fillMaxWidth().testTag("history-search"), singleLine = true) }
        item { FilterChip(selected = favorites, onClick = { vm.favoritesOnly(!favorites) }, label = { Text("Только избранное") }) }
        item { Column { Text("Показать расклады"); (listOf(null to "Все") + Spread.entries.map { it.id to it.title }).forEach { (id, name) -> FilterChip(selected = filter == id, onClick = { vm.filter.value = id; vm.limit.value = 50 }, label = { Text(name) }) } } }
        if (history.isEmpty()) item { Panel {
            if (search.isNotBlank() || favorites || filter != null) { Heading("Ничего не найдено"); Text("Измените запрос или фильтры."); TextButton(onClick = { vm.searchHistory(""); vm.favoritesOnly(false); vm.filter.value = null }) { Text("Сбросить фильтры") } }
            else { Heading("Здесь появятся ваши расклады"); Text("Сохранённые карты и тексты останутся прежними при повторном открытии."); PrimaryButton("Первый расклад") { vm.navigate("home") } }
        } }
        items(history, key = { it.id }) { r ->
            Panel {
                TextButton(onClick = { vm.open(r.id) }) { Text("${Spread.fromId(r.spreadId).title} · ${date(r)}") }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val revealed = 0 in r.revealed
                    AsyncImage(if (revealed) "file:///android_asset/thumbs/${r.cards[0].cardId}.webp" else "file:///android_asset/card_back.webp", null, modifier = Modifier.width(48.dp).aspectRatio(r.cards[0].aspectRatio).graphicsLayer { rotationZ = if (revealed && r.orientationAt(0) == Orientation.REVERSED) 180f else 0f }, contentScale = ContentScale.Fit)
                    Text(if (r.question.isNotBlank()) r.question else if (r.revealed.size == r.cards.size) r.interpretation.summary.take(140) else "Расклад сохранён. Откройте карты, когда будете готовы.", modifier = Modifier.weight(1f))
                }
                TextButton(onClick = { onDelete(r.id) }) { Text("Удалить") }
                TextButton(onClick = { vm.favorite(r.id) }) { Text(if (r.favorite) "★ Убрать из избранного" else "☆ В избранное") }
            }
        }
        if (history.size >= limit) item { PrimaryButton("Показать ещё") { vm.limit.value += 50 } }
    }
}

@Composable private fun Deck(cards: List<Card>, onCard: (String) -> Unit) {
    LazyColumn(contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("22 старших аркана · 44 значения. Откройте карту, чтобы сравнить прямое и перевёрнутое положения.", color = MaterialTheme.colorScheme.secondary) }
        items(cards, key = { it.cardId }) { card ->
            Card(onClick = { onCard(card.cardId) }, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage("file:///android_asset/thumbs/${card.cardId}.webp", null, modifier = Modifier.width(76.dp).aspectRatio(card.aspectRatio), contentScale = ContentScale.Fit)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { Heading(card.nameRu); Text(card.meaning, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable private fun SettingsScreen(s: Settings, vm: MainViewModel, busy: Boolean, reset: () -> Unit, import: () -> Unit, backup: () -> Unit, restore: () -> Unit) {
    LazyColumn(contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.testTag("settings-list")) {
        item { Panel { Heading("Оформление"); listOf("system" to "Как в системе", "light" to "Светлая тема", "dark" to "Тёмная тема").forEach { (id, title) -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { vm.setSettings(s.copy(theme = id)) }) { RadioButton(s.theme == id, { vm.setSettings(s.copy(theme = id)) }); Text(title, Modifier.weight(1f)) } } } }
        item { Panel { Row(verticalAlignment = Alignment.CenterVertically) { Text("Снизить анимацию", Modifier.weight(1f)); Switch(s.reduceMotion, { vm.setSettings(s.copy(reduceMotion = it)) }) }; Row(verticalAlignment = Alignment.CenterVertically) { Text("Тактильный отклик", Modifier.weight(1f)); Switch(s.haptics, { vm.setSettings(s.copy(haptics = it)) }) } } }
        item { Panel {
            Heading("Толкования")
            Text("Во всех новых раскладах каждая карта с равной вероятностью выпадает прямо или перевёрнуто. Положение сохраняется вместе с толкованием.")
            if (BuildConfig.DEBUG) {
                Text("Прототип позволяет использовать составные тексты и подготовленные толкования. Тестовые параметры доступны в отладочном меню.")
            } else Text("Встроенные толкования работают без интернета и дополнительных загрузок.")
            if (BuildConfig.DEBUG) PrimaryButton("Установить набор толкований", !busy, import)
        } }
        if (BuildConfig.DEBUG) item { OutlinedButton(onClick = { vm.navigate("debug") }, modifier = Modifier.fillMaxWidth().testTag("debug-menu-entry")) { Text("Отладочное меню") } }
        item { Panel {
            Heading("Резервная копия истории")
            Text("Сохраните расклады, заметки и избранное в файл, чтобы восстановить их на этом или другом устройстве. Включены закреплённые результаты и пауза общего расклада. Настройки и наборы толкований не входят в копию.")
            Text("Файл содержит личные тексты и не зашифрован. Выберите место, которому доверяете.", style = MaterialTheme.typography.bodySmall)
            PrimaryButton("Сохранить резервную копию", !busy, backup)
            OutlinedButton(onClick = restore, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Восстановить из файла") }
        } }
        item { Panel { Heading("Приватность"); Text("Вопросы, расклады и заметки хранятся на устройстве. Приложение работает без сети и не отправляет ваши тексты наружу. Автоматическое резервное копирование отключено."); OutlinedButton(onClick = reset) { Text("Сбросить личные данные") } } }
        item { Text("ГороТаро ${BuildConfig.VERSION_NAME} · ${if (BuildConfig.DEBUG) "отладочная версия" else "значения карт"}", style = MaterialTheme.typography.bodySmall) }
    }
}
