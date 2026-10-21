package dev.indi.appstore

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "catalog")
data class CatalogCache(@PrimaryKey val id: Int = 1, val origin: String, val payload: String, val etag: String)

@Entity(tableName = "downloads")
data class DownloadEntry(
    @PrimaryKey val id: String, val packageName: String, val name: String,
    val versionCode: Long, val versionName: String, val status: String = "QUEUED",
    val bytes: Long = 0, val total: Long, val filePath: String = "", val error: String = "",
    val iconUrl: String, val payload: String, val queuedAt: Long = System.currentTimeMillis(),
)

@Dao
interface StoreDao {
    @Query("SELECT * FROM catalog WHERE id = 1") suspend fun catalog(): CatalogCache?
    @Query("SELECT * FROM catalog WHERE id = 1") fun observeCatalog(): Flow<CatalogCache?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun cache(catalog: CatalogCache)
    @Query("DELETE FROM catalog") suspend fun clearCatalog()
    @Query("SELECT * FROM downloads ORDER BY queuedAt DESC") fun observeDownloads(): Flow<List<DownloadEntry>>
    @Query("SELECT * FROM downloads WHERE id = :id") suspend fun download(id: String): DownloadEntry?
    @Query("SELECT * FROM downloads WHERE status IN ('QUEUED','DOWNLOADING') ORDER BY queuedAt LIMIT 1") suspend fun nextDownload(): DownloadEntry?
    @Query("SELECT * FROM downloads WHERE status = 'INSTALLING'") suspend fun installing(): List<DownloadEntry>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putDownload(entry: DownloadEntry)
    @Query("UPDATE downloads SET status = :status, error = :error WHERE id = :id") suspend fun status(id: String, status: String, error: String = "")
    @Query("UPDATE downloads SET status = 'DOWNLOADING', bytes = :bytes WHERE id = :id AND status IN ('QUEUED','DOWNLOADING')") suspend fun progress(id: String, bytes: Long)
    @Query("UPDATE downloads SET status = 'READY', bytes = total, filePath = :path, error = '' WHERE id = :id AND status = 'DOWNLOADING'") suspend fun ready(id: String, path: String): Int
    @Query("UPDATE downloads SET status = 'CANCELLED' WHERE status IN ('QUEUED','DOWNLOADING','READY')") suspend fun cancelPending()
}

@Database(entities = [CatalogCache::class, DownloadEntry::class], version = 1, exportSchema = false)
abstract class StoreDatabase : RoomDatabase() { abstract fun storeDao(): StoreDao }
