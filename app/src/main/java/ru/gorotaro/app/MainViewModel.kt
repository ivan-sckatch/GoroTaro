package ru.gorotaro.app

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.gorotaro.app.data.Settings
import ru.gorotaro.domain.*
import java.util.UUID
import android.app.Application

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val app = application as GoroApplication
    val repo = app.repository
    private val settingsStore = app.settings
    val settings = settingsStore.flow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())
    val route = saved.getStateFlow("route", "home")
    val question = saved.getStateFlow("question", "")
    val topic = saved.getStateFlow("topic", "general")
    val noteStatus = MutableStateFlow("")
    private var noteJob: Job? = null
    private val noteMutex = Mutex()
    val optionA = saved.getStateFlow("a", "Сделать")
    val optionB = saved.getStateFlow("b", "Подождать")
    val cards = MutableStateFlow<List<Card>>(emptyList())
    val reading = MutableStateFlow<Reading?>(null)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val message = MutableStateFlow<String?>(null)
    val debugReport = MutableStateFlow("")
    val debugPreview = MutableStateFlow<Reading?>(null)
    private var debugPreviewGeneration = 0
    val periodic = MutableStateFlow<Map<Spread, Reading>>(emptyMap())
    val filter = MutableStateFlow<String?>(null)
    val limit = MutableStateFlow(50)
    val search = saved.getStateFlow("historySearch", "")
    val favorites = saved.getStateFlow("favoritesOnly", false)
    val latest = repo.history(null, 1).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val history = combine(filter, limit, search, favorites) { f, l, q, star -> HistoryQuery(f, l, q, star) }
        .flatMapLatest { repo.history(it.spread, it.limit, it.query, it.favorites) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private data class HistoryQuery(val spread: String?, val limit: Int, val query: String, val favorites: Boolean)
    fun searchHistory(value: String) { saved["historySearch"] = value.take(240); limit.value = 50 }
    fun favoritesOnly(value: Boolean) { saved["favoritesOnly"] = value; limit.value = 50 }
    init {
        work {
            cards.value = repo.initialize()
            if (route.value == "debug" && !BuildConfig.DEBUG) saved["route"] = "home"
            saved.get<String>("readingId")?.let { reading.value = repo.get(it) }
            if (route.value == "reading" && reading.value == null) saved["route"] = "home"
            flushNotes()
            refreshPeriods()
            if (route.value == "debug") refreshDebug()
        }
    }
    private fun work(action: suspend () -> Unit) = viewModelScope.launch {
        try { action() } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; error.value = e.message ?: "Не удалось выполнить действие. Попробуйте ещё раз." }
    }
    suspend fun refreshPeriods() { periodic.value = listOf(Spread.DAY, Spread.WEEK, Spread.GENERAL, Spread.SITUATION).mapNotNull { s -> repo.current(s)?.let { s to it } }.toMap() }
    fun refresh() = work { refreshPeriods() }
    fun navigate(route: String) {
        if (route == "debug" && !BuildConfig.DEBUG) return
        work {
            flushNotes()
            saved["route"] = route
            if (route == "home") refreshPeriods()
            if (route == "debug") { clearDebugPreview(); refreshDebug() }
        }
    }
    fun refreshDebug() {
        if (BuildConfig.DEBUG) work { debugReport.value = repo.debugReport() }
    }
    fun previewDebug(spread: Spread, ids: List<String>, orientations: List<Orientation>, variant: String) {
        if (!BuildConfig.DEBUG) return
        val generation = ++debugPreviewGeneration
        work {
            val result = repo.debugPreview(spread, ids, orientations, variant)
            if (generation == debugPreviewGeneration) debugPreview.value = result
        }
    }
    fun clearDebugPreview() { debugPreviewGeneration++; debugPreview.value = null }
    fun edit(key: String, value: String) { saved[key] = value }
    fun decision() {
        saved["requestId"] = UUID.randomUUID().toString()
        saved["question"] = ""; saved["a"] = "Сделать"; saved["b"] = "Подождать"
        navigate("decision")
    }
    fun situation() {
        if (busy.value) return
        saved["requestId"] = UUID.randomUUID().toString()
        saved["question"] = ""; saved["topic"] = "general"
        navigate("situation")
    }
    fun general() {
        if (busy.value) return
        saved["requestId"] = UUID.randomUUID().toString()
        draw(Spread.GENERAL)
    }
    fun draw(spread: Spread) {
        if (busy.value || cards.value.isEmpty()) return
        busy.value = true
        work {
            try {
                val request = if (spread == Spread.DECISION || spread == Spread.GENERAL || spread == Spread.SITUATION) saved.get<String>("requestId") ?: UUID.randomUUID().toString().also { saved["requestId"] = it } else null
                val r = repo.create(spread, if (BuildConfig.DEBUG) settings.value.mode else "COMPOSED", request, question.value, optionA.value, optionB.value, topic.value)
                show(r)
                if (spread == Spread.DAY) revealAll()
                refreshPeriods()
            } finally { busy.value = false }
        }
    }
    private suspend fun show(r: Reading) {
        flushNotes()
        if (saved.get<String>("draftId") != r.id) clearDraft()
        reading.value = repo.get(r.id) ?: r
        saved["readingId"] = r.id
        navigate("reading")
    }
    fun open(id: String) = work { repo.get(id)?.let { show(it) } }
    fun reveal(index: Int) = work { reading.value?.let { r ->
        val updated = repo.update(r.id) { if (it.canReveal(index)) it.copy(revealed = it.revealed + index) else it }
        if (reading.value?.id == r.id) reading.value = updated
    } }
    fun revealAll() = work { reading.value?.let { r ->
        val updated = repo.update(r.id) { it.copy(revealed = it.cards.indices.toSet()) }
        if (reading.value?.id == r.id) reading.value = updated
    } }
    fun noteDraft(r: Reading): Triple<String, String, String> = if (saved.get<String>("draftId") == r.id)
        Triple(saved["draftNote"] ?: r.note, saved["draftHappened"] ?: r.happened, saved["draftUseful"] ?: r.useful)
        else Triple(r.note, r.happened, r.useful)
    fun stageNotes(id: String, text: String, happened: String, useful: String) {
        saved["draftId"] = id; saved["draftNote"] = text.take(4000)
        saved["draftHappened"] = happened.take(4000); saved["draftUseful"] = useful.take(4000)
        saved["draftDirty"] = true
        noteStatus.value = "Сохраняем…"
        noteJob?.cancel()
        noteJob = work { delay(600); flushNotes() }
    }
    fun saveDraft() = work { flushNotes() }
    private fun clearDraft() {
        listOf("draftId", "draftNote", "draftHappened", "draftUseful").forEach { saved.remove<String>(it) }
        saved["draftDirty"] = false; noteStatus.value = ""
    }
    suspend fun flushNotes() = noteMutex.withLock {
        if (saved.get<Boolean>("draftDirty") != true) return@withLock
        val id = saved.get<String>("draftId") ?: return@withLock
        val draft = Triple(saved.get<String>("draftNote").orEmpty(), saved.get<String>("draftHappened").orEmpty(), saved.get<String>("draftUseful").orEmpty())
        val updated = try {
            repo.update(id) { it.copy(note = draft.first, happened = draft.second, useful = draft.third) }
        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException) noteStatus.value = "Не удалось сохранить. Нажмите «Сохранить заметки», чтобы повторить."
            throw e
        }
        if (reading.value?.id == id) reading.value = updated
        if (saved.get<String>("draftId") == id && noteDraft(updated) == draft) {
            saved["draftDirty"] = false; noteStatus.value = "Сохранено на устройстве"
        }
    }
    fun note(id: String, text: String, happened: String? = null, useful: String? = null) = work {
        val r = repo.get(id) ?: return@work
        stageNotes(id, text, happened ?: r.happened, useful ?: r.useful)
        flushNotes()
    }
    fun favorite(id: String) = work {
        val updated = repo.update(id) { it.copy(favorite = !it.favorite) }
        if (reading.value?.id == id) reading.value = updated
    }
    val pendingRestore = MutableStateFlow<ByteArray?>(null)
    val restoreCount = MutableStateFlow(0)
    fun prepareRestore(bytes: ByteArray) = work {
        val backup = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try { HistoryBackupCodec.decode(bytes) } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                throw IllegalArgumentException(e.message ?: "Резервная копия повреждена или имеет неподдерживаемый формат", e)
            }
        }
        restoreCount.value = backup.readings.size
        pendingRestore.value = bytes
    }
    fun cancelRestore() { pendingRestore.value = null }
    fun restoreBackup() {
        val bytes = pendingRestore.value ?: return
        if (busy.value) return
        pendingRestore.value = null; busy.value = true
        work { try {
            val (added, skipped) = repo.restore(bytes)
            refreshPeriods(); reading.value?.id?.let { reading.value = repo.get(it) }
            message.value = "Восстановлено: $added. Уже существовали: $skipped"
        } finally { busy.value = false } }
    }
    fun delete(id: String) = work { flushNotes(); if (saved.get<String>("draftId") == id) clearDraft(); repo.delete(id); navigate("history"); refreshPeriods() }
    fun setSettings(s: Settings) = work { settingsStore.save(s) }
    fun reset() = work {
        noteJob?.cancel(); noteMutex.withLock { clearDraft(); repo.reset() }; noteStatus.value = ""; settingsStore.reset(); reading.value = null; cancelRestore(); clearDebugPreview()
        saved.remove<String>("readingId"); saved.remove<String>("requestId")
        saved["question"] = ""; saved["topic"] = "general"; saved["a"] = "Сделать"; saved["b"] = "Подождать"
        searchHistory(""); favoritesOnly(false); filter.value = null; limit.value = 50
        navigate("home"); refreshPeriods()
    }
    fun importPackage(bytes: ByteArray) = work {
        busy.value = true
        try { ContentImporter(repo.db, getApplication()).import(bytes); message.value = "Пакет проверен и установлен" } finally { busy.value = false }
    }
}
