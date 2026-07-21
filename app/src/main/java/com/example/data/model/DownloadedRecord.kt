package com.example.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "downloaded_records",
    indices = [Index(value = ["recordId"], unique = true)]
)
data class DownloadedRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordId: Long,        // server record ID
    val sha256: String,
    val name: String,
    val size: Long,
    val localPath: String,     // absolute path in app internal storage
    val downloadedAt: Long = System.currentTimeMillis()
)
