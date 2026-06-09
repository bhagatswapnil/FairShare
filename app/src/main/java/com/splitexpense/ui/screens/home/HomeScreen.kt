package com.splitexpense.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.splitexpense.data.db.entity.Group
import com.splitexpense.data.db.entity.Member
import com.splitexpense.ui.theme.*
import java.util.Locale

val groupEmojis = listOf("💰","✈️","🏠","🍕","🎉","🛒","🏖️","🎸","🍺","🏋️","🎬","🚗")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onGroupClick: (String) -> Unit,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory)
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var searchQuery by remember { mutableStateOf("") }
    var showCreateDialog by remember { mutableStateOf(false) }

    val filtered = state.groups.filter {
        it.name.contains(searchQuery, ignoreCase = true) ||
        it.description.contains(searchQuery, ignoreCase = true)
    }

    Box(modifier = Modifier.fillMaxSize().background(Bg)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── App Bar ───────────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface1)
                    .border(width = 1.dp, color = Border, shape = RoundedCornerShape(0.dp))
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(top = 18.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            "FairShare",
                            style = MaterialTheme.typography.headlineSmall,
                            color = Text1,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "TRACK & SETTLE SHARED COSTS",
                            style = MaterialTheme.typography.labelSmall,
                            color = Text3,
                            letterSpacing = 1.sp
                        )
                    }
                    IconBtn(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "New group", tint = Text2, modifier = Modifier.size(20.dp))
                    }
                }

                // Search bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 14.dp)
                        .background(Surface3, RoundedCornerShape(10.dp))
                        .border(1.dp, Border, RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = Text3, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(8.dp))
                    BasicSearchField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.weight(1f)
                    )
                    if (searchQuery.isNotEmpty()) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Clear",
                            tint = Text3,
                            modifier = Modifier.size(14.dp).clickable { searchQuery = "" }
                        )
                    }
                }
            }

            if (state.isLoading) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = NeonGreen, strokeWidth = 2.dp)
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    // ── Summary Banner ────────────────────────────────────────
                    item {
                        SummaryBanner(
                            groupCount   = state.groups.size,
                            totalExpense = state.groupTotals.values.sum(),
                            totalPending = state.groupPendingAmounts.values.sum()
                        )
                        Spacer(Modifier.height(18.dp))
                    }

                    if (filtered.isEmpty()) {
                        item { EmptyState(hasGroups = state.groups.isEmpty()) }
                    } else {
                        // ── Section Header ────────────────────────────────────
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 0.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "ACTIVE GROUPS",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Text3,
                                    letterSpacing = 1.2.sp
                                )
                                Box(
                                    modifier = Modifier
                                        .background(Surface3, RoundedCornerShape(4.dp))
                                        .border(1.dp, Border, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 7.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        "${filtered.size} groups",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Text3
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                        }

                        items(filtered, key = { it.id }) { group ->
                            val total   = state.groupTotals[group.id] ?: 0.0
                            val pending = state.groupPendingAmounts[group.id] ?: 0.0
                            val settled = state.groupSettledAmounts[group.id] ?: 0.0
                            GroupCard(
                                group   = group,
                                total   = total,
                                pending = pending,
                                settled = settled,
                                members = state.groupMembers[group.id] ?: emptyList(),
                                onClick = { onGroupClick(group.id) }
                            )
                            Spacer(Modifier.height(10.dp))
                        }

                        // ── Create new hint ───────────────────────────────────
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, Border, RoundedCornerShape(14.dp))
                                    .clickable { showCreateDialog = true }
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("+", style = MaterialTheme.typography.titleMedium, color = Text3)
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "CREATE NEW GROUP",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Text3,
                                        letterSpacing = 0.6.sp
                                    )
                                }
                            }
                            Spacer(Modifier.height(100.dp))
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateGroupDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name, desc, emoji ->
                viewModel.createGroup(name, desc, emoji)
                showCreateDialog = false
            }
        )
    }

}

