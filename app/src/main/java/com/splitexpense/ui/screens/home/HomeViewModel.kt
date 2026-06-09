package com.splitexpense.ui.screens.home

import androidx.lifecycle.*
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.splitexpense.SplitExpenseApplication
import com.splitexpense.data.db.entity.Group
import com.splitexpense.data.db.entity.Member
import com.splitexpense.data.repository.ExpenseRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class HomeUiState(
    val groups: List<Group> = emptyList(),
    val groupTotals: Map<String, Double> = emptyMap(),        // groupId → total spent
    val groupMemberCounts: Map<String, Int> = emptyMap(),     // groupId → member count
    val groupMembers: Map<String, List<Member>> = emptyMap(), // groupId → member list (avatars)
    val groupPendingAmounts: Map<String, Double> = emptyMap(),// groupId → outstanding debt (settlements-adjusted)
    val groupSettledAmounts: Map<String, Double> = emptyMap(),// groupId → sum of recorded settlements
    val isLoading: Boolean = true
)

class HomeViewModel(private val repository: ExpenseRepository) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = combine(
        // Batch 1: static group aggregates (no computation needed)
        combine(
            repository.getAllGroups(),
            repository.getGroupExpenseTotals(),
            repository.getGroupMemberCounts()
        ) { groups, totals, counts -> Triple(groups, totals, counts) },

        // Batch 2: raw data needed for accurate pending calculation
        combine(
            repository.getAllMembers(),
            repository.getAllExpenses(),
            repository.getAllSplits()
        ) { members, expenses, splits -> Triple(members, expenses, splits) },

        // Batch 3: settlement data — amounts for progress bar + full list for pending calc
        combine(
            repository.getGroupSettledAmounts(),
            repository.getAllSettlements()
        ) { settledAmounts, allSettlements -> Pair(settledAmounts, allSettlements) }

    ) { (groups, totals, counts), (allMembers, allExpenses, allSplits), (settledMap, allSettlements) ->

        // Group settlements by groupId so computePendingPerGroup can factor them in
        val settlementsByGroup = allSettlements.groupBy { it.groupId }

        val pendingMap = repository.computePendingPerGroup(
            groups         = groups,
            allMembers     = allMembers,
            allExpenses    = allExpenses,
            allSplits      = allSplits,
            allSettlements = settlementsByGroup   // ← settlements now included
        )

        HomeUiState(
            groups              = groups,
            groupTotals         = totals,
            groupMemberCounts   = counts,
            groupMembers        = allMembers.groupBy { it.groupId },
            groupPendingAmounts = pendingMap,
            groupSettledAmounts = settledMap,
            isLoading           = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun createGroup(name: String, description: String, emoji: String) {
        viewModelScope.launch { repository.createGroup(name, description, emoji) }
    }

    fun deleteGroup(group: Group) {
        viewModelScope.launch { repository.deleteGroup(group) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) as SplitExpenseApplication
                HomeViewModel(app.repository)
            }
        }
    }
}
