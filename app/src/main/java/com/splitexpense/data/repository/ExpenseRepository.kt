package com.splitexpense.data.repository

import androidx.room.withTransaction
import com.splitexpense.data.db.AppDatabase
import com.splitexpense.data.db.entity.*
import com.splitexpense.data.model.DebtTransaction
import com.splitexpense.data.model.MemberBalance
import kotlin.math.round
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import java.util.UUID

/** Describes one line item from a scanned bill for use with [addExpenseWithItems]. */
data class BillItemData(
    /** Stable row ID — preserved from the DB for existing items, fresh UUID for new ones. */
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val amount: Double,
    /** Member IDs assigned to this item. Empty = split among all [allMemberIds]. */
    val assignedMemberIds: List<String>
)

class ExpenseRepository(private val db: AppDatabase) {

    // ─── Groups ────────────────────────────────────────────────────────────────

    fun getAllGroups(): Flow<List<Group>> = db.groupDao().getAllGroups()

    fun getGroupById(id: String): Flow<Group?> = db.groupDao().getGroupById(id)

    /** Returns a map of groupId → total expense amount. */
    fun getGroupExpenseTotals(): Flow<Map<String, Double>> =
        db.expenseDao().getGroupExpenseTotals()
            .map { list -> list.associate { it.groupId to it.total } }

    /** Returns a map of groupId → member count. */
    fun getGroupMemberCounts(): Flow<Map<String, Int>> =
        db.memberDao().getGroupMemberCounts()
            .map { list -> list.associate { it.groupId to it.count } }

    /** Returns a map of groupId → sum of settlement amounts. */
    fun getGroupSettledAmounts(): Flow<Map<String, Double>> =
        db.settlementDao().getGroupSettledAmounts()
            .map { list -> list.associate { it.groupId to it.settled } }

    suspend fun createGroup(name: String, description: String, emoji: String, currencyCode: String = "INR") {
        db.groupDao().insertGroup(
            Group(
                id           = UUID.randomUUID().toString(),
                name         = name,
                description  = description,
                emoji        = emoji,
                currencyCode = currencyCode
            )
        )
    }

    suspend fun updateGroup(group: Group) = db.groupDao().updateGroup(group)

    suspend fun deleteGroup(group: Group) = db.groupDao().deleteGroup(group)

    // ─── Members ───────────────────────────────────────────────────────────────

    fun getMembersForGroup(groupId: String): Flow<List<Member>> =
        db.memberDao().getMembersForGroup(groupId)

    /** All members across all groups — for home-screen avatar display. */
    fun getAllMembers(): Flow<List<Member>> = db.memberDao().getAllMembers()

    suspend fun addMember(groupId: String, name: String, email: String, avatarColor: Long) {
        db.memberDao().insertMember(
            Member(
                id          = UUID.randomUUID().toString(),
                groupId     = groupId,
                name        = name,
                email       = email,
                avatarColor = avatarColor
            )
        )
    }

    suspend fun deleteMember(member: Member) = db.memberDao().deleteMember(member)

    // ─── Expenses ──────────────────────────────────────────────────────────────

    fun getExpensesForGroup(groupId: String): Flow<List<Expense>> =
        db.expenseDao().getExpensesForGroup(groupId)

    /** All expenses across all groups — for home-screen pending calculation. */
    fun getAllExpenses(): Flow<List<Expense>> = db.expenseDao().getAllExpenses()

    fun getTotalForGroup(groupId: String): Flow<Double> =
        db.expenseDao().getTotalForGroup(groupId)

    suspend fun addExpense(
        groupId: String,
        description: String,
        amount: Double,
        paidById: String,
        category: Category,
        splitType: SplitType,
        splits: List<Pair<String, Double>>
    ) {
        val expenseId = UUID.randomUUID().toString()
        db.withTransaction {
            db.expenseDao().insertExpense(
                Expense(
                    id          = expenseId,
                    groupId     = groupId,
                    description = description,
                    amount      = amount,
                    paidById    = paidById,
                    category    = category,
                    splitType   = splitType
                )
            )
            db.expenseSplitDao().insertSplits(
                splits.map { (memberId, splitAmount) ->
                    val rounded = round(splitAmount * 100).toDouble() / 100.0
                    ExpenseSplit(
                        id         = UUID.randomUUID().toString(),
                        expenseId  = expenseId,
                        memberId   = memberId,
                        amount     = rounded,
                        percentage = if (splitType == SplitType.PERCENTAGE) rounded / amount * 100 else 0.0
                    )
                }
            )
        }
    }

    suspend fun getExpenseById(id: String): Expense? = db.expenseDao().getExpenseById(id)