@Composable
private fun SummaryBanner(groupCount: Int, totalExpense: Double, totalPending: Double) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface2, RoundedCornerShape(14.dp))
            .border(1.dp, Border, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Groups count
        Column {
            Text(
                "$groupCount",
                style = MaterialTheme.typography.titleLarge,
                color = Text1,
                fontWeight = FontWeight.Bold
            )
            Text("GROUPS", style = MaterialTheme.typography.labelSmall, color = Text3, letterSpacing = 0.8.sp)
        }

        Box(modifier = Modifier.width(1.dp).height(40.dp).background(Border))

        // Total spent
        Column {
            Text(
                formatAmount(totalExpense),
                style = MaterialTheme.typography.titleLarge,
                color = Text1,
                fontWeight = FontWeight.Bold
            )
            Text("TOTAL", style = MaterialTheme.typography.labelSmall, color = Text3, letterSpacing = 0.8.sp)
        }

        Box(modifier = Modifier.width(1.dp).height(40.dp).background(Border))

        // Pending (amber)
        Column {
            Text(
                formatAmount(totalPending),
                style = MaterialTheme.typography.titleLarge,
                color = AmberAccent,
                fontWeight = FontWeight.Bold
            )
            Text("PENDING", style = MaterialTheme.typography.labelSmall, color = Text3, letterSpacing = 0.8.sp)
        }

        // Emoji icon button
        Box(
            modifier = Modifier
                .size(46.dp)
                .background(Surface3, RoundedCornerShape(12.dp))
                .border(1.dp, Border, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("💰", fontSize = 20.sp)
        }
    }
}

@Composable
private fun GroupCard(
    group: Group,
    total: Double,
    pending: Double,
    settled: Double,
    members: List<Member>,
    onClick: () -> Unit
) {

    val hasExpenses = total > 0.01
    // Settled % only meaningful once there have been expenses and some debt exists
    val totalDebt   = settled + pending
    val settledPct  = if (totalDebt > 0.01) (settled / totalDebt * 100).toInt() else if (hasExpenses) 100 else 0
    // "All settled" means: group has expenses AND no remaining pending debt
    val allSettled  = hasExpenses && pending < 0.01
    val accentColor = when {
        !hasExpenses -> Border        // neutral: brand-new empty group
        allSettled   -> NeonGreen
        else         -> AmberAccent
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface2, RoundedCornerShape(16.dp))
            .border(1.dp, Border, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        // Left accent bar
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(accentColor)
                .align(Alignment.CenterStart)
        )

        Column(modifier = Modifier.padding(start = 18.dp, end = 14.dp, top = 16.dp, bottom = 14.dp)) {

            // ── Top row: emoji | name+desc | total SPENT ──────────────────────
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Emoji box
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .background(Surface3, RoundedCornerShape(12.dp))
                        .border(1.dp, Border, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(group.emoji, fontSize = 24.sp)
                }

                // Name + desc (fills remaining space)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        group.name,
                        style = MaterialTheme.typography.titleLarge,
                        color = Text1,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (group.description.isNotBlank()) {
                        Text(
                            group.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = Text3,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Total spent (top-right)
                if (total > 0) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            formatAmount(total),
                            style = MaterialTheme.typography.titleMedium,
                            color = Text1,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "SPENT",
                            style = MaterialTheme.typography.labelSmall,
                            color = Text3,
                            letterSpacing = 0.6.sp
                        )
                    }
                }
            }

            // ── Separator ──────────────────────────────────────────────────────
            Spacer(Modifier.height(12.dp))
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Border))
            Spacer(Modifier.height(10.dp))

            // ── Bottom row: member avatars | member count | pending chip ───────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Overlapping member avatar circles (up to 4)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val shown = members.take(4)
                    Box(modifier = Modifier.height(26.dp).width((shown.size * 20 + 6).dp)) {
                        shown.forEachIndexed { idx, member ->
                            val initials = member.name.split(" ")
                                .take(2).joinToString("") { it.firstOrNull()?.uppercase() ?: "" }
                            Box(
                                modifier = Modifier
                                    .offset(x = (idx * 18).dp)
                                    .size(26.dp)
                                    .background(Color(member.avatarColor), CircleShape)
                                    .border(1.5.dp, Surface2, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    initials,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${members.size} ${if (members.size == 1) "member" else "members"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Text3
                    )
                }

                // Pending chip — three states: no expenses / all settled / has pending
                when {
                    !hasExpenses -> Box(
                        modifier = Modifier
                            .background(Surface3, RoundedCornerShape(6.dp))
                            .border(1.dp, Border, RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "No expenses",
                            style = MaterialTheme.typography.labelSmall,
                            color = Text3
                        )
                    }
                    allSettled   -> Box(
                        modifier = Modifier
                            .background(NeonGreenDim, RoundedCornerShape(6.dp))
                            .border(1.dp, NeonGreen.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "All settled ✓",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = NeonGreen
                        )
                    }
                    else         -> Box(
                        modifier = Modifier
                            .background(AmberAccent.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                            .border(1.dp, AmberAccent.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "${formatAmount(pending)} pending",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = AmberAccent
                        )
                    }
                }
            }

            // ── Progress bar + settled% (only shown for groups that have expenses) ──
            if (hasExpenses) {
                Spacer(Modifier.height(10.dp))
                val barFraction = (settledPct / 100f).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .background(Surface3, RoundedCornerShape(3.dp))
                ) {
                    // Only draw the green fill when actually > 0 — no fake sliver
                    if (barFraction > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(barFraction)
                                .fillMaxHeight()
                                .background(NeonGreen, RoundedCornerShape(3.dp))
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "$settledPct% settled",
                    style = MaterialTheme.typography.labelSmall,
                    color = Text3,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                )
            }
        }
    }
}

