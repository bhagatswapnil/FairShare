package com.splitexpense.ui.screens.group

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.splitexpense.data.db.entity.Category
import com.splitexpense.data.db.entity.Expense
import com.splitexpense.data.db.entity.Member
import com.splitexpense.data.db.entity.Settlement
import com.splitexpense.data.model.DebtTransaction
import com.splitexpense.data.model.MemberBalance
import com.splitexpense.ui.components.CategoryChart
import com.splitexpense.ui.components.ChartEntry
import com.splitexpense.ui.components.HorizontalBarChart
import com.splitexpense.ui.screens.home.darkFieldColors
import com.splitexpense.ui.theme.*
import com.splitexpense.util.color
import com.splitexpense.util.displayName
import com.splitexpense.util.formatCurrency
import com.splitexpense.util.icon
import com.splitexpense.util.nameToAvatarColor
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupScreen(
    groupId: String,
    onBack: () -> Unit,
    onAddExpense: () -> Unit,
    onScanBill: () -> Unit = {},
    onEditExpense: (String) -> Unit = {},
    viewModel: GroupViewModel = viewModel(factory = GroupViewModel.factory(groupId))
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Sourced from the single app-wide currency setting (AppSettingsManager) — see
    // GroupUiState.currencySymbol. No longer derived from this group's own field.
    val currencySymbol = state.currencySymbol
    val context = LocalContext.current
    val tabs = listOf("Expenses", "Members", "Balances", "Charts")
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val coroutineScope = rememberCoroutineScope()
    var showAddMemberDialog by remember { mutableStateOf(false) }
    var showEditGroupDialog by remember { mutableStateOf(false) }
    var showNoMembersDialog by remember { mutableStateOf(false) }
    var fabExpanded by remember { mutableStateOf(false) }
    var deleteExpenseTarget by remember { mutableStateOf<Expense?>(null) }
    var showOverflow by remember { mutableStateOf(false) }
    var showDeleteGroupDialog by remember { mutableStateOf(false) }

    // Navigate back after the group has been deleted from the DB
    LaunchedEffect(state.groupDeleted) {
        if (state.groupDeleted) onBack()
    }

    LaunchedEffect(state.exportUri) {
        state.exportUri?.let { uri ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share Expense Report"))
            viewModel.clearExportState()
        }
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
                // Back button
                SmallIconBtn(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Text2, modifier = Modifier.size(18.dp))
                }

                // Title
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.group?.let { g ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(g.emoji, fontSize = 18.sp)
                            Text(
                                g.name,
                                style = MaterialTheme.typography.titleLarge,
                                color = Text1,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        val desc = buildString {
                            if (g.description.isNotBlank()) append(g.description)
                            if (g.description.isNotBlank() && state.members.isNotEmpty()) append(" · ")
                            if (state.members.isNotEmpty()) append("${state.members.size} members")
                        }
                        if (desc.isNotBlank()) {
                            Text(
                                desc.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = Text3,
                                letterSpacing = 1.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Add member shortcut
                SmallIconBtn(onClick = { showAddMemberDialog = true }) {
                    Icon(Icons.Filled.PersonAdd, contentDescription = "Add member", tint = Text2, modifier = Modifier.size(18.dp))
                }

                // More / export / edit
                if (state.isExporting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = NeonGreen, strokeWidth = 2.dp)
                } else {
                    Box {
                        SmallIconBtn(onClick = { showOverflow = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = Text2, modifier = Modifier.size(18.dp))
                        }
                        DropdownMenu(
                            expanded = showOverflow,
                            onDismissRequest = { showOverflow = false },
                            modifier = Modifier.background(Surface2).border(1.dp, Border, RoundedCornerShape(8.dp))
                        ) {
                            DropdownMenuItem(
                                text = { Text("Edit Group", style = MaterialTheme.typography.bodySmall, color = Text1) },
                                onClick = { showOverflow = false; showEditGroupDialog = true },
                                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null, tint = Text2, modifier = Modifier.size(14.dp)) }
                            )
                            DropdownMenuItem(
                                text = { Text("Export PDF", style = MaterialTheme.typography.bodySmall, color = Text1) },
                                onClick = { showOverflow = false; viewModel.exportPdf(context) },
                                leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null, tint = Text2, modifier = Modifier.size(14.dp)) }
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 4.dp),
                                color = Border
                            )
                            DropdownMenuItem(
                                text = { Text("Delete Group", style = MaterialTheme.typography.bodySmall, color = NeonRed) },
                                onClick = { showOverflow = false; showDeleteGroupDialog = true },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = NeonRed, modifier = Modifier.size(14.dp)) }
                            )
                        }
                    }
                }
            }

            // border under app bar
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Border))

            // ── Stats Strip ───────────────────────────────────────────────────
            val pendingAmt = state.balances.sumOf { if (it.netBalance < -0.01) -it.netBalance else 0.0 }
            // Settled % = value already settled / (value settled + value still pending)
            val settledAmt  = state.settlements.sumOf { it.amount }
            val totalOwed   = settledAmt + state.simplifiedDebts.sumOf { it.amount }
            val settledPct  = if (totalOwed < 0.01) 0 else (settledAmt / totalOwed * 100).toInt()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface2)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatCell("$currencySymbol${"%.0f".format(state.totalExpense)}", "TOTAL SPENT")
                Box(modifier = Modifier.width(1.dp).height(32.dp).background(Border))
                StatCell("${state.expenses.size}", "EXPENSES")
                Box(modifier = Modifier.width(1.dp).height(32.dp).background(Border))
                StatCell(
                    if (pendingAmt < 0.01) "Settled" else "$currencySymbol${"%.0f".format(pendingAmt)}",
                    "PENDING",
                    if (pendingAmt < 0.01) NeonGreen else AmberAccent
                )
                Box(modifier = Modifier.width(1.dp).height(32.dp).background(Border))
                StatCell("$settledPct%", "SETTLED", NeonGreen)
            }

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Border))

            // ── Tab Row ───────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface1)
                    .padding(horizontal = 4.dp)
            ) {
                tabs.forEachIndexed { i, title ->
                    val active = pagerState.currentPage == i
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { coroutineScope.launch { pagerState.animateScrollToPage(i) } }
                            .padding(horizontal = 4.dp, vertical = 0.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            title.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (active) Text1 else Text3,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            letterSpacing = 0.8.sp,
                            modifier = Modifier.padding(vertical = 14.dp)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .background(if (active) NeonGreen else Color.Transparent)
                        )
                    }
                }
            }
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Border))

            // HorizontalPager fills remaining space; swipe switches tabs
            if (state.isLoading) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = NeonGreen, strokeWidth = 2.dp)
                }
            } else {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f)
                ) { page ->
                    when (page) {
                        0 -> ExpensesTab(
                            expenses              = state.expenses,
                            members               = state.members,
                            splitCountByExpenseId = state.splitCountByExpenseId,
                            splitsByExpenseId     = state.splitsByExpenseId,
                            expenseItemsByExpenseId = state.expenseItemsByExpenseId,
                            currencySymbol        = currencySymbol,
                            onDelete              = { deleteExpenseTarget = it },
                            onEdit                = { expense -> onEditExpense(expense.id) }
                        )
                        1 -> MembersTab(
                            members             = state.members,
                            balances            = state.balances,
                            membersWithExpenses = state.membersWithExpenses,
                            currencySymbol      = currencySymbol,
                            onAddMember         = { showAddMemberDialog = true },
                            onDeleteMember      = { viewModel.deleteMember(it) }
                        )
                        2 -> BalancesTab(
                            debts             = state.simplifiedDebts,
                            expenses          = state.expenses,
                            members           = state.members,
                            balances          = state.balances,
                            settlements       = state.settlements,
                            splitsByExpenseId = state.splitsByExpenseId,
                            currencySymbol    = currencySymbol,
                            onSettle     = { viewModel.recordSettlement(it) },
                            onUndoSettle = { viewModel.deleteSettlement(it) }
                        )
                        3 -> ChartsTab(state.categorySpending, state.balances, state.expenses.size, currencySymbol)
                        else -> Box(Modifier.fillMaxSize())
                    }
                }
            }
        }

        // ── Speed-dial FAB — only on Expenses tab ────────────────────────────
        if (pagerState.currentPage == 0) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 22.dp, bottom = 28.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Mini action buttons — visible when expanded
                androidx.compose.animation.AnimatedVisibility(
                    visible = fabExpanded,
                    enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { it },
                    exit  = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically { it }
                ) {
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Scan Bill
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .background(Surface1, RoundedCornerShape(8.dp))
                                    .border(1.dp, Border, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text("Scan Bill", color = Text1,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold)
                            }
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(Surface2, RoundedCornerShape(12.dp))
                                    .border(1.dp, BorderHi, RoundedCornerShape(12.dp))
                                    .clickable {
                                        fabExpanded = false
                                        if (state.members.isEmpty()) showNoMembersDialog = true else onScanBill()
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.DocumentScanner, contentDescription = "Scan bill",
                                    tint = NeonGreen, modifier = Modifier.size(20.dp))
                            }
                        }
                        // Add Manually
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .background(Surface1, RoundedCornerShape(8.dp))
                                    .border(1.dp, Border, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text("Add Manually", color = Text1,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold)
                            }
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(Surface2, RoundedCornerShape(12.dp))
                                    .border(1.dp, BorderHi, RoundedCornerShape(12.dp))
                                    .clickable {
                                        fabExpanded = false
                                        if (state.members.isEmpty()) showNoMembersDialog = true else onAddExpense()
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.Edit, contentDescription = "Add manually",
                                    tint = Text1, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }

                // Main FAB — toggles speed dial
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .background(Text1, RoundedCornerShape(16.dp))
                        .clickable { fabExpanded = !fabExpanded },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (fabExpanded) Icons.Filled.Close else Icons.Filled.Add,
                        contentDescription = "Add expense",
                        tint = Bg,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────
    if (showAddMemberDialog) {
        AddMemberDialog(
            existingMembers = state.members,
            onDismiss = { showAddMemberDialog = false },
            onAdd = { name, email ->
                viewModel.addMember(name, email, nameToAvatarColor(name))
                // dialog stays open — user taps Done when finished
            }
        )
    }

    if (showNoMembersDialog) {
        AlertDialog(
            onDismissRequest = { showNoMembersDialog = false },
            containerColor = Surface2,
            shape = RoundedCornerShape(16.dp),
            icon = { Icon(Icons.Filled.PersonAdd, contentDescription = null, tint = AmberAccent) },
            title = {
                Text("Add a member first", color = Text1, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium)
            },
            text = {
                Text(
                    "This group has no members yet. Add at least one member before recording an expense.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Text2
                )
            },
            confirmButton = {
                TextButton(onClick = { showNoMembersDialog = false; showAddMemberDialog = true }) {
                    Text("Add Member", color = NeonGreen, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNoMembersDialog = false }) {
                    Text("Cancel", color = Text3)
                }
            }
        )
    }

    if (showEditGroupDialog) {
        state.group?.let { group ->
            EditGroupDialog(
                initialName = group.name,
                initialDescription = group.description,
                initialEmoji = group.emoji,
                onDismiss = { showEditGroupDialog = false },
                onSave = { name, description, emoji ->
                    viewModel.editGroup(name, description, emoji)
                    showEditGroupDialog = false
                }
            )
        }
    }

    deleteExpenseTarget?.let { expense ->
        AlertDialog(
            onDismissRequest = { deleteExpenseTarget = null },
            containerColor = Surface2,
            titleContentColor = Text1,
            textContentColor = Text2,
            title = { Text("Delete expense?") },
            text = { Text("\"${expense.description}\" (${formatCurrency(expense.amount, currencySymbol)}) will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteExpense(expense); deleteExpenseTarget = null }) {
                    Text("Delete", color = NeonRed)
                }
            },
            dismissButton = { TextButton(onClick = { deleteExpenseTarget = null }) { Text("Cancel", color = Text2) } }
        )
    }

    state.exportError?.let { error ->
        AlertDialog(
            onDismissRequest = { viewModel.clearExportState() },
            containerColor = Surface2,
            titleContentColor = Text1,
            textContentColor = Text2,
            title = { Text("Export failed") },
            text = { Text(error) },
            confirmButton = { TextButton(onClick = { viewModel.clearExportState() }) { Text("OK", color = NeonGreen) } }
        )
    }

    if (showDeleteGroupDialog) {
        val groupName = state.group?.name ?: "this group"
        AlertDialog(
            onDismissRequest = { showDeleteGroupDialog = false },
            containerColor = Surface2,
            titleContentColor = Text1,
            textContentColor = Text2,
            title = { Text("Delete $groupName?") },
            text = { Text("All expenses, members, and settlements will be permanently deleted. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { showDeleteGroupDialog = false; viewModel.deleteGroup() }) {
                    Text("Delete", color = NeonRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteGroupDialog = false }) { Text("Cancel", color = Text2) }
            }
        )
    }
}

