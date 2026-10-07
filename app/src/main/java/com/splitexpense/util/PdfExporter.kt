package com.splitexpense.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.splitexpense.data.db.entity.Category
import com.splitexpense.data.db.entity.Expense
import com.splitexpense.data.db.entity.ExpenseItem
import com.splitexpense.data.db.entity.ExpenseSplit
import com.splitexpense.data.db.entity.Group
import com.splitexpense.data.db.entity.ItemSplitMode
import com.splitexpense.data.db.entity.Settlement
import org.json.JSONArray
import org.json.JSONObject
import com.splitexpense.util.displayName
import com.splitexpense.data.db.entity.Member
import com.splitexpense.data.model.DebtTransaction
import com.splitexpense.data.model.MemberBalance
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PdfExporter(private val context: Context) {

    // ── Page geometry ────────────────────────────────────────────────────────
    private val PW = 595f       // A4 width  (pts)
    private val PH = 842f       // A4 height (pts)
    private val M  = 40f        // margin
    private val CW = PW - 2 * M // content width

    // ── Palette ──────────────────────────────────────────────────────────────
    private val INK       = Color.parseColor("#111111")
    private val INK_MID   = Color.parseColor("#555555")
    private val INK_LIGHT = Color.parseColor("#888888")
    private val RULE      = Color.parseColor("#E0E0E0")
    private val ROW_ALT   = Color.parseColor("#F8F8F8")
    private val CHIP_BG   = Color.parseColor("#F2F2F2")
    private val GREEN     = Color.parseColor("#1A7D3A")
    private val GREEN_BG  = Color.parseColor("#EAF6EE")
    private val RED       = Color.parseColor("#C0392B")
    private val RED_BG    = Color.parseColor("#FDEDEB")
    private val HEADER_BG = Color.parseColor("#111111")
    private val ACCENT    = Color.parseColor("#111111")
    private val WHITE     = Color.WHITE

    private val dateFmt   = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    private val tsFmt     = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())

    // ── Public API ───────────────────────────────────────────────────────────

    fun export(
        group: Group,
        members: List<Member>,
        expenses: List<Expense>,
        splits: List<ExpenseSplit>,
        balances: List<MemberBalance>,
        debts: List<DebtTransaction>,
        settlements: List<Settlement> = emptyList(),
        expenseItems: List<ExpenseItem> = emptyList(),
        // Currency is now an app-wide setting (AppSettingsManager), not per-group.
        // Defaults to the group's stored (vestigial) currencyCode for any caller
        // that hasn't been updated to pass the live app-wide symbol explicitly.
        currencySymbol: String = currencySymbolFor(group.currencyCode)
    ): Uri {
        val doc = PdfDocument()
        val memberMap    = members.associateBy { it.id }
        val splitsMap    = splits.groupBy { it.expenseId }
        val itemsMap     = expenseItems.groupBy { it.expenseId }
        // Pre-compute each member's raw expense share (sum of their split amounts).
        // Used in the summary page so the Share column reflects expense obligations,
        // not the settlement-adjusted net balance.
        val memberShareMap = splits.groupBy { it.memberId }.mapValues { (_, s) -> s.sumOf { it.amount } }

        // Page 1 – summary
        val p1 = doc.startPage(PdfDocument.PageInfo.Builder(PW.toInt(), PH.toInt(), 1).create())
        drawSummaryPage(p1.canvas, group, members, expenses, balances, debts, settlements, memberShareMap, currencySymbol)
        doc.finishPage(p1)

        // Page 2+ – expense detail
        drawExpensePages(doc, expenses, splitsMap, memberMap, itemsMap, currencySymbol)

        val fileName = "FairShare_${group.name.replace(Regex("[^A-Za-z0-9]"), "_")}.pdf"
        val file = File(context.cacheDir, fileName)
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()

        return FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    }

    // ── Page 1 ───────────────────────────────────────────────────────────────

    private fun drawSummaryPage(
        c: Canvas,
        group: Group,
        members: List<Member>,
        expenses: List<Expense>,
        balances: List<MemberBalance>,
        debts: List<DebtTransaction>,
        settlements: List<Settlement>,
        memberShares: Map<String, Double>,
        currencySymbol: String
    ) {
        // Header bar
        c.drawRect(0f, 0f, PW, 44f, fill(HEADER_BG))
        c.drawText("FairShare", M, 18f, txt(WHITE, 14f, bold = true))
        c.drawText("Expense Report", M, 34f, txt(Color.parseColor("#AAAAAA"), 8f))
        c.drawText(tsFmt.format(Date()), PW - M, 34f, txt(Color.parseColor("#AAAAAA"), 8f, right = true))

        var y = 88f

        // Group title
        c.drawText("${group.emoji}  ${group.name}", M, y, txt(INK, 22f, bold = true))
        y += 8f
        if (group.description.isNotBlank()) {
            c.drawText(group.description, M, y + 12f, txt(INK_LIGHT, 9f))
            y += 18f
        }
        y += 16f

        // Date range
        if (expenses.isNotEmpty()) {
            val earliest = dateFmt.format(Date(expenses.minOf { it.date }))
            val latest   = dateFmt.format(Date(expenses.maxOf { it.date }))
            c.drawText("Period: $earliest – $latest", M, y, txt(INK_LIGHT, 8f))
            y += 18f
        }

        // Stats row
        val total = expenses.sumOf { it.amount }
        val statLabels = listOf("Total Spent" to "$currencySymbol${"%.2f".format(total)}", "Expenses" to "${expenses.size}", "Members" to "${members.size}")
        statLabels.forEachIndexed { i, (label, value) ->
            val bx = M + i * (CW / 3f)
            c.drawRoundRect(RectF(bx, y, bx + CW / 3f - 6f, y + 42f), 6f, 6f, fill(CHIP_BG))
            c.drawText(value, bx + 10f, y + 24f, txt(INK, 14f, bold = true, mono = true))
            c.drawText(label, bx + 10f, y + 37f, txt(INK_LIGHT, 8f))
        }
        y += 58f

        hRule(c, y); y += 14f

        // ── Member Balances table ──
        sectionTitle(c, y, "MEMBER BALANCES"); y += 16f

        val colX = floatArrayOf(M, M + 180f, M + 300f, M + 420f)
        c.drawText("Member",  colX[0], y, txt(INK_LIGHT, 7.5f, bold = true))
        c.drawText("Paid",    colX[1], y, txt(INK_LIGHT, 7.5f, bold = true, right = true))
        c.drawText("Share",   colX[2], y, txt(INK_LIGHT, 7.5f, bold = true, right = true))
        c.drawText("Balance", colX[3], y, txt(INK_LIGHT, 7.5f, bold = true, right = true))
        y += 4f; thinRule(c, y); y += 10f

        val SUB_ROW_H = 12f   // linked-account member sub-row
        val memberNameById = members.associate { it.id to it.name }
        for ((idx, bal) in balances.withIndex()) {
            // Linked accounts get one indented sub-row per member beneath the account row.
            val subRows = if (bal.memberIds.size > 1) bal.memberIds else emptyList()
            if (idx % 2 == 1) {
                c.drawRect(M - 2f, y - 10f, PW - M + 2f, y + 6f + subRows.size * SUB_ROW_H, fill(ROW_ALT))
            }
            // A linked-account row covers several members — sum over all of them.
            val paid  = expenses.filter { it.paidById in bal.memberIds }.sumOf { it.amount }
            val share = bal.memberIds.sumOf { memberShares[it] ?: 0.0 }
            val netTxt = when {
                bal.netBalance > 0.01  -> "+$currencySymbol${"%.2f".format(bal.netBalance)}"
                bal.netBalance < -0.01 -> "-$currencySymbol${"%.2f".format(-bal.netBalance)}"
                else                    -> "Settled"
            }
            val netColor = when {
                bal.netBalance > 0.01  -> GREEN
                bal.netBalance < -0.01 -> RED
                else                    -> INK_LIGHT
            }
            c.drawText(bal.memberName, colX[0], y, txt(INK, 8.5f))
            c.drawText("$currencySymbol${"%.2f".format(paid)}", colX[1], y, txt(INK_MID, 8.5f, mono = true, right = true))
            c.drawText("$currencySymbol${"%.2f".format(share)}", colX[2], y, txt(INK_MID, 8.5f, mono = true, right = true))
            c.drawText(netTxt, colX[3], y, txt(netColor, 8.5f, bold = true, mono = true, right = true))
            // Per-member Paid/Share; no Balance column — the account settles as one.
            subRows.forEach { memberId ->
                y += SUB_ROW_H
                val mPaid  = expenses.filter { it.paidById == memberId }.sumOf { it.amount }
                val mShare = memberShares[memberId] ?: 0.0
                c.drawText("· ${memberNameById[memberId] ?: "?"}", colX[0] + 10f, y, txt(INK_LIGHT, 7.5f))
                c.drawText("$currencySymbol${"%.2f".format(mPaid)}", colX[1], y, txt(INK_LIGHT, 7.5f, mono = true, right = true))
                c.drawText("$currencySymbol${"%.2f".format(mShare)}", colX[2], y, txt(INK_LIGHT, 7.5f, mono = true, right = true))
            }
            y += 16f
        }
        y += 6f; hRule(c, y); y += 14f

        // ── Settle Up ──
        if (debts.isNotEmpty() || settlements.isNotEmpty()) {
            sectionTitle(c, y, "PAYMENTS TO SETTLE"); y += 16f
            // Pending debts — red
            for (debt in debts) {
                c.drawRoundRect(RectF(M, y - 11f, PW - M, y + 5f), 4f, 4f, fill(RED_BG))
                c.drawText(debt.fromName, M + 8f, y, txt(RED, 8.5f, bold = true))
                c.drawText("  owes  ", M + 8f + paint8w(debt.fromName), y, txt(INK_MID, 8.5f))
                c.drawText(debt.toName, M + 8f + paint8w("${debt.fromName}  owes  "), y, txt(GREEN, 8.5f, bold = true))
                c.drawText("$currencySymbol${"%.2f".format(debt.amount)}", PW - M - 8f, y, txt(RED, 9f, bold = true, mono = true, right = true))
                y += 18f
            }
            // Recorded settlements — green background, "(Settled)" in green after names
            for (settlement in settlements) {
                c.drawRoundRect(RectF(M, y - 11f, PW - M, y + 5f), 4f, 4f, fill(GREEN_BG))
                c.drawText(settlement.fromName, M + 8f, y, txt(GREEN, 8.5f, bold = true))
                val x1 = M + 8f + paint8w(settlement.fromName)
                c.drawText("  paid  ", x1, y, txt(INK_MID, 8.5f))
                val x2 = x1 + paint8w("  paid  ")
                c.drawText(settlement.toName, x2, y, txt(INK, 8.5f, bold = true))
                val x3 = x2 + paint8w(settlement.toName)
                c.drawText("  (Settled)", x3, y, txt(GREEN, 8.5f))
                c.drawText("$currencySymbol${"%.2f".format(settlement.amount)}", PW - M - 8f, y, txt(INK_MID, 8.5f, mono = true, right = true))
                y += 18f
            }
            y += 6f; hRule(c, y); y += 14f
        }

        // ── Category breakdown — donut chart on right, legend on left ──
        sectionTitle(c, y, "SPENDING BY CATEGORY"); y += 16f
        val catTotals = expenses.groupBy { it.category }
            .mapValues { (_, list) -> list.sumOf { it.amount } }
            .entries.sortedByDescending { it.value }
        val grandTotal = expenses.sumOf { it.amount }

        if (catTotals.isNotEmpty()) {
            val PIE_R  = 78f
            val PIE_CX = PW - M - PIE_R - 4f          // flush with right margin
            val PIE_CY = y + PIE_R - 8f                // pie top aligned with top of first legend swatch

            // Draw filled pie slices
            var startAngle = -90f
            for ((cat, amt) in catTotals) {
                val sweep = if (grandTotal > 0) (amt / grandTotal * 360f).toFloat() else 0f
                c.drawArc(
                    RectF(PIE_CX - PIE_R, PIE_CY - PIE_R, PIE_CX + PIE_R, PIE_CY + PIE_R),
                    startAngle, sweep, true, fill(categoryColorInt(cat))
                )
                startAngle += sweep
            }
            // Donut hole (50% radius) — white fill creates the ring effect
            val holeR = PIE_R * 0.50f
            c.drawCircle(PIE_CX, PIE_CY, holeR, fill(WHITE))
            // Total label inside the hole
            c.drawText("$currencySymbol${"%.0f".format(grandTotal)}", PIE_CX, PIE_CY + 3f,
                txt(INK, 7.5f, bold = true, mono = true, center = true))
            c.drawText("total", PIE_CX, PIE_CY + 13f, txt(INK_LIGHT, 6f, center = true))

            // Legend rows on the left — amount column is a fixed 200pt from margin so
            // name and amount stay close together rather than spanning the full legend width.
            val legendAmtX = M + 250f
            for ((catIdx, entry) in catTotals.withIndex()) {
                val (cat, amt) = entry
                val pct = if (grandTotal > 0.0) (amt / grandTotal * 100).toInt() else 0
                // Alternating grey band — only over the legend columns, not the pie area
                if (catIdx % 2 == 1)
                    c.drawRect(M - 2f, y - 9f, legendAmtX + 2f, y + 5f, fill(ROW_ALT))
                // Coloured square swatch
                c.drawRect(M, y - 8f, M + 8f, y, fill(categoryColorInt(cat)))
                c.drawText(cat.displayName(), M + 13f, y, txt(INK, 8f))
                c.drawText("$currencySymbol${"%.2f".format(amt)}  ($pct%)", legendAmtX, y,
                    txt(INK_MID, 7.5f, mono = true, right = true))
                y += 14f
            }
            // Ensure y clears the bottom of the pie before the footer
            y = maxOf(y, PIE_CY + PIE_R + 10f)
        }

        // Footer
        pageFooter(c, 1)
    }

    // ── Expense detail pages ─────────────────────────────────────────────────

    private fun drawExpensePages(
        doc: PdfDocument,
        expenses: List<Expense>,
        splitsMap: Map<String, List<ExpenseSplit>>,
        memberMap: Map<String, Member>,
        itemsMap: Map<String, List<ExpenseItem>> = emptyMap(),
        currencySymbol: String = "₹"
    ) {
        if (expenses.isEmpty()) return

        val sorted  = expenses.sortedByDescending { it.date }

        val ROW_H    = 14f   // main expense row height
        val SPLIT_H  = 12f   // height of each per-member split line
        val ITEM_LBL = 9f    // "BILL ITEMS" mini-header height
        val ITEM_H   = 20f   // two lines per item: name+amount (11f) + assignments (9f)
        val HDR_Y    = 58f   // y position of column header text
        val START_Y  = 72f   // y where the first data row begins
        val BOTTOM   = PH - 60f  // lower bound — keeps room for grand-total + footer

        var pageNum = 2
        var curPage = doc.startPage(PdfDocument.PageInfo.Builder(PW.toInt(), PH.toInt(), pageNum).create())
        var c       = curPage.canvas
        var y       = START_Y
        var rowIdx  = 0

        // Draw the repeated page header + column labels
        fun drawPageHeader() {
            c.drawRect(0f, 0f, PW, 44f, fill(HEADER_BG))
            c.drawText("EXPENSE DETAIL", M, 18f, txt(WHITE, 9f, bold = true))
            c.drawText("${sorted.size} expenses  ·  Page $pageNum", PW - M, 32f,
                txt(Color.parseColor("#AAAAAA"), 8f, right = true))
            c.drawText("Date",        M,        HDR_Y, txt(INK_LIGHT, 7f, bold = true))
            c.drawText("Description", M + 65f,  HDR_Y, txt(INK_LIGHT, 7f, bold = true))
            c.drawText("Paid By",     M + 210f, HDR_Y, txt(INK_LIGHT, 7f, bold = true))
            c.drawText("Category",    M + 345f, HDR_Y, txt(INK_LIGHT, 7f, bold = true))
            c.drawText("Amount",      PW - M,   HDR_Y, txt(INK_LIGHT, 7f, bold = true, right = true))
            hRule(c, HDR_Y + 5f)
        }

        drawPageHeader()

        for (expense in sorted) {
            val expSplits = (splitsMap[expense.id] ?: emptyList())
                .sortedBy { memberMap[it.memberId]?.name ?: "" }
            val expItems  = itemsMap[expense.id] ?: emptyList()
            val itemsBlockH = if (expItems.isNotEmpty()) ITEM_LBL + expItems.size * ITEM_H else 0f
            val blockH = ROW_H + expSplits.size * SPLIT_H + itemsBlockH
            val GAP_H = 5f   // white space between consecutive expense entries

            // Start a new page if this block won't fit (include inter-row gap in the check)
            if (y + blockH + GAP_H > BOTTOM) {
                pageFooter(c, pageNum)
                doc.finishPage(curPage)
                pageNum++
                curPage = doc.startPage(PdfDocument.PageInfo.Builder(PW.toInt(), PH.toInt(), pageNum).create())
                c = curPage.canvas
                y = START_Y
                rowIdx = 0
                drawPageHeader()
            }

            // Alternating row background spanning main row + all split lines + items.
            // +5f bottom padding so the last text line doesn't touch the edge of the band.
            if (rowIdx % 2 == 1)
                c.drawRect(M - 2f, y - 10f, PW - M + 2f, y - 10f + blockH + 5f, fill(ROW_ALT))

            // Main row
            val payer = memberMap[expense.paidById]?.name
                ?.let { if (it.length > 18) it.take(17) + "…" else it } ?: "—"
            val desc  = if (expense.description.length > 22) expense.description.take(20) + "…"
                        else expense.description

            c.drawText(dateFmt.format(Date(expense.date)), M,        y, txt(INK_MID, 7.5f))
            c.drawText(desc,                               M + 65f,  y, txt(INK,     8f, bold = true))
            c.drawText(payer,                              M + 210f, y, txt(INK_MID, 7.5f))
            drawCategoryChip(c, M + 345f, y, expense.category)
            c.drawText("$currencySymbol${"%.2f".format(expense.amount)}", PW - M, y,
                txt(INK, 8f, bold = true, mono = true, right = true))

            y += ROW_H

            // Per-member split lines indented under Description
            for (split in expSplits) {
                val name = memberMap[split.memberId]?.name
                    ?.let { if (it.length > 28) it.take(26) + "…" else it } ?: "—"
                val pct = if (expense.amount > 0.01)
                    split.amount / expense.amount * 100.0 else 0.0
                c.drawText("· $name (${"%.0f".format(pct)}%)", M + 76f, y, txt(INK_LIGHT, 6.5f))
                c.drawText("$currencySymbol${"%.2f".format(split.amount)}", PW - M, y,
                    txt(INK_MID, 7f, mono = true, right = true))
                y += SPLIT_H
            }

            // Bill items sub-section (only for scanned expenses)
            // Each item uses two lines: name + amount on line 1; member assignments on line 2.
            if (expItems.isNotEmpty()) {
                c.drawText("BILL ITEMS", M + 76f, y, txt(INK_LIGHT, 5.5f, bold = true))
                y += ITEM_LBL
                for (item in expItems) {
                    val splitMode = runCatching { ItemSplitMode.valueOf(item.splitMode) }
                        .getOrDefault(ItemSplitMode.EQUAL)

                    // Line 1: item name (left) + amount (right)
                    val itemName = if (item.name.length > 40) item.name.take(38) + "…" else item.name
                    c.drawText("▸ $itemName", M + 76f, y, txt(INK_LIGHT, 7f))
                    c.drawText("$currencySymbol${"%.2f".format(item.amount)}", PW - M, y,
                        txt(INK_MID, 7f, mono = true, right = true))
                    y += 9f

                    // Line 2: member assignments — full names, no truncation pressure
                    val assignedLabel = when (splitMode) {
                        ItemSplitMode.PERCENTAGE -> {
                            val weighted = parseWeightedIds(item.assignedMemberIds)
                            val totalPct = weighted.sumOf { it.second }
                            if (weighted.isEmpty()) "All members (equal)"
                            else weighted.mapNotNull { (id, w) ->
                                memberMap[id]?.name?.let { name ->
                                    val pct = if (totalPct > 0) w / totalPct * 100 else 0.0
                                    "$name ${"%.0f".format(pct)}%"
                                }
                            }.joinToString("  ·  ")
                        }
                        ItemSplitMode.QUANTITY -> {
                            val weighted = parseWeightedIds(item.assignedMemberIds)
                            if (weighted.isEmpty()) "All members (equal)"
                            else weighted.mapNotNull { (id, w) ->
                                memberMap[id]?.name?.let { name ->
                                    "$name ×${"%.0f".format(w)}"
                                }
                            }.joinToString("  ·  ")
                        }
                        else -> {
                            val assignedIds = parseJsonIds(item.assignedMemberIds)
                            if (assignedIds.isEmpty()) "All members (equal)"
                            else assignedIds.mapNotNull { memberMap[it]?.name }.joinToString("  ·  ")
                        }
                    }
                    c.drawText(assignedLabel, M + 88f, y, txt(INK_LIGHT, 6f))
                    y += 11f   // line 1 (9f) + line 2 (11f) = 20f = ITEM_H
                }
            }

            y += GAP_H   // breathing room between consecutive expense entries
            rowIdx++
        }

        // Grand total at the bottom of the last page
        y += 8f; hRule(c, y); y += 12f
        c.drawText("Grand Total", M, y, txt(INK, 9f, bold = true))
        c.drawText("$currencySymbol${"%.2f".format(sorted.sumOf { it.amount })}", PW - M, y,
            txt(INK, 10f, bold = true, mono = true, right = true))

        pageFooter(c, pageNum)
        doc.finishPage(curPage)
    }

    // ── Drawing helpers ──────────────────────────────────────────────────────

    private fun drawCategoryChip(c: Canvas, x: Float, y: Float, category: Category) {
        val label = category.displayName()
        val p = txt(INK_MID, 7f)
        val tw = p.measureText(label)
        c.drawRoundRect(RectF(x, y - 8f, x + tw + 8f, y + 3f), 3f, 3f, fill(CHIP_BG))
        c.drawText(label, x + 4f, y, p)
    }

    private fun hRule(c: Canvas, y: Float) =
        c.drawLine(M, y, PW - M, y, Paint().apply { color = RULE; strokeWidth = 0.8f })

    private fun thinRule(c: Canvas, y: Float) =
        c.drawLine(M, y, PW - M, y, Paint().apply { color = RULE; strokeWidth = 0.4f })

    private fun sectionTitle(c: Canvas, y: Float, title: String) =
        c.drawText(title, M, y, txt(INK_LIGHT, 7.5f, bold = true))

    private fun pageFooter(c: Canvas, page: Int) {
        thinRule(c, PH - 22f)
        c.drawText("FairShare  ·  Expense Report  ·  Page $page", PW / 2f, PH - 10f,
            txt(INK_LIGHT, 7f, center = true))
    }

    // ── Paint factories ──────────────────────────────────────────────────────

    private fun txt(
        color: Int = INK,
        size: Float = 9f,
        bold: Boolean = false,
        mono: Boolean = false,
        right: Boolean = false,
        center: Boolean = false
    ) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        this.textSize = size
        this.typeface = when {
            mono && bold -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            mono         -> Typeface.MONOSPACE
            bold         -> Typeface.DEFAULT_BOLD
            else         -> Typeface.DEFAULT
        }
        this.textAlign = when {
            right  -> Paint.Align.RIGHT
            center -> Paint.Align.CENTER
            else   -> Paint.Align.LEFT
        }
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    private fun paint8w(text: String): Float =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8.5f }.measureText(text)

    /** Deserialises a plain-ID array: `["id1","id2"]` → list of id strings. */
    private fun parseJsonIds(json: String): List<String> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { arr.getString(it) }
    } catch (_: Exception) { emptyList() }

    /** Deserialises a weighted array: `[{"id":"…","w":60.0}]` → list of (id, weight) pairs. */
    private fun parseWeightedIds(json: String): List<Pair<String, Double>> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val obj = arr.getJSONObject(i)
                obj.getString("id") to obj.getDouble("w")
            }.getOrNull()
        }
    } catch (_: Exception) { emptyList() }

    /** Returns an Android color int matching the app's per-category palette. */
    private fun categoryColorInt(cat: Category): Int = when (cat) {
        Category.FOOD          -> Color.parseColor("#EF5350")
        Category.TRAVEL        -> Color.parseColor("#29B6F6")
        Category.UTILITIES     -> Color.parseColor("#FFCA28")
        Category.ENTERTAINMENT -> Color.parseColor("#66BB6A")
        Category.SHOPPING      -> Color.parseColor("#7E57C2")
        Category.HEALTH        -> Color.parseColor("#EC407A")
        Category.DRINKS        -> Color.parseColor("#FF7043")
        Category.BEVERAGES     -> Color.parseColor("#26C6DA")
        Category.OTHER         -> Color.parseColor("#78909C")
    }
}
