package com.splitexpense.ui.screens.group

import android.content.Context
import android.net.Uri
import androidx.lifecycle.*
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.splitexpense.SplitExpenseApplication
import com.splitexpense.data.db.entity.Category
import com.splitexpense.data.db.entity.Expense
import com.splitexpense.data.db.entity.ExpenseItem
import com.splitexpense.data.db.entity.ExpenseSplit
import com.splitexpense.data.db.entity.Group
import com.splitexpense.data.db.entity.Member
import com.splitexpense.data.db.entity.Settlement
import com.splitexpense.data.model.DebtTransaction
import com.splitexpense.data.model.MemberBalance
import com.splitexpense.data.repository.ExpenseRepository
import com.splitexpense.util.AppSettingsManager
import com.splitexpense.util.PdfExporter
import com.splitexpense.util.currencySymbolFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GroupUiState(
    val group: Group? = null,
    val members: List<Member> = emptyList(),
    val expenses: List<Expense> = emptyList(),
    val balances: List<MemberBalance> = emptyList(),
    val simplifiedDebts: List<DebtTransaction> = emptyList(),
    val categorySpending: Map<Category, Double> = emptyMap(),
    val totalExpense: Double = 0.0,
    val settlements: List<Settlement> = emptyList(),
    val isLoading: Boolean = true,
    val isExporting: Boolean = false,
    val exportUri: Uri? = null,
    val exportError: String? = null,
    val groupDeleted: Boolean = false,
    val membersWithExpenses: Set<String> = emptySet(),
    val splitCountByExpenseId: Map<String, Int> = emptyMap(),
    val splitsByExpenseId: Map<String, List<ExpenseSplit>> = emptyMap(),
    val expenseItemsByExpenseId: Map<String, List<ExpenseItem>> = emptyMap(),
    /** The single app-wide currency symbol (e.g. "₹", "$"). Defaults to "₹" until loaded. */
    val currencySymbol: String = "₹"
)

