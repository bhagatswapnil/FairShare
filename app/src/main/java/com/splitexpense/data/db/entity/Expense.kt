package com.splitexpense.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class SplitType { EQUAL, PERCENTAGE, AMOUNT }

enum class Category {
    FOOD, TRAVEL, UTILITIES, ENTERTAINMENT, SHOPPING, HEALTH, OTHER
}

@Entity(
    tableName = "expenses",
    foreignKeys = [
        ForeignKey(
            entity = Group::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("groupId")]
)
data class Expense(
    @PrimaryKey val id: String,
    val groupId: String,
    val description: String,
    val amount: Double,
    val paidById: String,
    val category: Category = Category.OTHER,
    val splitType: SplitType = SplitType.EQUAL,
    val date: Long = System.currentTimeMillis(),
    val notes: String = ""
)