// ── Stats strip cell ──────────────────────────────────────────────────────────

@Composable
private fun StatCell(value: String, label: String, valueColor: Color = Text1) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, style = MaterialTheme.typography.titleSmall, color = valueColor, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Text3, letterSpacing = 0.8.sp)
    }
}

// ── Expenses Tab ──────────────────────────────────────────────────────────────

@Composable
private fun ExpensesTab(
    expenses: List<Expense>,
    members: List<Member>,
    splitCountByExpenseId: Map<String, Int>,
    splitsByExpenseId: Map<String, List<com.splitexpense.data.db.entity.ExpenseSplit>>,
    expenseItemsByExpenseId: Map<String, List<com.splitexpense.data.db.entity.ExpenseItem>>,
    currencySymbol: String,
    onDelete: (Expense) -> Unit,
    onEdit: (Expense) -> Unit
) {
    val memberMap = remember(members) { members.associateBy { it.id } }
    val fmt = remember { SimpleDateFormat("dd MMM", Locale.getDefault()) }
    val dayFmt = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }

    if (expenses.isEmpty()) {
        EmptyTab("No expenses yet", "Tap + to add your first expense", "🧾")
        return
    }

    val grouped = remember(expenses) {
        expenses.sortedByDescending { it.date }.groupBy { dayFmt.format(Date(it.date)) }
    }

    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        grouped.forEach { (day, dayExpenses) ->
            item(key = "header_$day") {
                Text(
                    day.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Text3,
                    letterSpacing = 1.2.sp,
                    modifier = Modifier.padding(horizontal = 2.dp, vertical = 10.dp)
                )
            }
            items(dayExpenses, key = { it.id }) { expense ->
                ExpenseItem(
                    expense      = expense,
                    payerName    = memberMap[expense.paidById]?.name ?: "Unknown",
                    memberCount  = splitCountByExpenseId[expense.id] ?: members.size,
                    billItems    = expenseItemsByExpenseId[expense.id] ?: emptyList(),
                    splits       = splitsByExpenseId[expense.id] ?: emptyList(),
                    memberMap    = memberMap,
                    currencySymbol = currencySymbol,
                    onDelete     = onDelete,
                    onEdit       = onEdit,
                    fmt          = fmt
                )
                Spacer(Modifier.height(8.dp))
            }
        }
        item { Spacer(Modifier.height(100.dp)) }
    }
}

