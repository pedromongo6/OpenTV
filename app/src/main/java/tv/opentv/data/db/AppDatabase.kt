// language: Kotlin, file: data/db/AppDatabase.kt, target: Android 14
// *Room cache: sources, favourites, watch-later, resume positions, EPG snapshot.*

package tv.opentv.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** JSON-encoded SourceKind. */
    val kindJson: String,
    val addedAt: Long,
)

@Entity(tableName = "favourites", primaryKeys = ["itemId", "kind"])
data class FavouriteEntity(
    val itemId: String,
    val kind: String,           // "live" | "movie" | "series"
    val title: String,
    val url: String,
    val logo: String?,
    val sourceId: Long,
    val addedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "watch_later", primaryKeys = ["itemId"])
data class WatchLaterEntity(
    val itemId: String,
    val title: String,
    val url: String,
    val poster: String?,
    val isSeries: Boolean,
    val sourceId: Long,
    val addedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "resume")
data class ResumeEntity(
    @PrimaryKey val itemId: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val fraction: Float get() = if (durationMs <= 0) 0f else positionMs.toFloat() / durationMs
}

@Entity(tableName = "epg_cache", primaryKeys = ["channelId", "startUtc"])
data class EpgEntity(
    val channelId: String,
    val startUtc: Long,
    val endUtc: Long,
    val title: String,
    val description: String?,
    val fetchedAt: Long = System.currentTimeMillis(),
)

@Dao
interface SourceDao {
    @Query("SELECT * FROM sources ORDER BY addedAt ASC")
    fun all(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources ORDER BY addedAt ASC")
    suspend fun allOnce(): List<SourceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(s: SourceEntity): Long

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM sources")
    suspend fun count(): Int
}

@Dao
interface FavouriteDao {
    @Query("SELECT * FROM favourites WHERE kind = :kind ORDER BY addedAt DESC")
    fun byKind(kind: String): Flow<List<FavouriteEntity>>

    @Query("SELECT * FROM favourites ORDER BY addedAt DESC")
    fun all(): Flow<List<FavouriteEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favourites WHERE itemId = :id AND kind = :kind)")
    suspend fun isFavourite(id: String, kind: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(f: FavouriteEntity)

    @Query("DELETE FROM favourites WHERE itemId = :id AND kind = :kind")
    suspend fun delete(id: String, kind: String)
}

@Dao
interface WatchLaterDao {
    @Query("SELECT * FROM watch_later ORDER BY addedAt DESC")
    fun all(): Flow<List<WatchLaterEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM watch_later WHERE itemId = :id)")
    suspend fun contains(id: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(w: WatchLaterEntity)

    @Query("DELETE FROM watch_later WHERE itemId = :id")
    suspend fun delete(id: String)
}

@Dao
interface ResumeDao {
    @Query("SELECT * FROM resume WHERE itemId = :id")
    suspend fun get(id: String): ResumeEntity?

    @Query("SELECT * FROM resume WHERE durationMs > 0 AND positionMs > 0 ORDER BY updatedAt DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<ResumeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(r: ResumeEntity)

    @Query("DELETE FROM resume WHERE itemId = :id")
    suspend fun delete(id: String)
}

@Dao
interface EpgDao {
    @Query("SELECT * FROM epg_cache WHERE channelId = :id AND endUtc >= :since ORDER BY startUtc ASC LIMIT :limit")
    suspend fun forChannel(id: String, since: Long, limit: Int): List<EpgEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<EpgEntity>)

    @Query("DELETE FROM epg_cache WHERE fetchedAt < :before")
    suspend fun prune(before: Long)

    @Query("SELECT MAX(fetchedAt) FROM epg_cache WHERE channelId = :id")
    suspend fun lastFetch(id: String): Long?
}

@Database(
    entities = [
        SourceEntity::class,
        FavouriteEntity::class,
        WatchLaterEntity::class,
        ResumeEntity::class,
        EpgEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sources(): SourceDao
    abstract fun favourites(): FavouriteDao
    abstract fun watchLater(): WatchLaterDao
    abstract fun resume(): ResumeDao
    abstract fun epg(): EpgDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "opentv.db",
            )
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