class GroupViewModel(
    private val repository: ExpenseRepository,
    private val groupId: String,
    private val appSettingsManager: AppSettingsManager
) : ViewModel() {

    // Guards against rapid double-taps on the Confirm button producing duplicate rows.
    // Accessed only from the main thread (ViewModel action methods are main-thread-only).
    private var isSettling = false

    private val _uiState = MutableStateFlow(GroupUiState())
    val uiState: StateFlow<GroupUiState> = _uiState.asStateFlow()

    init {
        // Collect bill-scan items separately (6th flow — beyond combine's 5-arg limit)
        viewModelScope.launch {
            repository.getItemsForGroup(groupId).collect { items ->
                _uiState.update { it.copy(expenseItemsByExpenseId = items.groupBy { it.expenseId }) }
            }
        }

        // Collect the app-wide currency separately too — same reasoning as the items flow.
        viewModelScope.launch {
            appSettingsManager.currencyCode.collect { code ->
                _uiState.update { it.copy(currencySymbol = currencySymbolFor(code)) }
            }
        }

        viewModelScope.launch {
            // Combine 5 flows: group, members, expenses, splits, settlements
            combine(
                repository.getGroupById(groupId),
                repository.getMembersForGroup(groupId),
                repository.getExpensesForGroup(groupId),
                repository.getSplitsForGroup(groupId),
                repository.getSettlementsForGroup(groupId)
            ) { group, members, expenses, splits, settlements ->
                val balances = repository.calculateBalances(members, expenses, splits, settlements)
                val memberIdsWithExpenses = buildSet<String> {
                    expenses.forEach { add(it.paidById) }
                    splits.forEach { add(it.memberId) }
                    settlements.forEach { add(it.fromId); add(it.toId) }
                }
                val splitsGrouped = splits.groupBy { it.expenseId }
                val splitCountByExpenseId = splitsGrouped.mapValues { it.value.size }
                _uiState.value.copy(
                    group                  = group,
                    members                = members,
                    expenses               = expenses,
                    balances               = balances,
                    simplifiedDebts        = repository.simplifyDebts(balances),
                    categorySpending       = repository.categorySpending(expenses, splits, null),
                    totalExpense           = expenses.sumOf { it.amount },
                    settlements            = settlements,
                    membersWithExpenses    = memberIdsWithExpenses,
                    splitCountByExpenseId  = splitCountByExpenseId,
                    splitsByExpenseId      = splitsGrouped,
                    isLoading              = false
                )
            }.collect { newState ->
                // Use update (CAS) so the expenseItemsByExpenseId and currencySymbol written
                // by the other two coroutines are never silently overwritten by a concurrent
                // combine emission.
                _uiState.update { current ->
                    newState.copy(
                        expenseItemsByExpenseId = current.expenseItemsByExpenseId,
                        currencySymbol          = current.currencySymbol
                    )
                }
            }
        }
    }

    // ── Group actions ──────────────────────────────────────────────────────────

    fun deleteGroup() {
        val group = _uiState.value.group ?: return
        viewModelScope.launch {
            repository.deleteGroup(group)
            _uiState.update { it.copy(groupDeleted = true) }
        }
    }

    fun editGroup(name: String, description: String, emoji: String) {
        val current = _uiState.value.group ?: return
        viewModelScope.launch {
            repository.updateGroup(
                current.copy(
                    name        = name.trim(),
                    description = description.trim(),
                    emoji       = emoji.trim().ifBlank { "💰" }
                    // currencyCode is intentionally left untouched — currency is now a
                    // single app-wide setting (AppSettingsManager), not editable per group.
                )
            )
        }
    }

    // ── Member actions ─────────────────────────────────────────────────────────

    fun addMember(name: String, email: String, avatarColor: Long) {
        viewModelScope.launch { repository.addMember(groupId, name, email, avatarColor) }
    }

    fun deleteMember(member: Member) {
        viewModelScope.launch { repository.deleteMember(member) }
    }

    // ── Expense actions ────────────────────────────────────────────────────────

    fun deleteExpense(expense: Expense) {
        viewModelScope.launch { repository.deleteExpense(expense) }
    }

    // ── Settlement actions ─────────────────────────────────────────────────────

    /** Records a full settlement of the given debt transaction. */
    fun recordSettlement(debt: DebtTransaction) {
        if (isSettling) return
        isSettling = true
        viewModelScope.launch {
            try {
                repository.addSettlement(
                    groupId  = groupId,
                    fromId   = debt.fromId,
                    toId     = debt.toId,
                    fromName = debt.fromName,
                    toName   = debt.toName,
                    amount   = debt.amount
                )
            } finally {
                isSettling = false
            }
        }
    }

    /** Removes a previously recorded settlement (undo). */
    fun deleteSettlement(settlement: Settlement) {
        viewModelScope.launch { repository.deleteSettlement(settlement) }
    }

    // ── Export ─────────────────────────────────────────────────────────────────

    fun exportPdf(context: Context) {
        val state = _uiState.value
        val group = state.group ?: return
        if (state.isExporting) return
        _uiState.update { it.copy(isExporting = true, exportUri = null, exportError = null) }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val allSplits = repository.getSplitsForGroupOnce(groupId)
                    val allItems  = repository.getItemsForGroupOnce(groupId)
                    PdfExporter(context).export(
                        group          = group,
                        members        = state.members,
                        expenses       = state.expenses,
                        splits         = allSplits,
                        balances       = state.balances,
                        debts          = state.simplifiedDebts,
                        expenseItems   = allItems,
                        currencySymbol = state.currencySymbol
                    )
                }
            }.fold(
                onSuccess = { uri -> _uiState.update { it.copy(isExporting = false, exportUri = uri) } },
                onFailure = { err -> _uiState.update { it.copy(isExporting = false, exportError = err.message ?: "Export failed") } }
            )
        }
    }

    fun clearExportState() {
        _uiState.update { it.copy(exportUri = null, exportError = null) }
    }

    companion object {
        fun factory(groupId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) as SplitExpenseApplication
                GroupViewModel(app.repository, groupId, app.appSettingsManager)
            }
        }
    }
}
