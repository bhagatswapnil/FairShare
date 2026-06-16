package com.splitexpense.data.db.dao

import androidx.room.*
import com.splitexpense.data.db.entity.ExpenseSplit
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpenseSplitDao {
    @Query("SELECT * FROM expense_splits WHERE expenseId = :expenseId")
    fun getSplitsForExpense(expenseId: String): Flow<List<ExpenseSplit>>

    @Query("SELECT * FROM expense_splits WHERE expenseId = :expenseId")
    suspend fun getSplitsForExpenseOnce(expenseId: String): List<ExpenseSplit>

    @Query("""
        SELECT es.* FROM expense_splits es
        INNER JOIN expenses e ON es.expenseId = e.id
        WHERE e.groupId = :groupId
    """)
    fun getSplitsForGroup(groupId: String): Flow<List<ExpenseSplit>>

    @Query("""
        SELECT es.* FROM expense_splits es
        INNER JOIN expenses e ON es.expenseId = e.id
        WHERE e.groupId = :groupId
    """)
    suspend fun getSplitsForGroupOnce(groupId: String): List<ExpenseSplit>

    /** All splits across every group — used by the home screen for pending calculation. */
    @Query("SELECT * FROM expense_splits")
    fun getAllSplits(): Flow<List<ExpenseSplit>>

    /** One-shot read of every split — used by [com.splitexpense.util.BackupManager]. */
    @Query("SELECT * FROM expense_splits")
    suspend fun getAllSplitsOnce(): List<ExpenseSplit>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSplits(splits: List<ExpenseSplit>)

    @Query("DELETE FROM expense_splits WHERE expenseId = :expenseId")
    suspend fun deleteSplitsForExpense(expenseId: String)
}
