package com.splitexpense.data.db.dao

import androidx.room.*
import com.splitexpense.data.db.entity.Member
import kotlinx.coroutines.flow.Flow

@Dao
interface MemberDao {
    @Query("SELECT * FROM members WHERE groupId = :groupId ORDER BY name ASC")
    fun getMembersForGroup(groupId: String): Flow<List<Member>>

    /** All members across every group — used by the home screen for avatar display. */
    @Query("SELECT * FROM members ORDER BY name ASC")
    fun getAllMembers(): Flow<List<Member>>

    @Query("SELECT * FROM members WHERE id = :id")
    suspend fun getMemberById(id: String): Member?

    /** One-shot read of every member — used by [com.splitexpense.util.BackupManager]. */
    @Query("SELECT * FROM members ORDER BY name ASC")
    suspend fun getAllMembersOnce(): List<Member>

    @Query("SELECT groupId, COUNT(*) AS count FROM members GROUP BY groupId")
    fun getGroupMemberCounts(): Flow<List<GroupMemberCount>>

    /**
     * Insert only — never use this to *update* an existing member. REPLACE is DELETE + INSERT,
     * and the DELETE cascades to expense_splits (FK memberId ON DELETE CASCADE), silently
     * wiping the member's splits. Use a targeted UPDATE query like [setAccountId] instead.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMember(member: Member)

    @Query("UPDATE members SET accountId = :accountId WHERE id IN (:ids)")
    suspend fun setAccountId(ids: List<String>, accountId: String?)

    @Query("SELECT * FROM members WHERE accountId = :accountId")
    suspend fun getMembersInAccount(accountId: String): List<Member>

    @Delete
    suspend fun deleteMember(member: Member)
}

/** Projection used by [getGroupMemberCounts]. */
data class GroupMemberCount(val groupId: String, val count: Int)
