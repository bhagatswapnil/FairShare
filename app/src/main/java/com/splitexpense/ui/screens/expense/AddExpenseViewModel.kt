package com.splitexpense.ui.screens.expense

import androidx.lifecycle.*
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.splitexpense.SplitExpenseApplication
import com.splitexpense.data.db.entity.Category
import com.splitexpense.data.db.entity.Expense
import com.splitexpense.data.db.entity.ItemSplitMode
import com.splitexpense.data.db.entity.Member
import com.splitexpense.data.db.entity.SplitType
import com.splitexpense.data.repository.BillItemData
import com.splitexpense.data.repository.ExpenseRepository
import com.splitexpense.util.AppSettingsManager
import com.splitexpense.util.currencySymbolFor
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

/** A single editable line-item in the expense form. */
data class ExpenseItemState(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    /** EQUAL/PERCENTAGE: total amount. QUANTITY: unit price (total = unitPrice × totalQty). */
    val amount: String = "",
    val splitMode: ItemSplitMode = ItemSplitMode.EQUAL,
    val assignedMemberIds: Set<String> = emptySet(),      // used only in EQUAL mode
    val memberWeights: Map<String, String> = emptyMap(),  // PERCENTAGE → pct string; QUANTITY → qty string
    /** When true the item amount is treated as negative (discount / offer reduction). */
    val isDiscount: Boolean = false
) {
    val parsedAmount: Double get() {
        val raw = when (splitMode) {
            ItemSplitMode.EQUAL, ItemSplitMode.PERCENTAGE -> amount.toDoubleOrNull() ?: 0.0
            ItemSplitMode.QUANTITY -> {
                val unit = amount.toDoubleOrNull() ?: 0.0
                val totalQty = memberWeights.values.sumOf { it.toDoubleOrNull() ?: 0.0 }
                unit * totalQty
            }
        }
        return if (isDiscount) -raw else raw
    }
}

data class AddExpenseUiState(
    val members: List<Member> = emptyList(),
    val description: String = "",
    val amount: String = "",
    val paidById: String = "",
    val category: Category = Category.OTHER,
    val splitType: SplitType = SplitType.EQUAL,
    val selectedMemberIds: Set<String> = emptySet(),
    val customSplits: Map<String, String> = emptyMap(),
    /** Non-empty when the user is working with an itemised bill. */
    val expenseItems: List<ExpenseItemState> = emptyList(),
    /** The owning group's currency symbol (e.g. "₹", "$"). Defaults to "₹" until loaded. */
    val currencySymbol: String = "₹",
    /** True in edit mode until the expense + splits + items are loaded from the DB. */
    val isInitializing: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false
) {
    /** Computed total when items drive the expense; ignored when items are empty. */
    val itemsTotal: Double get() = expenseItems.sumOf { it.parsedAmount }
}

