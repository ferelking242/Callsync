package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "logs")
data class LogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tag: String,
    val message: String,
    val isError: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)