    suspend fun updateExpense(expense: Expense, splits: List<Pair<String, Double>>) {
        db.withTransaction {
            // Always save with isItemised = false on the standard path so that
            // converting an itemised expense back to a regular one is fully clean.
            db.expenseDao().updateExpense(expense.copy(isItemised = false))
            db.expenseSplitDao().deleteSplitsForExpense(expense.id)
            db.expenseSplitDao().insertSplits(
                splits.map { (memberId, splitAmount) ->
                    val rounded = round(splitAmount * 100).toDouble() / 100.0
                    ExpenseSplit(
                        id         = UUID.randomUUID().toString(),
                        expenseId  = expense.id,
                        memberId   = memberId,
                        amount     = rounded,
                        percentage = if (expense.splitType == SplitType.PERCENTAGE) rounded / expense.amount * 100 else 0.0
                    )
                }
            )
            // Remove any leftover items from a previous itemised state.
            db.expenseItemDao().deleteItemsForExpense(expense.id)
        }
    }

    suspend fun deleteExpense(expense: Expense) {
        // expense_splits cascade-delete via FK; explicit delete kept for ordering clarity
        db.withTransaction {
            db.expenseSplitDao().deleteSplitsForExpense(expense.id)
            db.expenseDao().deleteExpense(expense)
        }
    }

    // ─── Expense Items ─────────────────────────────────────────────────────────

    fun getItemsForGroup(groupId: String): Flow<List<ExpenseItem>> =
        db.expenseItemDao().getItemsForGroup(groupId)

    suspend fun getItemsForExpenseOnce(expenseId: String): List<ExpenseItem> =
        db.expenseItemDao().getItemsForExpenseOnce(expenseId)

    suspend fun getItemsForGroupOnce(groupId: String): List<ExpenseItem> =
        db.expenseItemDao().getItemsForGroupOnce(groupId)

    /**
     * Creates an expense with itemized line items (from bill scanning).
     * Splits are derived from item assignments; items are stored for display.
     */
    suspend fun addExpenseWithItems(
        groupId: String,
        description: String,
        paidById: String,
        category: Category,
        items: List<BillItemData>,
        allMemberIds: List<String>
    ) {
        val expenseId = UUID.randomUUID().toString()
        val totalAmount = items.sumOf { it.amount }

        // Accumulate unrounded per-member totals; round only at DB write time so that
        // each member's total is rounded once (not per-item), keeping sum(splits)
        // as close to expense.amount as floating-point allows.
        val memberTotals = mutableMapOf<String, Double>()
        items.forEach { item ->
            val assignees = item.assignedMemberIds.ifEmpty { allMemberIds }
            if (assignees.isEmpty()) return@forEach
            val share = item.amount / assignees.size
            assignees.forEach { id -> memberTotals[id] = (memberTotals[id] ?: 0.0) + share }
        }

        db.withTransaction {
            db.expenseDao().insertExpense(
                Expense(
                    id          = expenseId,
                    groupId     = groupId,
                    description = description,
                    amount      = totalAmount,
                    paidById    = paidById,
                    category    = category,
                    splitType   = SplitType.AMOUNT,
                    isItemised  = true
                )
            )
            db.expenseSplitDao().insertSplits(
                memberTotals.map { (memberId, amt) ->
                    ExpenseSplit(
                        id        = UUID.randomUUID().toString(),
                        expenseId = expenseId,
                        memberId  = memberId,
                        amount    = round(amt * 100).toDouble() / 100.0
                    )
                }
            )
            db.expenseItemDao().insertItems(
                items.map { item ->
                    val assignees = item.assignedMemberIds.ifEmpty { allMemberIds }
                    ExpenseItem(
                        id                = item.id,   // stable ID from BillItemData
                        expenseId         = expenseId,
                        name              = item.name,
                        amount            = item.amount,
                        assignedMemberIds = JSONArray(assignees).toString()
                    )
                }
            )
        }
    }

    /**
     * Updates an existing expense that has (or is being given) itemized line items.
     * Recalculates splits from item assignments and applies minimal item changes in one transaction:
     *  - Only [deletedItemIds] are removed from the DB (items the user explicitly removed).
     *  - All current [items] are upserted (REPLACE) — new rows are inserted, existing rows
     *    are updated in-place, and unchanged rows are rewritten with the same values.
     * This avoids the wipe-and-re-insert anti-pattern that would discard unmodified rows.
     */
    suspend fun updateExpenseWithItems(
        expense: Expense,
        items: List<BillItemData>,
        deletedItemIds: List<String>,
        allMemberIds: List<String>
    ) {
        val totalAmount = items.sumOf { it.amount }

        val memberTotals = mutableMapOf<String, Double>()
        items.forEach { item ->
            val assignees = item.assignedMemberIds.ifEmpty { allMemberIds }
            if (assignees.isEmpty()) return@forEach
            val share = item.amount / assignees.size
            assignees.forEach { id -> memberTotals[id] = (memberTotals[id] ?: 0.0) + share }
        }

        db.withTransaction {
            db.expenseDao().updateExpense(
                expense.copy(amount = totalAmount, splitType = SplitType.AMOUNT, isItemised = true)
            )
            db.expenseSplitDao().deleteSplitsForExpense(expense.id)
            db.expenseSplitDao().insertSplits(
                memberTotals.map { (memberId, amt) ->
                    ExpenseSplit(
                        id        = UUID.randomUUID().toString(),
                        expenseId = expense.id,
                        memberId  = memberId,
                        amount    = round(amt * 100).toDouble() / 100.0
                    )
                }
            )
            // Delete only the items the user explicitly removed — preserves untouched rows.
            if (deletedItemIds.isNotEmpty()) {
                db.expenseItemDao().deleteItemsByIds(deletedItemIds)
            }
            // Upsert current items: REPLACE updates existing rows, inserts new ones.
            db.expenseItemDao().insertItems(
                items.map { item ->
                    val assignees = item.assignedMemberIds.ifEmpty { allMemberIds }
                    ExpenseItem(
                        id                = item.id,   // stable ID preserves row identity
                        expenseId         = expense.id,
                        name              = item.name,
                        amount            = item.amount,
                        assignedMemberIds = JSONArray(assignees).toString()
                    )
                }
            )
        }
    }

