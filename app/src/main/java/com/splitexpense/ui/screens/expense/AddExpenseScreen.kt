@file:OptIn(ExperimentalMaterial3Api::class)

package com.splitexpense.ui.screens.expense

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.splitexpense.data.db.entity.Category
import com.splitexpense.data.db.entity.Member
import com.splitexpense.data.db.entity.SplitType
import com.splitexpense.ui.screens.home.darkFieldColors
import com.splitexpense.ui.theme.*
import com.splitexpense.util.color
import com.splitexpense.util.displayName
import com.splitexpense.util.formatCurrency
import com.splitexpense.util.icon

@Composable
fun AddExpenseScreen(
    groupId: String,
    expenseId: String? = null,
    onBack: () -> Unit,
    viewModel: AddExpenseViewModel = viewModel(
        key = "add_expense_${groupId}_${expenseId ?: "new"}",
        factory = AddExpenseViewModel.factory(groupId, expenseId)
    )
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val hasItems = state.expenseItems.isNotEmpty()
    var showItemsWarning by remember { mutableStateOf(false) }

    // Warn before switching to item mode when there is already amount/split data to lose.
    // Only fires on the very first tap (items list is empty → transitioning into item mode).
    val hasExistingData = viewModel.isEditing || state.amount.isNotBlank()
    val onAddFirstItem: () -> Unit = {
        if (!hasItems && hasExistingData) showItemsWarning = true
        else viewModel.onAddItem()
    }

    LaunchedEffect(state.saved) {
        if (state.saved) onBack()
    }

    if (showItemsWarning) {
        AlertDialog(
            onDismissRequest = { showItemsWarning = false },
            containerColor   = Surface2,
            shape            = RoundedCornerShape(16.dp),
            icon = {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = AmberAccent)
            },
            title = {
                Text("Replace split data?", color = Text1, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium)
            },
            text = {
                Text(
                    "Switching to itemised mode will erase the current amount and split " +
                    "settings. This cannot be undone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Text2
                )
            },
            confirmButton = {
                TextButton(onClick = { showItemsWarning = false; viewModel.onAddItem() }) {
                    Text("Continue", color = NeonRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showItemsWarning = false }) {
                    Text("Cancel", color = Text3)
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(Bg)) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── App Bar ───────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface1)
                    .statusBarsPadding()
                    .padding(horizontal = 18.dp)
                    .padding(top = 16.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(Surface3, RoundedCornerShape(10.dp))
                        .border(1.dp, Border, RoundedCornerShape(10.dp))
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Text2, modifier = Modifier.size(18.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        if (viewModel.isEditing) "Edit Expense" else "Add Expense",
                        style = MaterialTheme.typography.titleLarge, color = Text1, fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (viewModel.isEditing) "EDIT TRANSACTION" else "NEW TRANSACTION",
                        style = MaterialTheme.typography.labelSmall, color = Text3, letterSpacing = 1.sp
                    )
                }
            }
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Border))

            // Show spinner until edit-mode data is fully loaded
            if (state.isInitializing) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = NeonGreen, strokeWidth = 2.dp, modifier = Modifier.size(36.dp))
                }
                return@Column
            }

            // ── Form ──────────────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Amount: computed from items (read-only) or manual
                if (hasItems) {
                    ComputedAmountDisplay(total = state.itemsTotal, currencySymbol = state.currencySymbol)
                } else {
                    AmountField(amount = state.amount, onAmountChange = viewModel::onAmountChange, currencySymbol = state.currencySymbol)
                }

                OutlinedTextField(
                    value = state.description,
                    onValueChange = viewModel::onDescriptionChange,
                    label = { Text("Description *", color = Text3) },
                    placeholder = { Text("What's this expense for?", color = Text3) },
                    leadingIcon = { Icon(Icons.Filled.Description, contentDescription = null, tint = Text3, modifier = Modifier.size(16.dp)) },
                    singleLine = true,
                    colors = darkFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )

                PaidBySelector(members = state.members, selectedId = state.paidById, onSelect = viewModel::onPaidByChange)
                CategorySelector(selected = state.category, onSelect = viewModel::onCategoryChange)

                // Split controls only shown when NOT in item mode
                if (!hasItems) {
                    SplitTypeSelector(selected = state.splitType, onSelect = viewModel::onSplitTypeChange)
                    MemberSplitSection(
                        members             = state.members,
                        selectedIds         = state.selectedMemberIds,
                        splitType           = state.splitType,
                        amount              = state.amount.toDoubleOrNull() ?: 0.0,
                        customSplits        = state.customSplits,
                        currencySymbol      = state.currencySymbol,
                        onToggleMember      = viewModel::onMemberToggle,
                        onCustomSplitChange = viewModel::onCustomSplitChange
                    )
                }

                // Bill items section (always present)
                ExpenseItemsSection(
                    items              = state.expenseItems,
                    members            = state.members,
                    currencySymbol     = state.currencySymbol,
                    onAddItem          = onAddFirstItem,
                    onRemoveItem       = viewModel::onRemoveItem,
                    onItemNameChange   = viewModel::onItemNameChange,
                    onItemAmountChange = viewModel::onItemAmountChange,
                    onToggleMember     = viewModel::onItemToggleMember
                )

                state.error?.let { err ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(NeonRedDim, RoundedCornerShape(8.dp))
                            .border(1.dp, NeonRed.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Error, contentDescription = null, tint = NeonRed, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(err, style = MaterialTheme.typography.bodySmall, color = NeonRed)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .background(if (!state.isLoading) Text1 else Surface3, RoundedCornerShape(12.dp))
                        .clickable(enabled = !state.isLoading) { viewModel.saveExpense() },
                    contentAlignment = Alignment.Center
                ) {
                    if (state.isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = NeonGreen, strokeWidth = 2.dp)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Filled.Save, contentDescription = null, tint = Bg, modifier = Modifier.size(18.dp))
                            Text(
                                if (viewModel.isEditing) "Save Changes" else "Save Expense",
                                style = MaterialTheme.typography.titleSmall, color = Bg, fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

// ── Computed total (item-mode) ────────────────────────────────────────────────

@Composable
private fun ComputedAmountDisplay(total: Double, currencySymbol: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface2, RoundedCornerShape(14.dp))
            .border(1.dp, Border, RoundedCornerShape(14.dp))
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("TOTAL FROM ITEMS", style = MaterialTheme.typography.labelSmall,
            color = Text3, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(currencySymbol, style = MaterialTheme.typography.headlineLarge,
                color = NeonGreen, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(4.dp))
            Text(
                "%.2f".format(total),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = Text1
            )
        }
        Text("Auto-calculated from bill items below",
            style = MaterialTheme.typography.labelSmall, color = Text3)
    }
}