class AddExpenseViewModel(
    private val repository: ExpenseRepository,
    private val groupId: String,
    private val appSettingsManager: AppSettingsManager,
    private val expenseId: String? = null   // non-null → edit mode
) : ViewModel() {

    private val _state = MutableStateFlow(
        // In edit mode keep the form hidden until the expense data is loaded,
        // preventing a jarring empty-form → filled-form layout shift.
        AddExpenseUiState(isInitializing = expenseId != null)
    )
    val state: StateFlow<AddExpenseUiState> = _state.asStateFlow()

    // Holds the original expense entity in edit mode (preserves id / date / groupId)
    private var originalExpense: Expense? = null

    val isEditing: Boolean get() = expenseId != null

    /**
     * IDs of expense-item rows that existed in the DB when the form was opened in edit mode.
     * Used to distinguish "remove an item that was already persisted" from "remove a brand-new
     * item that was never written to the DB" — only the former needs a DB DELETE on save.
     */
    private var originalItemIds = emptySet<String>()

    /**
     * Accumulates DB-backed item IDs that the user removed during this editing session.
     * Populated only in [onRemoveItem] and consumed (then cleared) inside [saveExpense].
     * No DB operation is triggered by removal itself — deletes happen exclusively on save.
     */
    private val pendingItemDeletes = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            // 0. Load the app-wide currency symbol so amount fields render the right prefix.
            appSettingsManager.currencyCode.collect { code ->
                _state.update { it.copy(currencySymbol = currencySymbolFor(code)) }
            }
        }

        viewModelScope.launch {
            // 1. Load members
            repository.getMembersForGroup(groupId).collect { members ->
                _state.update { cur ->
                    cur.copy(
                        members = members,
                        paidById = if (cur.paidById.isEmpty() && members.isNotEmpty()) members.first().id else cur.paidById,
                        selectedMemberIds = if (!isEditing && cur.selectedMemberIds.isEmpty())
                            members.map { it.id }.toSet()
                        else
                            cur.selectedMemberIds
                    )
                }
            }
        }

        // 2. In edit mode, pre-fill from existing expense + items
        if (expenseId != null) {
            viewModelScope.launch {
                val loadStart = System.currentTimeMillis()

                val expense = repository.getExpenseById(expenseId) ?: run {
                    // Expense deleted externally while the screen was being opened.
                    // Clear the spinner and show an error rather than hanging forever.
                    _state.update { it.copy(isInitializing = false, error = "Expense not found — it may have been deleted.") }
                    return@launch
                }
                originalExpense = expense

                // Run splits and (conditionally) items in parallel — items query skipped
                // entirely for non-itemised expenses thanks to the isItemised flag.
                val splitsDeferred = async { repository.getSplitsForExpenseOnce(expenseId) }
                val itemsDeferred  = if (expense.isItemised) {
                    async { repository.getItemsForExpenseOnce(expenseId) }
                } else null

                val splits = splitsDeferred.await()
                val items  = itemsDeferred?.await() ?: emptyList()

                val splitMap: Map<String, String> = when (expense.splitType) {
                    SplitType.EQUAL      -> emptyMap()
                    SplitType.PERCENTAGE -> splits.associate { it.memberId to "%.2f".format(it.percentage) }
                    SplitType.AMOUNT     -> splits.associate { it.memberId to "%.2f".format(it.amount) }
                }

                val itemStates = items.map { item ->
                    val mode = try { ItemSplitMode.valueOf(item.splitMode) } catch (_: Exception) { ItemSplitMode.EQUAL }
                    val isDiscount = item.amount < 0
                    val absAmount = Math.abs(item.amount)
                    when (mode) {
                        ItemSplitMode.EQUAL -> {
                            val ids = try {
                                val arr = org.json.JSONArray(item.assignedMemberIds)
                                (0 until arr.length()).map { arr.getString(it) }.toSet()
                            } catch (_: Exception) { emptySet<String>() }
                            ExpenseItemState(id = item.id, name = item.name,
                                amount = "%.2f".format(absAmount),
                                splitMode = mode, assignedMemberIds = ids, isDiscount = isDiscount)
                        }
                        ItemSplitMode.PERCENTAGE, ItemSplitMode.QUANTITY -> {
                            val weights = try {
                                val arr = org.json.JSONArray(item.assignedMemberIds)
                                (0 until arr.length()).associate { i ->
                                    val obj = arr.getJSONObject(i)
                                    obj.getString("id") to "%.2f".format(obj.getDouble("w"))
                                }
                            } catch (_: Exception) { emptyMap() }
                            // QUANTITY mode stores total in DB; convert back to unit price
                            val displayAmt = if (mode == ItemSplitMode.QUANTITY) {
                                val totalQty = weights.values.sumOf { it.toDoubleOrNull() ?: 0.0 }
                                if (totalQty > 0) "%.2f".format(absAmount / totalQty)
                                else "%.2f".format(absAmount)
                            } else "%.2f".format(absAmount)
                            ExpenseItemState(id = item.id, name = item.name,
                                amount = displayAmt, splitMode = mode, memberWeights = weights, isDiscount = isDiscount)
                        }
                    }
                }

                // Remember which item IDs came from the DB so onRemoveItem can
                // schedule only those for deletion (new items added in this session
                // are never written to the DB and need no DELETE).
                originalItemIds = itemStates.map { it.id }.toSet()

                // Guarantee the spinner is visible for at least MIN_INIT_MS so the
                // form doesn't flash in on fast devices causing a jarring layout jump.
                // If loading already took longer, this is a no-op.
                val elapsed = System.currentTimeMillis() - loadStart
                if (elapsed < MIN_INIT_MS) delay(MIN_INIT_MS - elapsed)

                _state.update { cur ->
                    cur.copy(
                        description       = expense.description,
                        amount            = "%.2f".format(expense.amount),
                        paidById          = expense.paidById,
                        category          = expense.category,
                        splitType         = expense.splitType,
                        selectedMemberIds = splits.map { it.memberId }.toSet(),
                        customSplits      = splitMap,
                        expenseItems      = itemStates,
                        isInitializing    = false
                    )
                }
            }
        }
    }

    // ── Standard field actions ─────────────────────────────────────────────────

    fun onDescriptionChange(v: String) = _state.update { it.copy(description = v, error = null) }
    fun onAmountChange(v: String)      = _state.update { it.copy(amount = v, error = null) }
    fun onItemDiscountToggle(index: Int) = updateItem(index) { it.copy(isDiscount = !it.isDiscount) }
    fun onPaidByChange(id: String)     = _state.update { it.copy(paidById = id) }
    fun onCategoryChange(c: Category)  = _state.update { it.copy(category = c) }
    fun onSplitTypeChange(t: SplitType) = _state.update { it.copy(splitType = t, customSplits = emptyMap()) }

    fun onMemberToggle(memberId: String) {
        _state.update {
            val set = it.selectedMemberIds.toMutableSet()
            if (memberId in set) set.remove(memberId) else set.add(memberId)
            it.copy(selectedMemberIds = set)
        }
    }

    fun onCustomSplitChange(memberId: String, value: String) {
        _state.update { it.copy(customSplits = it.customSplits + (memberId to value)) }
    }

    // ── Bill-item actions ──────────────────────────────────────────────────────

    fun onAddItem() = _state.update {
        it.copy(expenseItems = it.expenseItems + ExpenseItemState(), error = null)
    }

    fun onRemoveItem(index: Int) {
        // If this item was originally loaded from the DB, schedule its ID for deletion
        // on save.  Items added fresh in this session never existed in the DB, so they
        // need no DELETE — just dropping them from the in-memory list is enough.
        // No DB call is made here; the actual DELETE runs inside saveExpense().
        val item = _state.value.expenseItems.getOrNull(index)
        if (item != null && item.id in originalItemIds) {
            pendingItemDeletes.add(item.id)
        }

        _state.update { s ->
            val prevTotal = s.itemsTotal
            val newItems  = s.expenseItems.toMutableList().also { it.removeAt(index) }
            s.copy(
                expenseItems = newItems,
                // When the last item is removed, switch back to standard mode and
                // pre-fill the amount so the user doesn't land on an empty field.
                amount = if (newItems.isEmpty()) "%.2f".format(prevTotal) else s.amount
            )
        }
    }

    fun onItemNameChange(index: Int, name: String)   = updateItem(index) { it.copy(name = name) }
    fun onItemAmountChange(index: Int, amt: String)  = updateItem(index) { it.copy(amount = amt) }

    fun onItemToggleMember(itemIndex: Int, memberId: String) = updateItem(itemIndex) { item ->
        val set = item.assignedMemberIds.toMutableSet()
        if (memberId in set) set.remove(memberId) else set.add(memberId)
        item.copy(assignedMemberIds = set)
    }

    fun onItemSplitModeChange(itemIndex: Int, mode: ItemSplitMode) = updateItem(itemIndex) {
        it.copy(splitMode = mode, assignedMemberIds = emptySet(), memberWeights = emptyMap())
    }

    fun onItemWeightChange(itemIndex: Int, memberId: String, value: String) = updateItem(itemIndex) { item ->
        item.copy(memberWeights = item.memberWeights + (memberId to value))
    }

    private fun updateItem(index: Int, transform: (ExpenseItemState) -> ExpenseItemState) {
        _state.update { s ->
            val list = s.expenseItems.toMutableList()
            if (index in list.indices) list[index] = transform(list[index])
            s.copy(expenseItems = list, error = null)
        }
    }

    // ── Save ───────────────────────────────────────────────────────────────────

    fun saveExpense() {
        val s = _state.value

        // Common validations
        if (s.description.isBlank()) {
            _state.update { it.copy(error = "Enter a description") }; return
        }
        if (s.paidById.isEmpty()) {
            _state.update { it.copy(error = "Select who paid") }; return
        }
        if (isEditing && originalExpense == null) {
            _state.update { it.copy(error = "Original expense not found — it may have been deleted.") }
            return
        }

        if (s.expenseItems.isNotEmpty()) {
            // ── Item-driven save ───────────────────────────────────────────────
            if (s.expenseItems.any { it.name.isBlank() }) {
                _state.update { it.copy(error = "All items must have a name") }; return
            }
            if (s.expenseItems.any { Math.abs(it.parsedAmount) < 0.01 }) {
                _state.update { it.copy(error = "All item amounts must be non-zero") }; return
            }
            val total = s.itemsTotal
            if (Math.abs(total) < 0.01) {
                _state.update { it.copy(error = "Total must be non-zero") }; return
            }

            _state.update { it.copy(isLoading = true, error = null) }
            viewModelScope.launch {
                val allMemberIds = s.members.map { it.id }
                // Carry each item's stable ID so the repository can upsert by ID
                // instead of wiping the entire set and re-inserting.
                val billItems = s.expenseItems.map { item ->
                    BillItemData(
                        id                = item.id,
                        name              = item.name.trim().ifBlank { "Item" },
                        amount            = item.parsedAmount,
                        assignedMemberIds = item.assignedMemberIds.toList(),
                        splitMode         = item.splitMode,
                        memberWeights     = item.memberWeights.mapValues { (_, v) -> v.toDoubleOrNull() ?: 0.0 }
                    )
                }
                // Snapshot and clear the pending deletes before the suspend call so a
                // second tap after a transient error doesn't re-delete the same IDs.
                val toDelete = pendingItemDeletes.toList()
                pendingItemDeletes.clear()
                runCatching {
                    if (originalExpense != null) {
                        repository.updateExpenseWithItems(
                            expense        = originalExpense!!.copy(
                                description = s.description.trim(),
                                paidById    = s.paidById,
                                category    = s.category
                            ),
                            items          = billItems,
                            deletedItemIds = toDelete,
                            allMemberIds   = allMemberIds
                        )
                    } else {
                        repository.addExpenseWithItems(
                            groupId      = groupId,
                            description  = s.description.trim(),
                            paidById     = s.paidById,
                            category     = s.category,
                            items        = billItems,
                            allMemberIds = allMemberIds
                        )
                    }
                }.fold(
                    onSuccess = { _state.update { it.copy(isLoading = false, saved = true) } },
                    onFailure = { err ->
                        // Restore pending deletes so a retry will still remove them.
                        pendingItemDeletes.addAll(toDelete)
                        _state.update { it.copy(isLoading = false, error = err.message ?: "Save failed") }
                    }
                )
            }
        } else {
            // ── Standard save ──────────────────────────────────────────────────
            val totalAmount = s.amount.toDoubleOrNull() ?: run {
                _state.update { it.copy(error = "Enter a valid amount") }; return
            }
            if (totalAmount <= 0.0) {
                _state.update { it.copy(error = "Amount must be greater than zero") }; return
            }
            val finalAmount = totalAmount
            if (s.selectedMemberIds.isEmpty()) {
                _state.update { it.copy(error = "Select at least one member") }; return
            }

            val splits: List<Pair<String, Double>> = when (s.splitType) {
                SplitType.EQUAL -> {
                    val share = finalAmount / s.selectedMemberIds.size
                    s.selectedMemberIds.map { it to share }
                }
                SplitType.PERCENTAGE -> {
                    val sumPct = s.selectedMemberIds.sumOf { s.customSplits[it]?.toDoubleOrNull() ?: 0.0 }
                    if (Math.abs(sumPct - 100.0) > 0.01) {
                        _state.update { it.copy(error = "Percentages must sum to 100 (currently ${"%.1f".format(sumPct)})") }
                        return
                    }
                    val amounts = s.selectedMemberIds.map { id ->
                        id to finalAmount * (s.customSplits[id]?.toDoubleOrNull() ?: 0.0) / 100.0
                    }
                    if (amounts.any { Math.abs(it.second) < 0.01 }) {
                        _state.update { it.copy(error = "Each member's computed share must be at least ${s.currencySymbol}0.01") }
                        return
                    }
                    amounts
                }
                SplitType.AMOUNT -> {
                    val pairs = s.selectedMemberIds.map { id -> id to (s.customSplits[id]?.toDoubleOrNull() ?: 0.0) }
                    if (pairs.any { it.second < 0.01 }) {
                        _state.update { it.copy(error = "Each member's share must be at least ${s.currencySymbol}0.01") }
                        return
                    }
                    val sumAmt = pairs.sumOf { it.second }
                    if (Math.abs(sumAmt - totalAmount) > 0.01) {
                        _state.update { it.copy(error = "Amounts must sum to ${s.currencySymbol}${"%.2f".format(totalAmount)} (currently ${s.currencySymbol}${"%.2f".format(sumAmt)})") }
                        return
                    }
                    pairs
                }
            }

            _state.update { it.copy(isLoading = true, error = null) }
            viewModelScope.launch {
                runCatching {
                    if (originalExpense != null) {
                        repository.updateExpense(
                            expense = originalExpense!!.copy(
                                description = s.description.trim(),
                                amount      = finalAmount,
                                paidById    = s.paidById,
                                category    = s.category,
                                splitType   = s.splitType
                            ),
                            splits = splits
                        )
                    } else {
                        repository.addExpense(
                            groupId, s.description.trim(), finalAmount,
                            s.paidById, s.category, s.splitType, splits
                        )
                    }
                }.fold(
                    onSuccess = { _state.update { it.copy(isLoading = false, saved = true) } },
                    onFailure = { err -> _state.update { it.copy(isLoading = false, error = err.message ?: "Save failed") } }
                )
            }
        }
    }

    companion object {
        fun factory(groupId: String, expenseId: String? = null): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    val app = checkNotNull(this[APPLICATION_KEY]) as SplitExpenseApplication
                    AddExpenseViewModel(app.repository, groupId, app.appSettingsManager, expenseId)
                }
            }

        /**
         * Minimum milliseconds the edit-mode initialisation spinner must stay visible.
         * Prevents a sub-frame flash on fast devices where DB reads finish almost instantly.
         */
        private const val MIN_INIT_MS = 300L
    }
}
