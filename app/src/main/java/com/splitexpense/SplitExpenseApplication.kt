package com.splitexpense

import android.app.Application
import com.splitexpense.data.db.AppDatabase
import com.splitexpense.data.repository.ExpenseRepository

class SplitExpenseApplication : Application() {
    val database by lazy { AppDatabase.getInstance(this) }
    val repository by lazy { ExpenseRepository(database) }
}