/** Formats a monetary amount as ₹82k / ₹3.2k / ₹250. */
private fun formatAmount(amount: Double): String = when {
    amount >= 1_00_000 -> "₹${"%.1f".format(amount / 1_00_000)}L"
    amount >= 1_000    -> "₹${"%.1f".format(amount / 1_000)}k"
    else               -> "₹${"%.0f".format(amount)}"
}

@Composable
private fun EmptyState(hasGroups: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("💰", fontSize = 40.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            if (hasGroups) "No groups match" else "No groups yet",
            style = MaterialTheme.typography.titleMedium,
            color = Text1
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (hasGroups) "Try a different search" else "Tap + to create your first group",
            style = MaterialTheme.typography.bodySmall,
            color = Text3
        )
    }
}

@Composable
private fun CreateGroupDialog(onDismiss: () -> Unit, onCreate: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var selectedEmoji by remember { mutableStateOf("💰") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface2,
        titleContentColor = Text1,
        textContentColor = Text2,
        title = { Text("Create Group") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Group Name *", color = Text3) },
                    singleLine = true,
                    colors = darkFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("Description", color = Text3) },
                    singleLine = true,
                    colors = darkFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Pick an emoji", style = MaterialTheme.typography.labelMedium, color = Text3)
                EmojiGrid(selected = selectedEmoji, onSelect = { selectedEmoji = it })
            }
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank()) onCreate(name.trim(), desc.trim(), selectedEmoji) },
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = Bg)
            ) { Text("Create", fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Text2) }
        }
    )
}

@Composable
private fun EmojiGrid(selected: String, onSelect: (String) -> Unit) {
    groupEmojis.chunked(6).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.forEach { emoji ->
                val isSelected = emoji == selected
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(
                            if (isSelected) NeonGreenDim else Surface3,
                            RoundedCornerShape(8.dp)
                        )
                        .border(
                            1.dp,
                            if (isSelected) NeonGreen else Border,
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { onSelect(emoji) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(emoji, fontSize = 18.sp)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

// ── Small helpers ──────────────────────────────────────────────────────────────

@Composable
private fun IconBtn(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(Surface3, RoundedCornerShape(10.dp))
            .border(1.dp, Border, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
private fun BasicSearchField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall.copy(color = Text2),
        decorationBox = { inner ->
            if (value.isEmpty()) {
                Text("search groups…", style = MaterialTheme.typography.bodySmall, color = Text3)
            }
            inner()
        },
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun darkFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor     = NeonGreen,
    unfocusedBorderColor   = Border,
    focusedLabelColor      = NeonGreen,
    unfocusedLabelColor    = Text3,
    cursorColor            = NeonGreen,
    focusedTextColor       = Text1,
    unfocusedTextColor     = Text1,
    focusedContainerColor  = Surface3,
    unfocusedContainerColor = Surface3,
)
