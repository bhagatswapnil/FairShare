package com.splitexpense.ui.screens.home

import android.net.Uri
import androidx.lifecycle.*
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.splitexpense.SplitExpenseApplication
import com.splitexpense.data.db.entity.Group
import com.splitexpense.data.db.entity.Member
import com.splitexpense.data.repository.ExpenseRepository
import com.splitexpense.util.AppSettingsManager
import com.splitexpense.util.BackupManager
import com.splitexpense.util.currencySymbolFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

// ── UI state ───────────────────────────────────────────────────────────────────

data class HomeUiState(
    val groups: List<Group> = emptyList(),
    val groupTotals: Map<String, Double> = emptyMap(),        // groupId → total spent
    val groupMemberCounts: Map<String, Int> = emptyMap(),     // groupId → member count
    val groupMembers: Map<String, List<Member>> = emptyMap(), // groupId → member list (avatars)
    val groupPendingAmounts: Map<String, Double> = emptyMap(),// groupId → outstanding debt (settlements-adjusted)
    val groupSettledAmounts: Map<String, Double> = emptyMap(),// groupId → sum of recorded settlements
    /** The single app-wide currency. Same value for every group — see [AppSettingsManager]. */
    val currencyCode: String = "INR",
    val currencySymbol: String = "₹",
    val isLoading: Boolean = true
)

/**
 * Represents the current state of a backup or restore operation.
 * Kept in a separate [StateFlow] from [HomeUiState] to avoid coupling it to the
 * complex `combine` chain that drives the group list.
 */
sealed class BackupRestoreState {
    data object Idle : BackupRestoreState()
    data object InProgress : BackupRestoreState()
    /** Backup ZIP written to local storage; [savedPath] is a human-readable location string. */
    data class BackupReady(val savedPath: String) : BackupRestoreState()
    data object RestoreComplete : BackupRestoreState()
    data class Error(val message: String) : BackupRestoreState()
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

class HomeViewModel(
    private val repository: ExpenseRepository,
    private val backupManager: BackupManager,
    private val appSettingsManager: AppSettingsManager
) : ViewModel() {

    // ── Group list state ───────────────────────────────────────────────────────

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
        ) { settledAmounts, allSettlements -> Pair(settledAmounts, allSettlements) },

        // Batch 4: the single app-wide currency — same value applies to every group.
        appSettingsManager.currencyCode

    ) { (groups, totals, counts), (allMembers, allExpenses, allSplits), (settledMap, allSettlements), currencyCode ->

        // Group settlements by groupId so computePendingPerGroup can factor them in
        val settlementsByGroup = allSettlements.groupBy { it.groupId }

        val pendingMap = repository.computePendingPerGroup(
            groups         = groups,
            allMembers     = allMembers,
            allExpenses    = allExpenses,
            allSplits      = allSplits,
            allSettlements = settlementsByGroup
        )

        HomeUiState(
            groups              = groups,
            groupTotals         = totals,
            groupMemberCounts   = counts,
            groupMembers        = allMembers.groupBy { it.groupId },
            groupPendingAmounts = pendingMap,
            groupSettledAmounts = settledMap,
            currencyCode        = currencyCode,
            currencySymbol      = currencySymbolFor(currencyCode),
            isLoading           = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    // ── Backup / restore state ─────────────────────────────────────────────────

    private val _backupRestoreState = MutableStateFlow<BackupRestoreState>(BackupRestoreState.Idle)
    val backupRestoreState: StateFlow<BackupRestoreState> = _backupRestoreState.asStateFlow()

    /**
     * Serialises the entire DB to a JSON file in the cache dir and emits [BackupRestoreState.BackupReady]
     * with the FileProvider URI when done.  The UI is responsible for launching the share sheet.
     */
    fun createBackup() {
        if (_backupRestoreState.value is BackupRestoreState.InProgress) return
        viewModelScope.launch(Dispatchers.IO) {
            _backupRestoreState.value = BackupRestoreState.InProgress
            backupManager.createBackup().fold(
                onSuccess = { uri -> _backupRestoreState.value = BackupRestoreState.BackupReady(uri) },
                onFailure = { e  -> _backupRestoreState.value = BackupRestoreState.Error(e.message ?: "Backup failed") }
            )
        }
    }

    /**
     * Parses [uri] as a FairShare backup JSON and replaces the entire database.
     * Parsing happens before any destructive write so a corrupt file causes no data loss.
     */
    fun restoreBackup(uri: Uri) {
        if (_backupRestoreState.value is BackupRestoreState.InProgress) return
        viewModelScope.launch(Dispatchers.IO) {
            _backupRestoreState.value = BackupRestoreState.InProgress
            backupManager.restoreBackup(uri).fold(
                onSuccess = { _backupRestoreState.value = BackupRestoreState.RestoreComplete },
                onFailure = { e -> _backupRestoreState.value = BackupRestoreState.Error(e.message ?: "Restore failed") }
            )
        }
    }

    /** Call after the UI has handled a terminal backup/restore state to return to [BackupRestoreState.Idle]. */
    fun resetBackupRestoreState() { _backupRestoreState.value = BackupRestoreState.Idle }

    // ── Group actions ─────────────────────────────────────────────────────────

    fun createGroup(name: String, description: String, emoji: String) {
        // Stamp the new group with the current global currency. The column itself is no
        // longer read for display anywhere (the app-wide setting is the source of truth),
        // but keeping it populated avoids a confusing "INR" default sitting in the DB.
        val currencyCode = appSettingsManager.currencyCode.value
        viewModelScope.launch { repository.createGroup(name, description, emoji, currencyCode) }
    }

    fun deleteGroup(group: Group) {
        viewModelScope.launch { repository.deleteGroup(group) }
    }

    /** Changes the single app-wide currency. Reflected immediately on every open screen. */
    fun setCurrencyCode(code: String) = appSettingsManager.setCurrencyCode(code)

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) as SplitExpenseApplication
                HomeViewModel(app.repository, app.backupManager, app.appSettingsManager)
            }
        }
    }
}