@Composable
private fun ExpenseItem(
    expense: Expense,
    payerName: String,
    memberCount: Int,
    billItems: List<com.splitexpense.data.db.entity.ExpenseItem>,
    splits: List<com.splitexpense.data.db.entity.ExpenseSplit>,
    memberMap: Map<String, com.splitexpense.data.db.entity.Member>,
    currencySymbol: String,
    onDelete: (Expense) -> Unit,
    onEdit: (Expense) -> Unit,
    fmt: SimpleDateFormat
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var itemsExpanded by remember { mutableStateOf(false) }
    val catColor = expense.category.color()
    val shareAmt = if (memberCount > 0) expense.amount / memberCount else 0.0
    // Prefer the DB flag (fast, no list traversal); fall back to list check for safety.
    val hasItems = expense.isItemised || billItems.isNotEmpty()
    // Normal (non-itemised) expenses can still expand to show the per-member split breakdown.
    val canExpand = hasItems || splits.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface2, RoundedCornerShape(12.dp))
            .border(1.dp, Border, RoundedCornerShape(12.dp))
    ) {
        // Main row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Category icon
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(catColor.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(expense.category.icon(), contentDescription = null, tint = catColor, modifier = Modifier.size(18.dp))
            }

            // Info
            Column(modifier = Modifier.weight(1f)) {
                Text(expense.description, style = MaterialTheme.typography.titleSmall,
                    color = Text1, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text("Paid by $payerName · ${expense.category.displayName()}",
                    style = MaterialTheme.typography.bodySmall, color = Text3)
            }

            // Amount
            Column(horizontalAlignment = Alignment.End) {
                Text(formatCurrency(expense.amount, currencySymbol), style = MaterialTheme.typography.titleSmall,
                    color = Text1, fontWeight = FontWeight.Bold)
                if (memberCount > 1 && !hasItems) {
                    Text("${formatCurrency(shareAmt, currencySymbol)}/person",
                        style = MaterialTheme.typography.labelSmall, color = Text3)
                }
            }

            // Expand toggle — bill items for itemised expenses, split breakdown otherwise
            if (canExpand) {
                Icon(
                    if (itemsExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = "Toggle details",
                    tint = Text3,
                    modifier = Modifier.size(18.dp).clickable { itemsExpanded = !itemsExpanded }
                )
            }

            // 3-dot menu
            Box {
                Icon(Icons.Filled.MoreVert, contentDescription = null,
                    tint = Text3,
                    modifier = Modifier.size(16.dp).clickable { menuExpanded = true })
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier.background(Surface2).border(1.dp, Border, RoundedCornerShape(8.dp))
                ) {
                    DropdownMenuItem(
                        text = { Text("Edit", color = Text1, style = MaterialTheme.typography.bodySmall) },
                        onClick = { menuExpanded = false; onEdit(expense) },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null, tint = Text2, modifier = Modifier.size(14.dp)) }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = NeonRed, style = MaterialTheme.typography.bodySmall) },
                        onClick = { menuExpanded = false; onDelete(expense) },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = NeonRed, modifier = Modifier.size(14.dp)) }
                    )
                }
            }
        }

        // Expandable details — bill items for itemised expenses, split breakdown otherwise
        if (canExpand && itemsExpanded) {
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Border))
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (hasItems) {
                    billItems.forEach { item ->
                        val assignedIds = runCatching {
                            val arr = org.json.JSONArray(item.assignedMemberIds)
                            (0 until arr.length()).map { arr.getString(it) }
                        }.getOrDefault(emptyList())

                        val assignedNames = if (assignedIds.isEmpty()) "Everyone"
                        else assignedIds.mapNotNull { memberMap[it]?.name }.joinToString(", ")
                            .ifBlank { "Everyone" }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.name, color = Text2,
                                    style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                                Text(assignedNames, color = Text3,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                            Text(formatCurrency(item.amount, currencySymbol), color = Text2,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium)
                        }
                    }
                } else {
                    // Per-member split breakdown for a standard (non-itemised) expense.
                    // Percentage is always recalculated from amount/expense.amount — the
                    // stored ExpenseSplit.percentage field is only meaningful for PERCENTAGE splits.
                    splits.sortedByDescending { it.amount }.forEach { split ->
                        val name = memberMap[split.memberId]?.name ?: "Unknown"
                        val pct  = if (expense.amount > 0.01) split.amount / expense.amount * 100 else 0.0

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(name, color = Text2,
                                    style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                                Text("${"%.1f".format(pct)}% share", color = Text3,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                            Text(formatCurrency(split.amount, currencySymbol), color = Text2,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

// ── Members Tab ───────────────────────────────────────────────────────────────

@Composable
private fun MembersTab(
    members: List<Member>,
    balances: List<MemberBalance>,
    membersWithExpenses: Set<String>,
    currencySymbol: String,
    onAddMember: () -> Unit,
    onDeleteMember: (Member) -> Unit
) {
    val balanceMap = remember(balances) { balances.associateBy { it.memberId } }
    var deletePending by remember { mutableStateOf<Member?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Add Member CTA — pinned at the top of the tab
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .background(Surface3, RoundedCornerShape(10.dp))
                .border(1.dp, BorderHi, RoundedCornerShape(10.dp))
                .clickable(onClick = onAddMember)
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.PersonAdd, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(16.dp))
                Text("Add Member", style = MaterialTheme.typography.labelLarge, color = NeonGreen, fontWeight = FontWeight.SemiBold)
            }
        }

        if (members.isEmpty()) {
            EmptyTab(
                title    = "No members yet",
                subtitle = "Tap \"Add Member\" above to add people",
                emoji    = "👥",
                modifier = Modifier.weight(1f)
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(members, key = { it.id }) { member ->
                    MemberItem(
                        member          = member,
                        balance         = balanceMap[member.id]?.netBalance ?: 0.0,
                        hasExpenses     = member.id in membersWithExpenses,
                        currencySymbol  = currencySymbol,
                        onDeleteRequest = { deletePending = member }
                    )
                }
                item { Spacer(Modifier.height(40.dp)) }
            }
        }
    }

    // Confirm before removing
    deletePending?.let { member ->
        AlertDialog(
            onDismissRequest = { deletePending = null },
            containerColor   = Surface2,
            titleContentColor = Text1,
            textContentColor  = Text2,
            title = { Text("Remove member?") },
            text  = { Text("Remove \"${member.name}\" from this group? Their expenses will remain.") },
            confirmButton = {
                TextButton(onClick = { onDeleteMember(member); deletePending = null }) {
                    Text("Remove", color = NeonRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletePending = null }) { Text("Cancel", color = Text2) }
            }
        )
    }
}

@Composable
private fun MemberItem(member: Member, balance: Double, hasExpenses: Boolean, currencySymbol: String, onDeleteRequest: () -> Unit) {
    val initials = member.name.split(" ").take(2).joinToString("") { it.firstOrNull()?.uppercase() ?: "" }
    val balColor = when {
        balance > 0.01  -> NeonGreen
        balance < -0.01 -> NeonRed
        else            -> Text3
    }
    val balText = when {
        balance > 0.01  -> "+${formatCurrency(balance, currencySymbol)}"
        balance < -0.01 -> formatCurrency(balance, currencySymbol)
        else            -> "Settled"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface2, RoundedCornerShape(12.dp))
            .border(1.dp, Border, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Avatar circle
        Box(
            modifier = Modifier.size(36.dp).background(Color(member.avatarColor), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(initials, style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.Bold)
        }

        // Name + email
        Column(modifier = Modifier.weight(1f)) {
            Text(member.name, style = MaterialTheme.typography.titleSmall, color = Text1, fontWeight = FontWeight.SemiBold)
            if (member.email.isNotBlank()) {
                Text(member.email, style = MaterialTheme.typography.bodySmall, color = Text3)
            }
        }

        // Balance badge
        Text(balText, style = MaterialTheme.typography.labelMedium, color = balColor, fontWeight = FontWeight.Bold)

        // Remove button — locked if member is on any expense/split
        if (hasExpenses) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(Surface3, RoundedCornerShape(8.dp))
                    .border(1.dp, Border, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Lock, contentDescription = "Has expenses — cannot remove", tint = Text3, modifier = Modifier.size(14.dp))
            }
        } else {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(NeonRedDim, RoundedCornerShape(8.dp))
                    .border(1.dp, NeonRed.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                    .clickable(onClick = onDeleteRequest),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.PersonRemove, contentDescription = "Remove member", tint = NeonRed, modifier = Modifier.size(14.dp))
            }
        }
    }
}

