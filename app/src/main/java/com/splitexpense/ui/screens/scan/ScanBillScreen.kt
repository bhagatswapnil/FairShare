@file:OptIn(ExperimentalMaterial3Api::class)

package com.splitexpense.ui.screens.scan

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.splitexpense.data.db.entity.Category
import com.splitexpense.data.db.entity.Member
import com.splitexpense.ui.components.ApiKeyDialog
import com.splitexpense.ui.screens.home.darkFieldColors
import com.splitexpense.ui.theme.*
import com.splitexpense.util.color
import com.splitexpense.util.displayName
import com.splitexpense.util.formatCurrency
import com.splitexpense.util.icon
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ScanBillScreen(
    groupId: String,
    onBack: () -> Unit,
    viewModel: ScanBillViewModel = viewModel(factory = ScanBillViewModel.factory(groupId))
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(state.saved) { if (state.saved) onBack() }

    // API key dialog — triggered when user tries to scan without a key set
    if (state.showApiKeyDialog) {
        ApiKeyDialog(
            currentKey = "",
            onSave     = viewModel::saveApiKey,
            onDismiss  = viewModel::dismissApiKeyDialog
        )
    }

    // Camera temp file created on IO thread to avoid main-thread disk I/O and to
    // surface IOException gracefully.  Deleted when the composable leaves composition.
    var cameraFile by remember { mutableStateOf<File?>(null) }
    val cameraUri = remember(cameraFile) {
        cameraFile?.let { FileProvider.getUriForFile(context, "${context.packageName}.provider", it) }
    }
    LaunchedEffect(Unit) {
        cameraFile = withContext(Dispatchers.IO) {
            File.createTempFile("bill_", ".jpg", context.cacheDir)
        }
    }
    DisposableEffect(Unit) { onDispose { cameraFile?.delete() } }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        if (ok && uri != null) viewModel.processImage(uri, context)
    }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { viewModel.processImage(it, context) }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) cameraUri?.let { cameraLauncher.launch(it) }
    }

    Box(modifier = Modifier.fillMaxSize().background(Bg)) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── App bar ───────────────────────────────────────────────────────
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
                        .clickable(onClick = if (state.phase == ScanPhase.REVIEW) viewModel::resetToCapture else onBack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Text2, modifier = Modifier.size(18.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        when (state.phase) {
                            ScanPhase.CAPTURE    -> "Scan Bill"
                            ScanPhase.PROCESSING -> "Analyzing..."
                            ScanPhase.REVIEW     -> "Review & Assign"
                        },
                        style = MaterialTheme.typography.titleLarge, color = Text1, fontWeight = FontWeight.Bold
                    )
                    Text(
                        when (state.phase) {
                            ScanPhase.CAPTURE    -> "AI-POWERED RECEIPT SCANNER"
                            ScanPhase.PROCESSING -> "READING YOUR BILL"
                            ScanPhase.REVIEW     -> "ASSIGN ITEMS TO MEMBERS"
                        },
                        style = MaterialTheme.typography.labelSmall, color = Text3, letterSpacing = 1.sp
                    )
                }
            }
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Border))

            // ── Content by phase ─────────────────────────────────────────────
            when (state.phase) {
                ScanPhase.CAPTURE    -> CapturePhase(
                    error             = state.error,
                    cameraReady       = cameraUri != null,
                    onTakePhoto       = { cameraPermission.launch(android.Manifest.permission.CAMERA) },
                    onPickGallery     = { galleryLauncher.launch("image/*") }
                )
                ScanPhase.PROCESSING -> ProcessingPhase()
                ScanPhase.REVIEW     -> ReviewPhase(
                    state     = state,
                    onDescriptionChange   = viewModel::onDescriptionChange,
                    onCategoryChange      = viewModel::onCategoryChange,
                    onPaidByChange        = viewModel::onPaidByChange,
                    onItemNameChange      = viewModel::onItemNameChange,
                    onItemAmountChange    = viewModel::onItemAmountChange,
                    onToggleMember        = viewModel::onToggleMember,
                    onAddItem             = viewModel::addItem,
                    onRemoveItem          = viewModel::removeItem,
                    onSave                = viewModel::saveExpense
                )
            }
        }
    }
}

