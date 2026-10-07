package com.splitexpense.data.model

import com.splitexpense.data.db.entity.Category
import com.splitexpense.data.db.entity.Expense
import com.splitexpense.data.db.entity.ExpenseSplit
import com.splitexpense.data.db.entity.Member
import com.splitexpense.data.db.entity.Settlement
import com.splitexpense.data.db.entity.SplitType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountMergeTest {

    private val eps = 0.001
    private val acct = "acct-1"

    // Name-ordered, as the DAO returns them: Priya is the representative of acct-1.
    private val priya   = Member("p", "g", "Priya",   accountId = acct)
    private val rahul   = Member("r", "g", "Rahul")
    private val swapnil = Member("s", "g", "Swapnil", accountId = acct)
    private val members = listOf(priya, rahul, swapnil)

    /** ₹900 dinner paid by Rahul, split equally three ways. */
    private val dinner = Expense(
        id = "e1", groupId = "g", description = "Dinner", amount = 900.0,
        paidById = "r", category = Category.FOOD, splitType = SplitType.EQUAL
    )
    private val dinnerSplits = listOf("p", "r", "s").map { ExpenseSplit("sp_$it", "e1", it, 300.0) }

    private fun settlement(fromId: String, toId: String, amount: Double) =
        Settlement("st_$fromId", "g", fromId, toId, "", "", amount)

    private fun merged(settlements: List<Settlement> = emptyList()) = mergeBalancesByAccount(
        members,
        calculateBalances(members, listOf(dinner), dinnerSplits, settlements),
        mapOf(acct to "The Bhagats")
    )

    @Test
    fun `linked members merge into one named balance`() {
        val result = merged()
        assertEquals(2, result.size)

        val account = result.single { it.memberIds.size == 2 }
        assertEquals("p", account.memberId)               // representative = first by name
        assertEquals("The Bhagats", account.memberName)
        assertEquals(-600.0, account.netBalance, eps)
        assertEquals(setOf("p", "s"), account.memberIds.toSet())

        assertEquals(600.0, result.single { it.memberId == "r" }.netBalance, eps)
    }

    @Test
    fun `simplifyDebts yields one transaction from the account`() {
        val debts = simplifyDebts(merged())
        assertEquals(1, debts.size)
        with(debts.single()) {
            assertEquals("p", fromId)
            assertEquals("The Bhagats", fromName)
            assertEquals("r", toId)
            assertEquals(600.0, amount, eps)
        }
    }

    @Test
    fun `settlement against the representative clears everyone`() {
        val result = merged(listOf(settlement("p", "r", 600.0)))
        result.forEach { assertEquals(0.0, it.netBalance, eps) }
        assertTrue(simplifyDebts(result).isEmpty())
    }

    @Test
    fun `settlement against the non-representative member also clears the account`() {
        val result = merged(listOf(settlement("s", "r", 600.0)))
        result.forEach { assertEquals(0.0, it.netBalance, eps) }
        assertTrue(simplifyDebts(result).isEmpty())
    }

    @Test
    fun `missing account name falls back to joined member names`() {
        val result = mergeBalancesByAccount(
            members, calculateBalances(members, listOf(dinner), dinnerSplits)
        )
        assertEquals("Priya & Swapnil", result.single { it.memberIds.size == 2 }.memberName)
    }

    @Test
    fun `solo-only group is unchanged by the merge`() {
        val solo = members.map { it.copy(accountId = null) }
        val balances = calculateBalances(solo, listOf(dinner), dinnerSplits)
        assertEquals(balances, mergeBalancesByAccount(solo, balances))
    }

    @Test
    fun `account id held by a single member is treated as solo`() {
        val orphaned = listOf(priya, rahul, swapnil.copy(accountId = null))
        val balances = calculateBalances(orphaned, listOf(dinner), dinnerSplits)
        assertEquals(balances, mergeBalancesByAccount(orphaned, balances))
    }
}
