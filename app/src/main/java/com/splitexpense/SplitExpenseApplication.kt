package com.splitexpense

import android.app.Application
import com.splitexpense.data.db.AppDatabase
import com.splitexpense.data.repository.ExpenseRepository
import com.splitexpense.util.ApiKeyManager
import com.splitexpense.util.AppSettingsManager
import com.splitexpense.util.BackupManager

class SplitExpenseApplication : Application() {
    val database by lazy { AppDatabase.getInstance(this) }
    val repository by lazy { ExpenseRepository(database) }
    val apiKeyManager by lazy { ApiKeyManager(this) }
    val appSettingsManager by lazy { AppSettingsManager(this) }
    val backupManager by lazy { BackupManager(this, database) }
}
