package com.splitexpense.util

import com.splitexpense.data.db.AppDatabase
import com.splitexpense.data.db.entity.*
import java.util.UUID
import java.util.concurrent.TimeUnit

object SampleData {
    suspend fun insert(db: AppDatabase) {
        // ─── Group 1: Goa Trip ───────────────────────────────────────────────
        val g1 = "group_goa"
        db.groupDao().insertGroup(Group(g1, "Goa Trip", "Beach vacation 2024", "🏖️", System.currentTimeMillis() - TimeUnit.DAYS.toMillis(10)))
        val alice = Member("m_alice", g1, "Alice", "alice@example.com", 0xFFE53935)
        val bob = Member("m_bob", g1, "Bob", "bob@example.com", 0xFF1E88E5)
        val charlie = Member("m_charlie", g1, "Charlie", "charlie@example.com", 0xFF43A047)
        val diana = Member("m_diana", g1, "Diana", "diana@example.com", 0xFF8E24AA)
        listOf(alice, bob, charlie, diana).forEach { db.memberDao().insertMember(it) }

        addEqualExpense(db, g1, "Hotel (2 nights)", 8000.0, "m_alice", Category.TRAVEL, listOf("m_alice","m_bob","m_charlie","m_diana"), 8)
        addEqualExpense(db, g1, "Beach shack dinner", 3200.0, "m_bob", Category.FOOD, listOf("m_alice","m_bob","m_charlie","m_diana"), 7)
        addEqualExpense(db, g1, "Jet ski & parasailing", 5600.0, "m_charlie", Category.ENTERTAINMENT, listOf("m_alice","m_bob","m_charlie","m_diana"), 6)
        addEqualExpense(db, g1, "Taxi & fuel", 2400.0, "m_diana", Category.TRAVEL, listOf("m_alice","m_bob","m_charlie","m_diana"), 5)
        addEqualExpense(db, g1, "Breakfast Day 2", 1200.0, "m_alice", Category.FOOD, listOf("m_alice","m_bob","m_charlie","m_diana"), 4)

        // ─── Group 2: Flat Expenses ──────────────────────────────────────────
        val g2 = "group_flat"
        db.groupDao().insertGroup(Group(g2, "Flat Expenses", "Monthly shared costs", "🏠", System.currentTimeMillis() - TimeUnit.DAYS.toMillis(5)))
        val raj = Member("m_raj", g2, "Raj", "raj@example.com", 0xFFFF6F00)
        val priya = Member("m_priya", g2, "Priya", "priya@example.com", 0xFFD81B60)
        val sam = Member("m_sam", g2, "Sam", "sam@example.com", 0xFF00897B)
        listOf(raj, priya, sam).forEach { db.memberDao().insertMember(it) }

        addEqualExpense(db, g2, "Monthly rent", 45000.0, "m_raj", Category.UTILITIES, listOf("m_raj","m_priya","m_sam"), 3)
        addEqualExpense(db, g2, "Electricity bill", 2100.0, "m_priya", Category.UTILITIES, listOf("m_raj","m_priya","m_sam"), 2)
        addEqualExpense(db, g2, "Groceries", 4500.0, "m_sam", Category.SHOPPING, listOf("m_raj","m_priya","m_sam"), 1)
        addEqualExpense(db, g2, "Internet", 999.0, "m_raj", Category.UTILITIES, listOf("m_raj","m_priya","m_sam"), 0)

        // ─── Group 3: Office Lunch ───────────────────────────────────────────
        val g3 = "group_office"
        db.groupDao().insertGroup(Group(g3, "Office Lunch", "Team lunch pool", "🍱", System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2)))
        val nik = Member("m_nik", g3, "Nikhil", "", 0xFF3949AB)
        val shr = Member("m_shr", g3, "Shruti", "", 0xFFF4511E)
        val avi = Member("m_avi", g3, "Avinash", "", 0xFF00ACC1)
        listOf(nik, shr, avi).forEach { db.memberDao().insertMember(it) }

        addEqualExpense(db, g3, "Monday biryani", 1500.0, "m_nik", Category.FOOD, listOf("m_nik","m_shr","m_avi"), 1)
        addEqualExpense(db, g3, "Friday pizza party", 2200.0, "m_shr", Category.FOOD, listOf("m_nik","m_shr","m_avi"), 0)
    }

    private suspend fun addEqualExpense(
        db: AppDatabase,
        groupId: String,
        desc: String,
        amount: Double,
        paidById: String,
        category: Category,
        memberIds: List<String>,
        daysAgo: Long
    ) {
        val expenseId = UUID.randomUUID().toString()
        db.expenseDao().insertExpense(
            Expense(
                id = expenseId,
                groupId = groupId,
                description = desc,
                amount = amount,
                paidById = paidById,
                category = category,
                splitType = SplitType.EQUAL,
                date = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(daysAgo)
            )
        )
        val share = amount / memberIds.size
        db.expenseSplitDao().insertSplits(
            memberIds.map { mid ->
                ExpenseSplit(UUID.randomUUID().toString(), expenseId, mid, share)
            }
        )
    }
}
