package com.splitexpense.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "groups")
data class Group(
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    val emoji: String = "💰",
    val createdAt: Long = System.currentTimeMillis()
)
