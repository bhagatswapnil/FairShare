package com.splitexpense.ui.screens.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.splitexpense.SplitExpenseApplication
import com.splitexpense.data.db.entity.Category
import com.splitexpense.data.db.entity.ItemSplitMode
import com.splitexpense.data.db.entity.Member
import com.splitexpense.data.repository.BillItemData
import com.splitexpense.data.repository.ExpenseRepository
import com.splitexpense.util.ApiKeyManager
import com.splitexpense.util.AppSettingsManager
import com.splitexpense.util.GeminiService
import com.splitexpense.util.currencySymbolFor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.UUID

enum class ScanPhase { CAPTURE, PROCESSING, REVIEW }

data class BillItemState(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** EQUAL/PERCENTAGE: total amount. QUANTITY: unit price. */
    val amount: String,
    val splitMode: ItemSplitMode = ItemSplitMode.EQUAL,
    val assignedMemberIds: Set<String> = emptySet(),
    val memberWeights: Map<String, String> = emptyMap(),
    /** When true the item is a discount / refund and its amount is negative. */
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

data class ScanBillUiState(
    val phase: ScanPhase = ScanPhase.CAPTURE,
    val members: List<Member> = emptyList(),
    // Processing
    val isProcessing: Boolean = false,
    val error: String? = null,
    // Review form
    val description: String = "",
    val category: Category = Category.FOOD,
    val paidById: String = "",
    val items: List<BillItemState> = emptyList(),
    val isSaving: Boolean = false,
    val saved: Boolean = false,
    // API key
    val showApiKeyDialog: Boolean = false,
    val pendingImageBytes: ByteArray? = null,
    /** The owning group's currency symbol (e.g. "₹", "$"). Defaults to "₹" until loaded. */
    val currencySymbol: String = "₹"
) {
    val total: Double get() = items.sumOf { it.parsedAmount }

    /** Per-member totals derived from item assignments (handles all three split modes). */
    fun memberTotals(allMemberIds: List<String>): Map<String, Double> {
        val result = mutableMapOf<String, Double>()
        items.forEach { item ->
            val amt = item.parsedAmount
            if (amt <= 0) return@forEach
            when (item.splitMode) {
                ItemSplitMode.PERCENTAGE -> {
                    val totalPct = item.memberWeights.values.sumOf { it.toDoubleOrNull() ?: 0.0 }
                    if (totalPct <= 0) return@forEach
                    item.memberWeights.forEach { (id, pctStr) ->
                        val pct = pctStr.toDoubleOrNull() ?: 0.0
                        if (pct > 0) result[id] = (result[id] ?: 0.0) + amt * pct / totalPct
                    }
                }
                ItemSplitMode.QUANTITY -> {
                    val totalQty = item.memberWeights.values.sumOf { it.toDoubleOrNull() ?: 0.0 }
                    if (totalQty <= 0) return@forEach
                    item.memberWeights.forEach { (id, qtyStr) ->
                        val qty = qtyStr.toDoubleOrNull() ?: 0.0
                        if (qty > 0) result[id] = (result[id] ?: 0.0) + amt * qty / totalQty
                    }
                }
                ItemSplitMode.EQUAL -> {
                    val assignees = item.assignedMemberIds.ifEmpty { allMemberIds.toSet() }
                    if (assignees.isEmpty()) return@forEach
                    val share = amt / assignees.size
                    assignees.forEach { id -> result[id] = (result[id] ?: 0.0) + share }
                }
            }
        }
        return result
    }
}

class ScanBillViewModel(
    private val repository: ExpenseRepository,
    private val groupId: String,
    private val apiKeyManager: ApiKeyManager,
    private val appSettingsManager: AppSettingsManager
) : ViewModel() {

    private val _state = MutableStateFlow(ScanBillUiState())
    val state: StateFlow<ScanBillUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.getMembersForGroup(groupId).collect { members ->
                _state.update { s ->
                    val paidById = if (s.paidById.isEmpty() && members.isNotEmpty())
                        members.first().id else s.paidById
                    s.copy(members = members, paidById = paidById)
                }
            }
        }
        viewModelScope.launch {
            appSettingsManager.currencyCode.collect { code ->
                _state.update { it.copy(currencySymbol = currencySymbolFor(code)) }
            }
        }
    }

    // ── Image capture ──────────────────────────────────────────────────────────

    fun processImage(uri: Uri, context: Context) {
        val bytes = try {
            readAndCompressImage(uri, context)
        } catch (e: Exception) {
            _state.update { it.copy(error = "Could not read image: ${e.message}") }
            return
        }
        val key = apiKeyManager.getKey()
        if (key.isBlank()) {
            // No key yet — stash bytes and ask the user to enter one
            _state.update { it.copy(showApiKeyDialog = true, pendingImageBytes = bytes) }
            return
        }
        doProcessImage(bytes, key)
    }

    /** Called from the API-key dialog when the user saves their key. */
    fun saveApiKey(key: String) {
        val trimmed = key.trim()
        apiKeyManager.setKey(trimmed)
        val pending = _state.value.pendingImageBytes
        _state.update { it.copy(showApiKeyDialog = false, pendingImageBytes = null) }
        if (pending != null && trimmed.isNotBlank()) {
            doProcessImage(pending, trimmed)
        }
    }

    fun dismissApiKeyDialog() {
        _state.update { it.copy(showApiKeyDialog = false, pendingImageBytes = null) }
    }

    private fun doProcessImage(bytes: ByteArray, apiKey: String) {
        _state.update { it.copy(phase = ScanPhase.PROCESSING, isProcessing = true, error = null) }
        viewModelScope.launch {
            GeminiService.parseReceipt(bytes, apiKey).fold(
                onSuccess = { bill ->
                    _state.update { s ->
                        s.copy(
                            phase        = ScanPhase.REVIEW,
                            isProcessing = false,
                            description  = bill.description,
                            category     = bill.category,
                            items        = bill.items.map { item ->
                                BillItemState(
                                        name       = item.name,
                                        amount     = "%.2f".format(Math.abs(item.amount)),
                                        isDiscount = item.amount < 0
                                    )
                            }
                        )
                    }
                },
                onFailure = { err ->
                    _state.update { it.copy(phase = ScanPhase.CAPTURE, isProcessing = false,
                        error = err.message ?: "Failed to analyze bill") }
                }
            )
        }
    }

    private fun readAndCompressImage(uri: Uri, context: Context): ByteArray {
        // use{} closes the stream even if decodeStream throws; ?: error covers a null return
        // (decodeStream returns null for unsupported formats or OOM).
        val bitmap = context.contentResolver.openInputStream(uri)
            ?.use { BitmapFactory.decodeStream(it) }
            ?: error("Cannot open or decode image")
        // Scale down to max 1024px wide to keep API payload small.
        // Recycle the original immediately when a new scaled copy is created.
        val scaled = if (bitmap.width > 1024) {
            val ratio = 1024.0f / bitmap.width
            Bitmap.createScaledBitmap(bitmap, 1024, (bitmap.height * ratio).toInt(), true)
                .also { bitmap.recycle() }
        } else bitmap
        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, baos)
        return baos.toByteArray()
    }

    fun resetToCapture() {
        _state.update { it.copy(phase = ScanPhase.CAPTURE, error = null, isProcessing = false) }
    }

    // ── Review form edits ──────────────────────────────────────────────────────

    fun onDescriptionChange(v: String) = _state.update { it.copy(description = v) }
    fun onCategoryChange(v: Category)  = _state.update { it.copy(category = v) }
    fun onPaidByChange(v: String)      = _state.update { it.copy(paidById = v) }

    fun onItemNameChange(index: Int, name: String) = updateItem(index) { it.copy(name = name) }
    fun onItemAmountChange(index: Int, amt: String) = updateItem(index) { it.copy(amount = amt) }

    fun onToggleMember(itemIndex: Int, memberId: String) = updateItem(itemIndex) { item ->
        val set = item.assignedMemberIds.toMutableSet()
        if (memberId in set) set.remove(memberId) else set.add(memberId)
        item.copy(assignedMemberIds = set)
    }

    fun onItemSplitModeChange(itemIndex: Int, mode: ItemSplitMode) = updateItem(itemIndex) {
        it.copy(splitMode = mode, assignedMemberIds = emptySet(), memberWeights = emptyMap())
    }

    fun onItemDiscountToggle(itemIndex: Int) = updateItem(itemIndex) { it.copy(isDiscount = !it.isDiscount) }

    fun onItemWeightChange(itemIndex: Int, memberId: String, value: String) = updateItem(itemIndex) { item ->
        item.copy(memberWeights = item.memberWeights + (memberId to value))
    }

    fun addItem() = _state.update {
        it.copy(items = it.items + BillItemState(name = "", amount = ""))
    }

    fun removeItem(index: Int) = _state.update {
        it.copy(items = it.items.toMutableList().also { list -> list.removeAt(index) })
    }

    private fun updateItem(index: Int, transform: (BillItemState) -> BillItemState) {
        _state.update { s ->
            val list = s.items.toMutableList()
            if (index in list.indices) list[index] = transform(list[index])
            s.copy(items = list)
        }
    }

    // ── Save ───────────────────────────────────────────────────────────────────

    fun saveExpense() {
        val s = _state.value
        if (s.isSaving) return
        if (s.description.isBlank()) {
            _state.update { it.copy(error = "Enter a description") }; return
        }
        if (s.paidById.isEmpty()) {
            _state.update { it.copy(error = "Select who paid") }; return
        }
        if (s.items.isEmpty()) {
            _state.update { it.copy(error = "Add at least one item") }; return
        }
        if (s.items.any { it.name.isBlank() }) {
            _state.update { it.copy(error = "All items must have a name") }; return
        }
        if (s.items.any { Math.abs(it.parsedAmount) < 0.01 }) {
            _state.update { it.copy(error = "All item amounts must be non-zero") }; return
        }
        if (Math.abs(s.total) < 0.01) {
            _state.update { it.copy(error = "Total must be non-zero") }; return
        }

        _state.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            runCatching {
                val allMemberIds = s.members.map { it.id }
                repository.addExpenseWithItems(
                    groupId      = groupId,
                    description  = s.description.trim(),
                    paidById     = s.paidById,
                    category     = s.category,
                    items        = s.items.map { item ->
                        BillItemData(
                            name              = item.name.trim().ifBlank { "Item" },
                            amount            = item.parsedAmount,
                            assignedMemberIds = item.assignedMemberIds.toList(),
                            splitMode         = item.splitMode,
                            memberWeights     = item.memberWeights.mapValues { (_, v) -> v.toDoubleOrNull() ?: 0.0 }
                        )
                    },
                    allMemberIds = allMemberIds
                )
            }.fold(
                onSuccess = { _state.update { it.copy(isSaving = false, saved = true) } },
                onFailure = { err ->
                    _state.update { it.copy(isSaving = false, error = err.message ?: "Save failed") }
                }
            )
        }
    }

    companion object {
        fun factory(groupId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as SplitExpenseApplication
                ScanBillViewModel(app.repository, groupId, app.apiKeyManager, app.appSettingsManager)
            }
        }
    }
}
