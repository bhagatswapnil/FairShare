package com.splitexpense.data.db.dao

import androidx.room.*
import com.splitexpense.data.db.entity.Settlement
import kotlinx.coroutines.flow.Flow

@Dao
interface SettlementDao {

    @Query("SELECT * FROM settlements WHERE groupId = :groupId ORDER BY date DESC")
    fun getSettlementsForGroup(groupId: String): Flow<List<Settlement>>

    /** Per-group sum of settled amounts — used by the home screen progress bar. */
    @Query("SELECT groupId, SUM(amount) AS settled FROM settlements GROUP BY groupId")
    fun getGroupSettledAmounts(): Flow<List<GroupSettledAmount>>

    /** All settlements across every group — needed to factor them into home-screen pending. */
    @Query("SELECT * FROM settlements")
    fun getAllSettlements(): Flow<List<Settlement>>

    /** One-shot read of every settlement — used by [com.splitexpense.util.BackupManager]. */
    @Query("SELECT * FROM settlements")
    suspend fun getAllSettlementsOnce(): List<Settlement>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSettlement(settlement: Settlement)

    @Delete
    suspend fun deleteSettlement(settlement: Settlement)
}

/** Projection for [SettlementDao.getGroupSettledAmounts]. */
data class GroupSettledAmount(val groupId: String, val settled: Double)
