package ru.gorotaro.app.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.settingsData by preferencesDataStore("settings")
data class Settings(val theme: String = "system", val reduceMotion: Boolean = false, val haptics: Boolean = true, val mode: String = "COMPOSED")
class SettingsStore(private val context: Context) {
    private val theme = stringPreferencesKey("theme")
    private val motion = booleanPreferencesKey("reduce_motion")
    private val haptics = booleanPreferencesKey("haptics")
    private val mode = stringPreferencesKey("mode")
    val flow = context.settingsData.data.map { Settings(it[theme] ?: "system", it[motion] ?: false, it[haptics] ?: true, it[mode] ?: "COMPOSED") }
    suspend fun save(settings: Settings) { context.settingsData.edit { it[theme] = settings.theme; it[motion] = settings.reduceMotion; it[haptics] = settings.haptics; it[mode] = settings.mode } }
    suspend fun reset() { context.settingsData.edit { it.clear() } }
}
