package com.splitexpense.ui.screens.expense

import androidx.lifecycle.*
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.splitexpense.SplitExpenseApplication
import com.splitexpense.data.db.entity.Category
import com.splitexpense.data.db.entity.Expense
import com.splitexpense.data.db.entity.Member
import com.splitexpense.data.db.entity.SplitType
import com.splitexpense.data.repository.ExpenseRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class AddExpenseUiState(
    val members: List<Member> = emptyList(),
    val description: String = "",
    val amount: String = "",
    val paidById: String = "",
    val category: Category = Category.OTHER,
    val splitType: SplitType = SplitType.EQUAL,
    val selectedMemberIds: Set<String> = emptySet(),
    val customSplits: Map<String, String> = emptyMap(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false
)

class AddExpenseViewModel(
    private val repository: ExpenseRepository,
    private val groupId: String,
    private val expenseId: String? = null   // non-null → edit mode
) : ViewModel() {

    private val _state = MutableStateFlow(AddExpenseUiState())
    val state: StateFlow<AddExpenseUiState> = _state.asStateFlow()

    // Holds the original expense entity in edit mode (preserves id / date / groupId)
    private var originalExpense: Expense? = null

    val isEditing: Boolean get() = expenseId != null

    init {
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

        // 2. In edit mode, pre-fill from existing expense
        if (expenseId != null) {
            viewModelScope.launch {
                val expense = repository.getExpenseById(expenseId) ?: return@launch
                val splits  = repository.getSplitsForExpenseOnce(expenseId)
                originalExpense = expense

                val splitMap: Map<String, String> = when (expense.splitType) {
                    SplitType.EQUAL      -> emptyMap()
                    SplitType.PERCENTAGE -> splits.associate { it.memberId to "%.2f".format(it.percentage) }
                    SplitType.AMOUNT     -> splits.associate { it.memberId to "%.2f".format(it.amount) }
                }

                _state.update { cur ->
                    cur.copy(
                        description       = expense.description,
                        amount            = "%.2f".format(expense.amount),
                        paidById          = expense.paidById,
                        category          = expense.category,
                        splitType         = expense.splitType,
                        selectedMemberIds = splits.map { it.memberId }.toSet(),
                        customSplits      = splitMap
                    )
                }
            }
        }
    }

    fun onDescriptionChange(v: String) = _state.update { it.copy(description = v, error = null) }
    fun onAmountChange(v: String)      = _state.update { it.copy(amount = v, error = null) }
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

    fun saveExpense() {
        val s = _state.value
        val totalAmount = s.amount.toDoubleOrNull() ?: run {
            _state.update { it.copy(error = "Enter a valid amount") }; return
        }
        if (totalAmount <= 0.0) {
            _state.update { it.copy(error = "Amount must be greater than zero") }; return
        }
        if (s.description.isBlank()) {
            _state.update { it.copy(error = "Enter a description") }; return
        }
        if (s.selectedMemberIds.isEmpty()) {
            _state.update { it.copy(error = "Select at least one member") }; return
        }
        if (s.paidById.isEmpty()) {
            _state.update { it.copy(error = "Select who paid") }; return
        }

        val splits: List<Pair<String, Double>> = when (s.splitType) {
            SplitType.EQUAL -> {
                val share = totalAmount / s.selectedMemberIds.size
                s.selectedMemberIds.map { it to share }
            }
            SplitType.PERCENTAGE -> {
                val sumPct = s.selectedMemberIds.sumOf { s.customSplits[it]?.toDoubleOrNull() ?: 0.0 }
                if (Math.abs(sumPct - 100.0) > 0.01) {
                    _state.update { it.copy(error = "Percentages must sum to 100 (currently ${"%.1f".format(sumPct)})") }
                    return
                }
                val amounts = s.selectedMemberIds.map { id ->
                    id to totalAmount * (s.customSplits[id]?.toDoubleOrNull() ?: 0.0) / 100.0
                }
                if (amounts.any { it.second < 0.01 }) {
                    _state.update { it.copy(error = "Each member's computed share must be at least ₹0.01") }
                    return
                }
                amounts
            }
            SplitType.AMOUNT -> {
                val pairs = s.selectedMemberIds.map { id -> id to (s.customSplits[id]?.toDoubleOrNull() ?: 0.0) }
                if (pairs.any { it.second < 0.01 }) {
                    _state.update { it.copy(error = "Each member's share must be at least ₹0.01") }
                    return
                }
                val sumAmt = pairs.sumOf { it.second }
                if (Math.abs(sumAmt - totalAmount) > 0.01) {
                    _state.update { it.copy(error = "Amounts must sum to ₹${"%.2f".format(totalAmount)} (currently ₹${"%.2f".format(sumAmt)})") }
                    return
                }
                pairs
            }
        }

        // In edit mode the original expense must have been loaded; if not (e.g. deleted
        // externally while the screen was open) refuse to save instead of creating a
        // duplicate via the addExpense path.
        if (isEditing && originalExpense == null) {
            _state.update { it.copy(error = "Original expense not found — it may have been deleted.") }
            return
        }

        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            if (originalExpense != null) {
                // Edit mode: update in place, preserving id / date / groupId
                repository.updateExpense(
                    expense = originalExpense!!.copy(
                        description = s.description.trim(),
                        amount      = totalAmount,
                        paidById    = s.paidById,
                        category    = s.category,
                        splitType   = s.splitType
                    ),
                    splits = splits
                )
            } else {
                repository.addExpense(
                    groupId, s.description.trim(), totalAmount,
                    s.paidById, s.category, s.splitType, splits
                )
            }
            _state.update { it.copy(isLoading = false, saved = true) }
        }
    }

    companion object {
        fun factory(groupId: String, expenseId: String? = null): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    val app = checkNotNull(this[APPLICATION_KEY]) as SplitExpenseApplication
                    AddExpenseViewModel(app.repository, groupId, expenseId)
                }
            }
    }
}
