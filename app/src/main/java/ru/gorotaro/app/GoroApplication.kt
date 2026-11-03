package ru.gorotaro.app

import android.app.Application
import androidx.room.Room
import ru.gorotaro.app.data.*
import ru.gorotaro.domain.*
import java.time.Clock
import java.time.ZoneId
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache

class GoroApplication : Application(), ImageLoaderFactory {
    val database by lazy { Room.databaseBuilder(this, GoroDatabase::class.java, "gorotaro.db").addMigrations(MIGRATION_1_2).build() }
    val repository by lazy { ReadingRepository(database, DrawEngine(SecureRandomSource(), Clock.systemUTC(), ZoneProvider { ZoneId.systemDefault() }), this) }
    val settings by lazy { SettingsStore(this) }
    override fun newImageLoader() = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizeBytes(16 * 1024 * 1024).build() }
        .diskCache(null)
        .build()
}
