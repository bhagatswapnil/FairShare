package com.splitexpense.data.model

data class MemberBalance(
    val memberId: String,
    val memberName: String,
    val avatarColor: Long,
    val netBalance: Double
)

data class DebtTransaction(
    val fromId: String,
    val fromName: String,
    val toId: String,
    val toName: String,
    val amount: Double
)
