package com.splitexpense.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A named set of members (e.g. a couple) who settle up as one party.
 * Members point here via [Member.accountId].
 */
@Entity(
    tableName = "member_accounts",
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
data class MemberAccount(
    @PrimaryKey val id: String,
    val groupId: String,
    val name: String
)
