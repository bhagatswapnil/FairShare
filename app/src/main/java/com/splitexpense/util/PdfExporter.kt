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
import org.json.JSONArray
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
        drawSummaryPage(p1.canvas, group, members, expenses, balances, debts, memberShareMap, currencySymbol)
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

        for ((idx, bal) in balances.withIndex()) {
            if (idx % 2 == 1) c.drawRect(M - 2f, y - 10f, PW - M + 2f, y + 6f, fill(ROW_ALT))
            val paid  = expenses.filter { it.paidById == bal.memberId }.sumOf { it.amount }
            val share = memberShares[bal.memberId] ?: 0.0
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
            y += 16f
        }
        y += 6f; hRule(c, y); y += 14f

        // ── Settle Up ──
        if (debts.isNotEmpty()) {
            sectionTitle(c, y, "MINIMUM PAYMENTS TO SETTLE"); y += 16f
            for (debt in debts) {
                c.drawRoundRect(RectF(M, y - 11f, PW - M, y + 5f), 4f, 4f, fill(RED_BG))
                c.drawText("${debt.fromName}", M + 8f, y, txt(RED, 8.5f, bold = true))
                c.drawText("  owes  ", M + 8f + paint8w(debt.fromName), y, txt(INK_MID, 8.5f))
                c.drawText(debt.toName, M + 8f + paint8w("${debt.fromName}  owes  "), y, txt(GREEN, 8.5f, bold = true))
                c.drawText("$currencySymbol${"%.2f".format(debt.amount)}", PW - M - 8f, y, txt(RED, 9f, bold = true, mono = true, right = true))
                y += 18f
            }
            y += 6f; hRule(c, y); y += 14f
        }

        // ── Category breakdown ──
        sectionTitle(c, y, "SPENDING BY CATEGORY"); y += 16f
        val catTotals = expenses.groupBy { it.category }
            .mapValues { (_, list) -> list.sumOf { it.amount } }
            .entries.sortedByDescending { it.value }
        val grandTotal = expenses.sumOf { it.amount }
        val barMaxW = CW - 100f

        for ((cat, amt) in catTotals) {
            val pct  = if (grandTotal > 0.0) (amt / grandTotal * 100).toInt() else 0
            val barW = if (grandTotal > 0.0) (amt / grandTotal * barMaxW).toFloat() else 0f
            c.drawText("${cat.displayName()}", M, y, txt(INK, 8.5f))
            c.drawRect(M + 95f, y - 9f, M + 95f + barMaxW, y - 3f, fill(CHIP_BG))
            c.drawRect(M + 95f, y - 9f, M + 95f + barW, y - 3f, fill(ACCENT))
            c.drawText("$currencySymbol${"%.2f".format(amt)}  ($pct%)", PW - M, y, txt(INK_MID, 8f, mono = true, right = true))
            y += 14f
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
        val ITEM_H   = 11f   // height of each bill item line
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
            c.drawText("Description", M + 68f,  HDR_Y, txt(INK_LIGHT, 7f, bold = true))
            c.drawText("Paid By",     M + 230f, HDR_Y, txt(INK_LIGHT, 7f, bold = true))
            c.drawText("Category",    M + 310f, HDR_Y, txt(INK_LIGHT, 7f, bold = true))
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

            // Start a new page if this block won't fit
            if (y + blockH > BOTTOM) {
                pageFooter(c, pageNum)
                doc.finishPage(curPage)
                pageNum++
                curPage = doc.startPage(PdfDocument.PageInfo.Builder(PW.toInt(), PH.toInt(), pageNum).create())
                c = curPage.canvas
                y = START_Y
                rowIdx = 0
                drawPageHeader()
            }

            // Alternating row background spanning main row + all split lines + items
            if (rowIdx % 2 == 1)
                c.drawRect(M - 2f, y - 10f, PW - M + 2f, y - 10f + blockH, fill(ROW_ALT))

            // Main row
            val payer = memberMap[expense.paidById]?.name
                ?.let { if (it.length > 11) it.take(10) + "…" else it } ?: "—"
            val desc  = if (expense.description.length > 22) expense.description.take(20) + "…"
                        else expense.description

            c.drawText(dateFmt.format(Date(expense.date)), M,        y, txt(INK_MID, 7.5f))
            c.drawText(desc,                               M + 68f,  y, txt(INK,     8f, bold = true))
            c.drawText(payer,                              M + 230f, y, txt(INK_MID, 7.5f))
            drawCategoryChip(c, M + 310f, y, expense.category)
            c.drawText("$currencySymbol${"%.2f".format(expense.amount)}", PW - M, y,
                txt(INK, 8f, bold = true, mono = true, right = true))

            y += ROW_H

            // Per-member split lines indented under Description
            for (split in expSplits) {
                val name = memberMap[split.memberId]?.name
                    ?.let { if (it.length > 17) it.take(16) + "…" else it } ?: "—"
                val pct = if (expense.amount > 0.01)
                    split.amount / expense.amount * 100.0 else 0.0
                c.drawText("· $name (${"%.0f".format(pct)}%)", M + 76f, y, txt(INK_LIGHT, 6.5f))
                c.drawText("$currencySymbol${"%.2f".format(split.amount)}", PW - M, y,
                    txt(INK_MID, 7f, mono = true, right = true))
                y += SPLIT_H
            }

            // Bill items sub-section (only for scanned expenses)
            if (expItems.isNotEmpty()) {
                c.drawText("BILL ITEMS", M + 76f, y, txt(INK_LIGHT, 5.5f, bold = true))
                y += ITEM_LBL
                for (item in expItems) {
                    val assignedIds   = parseJsonIds(item.assignedMemberIds)
                    val assignedLabel = if (assignedIds.isEmpty()) "all"
                        else assignedIds.mapNotNull { memberMap[it]?.name }
                            .joinToString(", ")
                            .let { if (it.length > 32) it.take(30) + "…" else it }
                    val itemName = if (item.name.length > 22) item.name.take(20) + "…" else item.name
                    c.drawText("▸ $itemName  →  $assignedLabel", M + 76f, y, txt(INK_LIGHT, 6.5f))
                    c.drawText("$currencySymbol${"%.2f".format(item.amount)}", PW - M, y,
                        txt(INK_MID, 6.5f, mono = true, right = true))
                    y += ITEM_H
                }
            }

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

    /** Safely deserialises the JSON member-ID array stored in [ExpenseItem.assignedMemberIds]. */
    private fun parseJsonIds(json: String): List<String> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { arr.getString(it) }
    } catch (_: Exception) { emptyList() }
}
