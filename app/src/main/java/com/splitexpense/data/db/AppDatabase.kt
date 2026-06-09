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
    entities = [Group::class, Member::class, Expense::class, ExpenseSplit::class, Settlement::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun groupDao(): GroupDao
    abstract fun memberDao(): MemberDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun expenseSplitDao(): ExpenseSplitDao
    abstract fun settlementDao(): SettlementDao

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

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "split_expense_db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
