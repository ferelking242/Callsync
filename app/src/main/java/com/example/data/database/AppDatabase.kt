package com.example.data.database

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import android.content.Context
import com.example.data.model.Upload
import com.example.data.model.LogEntry
import kotlinx.coroutines.flow.Flow

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

@Dao
interface LogDao {
    @Query("SELECT * FROM logs ORDER BY timestamp DESC LIMIT 500")
    fun getAllLogs(): Flow<List<LogEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: LogEntry)

    @Query("DELETE FROM logs")
    suspend fun clearAllLogs()
}

@Database(entities = [Upload::class, LogEntry::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun uploadDao(): UploadDao
    abstract fun logDao(): LogDao

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