// ── Capture phase ─────────────────────────────────────────────────────────────

@Composable
private fun CapturePhase(
    error: String?,
    cameraReady: Boolean,
    onTakePhoto: () -> Unit,
    onPickGallery: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(100.dp)
                .background(Surface2, RoundedCornerShape(28.dp))
                .border(1.dp, Border, RoundedCornerShape(28.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.DocumentScanner, contentDescription = null,
                tint = NeonGreen, modifier = Modifier.size(48.dp))
        }

        Spacer(Modifier.height(28.dp))
        Text("Scan a receipt or bill", style = MaterialTheme.typography.titleLarge,
            color = Text1, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("AI will extract items automatically.\nRequires internet connection.",
            style = MaterialTheme.typography.bodySmall, color = Text3,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)

        Spacer(Modifier.height(40.dp))

        // Take photo button — disabled until the temp file is ready
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (cameraReady) Text1 else Surface3, RoundedCornerShape(14.dp))
                .clickable(enabled = cameraReady, onClick = onTakePhoto)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Filled.CameraAlt, contentDescription = null,
                tint = if (cameraReady) Bg else Text3, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("Take Photo", color = if (cameraReady) Bg else Text3,
                fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        }

        Spacer(Modifier.height(12.dp))

        // Gallery button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface2, RoundedCornerShape(14.dp))
                .border(1.dp, BorderHi, RoundedCornerShape(14.dp))
                .clickable(onClick = onPickGallery)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Filled.PhotoLibrary, contentDescription = null,
                tint = Text1, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("Choose from Gallery", color = Text1, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall)
        }

        // Error
        AnimatedVisibility(visible = error != null, enter = fadeIn(), exit = fadeOut()) {
            error?.let {
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(NeonRedDim, RoundedCornerShape(10.dp))
                        .border(1.dp, NeonRed.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Filled.Error, contentDescription = null,
                        tint = NeonRed, modifier = Modifier.size(16.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = NeonRed)
                }
            }
        }
    }
}

// ── Processing phase ──────────────────────────────────────────────────────────

@Composable
private fun ProcessingPhase() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            CircularProgressIndicator(color = NeonGreen, strokeWidth = 3.dp,
                modifier = Modifier.size(52.dp))
            Text("Analyzing your bill...", style = MaterialTheme.typography.titleSmall,
                color = Text1, fontWeight = FontWeight.SemiBold)
            Text("This takes a few seconds", style = MaterialTheme.typography.bodySmall,
                color = Text3)
        }
    }
}

// ── Review phase ──────────────────────────────────────────────────────────────

