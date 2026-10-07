package com.splitexpense.data.repository

import androidx.room.withTransaction
import com.splitexpense.data.db.AppDatabase
import com.splitexpense.data.db.entity.*
import com.splitexpense.data.model.DebtTransaction
import com.splitexpense.data.model.MemberBalance
import com.splitexpense.data.model.mergeBalancesByAccount
import kotlin.math.round
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.splitexpense.data.db.entity.ItemSplitMode
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Describes one line item from a scanned bill for use with [addExpenseWithItems]. */
data class BillItemData(
    /** Stable row ID — preserved from the DB for existing items, fresh UUID for new ones. */
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** Always the **total** cost for this item (unit × qty for QUANTITY mode). */
    val amount: Double,
    /** Member IDs — used only when [splitMode] is EQUAL. Empty = all [allMemberIds]. */
    val assignedMemberIds: List<String>,
    val splitMode: ItemSplitMode = ItemSplitMode.EQUAL,
    /** For PERCENTAGE: memberId→percentage (sum≈100). For QUANTITY: memberId→unit count. */
    val memberWeights: Map<String, Double> = emptyMap()
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

    /** Deletes the member; dissolves their linked account if fewer than two members would remain. */
    suspend fun deleteMember(member: Member) {
        db.withTransaction {
            db.memberDao().deleteMember(member)
            val accountId = member.accountId ?: return@withTransaction
            val remaining = db.memberDao().getMembersInAccount(accountId)
            if (remaining.size < 2) {
                db.memberDao().setAccountId(remaining.map { it.id }, null)
                db.memberAccountDao().getAccountById(accountId)?.let { db.memberAccountDao().deleteAccount(it) }
            }
        }
    }

    // ─── Linked accounts ───────────────────────────────────────────────────────

    fun getAccountsForGroup(groupId: String): Flow<List<MemberAccount>> =
        db.memberAccountDao().getAccountsForGroup(groupId)

    /** Links [memberIds] (≥ 2) into a new named account that settles up as one party. */
    suspend fun linkMembers(groupId: String, name: String, memberIds: List<String>) {
        require(memberIds.size >= 2) { "An account needs at least two members" }
        require(name.isNotBlank()) { "Account name is required" }
        val account = MemberAccount(UUID.randomUUID().toString(), groupId, name.trim())
        db.withTransaction {
            db.memberAccountDao().insertAccount(account)
            db.memberDao().setAccountId(memberIds, account.id)
        }
    }

    suspend fun renameAccount(account: MemberAccount, newName: String) {
        require(newName.isNotBlank()) { "Account name is required" }
        db.memberAccountDao().updateAccount(account.copy(name = newName.trim()))
    }

    /** Returns every linked member to solo and removes the account row. */
    suspend fun unlinkAccount(account: MemberAccount) {
        db.withTransaction {
            val ids = db.memberDao().getMembersInAccount(account.id).map { it.id }
            db.memberDao().setAccountId(ids, null)
            db.memberAccountDao().deleteAccount(account)
        }
    }

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
        items.forEach { item -> accumulateShares(item, allMemberIds, memberTotals) }

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
                    ExpenseItem(
                        id                = item.id,
                        expenseId         = expenseId,
                        name              = item.name,
                        amount            = item.amount,
                        assignedMemberIds = serializeItemAssignments(item, allMemberIds),
                        splitMode         = item.splitMode.name
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
        items.forEach { item -> accumulateShares(item, allMemberIds, memberTotals) }

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
            if (deletedItemIds.isNotEmpty()) {
                db.expenseItemDao().deleteItemsByIds(deletedItemIds)
            }
            db.expenseItemDao().insertItems(
                items.map { item ->
                    ExpenseItem(
                        id                = item.id,
                        expenseId         = expense.id,
                        name              = item.name,
                        amount            = item.amount,
                        assignedMemberIds = serializeItemAssignments(item, allMemberIds),
                        splitMode         = item.splitMode.name
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

    // Implementations live in data/model/BalanceMath.kt as pure functions (unit-testable).

    /** Per-member net balances. See [com.splitexpense.data.model.calculateBalances]. */
    fun calculateBalances(
        members: List<Member>,
        expenses: List<Expense>,
        splits: List<ExpenseSplit>,
        settlements: List<Settlement> = emptyList()
    ): List<MemberBalance> =
        com.splitexpense.data.model.calculateBalances(members, expenses, splits, settlements)

    /** Pass account-merged balances so linked members settle as one party. */
    fun simplifyDebts(balances: List<MemberBalance>): List<DebtTransaction> =
        com.splitexpense.data.model.simplifyDebts(balances)

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

            // Merge linked accounts first so debts between linked members don't count as pending.
            val balances = mergeBalancesByAccount(
                gMembers, calculateBalances(gMembers, gExpenses, gSplits, gSettlements)
            )
            val pending  = balances.filter { it.netBalance > 0.01 }.sumOf { it.netBalance }
            group.id to pending
        }
    }

    // ─── Item split helpers ────────────────────────────────────────────────────

    /** Adds each item's per-member share to [totals], accumulating unrounded. */
    private fun accumulateShares(
        item: BillItemData,
        allMemberIds: List<String>,
        totals: MutableMap<String, Double>
    ) {
        when (item.splitMode) {
            ItemSplitMode.PERCENTAGE -> {
                val totalPct = item.memberWeights.values.sum()
                if (totalPct <= 0) return
                item.memberWeights.forEach { (id, pct) ->
                    if (pct > 0) totals[id] = (totals[id] ?: 0.0) + item.amount * pct / totalPct
                }
            }
            ItemSplitMode.QUANTITY -> {
                val totalQty = item.memberWeights.values.sum()
                if (totalQty <= 0) return
                item.memberWeights.forEach { (id, qty) ->
                    if (qty > 0) totals[id] = (totals[id] ?: 0.0) + item.amount * qty / totalQty
                }
            }
            ItemSplitMode.EQUAL -> {
                val assignees = item.assignedMemberIds.ifEmpty { allMemberIds }
                if (assignees.isEmpty()) return
                val share = item.amount / assignees.size
                assignees.forEach { id -> totals[id] = (totals[id] ?: 0.0) + share }
            }
        }
    }

    /** Serialises item assignments to the JSON format expected by [ExpenseItem.assignedMemberIds]. */
    private fun serializeItemAssignments(item: BillItemData, allMemberIds: List<String>): String =
        when (item.splitMode) {
            ItemSplitMode.EQUAL -> {
                val assignees = item.assignedMemberIds.ifEmpty { allMemberIds }
                JSONArray(assignees).toString()
            }
            else -> {
                val arr = JSONArray()
                item.memberWeights.filter { it.value > 0 }.forEach { (id, w) ->
                    arr.put(JSONObject().put("id", id).put("w", w))
                }
                arr.toString()
            }
        }
}
