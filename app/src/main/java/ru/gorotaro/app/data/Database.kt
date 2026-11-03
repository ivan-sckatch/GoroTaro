package ru.gorotaro.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "readings", indices = [Index(value = ["spreadId", "periodKey"], unique = true), Index(value = ["requestId"], unique = true), Index(value = ["comboKey"]), Index(value = ["createdAt", "id"])])
data class ReadingEntity(@PrimaryKey val id: String, val requestId: String?, val periodKey: String?, val spreadId: String, val comboKey: String, val createdAt: Long, val snapshot: String, val hidden: Boolean = false,
    @ColumnInfo(defaultValue = "''") val searchText: String = "",
    @ColumnInfo(defaultValue = "0") val favorite: Boolean = false)
@Entity(tableName = "variant_state", primaryKeys = ["comboKey", "contentVersion"])
data class VariantEntity(val comboKey: String, val contentVersion: String, val state: String)
@Entity(tableName = "cards") data class CardEntity(@PrimaryKey val cardId: String, val snapshot: String)
@Entity(tableName = "exact_content", primaryKeys = ["comboKey", "version"])
data class ExactEntity(val comboKey: String, val version: String, val snapshot: String)
@Entity(tableName = "metadata") data class MetaEntity(@PrimaryKey val key: String, val value: String)

@Dao interface GoroDao {
    @Query("SELECT * FROM readings WHERE spreadId=:spread AND periodKey=:period LIMIT 1") suspend fun period(spread: String, period: String): ReadingEntity?
    @Query("SELECT * FROM readings WHERE requestId=:request LIMIT 1") suspend fun request(request: String): ReadingEntity?
    // Hidden snapshots still enforce cooldowns after history deletion.
    @Query("SELECT * FROM readings WHERE spreadId=:spread ORDER BY createdAt DESC,id DESC LIMIT 1") suspend fun latest(spread: String): ReadingEntity?
    @Query("SELECT * FROM readings WHERE id=:id") suspend fun reading(id: String): ReadingEntity?
    @Query("SELECT * FROM readings WHERE hidden=0 AND (:spread IS NULL OR spreadId=:spread) ORDER BY createdAt DESC,id DESC LIMIT :limit") fun history(spread: String?, limit: Int): Flow<List<ReadingEntity>>
    @Query("SELECT * FROM readings WHERE hidden=0 AND (:spread IS NULL OR spreadId=:spread) AND (:favorites=0 OR favorite=1) AND instr(searchText,:query)>0 ORDER BY createdAt DESC,id DESC LIMIT :limit")
    fun searchHistory(spread: String?, query: String, favorites: Boolean, limit: Int): Flow<List<ReadingEntity>>
    @Query("SELECT * FROM readings ORDER BY createdAt,id") suspend fun allReadings(): List<ReadingEntity>
    @Query("SELECT * FROM readings ORDER BY createdAt,id LIMIT :limit OFFSET :offset") suspend fun readingPage(offset: Int, limit: Int): List<ReadingEntity>
    @Query("SELECT * FROM variant_state") suspend fun allBags(): List<VariantEntity>
    @Query("SELECT COUNT(*) FROM readings") suspend fun readingCount(): Int
    @Query("SELECT COALESCE(SUM(length(CAST(snapshot AS BLOB))),0) FROM readings") suspend fun readingBytes(): Long
    @Query("SELECT COALESCE(SUM(length(CAST(state AS BLOB))),0) FROM variant_state") suspend fun bagBytes(): Long
    @Insert suspend fun insertReading(reading: ReadingEntity)
    @Update suspend fun updateReading(reading: ReadingEntity)
    @Query("DELETE FROM readings WHERE id=:id") suspend fun deleteReading(id: String)
    @Query("SELECT * FROM variant_state WHERE comboKey=:key AND contentVersion=:version") suspend fun bag(key: String, version: String): VariantEntity?
    @Upsert suspend fun saveBag(bag: VariantEntity)
    @Upsert suspend fun saveCards(cards: List<CardEntity>)
    @Query("SELECT * FROM cards ORDER BY cardId") suspend fun cards(): List<CardEntity>
    @Query("SELECT * FROM exact_content WHERE comboKey=:key AND version=:version") suspend fun exact(key: String, version: String): ExactEntity?
    @Upsert suspend fun saveExact(records: List<ExactEntity>)
    @Query("SELECT * FROM metadata WHERE `key`=:key") suspend fun meta(key: String): MetaEntity?
    @Upsert suspend fun saveMeta(meta: MetaEntity)
    @Query("DELETE FROM readings") suspend fun clearReadings()
    @Query("DELETE FROM variant_state") suspend fun clearBags()
    @Query("SELECT COUNT(*) FROM readings WHERE hidden=0") suspend fun visibleReadingCount(): Int
    @Query("SELECT COUNT(*) FROM readings WHERE hidden=1") suspend fun hiddenReadingCount(): Int
    @Query("SELECT COUNT(*) FROM variant_state") suspend fun bagCount(): Int
    @Query("SELECT COUNT(*) FROM exact_content WHERE version=:version") suspend fun exactCount(version: String): Int
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE readings ADD COLUMN searchText TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE readings ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
    }
}

@Database(entities = [ReadingEntity::class, VariantEntity::class, CardEntity::class, ExactEntity::class, MetaEntity::class], version = 2, exportSchema = true)
abstract class GoroDatabase : RoomDatabase() { abstract fun dao(): GoroDao }
