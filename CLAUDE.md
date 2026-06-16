# FairShare — Complete Technical Reference

**App name:** FairShare  
**Package:** `com.splitexpense`  
**Version:** 1.0 (versionCode 1)  
**Platform:** Android (minSdk 26, targetSdk/compileSdk 35)  
**Language:** Kotlin  
**Build system:** Gradle KTS + KSP  

---

## Purpose

FairShare is an Android app for splitting shared expenses within groups (trips, flatmates, office lunches, etc.). Users create groups, add members, record expenses with three split modes (equal, percentage, custom amount), view live balances and minimum-payment debt suggestions, record settlements, and export a PDF report.

The app is primarily local/offline. The one network feature is **AI bill scanning**: users can photograph a receipt and have Gemini AI parse it into line items, which are then assigned to group members. This requires the user to supply their own Gemini API key (stored in private SharedPreferences). All expense and balance data lives in the on-device Room database.

---

## Architecture

**Pattern:** MVVM + single-repository pattern (no use-cases layer).

```
UI (Compose screens)
    ↓ observes StateFlow
ViewModels (androidx.lifecycle.ViewModel)
    ↓ calls suspend funs / collects Flow
ExpenseRepository  (single class, no interface)
    ↓ calls DAOs
Room DAOs → SQLite database

GeminiService (object singleton)       ← network only, called from ScanBillViewModel
    ↓ OkHttp async (suspendCancellableCoroutine)
Gemini REST API (generativelanguage.googleapis.com)
```

**Key constraints:**
- One `AppDatabase` singleton (lazy, thread-safe).
- One `ExpenseRepository` singleton, held on `SplitExpenseApplication`.
- One `ApiKeyManager` singleton, held on `SplitExpenseApplication` — used by `ScanBillViewModel` and `HomeScreen`.
- ViewModels receive dependencies via a custom `ViewModelProvider.Factory` that reads from `SplitExpenseApplication`. No DI framework.
- All database calls happen on Room's IO dispatcher. `withTransaction` is used for any multi-step writes.
- Network calls (GeminiService) use OkHttp's `enqueue()` wrapped in `suspendCancellableCoroutine` so coroutine cancellation cancels the in-flight HTTP request.

---

## Project Layout

```
app/src/main/java/com/splitexpense/
├── MainActivity.kt                       # single activity, sets FairShareTheme + AppNavigation
├── SplitExpenseApplication.kt            # Application; lazy singletons: db, repository, apiKeyManager
│
├── data/
│   ├── db/
│   │   ├── AppDatabase.kt                # Room DB, version 4; migrations 1→2→3→4
│   │   ├── entity/
│   │   │   ├── Group.kt
│   │   │   ├── Member.kt
│   │   │   ├── Expense.kt                # defines Category, SplitType enums; isItemised flag
│   │   │   ├── ExpenseSplit.kt
│   │   │   ├── ExpenseItem.kt            # NEW: line items from scanned bills
│   │   │   └── Settlement.kt
│   │   └── dao/
│   │       ├── GroupDao.kt
│   │       ├── MemberDao.kt
│   │       ├── ExpenseDao.kt             # includes GroupExpenseTotal projection
│   │       ├── ExpenseSplitDao.kt
│   │       ├── ExpenseItemDao.kt         # NEW: CRUD for expense_items
│   │       └── SettlementDao.kt          # includes GroupSettledAmount projection
│   ├── model/
│   │   └── BalanceModels.kt              # MemberBalance, DebtTransaction data classes
│   └── repository/
│       └── ExpenseRepository.kt          # all business logic + DB access; BillItemData data class
│
└── ui/
    ├── navigation/
    │   └── AppNavigation.kt              # NavHost, Routes object, slide transitions
    ├── screens/
    │   ├── splash/SplashScreen.kt
    │   ├── home/
    │   │   ├── HomeScreen.kt             # API key button added to app bar
    │   │   └── HomeViewModel.kt
    │   ├── group/
    │   │   ├── GroupScreen.kt            # speed-dial FAB; expandable bill-item cards
    │   │   └── GroupViewModel.kt         # expenseItemsByExpenseId; dual-coroutine init
    │   ├── expense/
    │   │   ├── AddExpenseScreen.kt       # bill items section; computed amount display
    │   │   └── AddExpenseViewModel.kt    # item-driven save path; isInitializing; pendingItemDeletes
    │   └── scan/                         # NEW: bill scanning feature
    │       ├── ScanBillScreen.kt         # 3-phase UI: Capture → Processing → Review
    │       └── ScanBillViewModel.kt      # image compress, Gemini call, item assignment, save
    ├── components/
    │   ├── Charts.kt                     # InteractivePieChart, HorizontalBarChart
    │   └── ApiKeyDialog.kt               # NEW: enter/update Gemini API key
    └── theme/
        ├── Color.kt                      # graphite dark palette + neon accents
        ├── Theme.kt                      # FairShareTheme (always dark)
        └── Type.kt                       # AppTypography (monospace throughout)

util/
├── CategoryExtensions.kt                 # displayName(), icon(), color(), formatCurrency(), nameToAvatarColor()
├── PdfExporter.kt                        # A4 renderer; now includes bill-item sub-section per expense
├── ApiKeyManager.kt                      # NEW: Gemini API key persistence (SharedPreferences)
├── GeminiService.kt                      # NEW: OkHttp → Gemini REST; model fallback chain
└── SampleData.kt                         # hardcoded seed data for first-launch demo
```