// ── Bill items section ────────────────────────────────────────────────────────

@Composable
private fun ExpenseItemsSection(
    items: List<ExpenseItemState>,
    members: List<Member>,
    currencySymbol: String,
    onAddItem: () -> Unit,
    onRemoveItem: (Int) -> Unit,
    onItemNameChange: (Int, String) -> Unit,
    onItemAmountChange: (Int, String) -> Unit,
    onToggleMember: (Int, String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

        // Section header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (items.isEmpty()) "BILL ITEMS (OPTIONAL)"
                else "BILL ITEMS  ·  ${items.size}  ·  ${formatCurrency(items.sumOf { it.parsedAmount }, currencySymbol)}",
                style = MaterialTheme.typography.labelSmall,
                color = Text3,
                letterSpacing = 0.8.sp,
                modifier = Modifier.weight(1f)
            )
        }

        // Item rows
        items.forEachIndexed { index, item ->
            ExpenseItemRow(
                item           = item,
                index          = index,
                members        = members,
                currencySymbol = currencySymbol,
                onNameChange   = { onItemNameChange(index, it) },
                onAmountChange = { onItemAmountChange(index, it) },
                onToggleMember = { onToggleMember(index, it) },
                onRemove       = { onRemoveItem(index) }
            )
        }

        // Add item button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface2, RoundedCornerShape(10.dp))
                .border(
                    1.dp,
                    if (items.isEmpty()) Border else BorderHi,
                    RoundedCornerShape(10.dp)
                )
                .clickable(onClick = onAddItem)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Filled.Add, contentDescription = null,
                tint = if (items.isEmpty()) Text3 else NeonGreen,
                modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                if (items.isEmpty()) "Add Bill Items" else "Add Item",
                color = if (items.isEmpty()) Text3 else NeonGreen,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun ExpenseItemRow(
    item: ExpenseItemState,
    index: Int,
    members: List<Member>,
    currencySymbol: String,
    onNameChange: (String) -> Unit,
    onAmountChange: (String) -> Unit,
    onToggleMember: (String) -> Unit,
    onRemove: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface2, RoundedCornerShape(12.dp))
            .border(1.dp, Border, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Name + Amount + Remove
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${index + 1}.", color = Text3,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(20.dp))
            OutlinedTextField(
                value = item.name,
                onValueChange = onNameChange,
                placeholder = { Text("Item name", color = Text3) },
                singleLine = true,
                colors = darkFieldColors(),
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodySmall.copy(color = Text1)
            )
            OutlinedTextField(
                value = item.amount,
                onValueChange = { v ->
                    if (v.isEmpty() || v.matches(Regex("^\\d*\\.?\\d{0,2}$"))) onAmountChange(v)
                },
                prefix = { Text(currencySymbol, color = Text3, style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                colors = darkFieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.width(100.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(color = Text1)
            )
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(NeonRedDim, RoundedCornerShape(8.dp))
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Remove",
                    tint = NeonRed, modifier = Modifier.size(14.dp))
            }
        }

        // Member assignment chips
        if (members.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("For:", color = Text3, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(24.dp))
                members.forEach { member ->
                    val assigned = member.id in item.assignedMemberIds
                    val isAll    = item.assignedMemberIds.isEmpty()
                    val active   = assigned || isAll
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (active) NeonGreenDim else Surface3)
                            .border(
                                1.dp,
                                if (active) NeonGreen.copy(alpha = 0.6f) else Border,
                                RoundedCornerShape(6.dp)
                            )
                            .alpha(if (active) 1f else 0.55f)
                            .clickable { onToggleMember(member.id) }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            member.name.take(8),
                            color = if (active) NeonGreen else Text3,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            // Per-person share hint
            val amt = item.parsedAmount
            val assigneeCount = if (item.assignedMemberIds.isEmpty()) members.size
                                else item.assignedMemberIds.size
            if (amt > 0.01 && assigneeCount > 1) {
                Text(
                    "${formatCurrency(amt / assigneeCount, currencySymbol)}/person",
                    color = Text3,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 30.dp)
                )
            }
        }
    }
}

