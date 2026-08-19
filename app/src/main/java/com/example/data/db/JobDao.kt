package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface JobDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertJob(job: JobEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPages(pages: List<PageEntity>): List<Long>

    @Update
    suspend fun updateJob(job: JobEntity)

    @Update
    suspend fun updatePage(page: PageEntity)

    @Query("SELECT * FROM jobs ORDER BY createdAt DESC")
    fun getAllJobs(): Flow<List<JobEntity>>

    @Query("SELECT * FROM jobs WHERE id = :jobId LIMIT 1")
    fun getJobById(jobId: Long): Flow<JobEntity?>

    @Query("SELECT * FROM jobs WHERE id = :jobId LIMIT 1")
    suspend fun getJobByIdDirect(jobId: Long): JobEntity?

    @Query("SELECT * FROM pages WHERE jobId = :jobId ORDER BY pageIndex ASC")
    fun getPagesForJob(jobId: Long): Flow<List<PageEntity>>

    @Query("SELECT * FROM pages WHERE jobId = :jobId ORDER BY pageIndex ASC")
    suspend fun getPagesForJobDirect(jobId: Long): List<PageEntity>

    @Query("SELECT * FROM pages WHERE id = :pageId LIMIT 1")
    fun getPageById(pageId: Long): Flow<PageEntity?>

    @Query("SELECT * FROM pages WHERE id = :pageId LIMIT 1")
    suspend fun getPageByIdDirect(pageId: Long): PageEntity?

    @Query("UPDATE jobs SET status = :status WHERE id = :jobId")
    suspend fun updateJobStatus(jobId: Long, status: String)

    @Query("DELETE FROM jobs WHERE id = :jobId")
    suspend fun deleteJob(jobId: Long)
}