// ── Net balance row ───────────────────────────────────────────────────────────

@Composable
private fun MemberTotalSpendItem(
    member: Member,
    totalPaid: Double,
    effectiveCost: Double,
    netBalance: Double,
    breakdown: List<Pair<String, Double>>,
    currencySymbol: String
) {
    var expanded by remember { mutableStateOf(false) }
    val isOwed = netBalance > 0.01
    val owes   = netBalance < -0.01

    // e.g. "Paid ₹1,000 − gets back ₹300" or "Paid ₹200 + owes ₹500"
    val formula = when {
        isOwed -> "Paid ${formatCurrency(totalPaid, currencySymbol)} − gets back ${formatCurrency(netBalance, currencySymbol)}"
        owes   -> "Paid ${formatCurrency(totalPaid, currencySymbol)} + owes ${formatCurrency(-netBalance, currencySymbol)}"
        else   -> "Paid ${formatCurrency(totalPaid, currencySymbol)}"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface2, RoundedCornerShape(12.dp))
            .border(1.dp, Border, RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Avatar
            val initials = member.name.split(" ").take(2)
                .joinToString("") { it.firstOrNull()?.uppercase() ?: "" }
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(Color(member.avatarColor), androidx.compose.foundation.shape.CircleShape)
                    .border(1.dp, Border, androidx.compose.foundation.shape.CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    initials,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }

            // Name + formula breakdown
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    member.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = Text1,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    formula,
                    style = MaterialTheme.typography.labelSmall,
                    color = Text3
                )
            }

            // Effective cost — how much this trip actually cost them (settlement-independent)
            Text(
                formatCurrency(effectiveCost, currencySymbol),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = AmberAccent
            )

            // Expand toggle — per-expense breakdown of this member's share
            if (breakdown.isNotEmpty()) {
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = "Toggle breakdown",
                    tint = Text3,
                    modifier = Modifier.size(18.dp).clickable { expanded = !expanded }
                )
            }
        }

        // Expandable breakdown — same visual pattern as itemised-expense bill items
        if (expanded && breakdown.isNotEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Border))
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                breakdown.forEach { (description, amount) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            description, color = Text2,
                            style = MaterialTheme.typography.bodySmall, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                        )
                        Text(
                            formatCurrency(amount, currencySymbol), color = Text2,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MemberNetBalanceItem(bal: MemberBalance) {
    val isOwed  = bal.netBalance > 0.01
    val owes    = bal.netBalance < -0.01
    val settled = !isOwed && !owes

    val chipBg    = when { isOwed -> NeonGreenDim; owes -> NeonRedDim; else -> Surface3 }
    val chipBdr   = when { isOwed -> NeonGreen.copy(alpha = 0.30f); owes -> NeonRed.copy(alpha = 0.30f); else -> Border }
    val amtColor  = when { isOwed -> NeonGreen; owes -> NeonRed; else -> Text3 }
    val amtText   = when {
        isOwed  -> "+${formatCurrency(bal.netBalance)}"
        owes    -> formatCurrency(bal.netBalance)
        else    -> "Settled"
    }
    val labelText = when { isOwed -> "gets back"; owes -> "owes"; else -> "all clear" }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface2, RoundedCornerShape(12.dp))
            .border(1.dp, Border, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Avatar
        val initials = bal.memberName.split(" ").take(2).joinToString("") { it.firstOrNull()?.uppercase() ?: "" }
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(chipBg, androidx.compose.foundation.shape.CircleShape)
                .border(1.dp, chipBdr, androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(initials, style = MaterialTheme.typography.labelSmall, color = amtColor, fontWeight = FontWeight.Bold)
        }

        // Name
        Text(
            bal.memberName,
            style = MaterialTheme.typography.bodySmall,
            color = Text1,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )

        // Label chip
        Box(
            modifier = Modifier
                .background(chipBg, RoundedCornerShape(6.dp))
                .border(1.dp, chipBdr, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(labelText, style = MaterialTheme.typography.labelSmall, color = amtColor)
        }

        // Amount
        Text(
            amtText,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = amtColor
        )
    }
}

// ── Balances Tab ──────────────────────────────────────────────────────────────

@Composable
private fun BalancesTab(
    debts: List<DebtTransaction>,
    expenses: List<Expense>,
    members: List<Member>,
    balances: List<MemberBalance>,
    settlements: List<Settlement>,
    splitsByExpenseId: Map<String, List<com.splitexpense.data.db.entity.ExpenseSplit>>,
    currencySymbol: String,
    onSettle: (DebtTransaction) -> Unit,
    onUndoSettle: (Settlement) -> Unit
) {
    var settleTarget by remember { mutableStateOf<DebtTransaction?>(null) }
    // Per-member total paid, sorted highest → lowest.
    val (memberSpend, totalSpent, maxSpend) = remember(expenses, members) {
        val spend = members
            .map { m -> m to expenses.filter { it.paidById == m.id }.sumOf { it.amount } }
            .sortedByDescending { it.second }
        val total = expenses.sumOf { it.amount }
        val max   = spend.maxOfOrNull { it.second }?.coerceAtLeast(1.0) ?: 1.0
        Triple(spend, total, max)
    }
    // Per-member "cost of the trip" breakdown — built directly from ExpenseSplit amounts so
    // it stays correct regardless of settlement status. Do NOT derive this from
    // `paid - netBalance`: netBalance includes settlement adjustments, so that formula would
    // make a member's effective cost shift every time a debt gets settled, even though their
    // actual share of the group's expenses never changed. (Same invariant as the PDF's
    // "Share" column — see PdfExporter / CLAUDE.md.)
    val expenseDescById = remember(expenses) { expenses.associateBy({ it.id }, { it.description }) }
    val memberBreakdown = remember(splitsByExpenseId, expenseDescById) {
        val map = mutableMapOf<String, MutableList<Pair<String, Double>>>()
        splitsByExpenseId.forEach { (expenseId, splitList) ->
            val desc = expenseDescById[expenseId] ?: "Unknown"
            splitList.forEach { split ->
                map.getOrPut(split.memberId) { mutableListOf() }.add(desc to split.amount)
            }
        }
        map
    }
    val memberShareMap = remember(memberBreakdown) {
        memberBreakdown.mapValues { (_, list) -> list.sumOf { it.second } }
    }
    // Per-member effective cost = sum of their ExpenseSplit amounts (settlement-independent).
    val memberTotalSpend = remember(memberSpend, memberShareMap) {
        memberSpend.map { (member, paid) ->
            val effectiveCost = memberShareMap[member.id] ?: 0.0
            Triple(member, paid, effectiveCost)
        }.sortedByDescending { it.third }
    }

    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // ── TOTAL SPEND PER MEMBER ───────────────────────────────────────────
        if (memberTotalSpend.isNotEmpty() && totalSpent > 0.01) {
            item(key = "total_spend_header") {
                Text(
                    "TOTAL SPEND",
                    style = MaterialTheme.typography.labelSmall,
                    color = Text3,
                    letterSpacing = 1.2.sp,
                    modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp)
                )
                Spacer(Modifier.height(6.dp))
            }
            items(memberTotalSpend, key = { "ts_${it.first.id}" }) { (member, paid, effectiveCost) ->
                val net = balances.find { it.memberId == member.id }?.netBalance ?: 0.0
                MemberTotalSpendItem(
                    member        = member,
                    totalPaid     = paid,
                    effectiveCost = effectiveCost,
                    netBalance    = net,
                    breakdown     = memberBreakdown[member.id]?.sortedByDescending { it.second } ?: emptyList(),
                    currencySymbol = currencySymbol
                )
            }
            item(key = "total_spend_divider") {
                Spacer(Modifier.height(16.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
                Spacer(Modifier.height(16.dp))
            }
        }

        // ── WHO PAID WHAT ─────────────────────────────────────────────────────
        if (memberSpend.isNotEmpty() && totalSpent > 0.01) {
            item(key = "spend_header") {
                Text(
                    "WHO PAID WHAT",
                    style = MaterialTheme.typography.labelSmall,
                    color = Text3,
                    letterSpacing = 1.2.sp,
                    modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp)
                )
                Spacer(Modifier.height(6.dp))
            }
            items(memberSpend, key = { "spend_${it.first.id}" }) { (member, spent) ->
                MemberSpendItem(
                    member   = member,
                    spent    = spent,
                    total    = totalSpent,
                    maxSpend = maxSpend,
                    currencySymbol = currencySymbol
                )
            }
            item(key = "spend_divider") {
                Spacer(Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Border)
                )
                Spacer(Modifier.height(16.dp))
            }
        }

        // ── SETTLE UP ─────────────────────────────────────────────────────────
        item(key = "settle_header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "SETTLE UP",
                    style = MaterialTheme.typography.labelSmall,
                    color = Text3,
                    letterSpacing = 1.2.sp
                )
                if (debts.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .background(Surface3, RoundedCornerShape(4.dp))
                            .border(1.dp, Border, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "${debts.size} txns",
                            style = MaterialTheme.typography.labelSmall,
                            color = Text3
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        if (debts.isEmpty()) {
            item(key = "settled") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("✅", fontSize = 28.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "All settled up!",
                        style = MaterialTheme.typography.titleSmall,
                        color = Text1,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "No outstanding debts",
                        style = MaterialTheme.typography.bodySmall,
                        color = Text3
                    )
                }
            }
        } else {
            items(debts, key = { "${it.fromId}_${it.toId}" }) { debt ->
                DebtItem(debt = debt, currencySymbol = currencySymbol, onSettle = { settleTarget = debt })
            }
        }

        // ── SETTLED (history) ─────────────────────────────────────────────────
        if (settlements.isNotEmpty()) {
            item(key = "settled_header") {
                Spacer(Modifier.height(16.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "SETTLED",
                        style = MaterialTheme.typography.labelSmall,
                        color = Text3,
                        letterSpacing = 1.2.sp
                    )
                    Box(
                        modifier = Modifier
                            .background(NeonGreenDim, RoundedCornerShape(4.dp))
                            .border(1.dp, NeonGreen.copy(alpha = 0.25f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("${settlements.size}", style = MaterialTheme.typography.labelSmall, color = NeonGreen)
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            items(settlements, key = { it.id }) { settlement ->
                SettledItem(settlement = settlement, currencySymbol = currencySymbol, onUndo = { onUndoSettle(settlement) })
            }
        }

        item { Spacer(Modifier.height(100.dp)) }
    }

    // ── Confirmation dialog ────────────────────────────────────────────────────
    settleTarget?.let { debt ->
        AlertDialog(
            onDismissRequest = { settleTarget = null },
            containerColor   = Surface2,
            titleContentColor = Text1,
            textContentColor  = Text2,
            title = { Text("Mark as settled?") },
            text = {
                Text(
                    "${debt.fromName} paid ${debt.toName} ${formatCurrency(debt.amount, currencySymbol)}.\n" +
                    "This will record the payment and clear the debt."
                )
            },
            confirmButton = {
                TextButton(onClick = { onSettle(debt); settleTarget = null }) {
                    Text("Confirm", color = NeonGreen, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { settleTarget = null }) { Text("Cancel", color = Text2) }
            }
        )
    }
}

@Composable
private fun MemberSpendItem(
    member: Member,
    spent: Double,
    total: Double,
    maxSpend: Double,
    currencySymbol: String
) {
    val initials = member.name
        .split(" ")
        .take(2)
        .joinToString("") { it.firstOrNull()?.uppercase() ?: "" }
    val fraction = (spent / maxSpend).coerceIn(0.0, 1.0).toFloat()
    val pct = if (total > 0) spent / total * 100 else 0.0

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface2, RoundedCornerShape(12.dp))
            .border(1.dp, Border, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Avatar
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(Color(member.avatarColor), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                initials,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }

        // Name + bar
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    member.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = Text1,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    formatCurrency(spent, currencySymbol),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = if (spent > 0.01) NeonGreen else Text3
                )
            }
            // Proportional spend bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Surface3, RoundedCornerShape(2.dp))
            ) {
                if (fraction > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .background(NeonGreen.copy(alpha = 0.65f), RoundedCornerShape(2.dp))
                    )
                }
            }
        }

        // Percentage
        Text(
            "${"%.0f".format(pct)}%",
            style = MaterialTheme.typography.labelSmall,
            color = Text3,
            modifier = Modifier.width(30.dp),
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun DebtItem(debt: DebtTransaction, currencySymbol: String, onSettle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface2, RoundedCornerShape(12.dp))
            .border(1.dp, Border, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // From avatar
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(NeonRedDim, CircleShape)
                .border(1.dp, NeonRed.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                debt.fromName.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.labelSmall,
                color = NeonRed,
                fontWeight = FontWeight.Bold
            )
        }

        // Description
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(debt.fromName, style = MaterialTheme.typography.bodySmall, color = NeonRed, fontWeight = FontWeight.SemiBold)
                Text(" owes ", style = MaterialTheme.typography.bodySmall, color = Text3)
                Text(debt.toName, style = MaterialTheme.typography.bodySmall, color = NeonGreen, fontWeight = FontWeight.SemiBold)
            }
            Text(
                formatCurrency(debt.amount, currencySymbol),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = NeonRed
            )
        }

        // Mark settled button
        Box(
            modifier = Modifier
                .background(NeonGreenDim, RoundedCornerShape(8.dp))
                .border(1.dp, NeonGreen.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                .clickable(onClick = onSettle)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Mark settled",
                    tint = NeonGreen,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    "Settle",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = NeonGreen
                )
            }
        }
    }
}