---

## Database Schema

**DB name:** `split_expense_db`  **Version:** 4

### `groups`
| Column | Type | Notes |
|---|---|---|
| id | TEXT PK | UUID |
| name | TEXT | |
| description | TEXT | default "" |
| emoji | TEXT | default "💰" |
| createdAt | INTEGER | epoch ms |

### `members`
| Column | Type | Notes |
|---|---|---|
| id | TEXT PK | UUID |
| groupId | TEXT | FK → groups.id ON DELETE CASCADE |
| name | TEXT | |
| email | TEXT | default "" |
| avatarColor | INTEGER (Long) | ARGB from `avatarPalette` |

### `expenses`
| Column | Type | Notes |
|---|---|---|
| id | TEXT PK | UUID |
| groupId | TEXT | FK → groups.id ON DELETE CASCADE |
| description | TEXT | |
| amount | REAL | always > 0.0 (enforced in ViewModel) |
| paidById | TEXT | member id — **no FK constraint** (intentional: historical record survives member deletion) |
| category | TEXT | Category enum name |
| splitType | TEXT | SplitType enum name |
| date | INTEGER | epoch ms |
| notes | TEXT | default "" |
| isItemised | INTEGER | 0/1 boolean (added v4). True when created via bill scanning. |

### `expense_splits`
| Column | Type | Notes |
|---|---|---|
| id | TEXT PK | UUID |
| expenseId | TEXT | FK → expenses.id ON DELETE CASCADE |
| memberId | TEXT | FK → members.id ON DELETE CASCADE |
| amount | REAL | rounded to 2 decimal places on write |
| percentage | REAL | set for PERCENTAGE splits only; 0.0 for EQUAL/AMOUNT |

### `expense_items` *(new in v3)*
| Column | Type | Notes |
|---|---|---|
| id | TEXT PK | UUID (stable — preserved across edits for upsert identity) |
| expenseId | TEXT | FK → expenses.id ON DELETE CASCADE |
| name | TEXT | item label |
| amount | REAL | item amount |
| assignedMemberIds | TEXT | JSON array string e.g. `["id1","id2"]`. `"[]"` = all group members |

Index: `index_expense_items_expenseId` on `expenseId`.

### `settlements`
| Column | Type | Notes |
|---|---|---|
| id | TEXT PK | UUID |
| groupId | TEXT | FK → groups.id ON DELETE CASCADE |
| fromId | TEXT | member who paid — **no FK constraint** |
| toId | TEXT | member who received — **no FK constraint** |
| fromName | TEXT | denormalized snapshot of name at record time |
| toName | TEXT | denormalized snapshot of name at record time |
| amount | REAL | |
| date | INTEGER | epoch ms |

**FK gap (known, intentional):** `Settlement.fromId`/`toId` have no FK to `members`. Enforced at application layer only. `calculateBalances` skips orphaned IDs defensively.

