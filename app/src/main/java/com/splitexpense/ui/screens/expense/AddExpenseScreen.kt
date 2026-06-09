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

    LaunchedEffect(state.saved) {
        if (state.saved) onBack()
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

            // ── Form ──────────────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                AmountField(amount = state.amount, onAmountChange = viewModel::onAmountChange)

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
                SplitTypeSelector(selected = state.splitType, onSelect = viewModel::onSplitTypeChange)
                MemberSplitSection(
                    members = state.members,
                    selectedIds = state.selectedMemberIds,
                    splitType = state.splitType,
                    amount = state.amount.toDoubleOrNull() ?: 0.0,
                    customSplits = state.customSplits,
                    onToggleMember = viewModel::onMemberToggle,
                    onCustomSplitChange = viewModel::onCustomSplitChange
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

@Composable
private fun AmountField(amount: String, onAmountChange: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface2, RoundedCornerShape(14.dp))
            .border(1.dp, Border, RoundedCornerShape(14.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("AMOUNT", style = MaterialTheme.typography.labelSmall, color = Text3, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("₹", style = MaterialTheme.typography.headlineLarge, color = NeonGreen, fontWeight = FontWeight.Bold)
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
    onToggleMember: (String) -> Unit,
    onCustomSplitChange: (String, String) -> Unit
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("SPLIT AMONG", style = MaterialTheme.typography.labelSmall, color = Text3, letterSpacing = 0.8.sp)
            Spacer(Modifier.weight(1f))
            if (splitType == SplitType.EQUAL && selectedIds.isNotEmpty() && amount > 0) {
                Text(
                    "${formatCurrency(amount / selectedIds.size)}/person",
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
                        suffix = { Text(if (splitType == SplitType.PERCENTAGE) "%" else "₹", color = Text3) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        colors = darkFieldColors(),
                        modifier = Modifier.width(90.dp)
                    )
                } else if (isSelected && amount > 0) {
                    Text(formatCurrency(amount / selectedIds.size), style = MaterialTheme.typography.bodySmall, color = NeonGreen)
                }
            }
        }
    }
}
