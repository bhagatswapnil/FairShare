package com.splitexpense.data.model

data class MemberBalance(
    val memberId: String,
    val memberName: String,
    val avatarColor: Long,
    val netBalance: Double,
    /**
     * Every member this balance covers. A solo member's balance covers only itself; a merged
     * linked-account balance (see [mergeBalancesByAccount]) covers all linked members, and
     * [memberId] is then the account's representative member.
     */
    val memberIds: List<String> = listOf(memberId)
)

data class DebtTransaction(
    val fromId: String,
    val fromName: String,
    val toId: String,
    val toName: String,
    val amount: Double
)
