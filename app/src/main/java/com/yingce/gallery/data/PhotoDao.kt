package com.yingce.gallery.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoDao {

    /**
     * 排序：拍摄时间倒序（taken_at -> id）。
     * 筛选：
     *  - [make]  机型；null = 全部
     *  - [place] -1 = 全部，0 = 无位置信息，1 = 有位置信息
     *  - [from]/[to] 拍摄时间区间（毫秒，闭区间）
     */
    @Query(
        """
        SELECT * FROM photos
        WHERE (:make IS NULL OR camera_model = :make)
          AND (:place = -1
               OR (:place = 0 AND gps_lat IS NULL)
               OR (:place = 1 AND gps_lat IS NOT NULL))
          AND taken_at BETWEEN :from AND :to
        ORDER BY taken_at DESC, id DESC
        """
    )
    fun observeFiltered(make: String?, place: Int, from: Long, to: Long): Flow<List<Photo>>

    @Query("SELECT * FROM photos ORDER BY taken_at DESC, id DESC")
    fun observeAll(): Flow<List<Photo>>

    @Query("SELECT * FROM photos ORDER BY taken_at DESC, id DESC")
    suspend fun getAll(): List<Photo>

    @Query("SELECT COUNT(*) FROM photos")
    suspend fun count(): Int

    @Query("SELECT * FROM photos WHERE stored_name = :storedName LIMIT 1")
    suspend fun findByStoredName(storedName: String): Photo?

    @Query("SELECT * FROM photos WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun findByFingerprint(fingerprint: String): Photo?

    @Query("SELECT * FROM photos WHERE source_uri = :uri LIMIT 1")
    suspend fun findBySourceUri(uri: String): Photo?

    /** 机型去重列表，供筛选器使用；空机型不入列。 */
    @Query("SELECT DISTINCT camera_model FROM photos WHERE camera_model IS NOT NULL AND camera_model <> '' ORDER BY camera_model COLLATE NOCASE")
    fun observeCameraModels(): Flow<List<String>>

    @Query("SELECT MIN(taken_at) FROM photos")
    suspend fun minTakenAt(): Long?

    @Insert
    suspend fun insert(photo: Photo): Long

    @Query("DELETE FROM photos")
    suspend fun deleteAll()

    @Query("DELETE FROM photos WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM photos WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("UPDATE photos SET state = :state WHERE id = :id")
    suspend fun updateState(id: Long, state: String)
}
