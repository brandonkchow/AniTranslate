package com.example.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.data.models.Bubble

@Entity(
    tableName = "pages",
    foreignKeys = [
        ForeignKey(
            entity = JobEntity::class,
            parentColumns = ["id"],
            childColumns = ["jobId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["jobId"])]
)
data class PageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val jobId: Long,
    val pageIndex: Int,
    val originalImageUri: String,
    val originalCachePath: String,
    val workingScaledPath: String = "",
    val wipedImagePath: String = "",
    val finalImagePath: String = "",
    val status: PageStatus = PageStatus.QUEUED,
    val bubblesJson: String = "[]",
    val activeSlotName: String = "",
    val errorMessage: String = "",
    val waitingUntilEpochMs: Long = 0L,
    val lastStageAttempted: String = "", // DETECT, TRANSLATE, WIPE, RENDER
    val width: Int = 0,
    val height: Int = 0
) {
    fun getBubbles(): List<Bubble> {
        return Bubble.parseListFromJson(bubblesJson)
    }

    fun remainingWaitSeconds(nowMs: Long = System.currentTimeMillis()): Long {
        val diff = waitingUntilEpochMs - nowMs
        return if (diff > 0) (diff + 999) / 1000 else 0L
    }
}
