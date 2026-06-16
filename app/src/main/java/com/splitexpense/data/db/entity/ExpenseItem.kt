package com.splitexpense.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single line item extracted from a scanned bill.
 * [assignedMemberIds] is a JSON array string (e.g. `["id1","id2"]`).
 * Empty string / "[]" means the item is split equally among ALL group members.
 */
@Entity(
    tableName = "expense_items",
    foreignKeys = [
        ForeignKey(
            entity = Expense::class,
            parentColumns = ["id"],
            childColumns = ["expenseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("expenseId")]
)
data class ExpenseItem(
    @PrimaryKey val id: String,
    val expenseId: String,
    val name: String,
    val amount: Double,
    /** JSON array of member IDs assigned to this item. "[]" = all members. */
    val assignedMemberIds: String = "[]"
)
