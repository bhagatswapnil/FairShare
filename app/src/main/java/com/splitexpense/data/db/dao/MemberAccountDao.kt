package com.splitexpense.data.db.dao

import androidx.room.*
import com.splitexpense.data.db.entity.MemberAccount
import kotlinx.coroutines.flow.Flow

@Dao
interface MemberAccountDao {
    @Query("SELECT * FROM member_accounts WHERE groupId = :groupId ORDER BY name ASC")
    fun getAccountsForGroup(groupId: String): Flow<List<MemberAccount>>

    /** One-shot read of every account — used by [com.splitexpense.util.BackupManager]. */
    @Query("SELECT * FROM member_accounts")
    suspend fun getAllAccountsOnce(): List<MemberAccount>

    @Query("SELECT * FROM member_accounts WHERE id = :id")
    suspend fun getAccountById(id: String): MemberAccount?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccount(account: MemberAccount)

    @Update
    suspend fun updateAccount(account: MemberAccount)

    @Delete
    suspend fun deleteAccount(account: MemberAccount)
}