// ── Amount field (manual mode) ────────────────────────────────────────────────

@Composable
private fun AmountField(amount: String, onAmountChange: (String) -> Unit, currencySymbol: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface2, RoundedCornerShape(14.dp))
            .border(1.dp, Border, RoundedCornerShape(14.dp))
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("AMOUNT", style = MaterialTheme.typography.labelSmall, color = Text3, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(currencySymbol, style = MaterialTheme.typography.headlineLarge, color = NeonGreen, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(4.dp))
            OutlinedTextField(
                value = amount,
                onValueChange = { if (it.matches(Regex("^\\d*\\.?\\d{0,2}$"))) onAmountChange(it) },
                placeholder = { Text("0.00", style = MaterialTheme.typography.headlineLarge, color = Text3) },
                textStyle = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold, color = Text1),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor    = Color.Transparent,
                    focusedBorderColor      = Color.Transparent,
                    cursorColor             = NeonGreen,
                    focusedTextColor        = Text1,
                    unfocusedTextColor      = Text1,
                    focusedContainerColor   = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                ),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun PaidBySelector(members: List<Member>, selectedId: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = members.firstOrNull { it.id == selectedId }

    Column {
        Text("PAID BY", style = MaterialTheme.typography.labelSmall, color = Text3, letterSpacing = 0.8.sp)
        Spacer(Modifier.height(6.dp))
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = selected?.name ?: "",
                onValueChange = {},
                readOnly = true,
                leadingIcon = {
                    if (selected != null) {
                        Box(
                            modifier = Modifier.size(24.dp).clip(CircleShape).background(Color(selected.avatarColor)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(selected.name.first().uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 9.sp)
                        }
                    } else {
                        Icon(Icons.Filled.Person, contentDescription = null, tint = Text3, modifier = Modifier.size(16.dp))
                    }
                },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = darkFieldColors(),
                modifier = Modifier.fillMaxWidth().menuAnchor()
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(Surface2)
            ) {
                members.forEach { member ->
                    DropdownMenuItem(
                        text = { Text(member.name, style = MaterialTheme.typography.bodySmall, color = Text1) },
                        onClick = { onSelect(member.id); expanded = false },
                        leadingIcon = {
                            Box(
                                modifier = Modifier.size(24.dp).clip(CircleShape).background(Color(member.avatarColor)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(member.name.first().uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 9.sp)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CategorySelector(selected: Category, onSelect: (Category) -> Unit) {
    Column {
        Text("CATEGORY", style = MaterialTheme.typography.labelSmall, color = Text3, letterSpacing = 0.8.sp)
        Spacer(Modifier.height(8.dp))
        Category.entries.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                row.forEach { cat ->
                    val isSelected = cat == selected
                    val catColor = cat.color()
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(if (isSelected) catColor.copy(alpha = 0.15f) else Surface3, RoundedCornerShape(8.dp))
                            .border(1.dp, if (isSelected) catColor.copy(alpha = 0.5f) else Border, RoundedCornerShape(8.dp))
                            .clickable { onSelect(cat) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Icon(cat.icon(), contentDescription = null, tint = if (isSelected) catColor else Text3, modifier = Modifier.size(14.dp))
                            Text(cat.displayName(), style = MaterialTheme.typography.labelSmall, color = if (isSelected) catColor else Text3, fontSize = 8.sp)
                        }
                    }
                }
                if (row.size < 4) repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SplitTypeSelector(selected: SplitType, onSelect: (SplitType) -> Unit) {
    Column {
        Text("SPLIT TYPE", style = MaterialTheme.typography.labelSmall, color = Text3, letterSpacing = 0.8.sp)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface3, RoundedCornerShape(10.dp))
                .border(1.dp, Border, RoundedCornerShape(10.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            SplitType.entries.forEach { type ->
                val isSelected = selected == type
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(if (isSelected) Surface1 else Color.Transparent, RoundedCornerShape(8.dp))
                        .border(if (isSelected) 1.dp else 0.dp, if (isSelected) BorderHi else Color.Transparent, RoundedCornerShape(8.dp))
                        .clickable { onSelect(type) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        when (type) {
                            SplitType.EQUAL      -> "Equal"
                            SplitType.PERCENTAGE -> "Percent"
                            SplitType.AMOUNT     -> "Amount"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isSelected) Text1 else Text3,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
private fun MemberSplitSection(
    members: List<Member>,
    selectedIds: Set<String>,
    splitType: SplitType,
    amount: Double,
    customSplits: Map<String, String>,
    currencySymbol: String,
    onToggleMember: (String) -> Unit,
    onCustomSplitChange: (String, String) -> Unit
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("SPLIT AMONG", style = MaterialTheme.typography.labelSmall, color = Text3, letterSpacing = 0.8.sp)
            Spacer(Modifier.weight(1f))
            if (splitType == SplitType.EQUAL && selectedIds.isNotEmpty() && amount > 0) {
                Text(
                    "${formatCurrency(amount / selectedIds.size, currencySymbol)}/person",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeonGreen
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        members.forEach { member ->
            val isSelected = member.id in selectedIds
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .background(Surface2, RoundedCornerShape(10.dp))
                    .border(1.dp, if (isSelected) BorderHi else Border, RoundedCornerShape(10.dp))
                    .alpha(if (isSelected) 1f else 0.55f)
                    .clickable { onToggleMember(member.id) }
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .background(if (isSelected) NeonGreenDim else Surface3, RoundedCornerShape(4.dp))
                        .border(1.dp, if (isSelected) NeonGreen else Border, RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) Icon(Icons.Filled.Check, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(12.dp))
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier.size(28.dp).background(Color(member.avatarColor), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(member.name.first().uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 9.sp)
                }
                Spacer(Modifier.width(8.dp))
                Text(member.name, style = MaterialTheme.typography.bodySmall, color = Text1, modifier = Modifier.weight(1f))

                if (isSelected && splitType != SplitType.EQUAL) {
                    OutlinedTextField(
                        value = customSplits[member.id] ?: "",
                        onValueChange = { onCustomSplitChange(member.id, it) },
                        suffix = { Text(if (splitType == SplitType.PERCENTAGE) "%" else currencySymbol, color = Text3) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        colors = darkFieldColors(),
                        modifier = Modifier.width(90.dp)
                    )
                } else if (isSelected && amount > 0) {
                    Text(formatCurrency(amount / selectedIds.size, currencySymbol), style = MaterialTheme.typography.bodySmall, color = NeonGreen)
                }
            }
        }
    }
}
