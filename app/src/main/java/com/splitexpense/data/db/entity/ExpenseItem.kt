package com.splitexpense.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** How the cost of one line item is divided among its assigned members. */
enum class ItemSplitMode { EQUAL, PERCENTAGE, QUANTITY }

/**
 * A single line item extracted from a scanned bill.
 *
 * [assignedMemberIds] is a JSON blob whose format depends on [splitMode]:
 *  - EQUAL:      `["id1","id2"]`  — simple string array; "[]" = all group members
 *  - PERCENTAGE: `[{"id":"id1","w":60.0},{"id":"id2","w":40.0}]` — weights are percentages
 *  - QUANTITY:   same object format; weights are unit quantities
 *
 * For QUANTITY mode, [amount] stores the **total** cost (unitPrice × totalQty).
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
    /** JSON — format described above. */
    val assignedMemberIds: String = "[]",
    @ColumnInfo(name = "split_mode") val splitMode: String = "EQUAL"
)