@Composable
private fun SettledItem(settlement: Settlement, currencySymbol: String, onUndo: () -> Unit) {
    val fmt = remember { java.text.SimpleDateFormat("dd MMM", java.util.Locale.getDefault()) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface2, RoundedCornerShape(12.dp))
            .border(1.dp, Border, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Tick icon
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(NeonGreenDim, CircleShape)
                .border(1.dp, NeonGreen.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = NeonGreen,
                modifier = Modifier.size(14.dp)
            )
        }

        // Description
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    settlement.fromName,
                    style = MaterialTheme.typography.bodySmall,
                    color = Text1,
                    fontWeight = FontWeight.SemiBold
                )
                Text(" paid ", style = MaterialTheme.typography.bodySmall, color = Text3)
                Text(
                    settlement.toName,
                    style = MaterialTheme.typography.bodySmall,
                    color = Text1,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                "${formatCurrency(settlement.amount, currencySymbol)} · ${fmt.format(java.util.Date(settlement.date))}",
                style = MaterialTheme.typography.labelSmall,
                color = Text3
            )
        }

        // Undo button
        Box(
            modifier = Modifier
                .background(Surface3, RoundedCornerShape(6.dp))
                .border(1.dp, Border, RoundedCornerShape(6.dp))
                .clickable(onClick = onUndo)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text("Undo", style = MaterialTheme.typography.labelSmall, color = Text2)
        }
    }
}

