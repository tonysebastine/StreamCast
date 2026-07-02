package in.thbz.streamcast.database

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "cast_history")
data class CastHistoryItem(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val url: String,
    val deviceName: String = "Unknown Device",
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "bookmarked_urls")
data class BookmarkedUrl(
    @PrimaryKey val url: String,
    val title: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface CastDao {
    @Query("SELECT * FROM cast_history ORDER BY timestamp DESC")
    fun getCastHistory(): Flow<List<CastHistoryItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistoryItem(item: CastHistoryItem)

    @Query("DELETE FROM cast_history WHERE id = :id")
    suspend fun deleteHistoryById(id: Int)

    @Query("SELECT * FROM bookmarked_urls ORDER BY timestamp DESC")
    fun getBookmarks(): Flow<List<BookmarkedUrl>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: BookmarkedUrl)

    @Delete
    suspend fun deleteBookmark(bookmark: BookmarkedUrl)
}

@Database(entities = [CastHistoryItem::class, BookmarkedUrl::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun castDao(): CastDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "stream_cast_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class CastRepository(private val castDao: CastDao) {
    val castHistory: Flow<List<CastHistoryItem>> = castDao.getCastHistory()
    val bookmarks: Flow<List<BookmarkedUrl>> = castDao.getBookmarks()

    suspend fun insertHistoryItem(item: CastHistoryItem) = castDao.insertHistoryItem(item)
    suspend fun deleteHistoryById(id: Int) = castDao.deleteHistoryById(id)
    suspend fun insertBookmark(bookmark: BookmarkedUrl) = castDao.insertBookmark(bookmark)
    suspend fun deleteBookmark(bookmark: BookmarkedUrl) = castDao.deleteBookmark(bookmark)
}
