package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "jobs")
data class JobEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val pageCount: Int = 0,
    val status: String = "RUNNING", // RUNNING, PAUSED, COMPLETED
    val targetLang: String = "en"
)
