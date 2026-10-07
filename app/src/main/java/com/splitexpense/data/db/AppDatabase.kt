package com.splitexpense.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.splitexpense.data.db.dao.*
import com.splitexpense.data.db.entity.*

class Converters {
    @TypeConverter fun categoryToString(c: Category): String = c.name
    @TypeConverter fun stringToCategory(s: String): Category = Category.valueOf(s)
    @TypeConverter fun splitTypeToString(s: SplitType): String = s.name
    @TypeConverter fun stringToSplitType(s: String): SplitType = SplitType.valueOf(s)
}

@Database(
    entities = [Group::class, Member::class, Expense::class, ExpenseSplit::class,
                Settlement::class, ExpenseItem::class, MemberAccount::class],
    version = 7,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun groupDao(): GroupDao
    abstract fun memberDao(): MemberDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun expenseSplitDao(): ExpenseSplitDao
    abstract fun settlementDao(): SettlementDao
    abstract fun expenseItemDao(): ExpenseItemDao
    abstract fun memberAccountDao(): MemberAccountDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS settlements (
                        id       TEXT NOT NULL PRIMARY KEY,
                        groupId  TEXT NOT NULL,
                        fromId   TEXT NOT NULL,
                        toId     TEXT NOT NULL,
                        fromName TEXT NOT NULL,
                        toName   TEXT NOT NULL,
                        amount   REAL NOT NULL,
                        date     INTEGER NOT NULL,
                        FOREIGN KEY (groupId) REFERENCES groups(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_settlements_groupId ON settlements(groupId)"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS expense_items (
                        id                 TEXT NOT NULL PRIMARY KEY,
                        expenseId          TEXT NOT NULL,
                        name               TEXT NOT NULL,
                        amount             REAL NOT NULL,
                        assignedMemberIds  TEXT NOT NULL DEFAULT '[]',
                        FOREIGN KEY (expenseId) REFERENCES expenses(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_expense_items_expenseId ON expense_items(expenseId)"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE expenses ADD COLUMN isItemised INTEGER NOT NULL DEFAULT 0"
                )
                // Backfill: any expense that already has rows in expense_items is itemised.
                database.execSQL(
                    "UPDATE expenses SET isItemised = 1 WHERE id IN (SELECT DISTINCT expenseId FROM expense_items)"
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE groups ADD COLUMN currencyCode TEXT NOT NULL DEFAULT 'INR'"
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE expense_items ADD COLUMN split_mode TEXT NOT NULL DEFAULT 'EQUAL'"
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS member_accounts (
                        id      TEXT NOT NULL PRIMARY KEY,
                        groupId TEXT NOT NULL,
                        name    TEXT NOT NULL,
                        FOREIGN KEY (groupId) REFERENCES groups(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_member_accounts_groupId ON member_accounts(groupId)"
                )
                database.execSQL("ALTER TABLE members ADD COLUMN accountId TEXT")
            }
        }

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "split_expense_db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