    // ─── Splits ────────────────────────────────────────────────────────────────

    fun getSplitsForGroup(groupId: String): Flow<List<ExpenseSplit>> =
        db.expenseSplitDao().getSplitsForGroup(groupId)

    /** All splits across all groups — for home-screen pending calculation. */
    fun getAllSplits(): Flow<List<ExpenseSplit>> = db.expenseSplitDao().getAllSplits()

    suspend fun getSplitsForGroupOnce(groupId: String): List<ExpenseSplit> =
        db.expenseSplitDao().getSplitsForGroupOnce(groupId)

    fun getSplitsForExpense(expenseId: String): Flow<List<ExpenseSplit>> =
        db.expenseSplitDao().getSplitsForExpense(expenseId)

    suspend fun getSplitsForExpenseOnce(expenseId: String): List<ExpenseSplit> =
        db.expenseSplitDao().getSplitsForExpenseOnce(expenseId)

    // ─── Settlements ───────────────────────────────────────────────────────────

    fun getSettlementsForGroup(groupId: String): Flow<List<Settlement>> =
        db.settlementDao().getSettlementsForGroup(groupId)

    /** All settlements across all groups — for home-screen accurate pending calculation. */
    fun getAllSettlements(): Flow<List<Settlement>> = db.settlementDao().getAllSettlements()

    suspend fun addSettlement(
        groupId: String,
        fromId: String,
        toId: String,
        fromName: String,
        toName: String,
        amount: Double
    ) {
        db.settlementDao().insertSettlement(
            Settlement(
                id       = UUID.randomUUID().toString(),
                groupId  = groupId,
                fromId   = fromId,
                toId     = toId,
                fromName = fromName,
                toName   = toName,
                amount   = amount
            )
        )
    }

    suspend fun deleteSettlement(settlement: Settlement) =
        db.settlementDao().deleteSettlement(settlement)

    // ─── Balance helpers ───────────────────────────────────────────────────────

    /**
     * Computes the net balance for every member in the group.
     *
     *   net = amount_paid_by_member − amount_owed_by_member
     *       + amount_settled_out (member paid someone) − amount_settled_in (member received)
     *
     * Positive net  → this member is owed money.
     * Negative net  → this member owes money.
     *
     * @param settlements Pass the group's settlement list to factor in recorded payments.
     *                    Defaults to empty (original balance with no settlements).
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

    fun categorySpending(
        expenses: List<Expense>,
        splits: List<ExpenseSplit>,
        memberId: String?
    ): Map<Category, Double> {
        val expenseMap = expenses.associateBy { it.id }
        return if (memberId == null) {
            expenses.groupBy { it.category }
                .mapValues { (_, list) -> list.sumOf { it.amount } }
        } else {
            splits.filter { it.memberId == memberId }
                .groupBy { expenseMap[it.expenseId]?.category ?: Category.OTHER }
                .mapValues { (_, list) -> list.sumOf { it.amount } }
        }
    }

    // ─── Home-screen pending helper ────────────────────────────────────────────

    /**
     * Computes per-group pending (sum of positive net balances) from raw lists.
     * Used by HomeViewModel so it can reuse the already-observed data flows
     * without an extra SQL query.
     */
    fun computePendingPerGroup(
        groups: List<Group>,
        allMembers: List<Member>,
        allExpenses: List<Expense>,
        allSplits: List<ExpenseSplit>,
        allSettlements: Map<String, List<Settlement>>   // groupId → settlements
    ): Map<String, Double> {
        val membersByGroup  = allMembers.groupBy  { it.groupId }
        val expensesByGroup = allExpenses.groupBy { it.groupId }

        return groups.associate { group ->
            val gMembers     = membersByGroup[group.id]  ?: emptyList()
            val gExpenses    = expensesByGroup[group.id] ?: emptyList()
            val gExpenseIds  = gExpenses.map { it.id }.toSet()
            val gSplits      = allSplits.filter { it.expenseId in gExpenseIds }
            val gSettlements = allSettlements[group.id]  ?: emptyList()

            val balances = calculateBalances(gMembers, gExpenses, gSplits, gSettlements)
            val pending  = balances.filter { it.netBalance > 0.01 }.sumOf { it.netBalance }
            group.id to pending
        }
    }
}