// ── Charts Tab ────────────────────────────────────────────────────────────────

@Composable
private fun ChartsTab(
    categorySpending: Map<Category, Double>,
    balances: List<MemberBalance>,
    expenseCount: Int,
    currencySymbol: String
) {
    // No expenses — show an informative placeholder
    if (categorySpending.isEmpty()) {
        EmptyTab(
            title    = "No chart data yet",
            subtitle  = if (expenseCount == 0)
                            "You have 0 expenses. Tap + to add one."
                        else
                            "$expenseCount expense(s) found but category data is still loading.",
            emoji    = "📊"
        )
        return
    }

    // Use Column + verticalScroll (not LazyColumn) — avoids any unbounded-height
    // Canvas measurement issues that LazyColumn can cause for chart items.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Category breakdown chart
        ChartCard("SPENDING BY CATEGORY") {
            val entries = categorySpending.entries.sortedByDescending { it.value }
                .map { (cat, amt) -> ChartEntry(cat.displayName(), amt.toFloat(), cat.color()) }
            CategoryChart(entries = entries, modifier = Modifier.fillMaxWidth(), currencySymbol = currencySymbol)
        }

        // Member balance bar chart — only when balances are non-trivial
        if (balances.any { kotlin.math.abs(it.netBalance) > 0.01 }) {
            ChartCard("MEMBER BALANCES") {
                val entries = balances
                    .filter { kotlin.math.abs(it.netBalance) > 0.01 }
                    .sortedByDescending { kotlin.math.abs(it.netBalance) }
                    .map { b ->
                        ChartEntry(
                            b.memberName.split(" ").first(),
                            kotlin.math.abs(b.netBalance).toFloat(),
                            if (b.netBalance >= 0) NeonGreen else NeonRed
                        )
                    }
                HorizontalBarChart(entries = entries, modifier = Modifier.fillMaxWidth(), currencySymbol = currencySymbol)
            }
        }

        Spacer(Modifier.height(100.dp))
    }
}

