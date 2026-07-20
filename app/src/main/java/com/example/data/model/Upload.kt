package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "uploads")
data class Upload(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sha256: String,
    val path: String,
    val name: String,
    val size: Long,
    val status: String, // "PENDING", "COMPLETED", "FAILED"
    val uploadedAt: Long? = null,
    val retryCount: Int = 0,
    val errorMessage: String? = null
)
