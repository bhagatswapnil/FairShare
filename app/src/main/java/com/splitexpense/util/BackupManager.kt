package com.splitexpense.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.room.withTransaction
import com.splitexpense.data.db.AppDatabase
import com.splitexpense.data.db.entity.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Creates and restores FairShare backups as ZIP archives containing a single
 * `fairshare_backup.json` entry.
 *
 * **Backup destination**
 * - Android 10+ (API 29+): written to the public `Downloads` folder via MediaStore — no
 *   special permission needed and the file is immediately visible in the Files app.
 * - Android 8–9 (API 26–28): written to the app's external-files `Documents` directory
 *   (`/sdcard/Android/data/com.splitexpense/files/Documents/`); accessible by any file
 *   manager without a permission grant.
 *
 * **Restore source**: any `.zip` file picked by the user via the system file picker.
 *
 * **Safety**: JSON is fully parsed and validated before any destructive write occurs —
 * a corrupt or wrong-format ZIP leaves the live database untouched.
 */
class BackupManager(private val context: Context, private val db: AppDatabase) {

    // ── Backup ──────────────────────────────────────────────────────────────────

    /**
     * Serialises all six tables to JSON, wraps the JSON in a ZIP archive, writes it
     * to local storage, and returns a human-readable description of where it was saved.
     * Must be called on a background dispatcher.
     */
    suspend fun createBackup(): Result<String> = runCatching {
        val json     = buildBackupJson()
        val zipBytes = wrapInZip(json)

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName  = "FairShare_backup_$timestamp.zip"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToDownloadsQ(fileName, zipBytes)
        } else {
            saveToDocumentsLegacy(fileName, zipBytes)
        }
    }

    // ── Restore ─────────────────────────────────────────────────────────────────

    /**
     * Opens [uri], extracts the first `.json` entry from the ZIP, parses it, and
     * replaces the entire database in a single transaction.  All parsing happens before
     * any destructive write so a bad file causes zero data loss.
     */
    suspend fun restoreBackup(uri: Uri): Result<Unit> = runCatching {
        val json = readJsonFromZip(uri)

        val root    = JSONObject(json)
        val version = root.optInt("version", -1)
        require(version == BACKUP_VERSION) {
            "Unsupported backup version $version (expected $BACKUP_VERSION)."
        }

        // Parse everything before touching the DB — error here = no data loss.
        val groups      = parseGroups(root.getJSONArray("groups"))
        val members     = parseMembers(root.getJSONArray("members"))
        val expenses    = parseExpenses(root.getJSONArray("expenses"))
        val splits      = parseSplits(root.getJSONArray("expenseSplits"))
        val settlements = parseSettlements(root.getJSONArray("settlements"))
        val items       = parseItems(root.optJSONArray("expenseItems") ?: JSONArray())

        db.withTransaction {
            db.groupDao().deleteAllGroups()          // CASCADE wipes everything
            groups.forEach      { db.groupDao().insertGroup(it) }
            members.forEach     { db.memberDao().insertMember(it) }
            expenses.forEach    { db.expenseDao().insertExpense(it) }
            db.expenseSplitDao().insertSplits(splits)
            settlements.forEach { db.settlementDao().insertSettlement(it) }
            db.expenseItemDao().insertItems(items)
        }
    }

    // ── JSON builder ─────────────────────────────────────────────────────────────

    private suspend fun buildBackupJson(): String {
        val groups      = db.groupDao().getAllGroupsOnce()
        val members     = db.memberDao().getAllMembersOnce()
        val expenses    = db.expenseDao().getAllExpensesOnce()
        val splits      = db.expenseSplitDao().getAllSplitsOnce()
        val settlements = db.settlementDao().getAllSettlementsOnce()
        val items       = db.expenseItemDao().getAllItemsOnce()

        return JSONObject().apply {
            put("version",    BACKUP_VERSION)
            put("exportedAt", System.currentTimeMillis())

            put("groups", JSONArray().also { arr ->
                groups.forEach { g ->
                    arr.put(JSONObject().apply {
                        put("id", g.id); put("name", g.name)
                        put("description", g.description); put("emoji", g.emoji)
                        put("createdAt", g.createdAt); put("currencyCode", g.currencyCode)
                    })
                }
            })
            put("members", JSONArray().also { arr ->
                members.forEach { m ->
                    arr.put(JSONObject().apply {
                        put("id", m.id); put("groupId", m.groupId)
                        put("name", m.name); put("email", m.email)
                        put("avatarColor", m.avatarColor)
                    })
                }
            })
            put("expenses", JSONArray().also { arr ->
                expenses.forEach { e ->
                    arr.put(JSONObject().apply {
                        put("id", e.id); put("groupId", e.groupId)
                        put("description", e.description); put("amount", e.amount)
                        put("paidById", e.paidById); put("category", e.category.name)
                        put("splitType", e.splitType.name); put("date", e.date)
                        put("notes", e.notes); put("isItemised", e.isItemised)
                    })
                }
            })
            put("expenseSplits", JSONArray().also { arr ->
                splits.forEach { s ->
                    arr.put(JSONObject().apply {
                        put("id", s.id); put("expenseId", s.expenseId)
                        put("memberId", s.memberId); put("amount", s.amount)
                        put("percentage", s.percentage)
                    })
                }
            })
            put("settlements", JSONArray().also { arr ->
                settlements.forEach { s ->
                    arr.put(JSONObject().apply {
                        put("id", s.id); put("groupId", s.groupId)
                        put("fromId", s.fromId); put("toId", s.toId)
                        put("fromName", s.fromName); put("toName", s.toName)
                        put("amount", s.amount); put("date", s.date)
                    })
                }
            })
            put("expenseItems", JSONArray().also { arr ->
                items.forEach { i ->
                    arr.put(JSONObject().apply {
                        put("id", i.id); put("expenseId", i.expenseId)
                        put("name", i.name); put("amount", i.amount)
                        put("assignedMemberIds", i.assignedMemberIds)
                    })
                }
            })
        }.toString(2) // pretty-print for human readability inside the ZIP
    }

    // ── ZIP helpers ──────────────────────────────────────────────────────────────

    /** Wraps [json] as a single `fairshare_backup.json` entry inside a ZIP byte array. */
    private fun wrapInZip(json: String): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(ZipEntry(JSON_ENTRY_NAME))
            zip.write(json.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return baos.toByteArray()
    }

    /**
     * Opens [uri] as a ZIP and returns the text content of the first entry whose name
     * ends with `.json`, or throws if none is found.
     */
    private fun readJsonFromZip(uri: Uri): String {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: error("Cannot open backup file — check file permissions")
        ZipInputStream(inputStream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name.endsWith(".json", ignoreCase = true)) {
                    return zip.bufferedReader(Charsets.UTF_8).readText()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        error("No JSON data found inside the ZIP archive. Is this a valid FairShare backup?")
    }

    // ── File-save helpers ─────────────────────────────────────────────────────────

    /**
     * Saves [data] to the public Downloads folder via MediaStore (Android 10+).
     * No permissions required.  Returns a display path shown to the user.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToDownloadsQ(fileName: String, data: ByteArray): String {
        val resolver = context.contentResolver
        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            ?: error("Could not create file in Downloads folder")
        try {
            resolver.openOutputStream(uri)?.use { it.write(data) }
                ?: error("Could not open output stream for Downloads file")
            cv.clear()
            cv.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, cv, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null) // clean up on failure
            throw e
        }
        return "Downloads/$fileName"
    }

    /**
     * Saves [data] to the app's external Documents directory (Android 8–9).
     * No runtime permission needed for app-owned external storage.
     * Returns the absolute path shown to the user.
     */
    private fun saveToDocumentsLegacy(fileName: String, data: ByteArray): String {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: context.filesDir // last-resort fallback to internal storage
        dir.mkdirs()
        val file = File(dir, fileName)
        file.writeBytes(data)
        return file.absolutePath
    }

    // ── Parsers ──────────────────────────────────────────────────────────────────

    private fun parseGroups(arr: JSONArray): List<Group> = buildList {
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            add(Group(
                id           = o.getString("id"),
                name         = o.getString("name"),
                description  = o.optString("description", ""),
                emoji        = o.optString("emoji", "💰"),
                createdAt    = o.optLong("createdAt", System.currentTimeMillis()),
                currencyCode = o.optString("currencyCode", "INR")
            ))
        }
    }

    private fun parseMembers(arr: JSONArray): List<Member> = buildList {
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            add(Member(
                id          = o.getString("id"),
                groupId     = o.getString("groupId"),
                name        = o.getString("name"),
                email       = o.optString("email", ""),
                avatarColor = o.optLong("avatarColor", 0xFF6200EE)
            ))
        }
    }

    private fun parseExpenses(arr: JSONArray): List<Expense> = buildList {
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            add(Expense(
                id          = o.getString("id"),
                groupId     = o.getString("groupId"),
                description = o.getString("description"),
                amount      = o.getDouble("amount"),
                paidById    = o.getString("paidById"),
                category    = runCatching { Category.valueOf(o.getString("category")) }.getOrDefault(Category.OTHER),
                splitType   = runCatching { SplitType.valueOf(o.getString("splitType")) }.getOrDefault(SplitType.EQUAL),
                date        = o.optLong("date", System.currentTimeMillis()),
                notes       = o.optString("notes", ""),
                isItemised  = o.optBoolean("isItemised", false)
            ))
        }
    }

    private fun parseSplits(arr: JSONArray): List<ExpenseSplit> = buildList {
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            add(ExpenseSplit(
                id         = o.getString("id"),
                expenseId  = o.getString("expenseId"),
                memberId   = o.getString("memberId"),
                amount     = o.getDouble("amount"),
                percentage = o.optDouble("percentage", 0.0)
            ))
        }
    }

    private fun parseSettlements(arr: JSONArray): List<Settlement> = buildList {
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            add(Settlement(
                id       = o.getString("id"),
                groupId  = o.getString("groupId"),
                fromId   = o.getString("fromId"),
                toId     = o.getString("toId"),
                fromName = o.optString("fromName", ""),
                toName   = o.optString("toName", ""),
                amount   = o.getDouble("amount"),
                date     = o.optLong("date", System.currentTimeMillis())
            ))
        }
    }

    private fun parseItems(arr: JSONArray): List<ExpenseItem> = buildList {
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            add(ExpenseItem(
                id                = o.getString("id"),
                expenseId         = o.getString("expenseId"),
                name              = o.getString("name"),
                amount            = o.getDouble("amount"),
                assignedMemberIds = o.optString("assignedMemberIds", "[]")
            ))
        }
    }

    companion object {
        const val BACKUP_VERSION  = 1
        const val JSON_ENTRY_NAME = "fairshare_backup.json"
    }
}
