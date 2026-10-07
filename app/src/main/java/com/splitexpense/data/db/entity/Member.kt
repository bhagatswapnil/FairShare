package com.splitexpense.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "members",
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
data class Member(
    @PrimaryKey val id: String,
    val groupId: String,
    val name: String,
    val email: String = "",
    val avatarColor: Long = 0xFF6200EE,
    /**
     * [MemberAccount.id] this member is linked into, or null when solo. Linked members
     * keep their own splits but settle up as one party. No FK constraint (added via
     * ALTER in v7) — consistency is maintained by [com.splitexpense.data.repository.ExpenseRepository].
     */
    val accountId: String? = null
)