@Composable
private fun ReviewPhase(
    state: ScanBillUiState,
    onDescriptionChange: (String) -> Unit,
    onCategoryChange: (Category) -> Unit,
    onPaidByChange: (String) -> Unit,
    onItemNameChange: (Int, String) -> Unit,
    onItemAmountChange: (Int, String) -> Unit,
    onToggleMember: (Int, String) -> Unit,
    onAddItem: () -> Unit,
    onRemoveItem: (Int) -> Unit,
    onSave: () -> Unit
) {
    val allMemberIds = remember(state.members) { state.members.map { it.id } }
    val memberTotals = remember(state.items, allMemberIds) { state.memberTotals(allMemberIds) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Description
        OutlinedTextField(
            value = state.description,
            onValueChange = onDescriptionChange,
            label = { Text("Description *", color = Text3) },
            singleLine = true,
            colors = darkFieldColors(),
            modifier = Modifier.fillMaxWidth()
        )

        // Category
        SectionLabel("CATEGORY")
        CategoryGrid(selected = state.category, onSelect = onCategoryChange)

        // Paid by
        SectionLabel("PAID BY")
        PaidBySelector(members = state.members, selectedId = state.paidById, onSelect = onPaidByChange)

        // Items
        SectionLabel("BILL ITEMS  ·  ${state.items.size} items  ·  ${formatCurrency(state.total, state.currencySymbol)}")

        state.items.forEachIndexed { index, item ->
            BillItemRow(
                item       = item,
                index      = index,
                members    = state.members,
                currencySymbol = state.currencySymbol,
                onNameChange   = { onItemNameChange(index, it) },
                onAmountChange = { onItemAmountChange(index, it) },
                onToggleMember = { onToggleMember(index, it) },
                onRemove   = { onRemoveItem(index) }
            )
        }

        // Add item
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface2, RoundedCornerShape(10.dp))
                .border(1.dp, BorderHi, RoundedCornerShape(10.dp))
                .clickable(onClick = onAddItem)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add Item", color = NeonGreen, style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold)
        }

        // Summary
        if (memberTotals.isNotEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Border))
            SectionLabel("MEMBER SUMMARY")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface2, RoundedCornerShape(12.dp))
                    .border(1.dp, Border, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.members.forEach { member ->
                    val amount = memberTotals[member.id] ?: 0.0
                    if (amount > 0.01) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(
                                    modifier = Modifier.size(24.dp)
                                        .background(Color(member.avatarColor.toInt()), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(member.name.take(1).uppercase(), color = Color.White,
                                        fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                                Text(member.name, color = Text1,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium)
                            }
                            Text(formatCurrency(amount, state.currencySymbol), color = AmberAccent,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold)
                        }
                    }
                }
                HorizontalDivider(color = Border)
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("TOTAL", color = Text3, style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 1.sp)
                    Text(formatCurrency(state.total, state.currencySymbol), color = Text1,
                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Error
        state.error?.let { err ->
            Row(
                modifier = Modifier.fillMaxWidth()
                    .background(NeonRedDim, RoundedCornerShape(8.dp))
                    .border(1.dp, NeonRed.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Filled.Error, contentDescription = null,
                    tint = NeonRed, modifier = Modifier.size(16.dp))
                Text(err, style = MaterialTheme.typography.bodySmall, color = NeonRed)
            }
        }

        // Save button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .background(if (!state.isSaving) Text1 else Surface3, RoundedCornerShape(12.dp))
                .clickable(enabled = !state.isSaving, onClick = onSave),
            contentAlignment = Alignment.Center
        ) {
            if (state.isSaving) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp),
                    color = NeonGreen, strokeWidth = 2.dp)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Save, contentDescription = null,
                        tint = Bg, modifier = Modifier.size(18.dp))
                    Text("Save Expense", style = MaterialTheme.typography.titleSmall,
                        color = Bg, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

// ── Bill item row ─────────────────────────────────────────────────────────────

@Composable
private fun BillItemRow(
    item: BillItemState,
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
                            .clickable { onToggleMember(member.id) }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            member.name.take(8),
                            color = if (active) NeonGreen else Text3,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }
            // Show per-person share
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

// ── Helpers ───────────────────────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall,
        color = Text3, letterSpacing = 1.sp,
        modifier = Modifier.padding(horizontal = 2.dp))
}

@Composable
private fun CategoryGrid(selected: Category, onSelect: (Category) -> Unit) {
    val cats = Category.entries
    val rows = cats.chunked(4)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { cat ->
                    val active = cat == selected
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                if (active) cat.color().copy(alpha = 0.15f) else Surface2,
                                RoundedCornerShape(10.dp)
                            )
                            .border(
                                1.dp,
                                if (active) cat.color() else Border,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable { onSelect(cat) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Icon(cat.icon(), contentDescription = null,
                                tint = if (active) cat.color() else Text3,
                                modifier = Modifier.size(16.dp))
                            Text(cat.displayName(),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (active) cat.color() else Text3,
                                maxLines = 1)
                        }
                    }
                }
                // Pad incomplete rows
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaidBySelector(
    members: List<Member>,
    selectedId: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = members.find { it.id == selectedId }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.name ?: "Select member",
            onValueChange = {},
            readOnly = true,
            colors = darkFieldColors(),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Surface2)
        ) {
            members.forEach { m ->
                DropdownMenuItem(
                    text = { Text(m.name, color = Text1,
                        style = MaterialTheme.typography.bodySmall) },
                    onClick = { onSelect(m.id); expanded = false }
                )
            }
        }
    }
}