@Composable
private fun ChartCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface2, RoundedCornerShape(14.dp))
            .border(1.dp, Border, RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = Text3, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(14.dp))
        content()
    }
}

// ── Shared helpers ────────────────────────────────────────────────────────────

@Composable
private fun EmptyTab(
    title: String,
    subtitle: String,
    emoji: String,
    modifier: Modifier = Modifier.fillMaxSize()
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(emoji, fontSize = 36.sp)
            Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, color = Text1, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Text3,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
        }
    }
}

@Composable
private fun SmallIconBtn(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .background(Surface3, RoundedCornerShape(10.dp))
            .border(1.dp, Border, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMemberDialog(
    existingMembers: List<Member>,
    onDismiss: () -> Unit,
    onAdd: (String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        titleContentColor = Text1,
        textContentColor = Text2,
        title = { Text("Add Members") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Input fields
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name *", color = Text3) },
                    singleLine = true,
                    colors = darkFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email (optional)", color = Text3) },
                    singleLine = true,
                    colors = darkFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        if (name.isNotBlank()) {
                            onAdd(name.trim(), email.trim())
                            name = ""
                            email = ""
                        }
                    },
                    enabled = name.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = Bg),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Add Member", fontWeight = FontWeight.Bold) }

                // Already-added members list
                if (existingMembers.isNotEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Border))
                    Text(
                        "${existingMembers.size} member${if (existingMembers.size == 1) "" else "s"} added",
                        style = MaterialTheme.typography.labelSmall,
                        color = Text3,
                        letterSpacing = 0.8.sp
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        existingMembers.forEach { member ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .background(
                                            Color(member.avatarColor.toInt()),
                                            CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        member.name.take(1).uppercase(),
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                                Column {
                                    Text(member.name, color = Text1, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                    if (member.email.isNotBlank()) {
                                        Text(member.email, color = Text3, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Surface3, contentColor = Text1)
            ) { Text("Done", fontWeight = FontWeight.Bold) }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditGroupDialog(
    initialName: String,
    initialDescription: String,
    initialEmoji: String,
    onDismiss: () -> Unit,
    onSave: (name: String, description: String, emoji: String) -> Unit
) {
    var name        by remember { mutableStateOf(initialName) }
    var description by remember { mutableStateOf(initialDescription) }
    var emoji       by remember { mutableStateOf(initialEmoji) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        titleContentColor = Text1,
        textContentColor = Text2,
        title = { Text("Edit Group") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Emoji row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = emoji,
                        onValueChange = { if (it.length <= 2) emoji = it },
                        label = { Text("Emoji", color = Text3) },
                        singleLine = true,
                        colors = darkFieldColors(),
                        modifier = Modifier.width(80.dp)
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Group name *", color = Text3) },
                        singleLine = true,
                        colors = darkFieldColors(),
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (optional)", color = Text3) },
                    singleLine = true,
                    colors = darkFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                // Currency is no longer edited per group — it's a single app-wide setting
                // (Home screen overflow menu → Currency) so totals stay consistent everywhere.
            }
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank()) onSave(name, description, emoji) },
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = Bg)
            ) { Text("Save", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Text2) } }
    )
}
