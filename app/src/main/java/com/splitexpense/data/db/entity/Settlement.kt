package com.splitexpense.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "settlements",
    foreignKeys = [
        ForeignKey(
            entity    = Group::class,
            parentColumns = ["id"],
            childColumns  = ["groupId"],
            onDelete  = ForeignKey.CASCADE
        )
    ],
    indices = [Index("groupId")]
)
data class Settlement(
    @PrimaryKey val id: String,
    val groupId: String,
    val fromId: String,    // person who paid (was the debtor)
    val toId: String,      // person who received (was the creditor)
    val fromName: String,
    val toName: String,
    val amount: Double,
    val date: Long = System.currentTimeMillis()
)
