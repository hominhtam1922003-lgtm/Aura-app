package com.example.nysa.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TitleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(titles: List<TitleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(title: TitleEntity)

    @Query("SELECT * FROM titles WHERE id = :id")
    fun observe(id: String): Flow<TitleEntity?>

    @Query("SELECT * FROM titles WHERE id = :id")
    suspend fun get(id: String): TitleEntity?

    @Query("SELECT * FROM titles WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<TitleEntity>

    @Query("SELECT * FROM titles WHERE id IN (:ids)")
    fun observeByIds(ids: List<String>): Flow<List<TitleEntity>>
}

@Dao
interface UserStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: UserStateEntity)

    @Query("SELECT * FROM user_states WHERE titleId = :titleId")
    fun observe(titleId: String): Flow<UserStateEntity?>

    @Query("SELECT * FROM user_states WHERE titleId = :titleId")
    suspend fun get(titleId: String): UserStateEntity?

    /** Every stored state row — backup export needs the whole book. */
    @Query("SELECT * FROM user_states")
    suspend fun allOnce(): List<UserStateEntity>

    @Query("SELECT * FROM user_states WHERE favorite = 1")
    fun favorites(): Flow<List<UserStateEntity>>

    @Query("SELECT * FROM user_states WHERE watchLater = 1")
    fun watchLater(): Flow<List<UserStateEntity>>

    @Query("SELECT * FROM user_states WHERE following = 1 ORDER BY (latestCount - seenCount) DESC, lastTouchedEpochSec DESC")
    fun following(): Flow<List<UserStateEntity>>

    @Query("SELECT * FROM user_states WHERE progressFraction > 0 AND finished = 0 AND lastTouchedEpochSec > 0 ORDER BY lastTouchedEpochSec DESC")
    fun continueWatching(): Flow<List<UserStateEntity>>

    @Query("SELECT * FROM user_states WHERE lastTouchedEpochSec > 0 ORDER BY lastTouchedEpochSec DESC LIMIT :limit")
    fun history(limit: Int): Flow<List<UserStateEntity>>

    @Query("SELECT * FROM user_states WHERE following = 1")
    suspend fun followingOnce(): List<UserStateEntity>

    @Query("SELECT COUNT(*) FROM user_states WHERE following = 1")
    fun followingCount(): Flow<Int>

    /**
     * Tracked titles (user rule 2026-11-01): everything the user favorited,
     * turned notifications on for, OR started watching/reading. New-episode
     * counting applies to all of these, baselined at tracking time.
     */
    @Query("SELECT * FROM user_states WHERE following = 1 OR notifyOn = 1 OR favorite = 1 OR lastUnitNumber > 0 ORDER BY (latestCount - seenCount) DESC, lastTouchedEpochSec DESC")
    fun tracked(): Flow<List<UserStateEntity>>

    @Query("SELECT * FROM user_states WHERE following = 1 OR notifyOn = 1 OR favorite = 1 OR lastUnitNumber > 0")
    suspend fun trackedOnce(): List<UserStateEntity>

    @Query("SELECT COUNT(*) FROM user_states WHERE following = 1 OR notifyOn = 1 OR favorite = 1 OR lastUnitNumber > 0")
    fun trackedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM user_states WHERE favorite = 1")
    fun favoriteCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM user_states WHERE watchLater = 1")
    fun watchLaterCount(): Flow<Int>

    @Query("UPDATE user_states SET lastTouchedEpochSec = 0")
    suspend fun clearHistory()
}
