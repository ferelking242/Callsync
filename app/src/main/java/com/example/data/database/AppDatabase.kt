package com.example.data.database

import android.content.Context
import androidx.room.*
import com.example.data.model.DownloadedRecord
import com.example.data.model.LogEntry
import com.example.data.model.Upload
import kotlinx.coroutines.flow.Flow

// ── Upload DAO ────────────────────────────────────────────────────────────────

@Dao
interface UploadDao {
    @Query("SELECT * FROM uploads ORDER BY id DESC")
    fun getAllUploads(): Flow<List<Upload>>

    @Query("SELECT * FROM uploads WHERE status = 'PENDING' OR status = 'FAILED' ORDER BY id ASC")
    suspend fun getPendingUploads(): List<Upload>

    @Query("SELECT * FROM uploads WHERE sha256 = :sha256 LIMIT 1")
    suspend fun getUploadBySha256(sha256: String): Upload?

    @Query("SELECT * FROM uploads WHERE path = :path LIMIT 1")
    suspend fun getUploadByPath(path: String): Upload?

    @Query("SELECT * FROM uploads WHERE status = 'UPLOADING' ORDER BY id ASC")
    suspend fun getUploadingUploads(): List<Upload>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertUpload(upload: Upload): Long

    @Update
    suspend fun updateUpload(upload: Upload)

    @Query("DELETE FROM uploads WHERE id = :id")
    suspend fun deleteUploadById(id: Long)

    @Query("DELETE FROM uploads")
    suspend fun clearAllUploads()
}

// ── Log DAO ───────────────────────────────────────────────────────────────────

@Dao
interface LogDao {
    @Query("SELECT * FROM logs ORDER BY timestamp DESC LIMIT 500")
    fun getAllLogs(): Flow<List<LogEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: LogEntry)

    @Query("DELETE FROM logs")
    suspend fun clearAllLogs()
}

// ── Downloaded Record DAO ─────────────────────────────────────────────────────

@Dao
interface DownloadedRecordDao {
    @Query("SELECT * FROM downloaded_records ORDER BY downloadedAt DESC")
    fun getAllDownloaded(): Flow<List<DownloadedRecord>>

    @Query("SELECT * FROM downloaded_records")
    suspend fun getAllDownloadedSync(): List<DownloadedRecord>

    @Query("SELECT * FROM downloaded_records WHERE recordId = :recordId LIMIT 1")
    suspend fun getByRecordId(recordId: Long): DownloadedRecord?

    @Query("SELECT COUNT(*) FROM downloaded_records")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: DownloadedRecord): Long

    @Query("DELETE FROM downloaded_records WHERE recordId = :recordId")
    suspend fun deleteByRecordId(recordId: Long)

    @Query("DELETE FROM downloaded_records")
    suspend fun clearAll()
}

// ── Database ──────────────────────────────────────────────────────────────────

@Database(
    entities = [Upload::class, LogEntry::class, DownloadedRecord::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun uploadDao(): UploadDao
    abstract fun logDao(): LogDao
    abstract fun downloadedRecordDao(): DownloadedRecordDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "callsync_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
