package com.splitexpense.data.db.dao

import androidx.room.*
import com.splitexpense.data.db.entity.ExpenseItem
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpenseItemDao {

    @Query("SELECT * FROM expense_items WHERE expenseId = :expenseId ORDER BY rowid ASC")
    fun getItemsForExpense(expenseId: String): Flow<List<ExpenseItem>>

    @Query("SELECT * FROM expense_items WHERE expenseId = :expenseId ORDER BY rowid ASC")
    suspend fun getItemsForExpenseOnce(expenseId: String): List<ExpenseItem>

    @Query("""
        SELECT ei.* FROM expense_items ei
        INNER JOIN expenses e ON ei.expenseId = e.id
        WHERE e.groupId = :groupId
        ORDER BY ei.rowid ASC
    """)
    fun getItemsForGroup(groupId: String): Flow<List<ExpenseItem>>

    @Query("""
        SELECT ei.* FROM expense_items ei
        INNER JOIN expenses e ON ei.expenseId = e.id
        WHERE e.groupId = :groupId
        ORDER BY ei.rowid ASC
    """)
    suspend fun getItemsForGroupOnce(groupId: String): List<ExpenseItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<ExpenseItem>)

    @Query("DELETE FROM expense_items WHERE expenseId = :expenseId")
    suspend fun deleteItemsForExpense(expenseId: String)

    /** Delete specific items by their primary-key IDs (used during save to remove only user-removed items). */
    @Query("DELETE FROM expense_items WHERE id IN (:ids)")
    suspend fun deleteItemsByIds(ids: List<String>)

    /** One-shot read of every expense item — used by [com.splitexpense.util.BackupManager]. */
    @Query("SELECT * FROM expense_items")
    suspend fun getAllItemsOnce(): List<ExpenseItem>
}
