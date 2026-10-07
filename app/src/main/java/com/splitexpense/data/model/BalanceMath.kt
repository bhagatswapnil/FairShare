package com.splitexpense.data.model

import com.splitexpense.data.db.entity.Expense
import com.splitexpense.data.db.entity.ExpenseSplit
import com.splitexpense.data.db.entity.Member
import com.splitexpense.data.db.entity.Settlement

// Pure balance functions — no DB access, so they are unit-testable without Room.
// ExpenseRepository delegates to these.

/**
 * Computes the net balance for every member in the group.
 *
 *   net = amount_paid_by_member − amount_owed_by_member
 *       + amount_settled_out (member paid someone) − amount_settled_in (member received)
 *
 * Positive net  → this member is owed money.
 * Negative net  → this member owes money.
 *
 * Always per member — linked accounts are folded afterwards by [mergeBalancesByAccount].
 */
fun calculateBalances(
    members: List<Member>,
    expenses: List<Expense>,
    splits: List<ExpenseSplit>,
    settlements: List<Settlement> = emptyList()
): List<MemberBalance> {
    val net = members.associate { it.id to 0.0 }.toMutableMap()

    // Only credit/debit members that still exist; orphaned IDs (deleted members) are ignored
    // so that their phantom entries don't corrupt the balances of surviving members.
    expenses.forEach { e ->
        if (e.paidById in net) net[e.paidById] = net.getValue(e.paidById) + e.amount
    }
    splits.forEach { s ->
        if (s.memberId in net) net[s.memberId] = net.getValue(s.memberId) - s.amount
    }
    settlements.forEach { s ->
        if (s.fromId in net) net[s.fromId] = net.getValue(s.fromId) + s.amount
        if (s.toId   in net) net[s.toId]   = net.getValue(s.toId)   - s.amount
    }

    return members.map { m ->
        MemberBalance(m.id, m.name, m.avatarColor, net[m.id] ?: 0.0)
    }
}

/**
 * Folds linked members (same non-null [Member.accountId]) into one balance per account.
 *
 * - The merged balance's [MemberBalance.memberId] is the account's representative: its first
 *   member in [members] order (name ASC from the DAO). Settlements recorded from a merged debt
 *   land on that member; since the merge sums every linked member, the account total stays
 *   correct whichever linked member a settlement was recorded against.
 * - Name comes from [accountNames] (accountId → custom name), falling back to "A & B".
 * - An accountId held by fewer than two members is treated as solo.
 *
 * Output order follows [members]: each account appears at its representative's position.
 */
fun mergeBalancesByAccount(
    members: List<Member>,
    balances: List<MemberBalance>,
    accountNames: Map<String, String> = emptyMap()
): List<MemberBalance> {
    val balanceById = balances.associateBy { it.memberId }
    val linked = members
        .filter { it.accountId != null }
        .groupBy { it.accountId!! }
        .filterValues { it.size >= 2 }

    val emittedAccounts = mutableSetOf<String>()
    return members.mapNotNull { m ->
        val accountId = m.accountId
        val accountMembers = if (accountId != null) linked[accountId] else null
        if (accountId == null || accountMembers == null) return@mapNotNull balanceById[m.id]
        // Later members of an account are already folded into the representative.
        if (!emittedAccounts.add(accountId)) return@mapNotNull null
        MemberBalance(
            memberId    = m.id,
            memberName  = accountNames[accountId] ?: accountMembers.joinToString(" & ") { it.name },
            avatarColor = m.avatarColor,
            netBalance  = accountMembers.sumOf { balanceById[it.id]?.netBalance ?: 0.0 },
            memberIds   = accountMembers.map { it.id }
        )
    }
}

/** Greedy max-creditor / max-debtor pairing; 0.01 epsilon; loop bounded at N². */
fun simplifyDebts(balances: List<MemberBalance>): List<DebtTransaction> {
    val net = balances.map { it.memberId to it.netBalance }.toMutableList()
    val memberName = balances.associate { it.memberId to it.memberName }
    val transactions = mutableListOf<DebtTransaction>()

    repeat(net.size * net.size) {
        val creditor = net.maxByOrNull { it.second } ?: return transactions
        val debtor   = net.minByOrNull { it.second } ?: return transactions
        if (creditor.second < 0.01 || debtor.second > -0.01) return transactions

        val amount = minOf(creditor.second, -debtor.second)
        transactions += DebtTransaction(
            debtor.first,
            memberName[debtor.first]   ?: "",
            creditor.first,
            memberName[creditor.first] ?: "",
            amount
        )

        val ci = net.indexOfFirst { it.first == creditor.first }
        val di = net.indexOfFirst { it.first == debtor.first }
        net[ci] = creditor.first to creditor.second - amount
        net[di] = debtor.first  to debtor.second  + amount
    }
    return transactions
}
