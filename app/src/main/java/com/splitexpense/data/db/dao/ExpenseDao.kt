package com.splitexpense.data.db.dao

import androidx.room.*
import com.splitexpense.data.db.entity.Expense
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM expenses WHERE groupId = :groupId ORDER BY date DESC")
    fun getExpensesForGroup(groupId: String): Flow<List<Expense>>

    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun getExpenseById(id: String): Expense?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpense(expense: Expense)

    @Update
    suspend fun updateExpense(expense: Expense)

    @Delete
    suspend fun deleteExpense(expense: Expense)

    @Query("SELECT COALESCE(SUM(amount), 0.0) FROM expenses WHERE groupId = :groupId")
    fun getTotalForGroup(groupId: String): Flow<Double>

    @Query("SELECT groupId, COALESCE(SUM(amount), 0.0) AS total FROM expenses GROUP BY groupId")
    fun getGroupExpenseTotals(): Flow<List<GroupExpenseTotal>>

    /** All expenses across every group — used by the home screen for pending calculation. */
    @Query("SELECT * FROM expenses")
    fun getAllExpenses(): Flow<List<Expense>>
}

/** Projection used by [getGroupExpenseTotals]. */
data class GroupExpenseTotal(val groupId: String, val total: Double)