### Migrations
| From → To | Change |
|---|---|
| v1 → v2 | Creates `settlements` table |
| v2 → v3 | Creates `expense_items` table + index |
| v3 → v4 | Adds `isItemised INTEGER NOT NULL DEFAULT 0` to `expenses`; backfills to 1 for any expense already having rows in `expense_items` |

All four migrations are registered in `AppDatabase.getInstance()`. Users upgrading from any prior version run the chain sequentially.

---

## Enums

```kotlin
enum class SplitType { EQUAL, PERCENTAGE, AMOUNT }

enum class Category {
    FOOD, TRAVEL, UTILITIES, ENTERTAINMENT, SHOPPING, HEALTH, OTHER
}
```

`Category` extension functions live in `CategoryExtensions.kt`:
- `displayName(): String`
- `icon(): ImageVector`
- `color(): Color` — unique colour per category for charts

---

## Data Models

```kotlin
data class MemberBalance(
    val memberId: String,
    val memberName: String,
    val avatarColor: Long,
    val netBalance: Double   // positive = owed money; negative = owes money
)

data class DebtTransaction(
    val fromId: String,    // debtor
    val fromName: String,
    val toId: String,      // creditor
    val toName: String,
    val amount: Double
)

// In ExpenseRepository.kt — passed to addExpenseWithItems / updateExpenseWithItems
data class BillItemData(
    val id: String = UUID.randomUUID().toString(),  // stable ID for upsert
    val name: String,
    val amount: Double,
    val assignedMemberIds: List<String>             // empty = all group members
)
```

---

## Repository — Key Business Logic

File: `data/repository/ExpenseRepository.kt`

### `addExpense` / `updateExpense` / `deleteExpense`
Standard expense paths. All multi-step DB writes are wrapped in `db.withTransaction {}`. Split amounts are rounded to 2 decimal places at write time. `updateExpense` now also calls `deleteItemsForExpense` to clean up any leftover items when converting an itemised expense back to a regular one, and forces `isItemised = false`.

### `addExpenseWithItems(groupId, description, paidById, category, items, allMemberIds)`
Creates an itemised expense in one transaction:
1. Inserts an `Expense` with `splitType = AMOUNT`, `isItemised = true`, `amount = sum(item.amount)`.
2. Computes per-member split totals by accumulating `item.amount / assignees.size` (unrounded) across all items, then rounds each member's total once at `insertSplits` time (`round(total * 100) / 100.0`). Rounding once per member (not per item) minimises sum(splits) vs expense.amount drift.
3. Inserts `ExpenseItem` rows with `assignedMemberIds` stored as a JSON array string.

### `updateExpenseWithItems(expense, items, deletedItemIds, allMemberIds)`
Updates an itemised expense in one transaction:
- Only `deletedItemIds` are DELETEd from `expense_items` (items explicitly removed by the user).
- All current `items` are upserted via `insertItems` (`OnConflictStrategy.REPLACE`) — new items are inserted, existing items are updated by stable ID.
- Splits are wiped and recomputed from the new item assignments (same rounding rule as `addExpenseWithItems`).

### `calculateBalances(members, expenses, splits, settlements)`
Builds `net: MutableMap<String, Double>` seeded **only from the `members` list**. Each step uses `if (id in net)` guards — orphaned IDs from deleted members are silently skipped rather than creating phantom map keys.

```
net formula per member:
  + amount for each expense where paidById == member.id
  - amount for each split where memberId == member.id
  + amount for each settlement where fromId == member.id (paid out)
  - amount for each settlement where toId == member.id   (received)
```

Positive `netBalance` → member is owed money. Negative → member owes money.

### `simplifyDebts(balances)`
Greedy min-creditor / max-debtor algorithm. 0.01 epsilon terminates the loop. Loop bound is N² (N = member count).

### `categorySpending(expenses, splits, memberId?)`
Returns `Map<Category, Double>`. `memberId == null` → group totals; non-null → per-member split amounts.

### `computePendingPerGroup(...)`
Called from `HomeViewModel`. In-memory filter of all splits/settlements per group. Pending = sum of positive net balances after settlement adjustment.

---

## ViewModels

