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
    val avatarColor: Long = 0xFF6200EE
)