### `HomeViewModel`
- **State:** `HomeUiState` — groups list, per-group totals/member counts/pending/settled amounts/member lists.
- **Flows:** 7 source flows combined in two nested `combine()` calls.
- **stateIn:** `SharingStarted.WhileSubscribed(5_000)`.

### `GroupViewModel`
- **State:** `GroupUiState` — group, members, expenses, balances, simplified debts, category spending, total, settlements, `membersWithExpenses`, `splitCountByExpenseId`, `expenseItemsByExpenseId`, loading/export flags.
- **Dual-coroutine init:** Two coroutines launch in `init`:
  1. **Combine coroutine** — `combine(group, members, expenses, splits, settlements)` for the core 5 flows. Its collect uses `_uiState.update { current -> newState.copy(expenseItemsByExpenseId = current.expenseItemsByExpenseId) }` to preserve the items map set by the second coroutine.
  2. **Items coroutine** — collects `repository.getItemsForGroup(groupId)` separately (Room's `combine` is limited to 5 args). Updates `expenseItemsByExpenseId` atomically via `_uiState.update`.
- **`expenseItemsByExpenseId: Map<String, List<ExpenseItem>>`** — grouped by `expenseId`, used to populate expandable bill-item cards in the Expenses tab.
- **`membersWithExpenses: Set<String>`** — union of expense `paidById`, split `memberId`, settlement `fromId`/`toId`. Members in this set show a lock icon and cannot be deleted.
- **`isSettling: Boolean`** — private flag gates `recordSettlement` against double-taps.
- **Export flow:** `exportPdf` re-fetches splits AND items from DB on `Dispatchers.IO`, passes both to `PdfExporter`. Stale UI state is not used.

### `AddExpenseViewModel`
- **State:** `AddExpenseUiState` — all form fields plus `expenseItems`, `isInitializing`, `isLoading`, `error`, `saved`.
- **`isInitializing: Boolean`** — true in edit mode until expense + splits + items are fully loaded. `AddExpenseScreen` shows a full-screen spinner while true. Reset to `false` on success or when the expense is not found.
- **Edit mode:** `expenseId != null`. Two coroutines in `init`: member flow + expense/splits/items loader. If `getExpenseById` returns null (expense deleted externally), `isInitializing` is immediately cleared and an error is shown (not a silent spinner hang).
- **`ExpenseItemState`** — in-memory representation of one editable line item (`id`, `name`, `amount: String`, `assignedMemberIds: Set<String>`). `id` defaults to a fresh UUID; in edit mode IDs are loaded from the DB to preserve upsert identity.
- **`originalItemIds: Set<String>`** — item IDs that came from the DB when the form was opened. Used to distinguish "remove a DB-backed item" from "remove a newly added item" in `onRemoveItem`.
- **`pendingItemDeletes: MutableSet<String>`** — accumulates DB-backed item IDs removed during the session. Snapshotted and cleared before the save coroutine launches; restored on failure so a retry still deletes them.
- **Two save paths in `saveExpense()`:**
  - *Item-driven:* `expenseItems.isNotEmpty()` — validates item names/amounts, calls `addExpenseWithItems` or `updateExpenseWithItems`.
  - *Standard:* `expenseItems.isEmpty()` — original amount/split-type/member validation, calls `addExpense` or `updateExpense`.
- **Validation order (common, applied before both paths):** description blank → paidById empty → `isEditing && originalExpense == null`.

### `ScanBillViewModel` *(new)*
- **State:** `ScanBillUiState` — `phase` (CAPTURE / PROCESSING / REVIEW), members, description, category, paidById, items (`List<BillItemState>`), isSaving, error, showApiKeyDialog, pendingImageBytes.
- **Three phases:**
  - `CAPTURE` — camera / gallery picker. If no API key, stashes compressed image bytes in state and transitions to `showApiKeyDialog = true`.
  - `PROCESSING` — Gemini API call via `GeminiService.parseReceipt()`. On success, transitions to REVIEW with parsed items. On failure, returns to CAPTURE with error.
  - `REVIEW` — editable item list with member assignment chips. Save calls `repository.addExpenseWithItems()`.
- **Image handling:** `readAndCompressImage()` — opens stream with `use{}` (closes on exit), null-checks bitmap from `decodeStream`, scales to max 1024px wide, recycles original bitmap after scaling, compresses to JPEG 85.
- **Validation in `saveExpense()`:** description blank → paidById empty → items empty → any item name blank → any item amount < ₹0.01 → total < ₹0.01.

---

## Navigation

File: `ui/navigation/AppNavigation.kt`

| Route | Screen | Params |
|---|---|---|
| `splash` | SplashScreen | — |
| `home` | HomeScreen | — |
| `group/{groupId}` | GroupScreen | groupId: String |
| `add_expense/{groupId}` | AddExpenseScreen | groupId: String |
| `edit_expense/{groupId}/{expenseId}` | AddExpenseScreen | groupId, expenseId: String |
| `scan_bill/{groupId}` | ScanBillScreen | groupId: String |

`AddExpenseScreen` is reused for add and edit — the `expenseId` param controls mode.  
Transitions: horizontal slide (300 ms) for push/pop. Splash fades out.

---

## Screens

### SplashScreen
Animated logo (spring scale + fade), "FairShare" title, tagline "Split bills. Not friendships.", feature pills. Exits to Home after ~2 s.

### HomeScreen
- Search bar (filters by name + description).
- Group list (`LazyColumn`) — each card shows: emoji, name, member count, total spent, pending amount, settled %, member avatar strip.
- App bar buttons: **Key icon** → `ApiKeyDialog` (enter/update Gemini API key); **+ icon** → "Create Group" dialog.
- "Create Group" dialog: name, description (optional), emoji picker (12 options).

### GroupScreen
4-tab `HorizontalPager`:

**Tab 1 — Expenses**  
Chronological `LazyColumn` of expense cards. Each card now has a **3-dot menu** (Edit / Delete) replacing the old long-press/swipe pattern. For itemised expenses, an **expand toggle (▾/▴)** reveals a bill-items sub-section with item name, assigned member names, and amount per item. The `/person` share hint is hidden for itemised expenses (splits are per-item, not uniform).

**Speed-dial FAB** (bottom-right, Expenses tab only):
- Main button (✕ / ✚ toggle) opens/closes the speed dial.
- **Scan Bill** mini-button → navigates to `ScanBillScreen`.
- **Add Manually** mini-button → navigates to `AddExpenseScreen`.
- The speed dial animates in/out with `fadeIn + slideInVertically`.

**Tab 2 — Members**  
Member list with avatar, name, email, net balance badge, delete button (locked if member has associated expenses/splits/settlements).

**Tab 3 — Balances**  
Summary stats bar. Per-member balance rows. Simplified debts with "Mark settled" → `recordSettlement`. Undo list for recorded settlements.

**Tab 4 — Charts**  
`InteractivePieChart` (category spending). `HorizontalBarChart` (per-member spend). Member spend table.

App bar: group emoji + name, overflow menu (Edit group, Delete group, Export PDF).

### AddExpenseScreen
Form fields in a scrollable column:
- **Amount field** (large, monospace) — hidden when bill items are present; replaced by `ComputedAmountDisplay` showing the auto-calculated total.
- Description (outlined text field).
- "Paid By" exposed dropdown.
- Category selector (icon + label chips, 7 categories, 4-per-row grid).
- **Split controls** (SplitTypeSelector + MemberSplitSection) — hidden when bill items are present.
- **Bill Items section** — always visible. When items is empty, shows an "Add Bill Items (optional)" button. When items exist, shows each item with name field, amount field, member assignment chips, and a per-person share hint. Adding the first item while the form has existing data triggers a confirmation dialog ("Replace split data?").
- **Loading spinner** (`isInitializing`) — shown in edit mode until data is loaded from DB.
- Error banner (red). Save button (spinner when `isLoading`).

### ScanBillScreen *(new)*
Three-phase screen:

**Phase 1 — Capture**  
Centered scanner icon, "Take Photo" button (full-width, disabled until camera temp file is ready), "Choose from Gallery" button. Error banner if previous scan failed. Camera temp file is created on `Dispatchers.IO` via `LaunchedEffect` and deleted in `DisposableEffect.onDispose`.

**Phase 2 — Processing**  
Full-screen spinner with "Analyzing your bill..." label.

**Phase 3 — Review & Assign**  
- Description field, Category grid, Paid By dropdown.
- Bill items list — each row: index number, item name field, ₹-prefixed amount field, remove button, member assignment chips (empty set = all members highlighted), per-person share hint.
- Add Item button.
- Member Summary panel — shows per-member total derived from item assignments. Visible only when at least one assignment exists.
- Error banner. Save button.

Back button in REVIEW phase returns to CAPTURE (not to the previous screen). Back in CAPTURE navigates back.

### ApiKeyDialog *(new component)*
`AlertDialog` with a password-style text field (show/hide toggle). Pre-fills with the current key if one exists. Save button disabled when field is blank. Used both in `HomeScreen` and triggered automatically by `ScanBillScreen` when a scan is attempted without a key.

---

## Design System

### Theme
`FairShareTheme` is always dark — no light mode variant. Uses `darkColorScheme` (`GraphiteScheme`).

### Colour Palette (`Color.kt`)
```
Bg        #0D0D0D   — page background
Surface1  #141414   — app bars, cards layer 1
Surface2  #1C1C1C   — card interiors
Surface3  #242424   — input fields, chips
Border    #2E2E2E   — default stroke
BorderHi  #3A3A3A   — focused/selected stroke

Text1     #F5F5F5   — primary text
Text2     #BBBBBB   — secondary text
Text3     #808080   — hint / label text

NeonGreen    #4AFF8C   — positive balances, CTAs, accent
NeonGreenDim #1F4AFF8C — green tinted backgrounds
NeonRed      #FF5F5F   — negative balances, errors, delete
NeonRedDim   #1FFF5F5F — red tinted backgrounds
AmberAccent  #FFB830   — secondary accent (unsettled indicator, member totals in scan review)
```

### Typography (`Type.kt`)
**All text uses `FontFamily.Monospace`** — this is the defining visual character of the app.

Key sizes: displaySmall 36sp, headlineLarge 26sp, titleLarge 19sp, bodySmall 11sp, labelSmall 9sp.

### Category Colours (for charts and chips)
| Category | Colour |
|---|---|
| FOOD | #EF5350 (red) |
| TRAVEL | #29B6F6 (blue) |
| UTILITIES | #FFCA28 (yellow) |
| ENTERTAINMENT | #66BB6A (green) |
| SHOPPING | #7E57C2 (purple) |
| HEALTH | #EC407A (pink) |
| OTHER | #78909C (grey) |

### Avatar Colours
12-colour palette in `avatarPalette`. Assigned deterministically: `avatarPalette[abs(name.hashCode()) % 12]` via `nameToAvatarColor(name)`.

---

## AI Bill Scanning

### Flow
1. User taps **Scan Bill** in the GroupScreen speed-dial FAB.
2. `ScanBillScreen` opens. User takes a photo or picks from gallery.
3. If no API key is set, `ApiKeyDialog` is shown. The compressed image bytes are stashed in `ScanBillUiState.pendingImageBytes` until the key is saved.
4. Image is compressed to JPEG 85%, max 1024px wide, on the calling thread (inside `processImage`'s try/catch, called from the camera/gallery result callback).
5. `GeminiService.parseReceipt(bytes, apiKey)` is called — tries models in sequence (`gemini-2.5-flash` → `gemini-2.5-flash-lite` → fallback), using async `enqueue()` with `invokeOnCancellation { call.cancel() }`.
6. Gemini returns JSON `{ description, total, category, items: [{name, amount}] }`. Parsed into `ParsedBill`.
7. Screen transitions to REVIEW. User can edit descriptions, amounts, reassign items to members.
8. Tapping Save calls `repository.addExpenseWithItems()` and navigates back to `GroupScreen`.

### GeminiService
File: `util/GeminiService.kt`

- Singleton `object` with a shared `OkHttpClient` (30s connect, 60s read).
- Model fallback list is tried in order; first success wins, errors are logged and swallowed.
- Response envelope parsing: `candidates[0].content.parts[0].text` → inner JSON.
- `parseBillJson` falls back gracefully: missing fields use defaults, zero-amount items are filtered, empty items list becomes a single item for the total amount.
- All HTTP is done via `Call.awaitString()` — a private `suspendCancellableCoroutine` extension that calls `call.cancel()` on coroutine cancellation.

### ApiKeyManager
File: `util/ApiKeyManager.kt`

- Wraps `SharedPreferences("fairshare_prefs", MODE_PRIVATE)`.
- Key: `"gemini_api_key"`. Stored as plaintext (acceptable for a personal-use app where the user owns the key, but not suitable for a shared/multi-user deployment).
- Singleton on `SplitExpenseApplication`; also read directly in `HomeScreen` via `remember { ApiKeyManager(context) }` (same prefs backing store, functionally identical).

---

## PDF Export

File: `util/PdfExporter.kt`

- Format: A4 (595 × 842 pts), 40 pt margins.
- Page 1: header bar, group title + description, date range, stats row (total / expense count / member count), member balances table, minimum payments, category bar chart.
- Page 2+: expense detail — chronological, per-member split lines with percentage. For itemised expenses, a **BILL ITEMS sub-section** is rendered below the split lines showing `▸ item name → assigned names` and item amount.
- Block height calculation includes `ITEM_LBL (9f) + items.size * ITEM_H (11f)` for itemised expenses to ensure correct page-break decisions.
- **Share column** = sum of raw `ExpenseSplit.amount` for that member (from `memberShareMap`), NOT derived from `netBalance`.
- `export()` now takes `expenseItems: List<ExpenseItem> = emptyList()` — fetched fresh from DB by `GroupViewModel.exportPdf()` on `Dispatchers.IO`.
- `parseJsonIds(json: String)` — private helper for deserialising `assignedMemberIds` JSON array.

---

## Charts

File: `ui/components/Charts.kt`

### `InteractivePieChart` / `CategoryChart` / `DonutChart`
Donut chart on `Canvas`. Tapping a slice highlights it and shows amount/label/percentage in the hole. Legend is also tappable.

### `HorizontalBarChart`
Horizontal bars with member names and amounts. Bar width = `value / maxValue`.

---

## Key Utilities

### `formatCurrency(amount: Double): String`
Returns `"₹%.2f".format(amount)`. The canonical currency formatter — use this, not inline `₹` interpolation.

### `nameToAvatarColor(name: String): Long`
Deterministic colour from name hash. Returns a value from `avatarPalette`.

### `SampleData.insert(db)`
Seeds 3 groups (Goa Trip / Flat Expenses / Office Lunch) with members and EQUAL-split expenses. Called once on first launch if the DB is empty.

---

## Critical Invariants

1. **Multi-step DB writes must be transactional.** `addExpense`, `updateExpense`, `deleteExpense`, `addExpenseWithItems`, `updateExpenseWithItems` are all wrapped in `db.withTransaction {}`. Never add a multi-step DB write without a transaction.

2. **Split amounts are always ≥ ₹0.01 and rounded to 2 decimal places.** Enforced in ViewModel validation (`saveExpense` in both `AddExpenseViewModel` and `ScanBillViewModel`) and in the repository at `insertSplits` time.

3. **`calculateBalances` only credits/debits IDs present in the seeded `net` map.** The `if (id in net)` guards must not be changed to `?: 0.0` fallbacks — that creates phantom keys for deleted members.

4. **`paidById` must be non-empty before any DB write.** Validated in all `saveExpense()` paths.

5. **`recordSettlement` is idempotent-per-tap via `isSettling`.** Do not remove this guard.

6. **Members with expenses, splits, or settlements cannot be deleted.** `membersWithExpenses` in `GroupUiState` includes all four ID sets. Do not remove any from this union.

7. **The PDF "Share" column must read from `memberShareMap` (split amounts sum), not from `paid - netBalance`.** Net balance includes settlement adjustments.

8. **`GroupViewModel` combine collect must use `_uiState.update` with `expenseItemsByExpenseId` preserved.** The items coroutine and combine coroutine both write to `_uiState`. The combine collect uses `_uiState.update { current -> newState.copy(expenseItemsByExpenseId = current.expenseItemsByExpenseId) }` to avoid overwriting the items map. Do not change this to `_uiState.value = newState`.

9. **Item rounding must happen once per member, not per item.** `addExpenseWithItems` and `updateExpenseWithItems` accumulate raw (unrounded) per-member shares across all items, then round each member's total once at `insertSplits`. Rounding inside the accumulation loop causes sum(splits) < expense.amount drift proportional to item count.

10. **`ExpenseItem.id` is stable across edits.** `updateExpenseWithItems` uses `OnConflictStrategy.REPLACE` on `expense_items`; items are upserted by primary key. Only IDs in `deletedItemIds` are DELETEd. New items added in the same session use fresh UUIDs that are not in `originalItemIds` and require no DELETE.

---

## Build & Run

```bash
# Debug APK (output: app/build/outputs/apk/debug/FairShare-v1.0-debug.apk)
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug

# Run unit tests (none exist yet)
./gradlew test
```

**Prerequisites:** Android Studio Hedgehog or later, JDK 17, Android SDK 35.

**APK naming:** Configured in `app/build.gradle.kts` via `applicationVariants.all` to produce `FairShare-v{versionName}-{buildType}.apk`.

**Permissions:**
- `CAMERA` — for taking bill photos (declared `android:required="false"`, requested at runtime).
- `INTERNET` — for Gemini API calls.

---

## Dependencies (key)

| Library | Purpose |
|---|---|
| `androidx.room` (runtime + ktx + ksp compiler) | Local SQLite ORM |
| `androidx.navigation.compose` | Compose NavHost |
| `androidx.lifecycle.viewmodel.compose` | `viewModel()` composable |
| `androidx.lifecycle.runtime.compose` | `collectAsStateWithLifecycle` |
| `androidx.compose.material3` | Material Design 3 components |
| `androidx.material.icons.extended` | Full Material icon set |
| `androidx.compose.animation` | Slide/fade transitions |
| `kotlinx.coroutines.android` | Coroutines |
| `com.squareup.okhttp3:okhttp` (4.12.0) | HTTP client for Gemini API calls |

No third-party DI (Hilt/Koin). No image loading library (Coil). No analytics.

---

## Known Limitations / Future Work

- **No `@Update` for members** — `MemberDao` only supports insert (REPLACE) and delete. Renaming a member is not exposed in the UI; doing so would leave `Settlement.fromName`/`toName` stale (those are snapshots).
- **Settlement `fromId`/`toId` lack DB-level FK constraints** — enforced at application layer only. A future migration could add FKs with `RESTRICT`.
- **Duplicate SQL** — `getSplitsForGroup` and `getSplitsForGroupOnce` in `ExpenseSplitDao` share identical query bodies. Same applies to `getItemsForGroup`/`getItemsForGroupOnce` in `ExpenseItemDao`. Update both if the schema changes.
- **HomeViewModel loads all rows** — `getAllExpenses`, `getAllSplits`, `getAllSettlements` are full-table flows. Performance degrades at large scale. A SQL-level pending aggregation query would eliminate the in-memory fan-out.
- **`formatCurrency` inconsistency** — `HomeScreen` has a private `formatAmount` helper with compact (k/L) display; `formatCurrency` uses full precision. Intentionally different (home uses compact for space) but independent implementations.
- **No tests** — zero unit or instrumented tests. High-value targets: `calculateBalances`, `simplifyDebts`, `addExpenseWithItems` rounding, ViewModel validation paths.
- **`SampleData` bypasses transaction wrapping** — acceptable for seed-only code; do not use as a pattern for production writes.
- **`expense_splits.percentage` is 0.0 for EQUAL and AMOUNT splits** — only meaningful for PERCENTAGE. All display/export code recalculates percentage from `amount / expense.amount * 100`. Do not rely on the stored field for non-PERCENTAGE splits.
- **API key stored as plaintext** — `ApiKeyManager` uses unencrypted `SharedPreferences`. Acceptable for a personal-use app where users own their own Gemini keys; would need `EncryptedSharedPreferences` for a shared/enterprise deployment.
- **Duplicate UI components** — `BillItemRow` (ScanBillScreen) and `ExpenseItemRow` (AddExpenseScreen) are near-identical composables. `PaidBySelector` and `CategoryGrid`/`CategorySelector` also have separate implementations in each screen. Any visual change must be applied in both places.
- **`exportSchema = false`** — Room does not write compile-time schema JSON files. The migration chain is correct but loses the compile-time validator. Future migrations should consider enabling schema export.
