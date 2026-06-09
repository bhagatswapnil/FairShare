# FairShare — Complete Technical Reference

**App name:** FairShare  
**Package:** `com.splitexpense`  
**Version:** 1.0 (versionCode 1)  
**Platform:** Android (minSdk 26, targetSdk/compileSdk 35)  
**Language:** Kotlin  
**Build system:** Gradle KTS + KSP  

---

## Purpose

FairShare is a fully offline, local-only Android app for splitting shared expenses within groups (trips, flatmates, office lunches, etc.). Users create groups, add members, record expenses with three split modes (equal, percentage, custom amount), view live balances and minimum-payment debt suggestions, record settlements, and export a PDF report. There is no backend, no network, no auth.

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
```

**Key constraints:**
- One `AppDatabase` singleton (lazy, thread-safe).
- One `ExpenseRepository` singleton, held on `SplitExpenseApplication`.
- ViewModels receive the repository via a custom `ViewModelProvider.Factory` that reads from `SplitExpenseApplication`. No DI framework.
- All database calls happen on Room's IO dispatcher. `withTransaction` is used for any multi-step writes.

---

## Project Layout

```
app/src/main/java/com/splitexpense/
├── MainActivity.kt                       # single activity, sets FairShareTheme + AppNavigation
├── SplitExpenseApplication.kt            # Application; lazy singletons for db and repository
│
├── data/
│   ├── db/
│   │   ├── AppDatabase.kt                # Room DB, version 2, MIGRATION_1_2 adds settlements table
│   │   ├── entity/
│   │   │   ├── Group.kt
│   │   │   ├── Member.kt
│   │   │   ├── Expense.kt                # also defines Category and SplitType enums
│   │   │   ├── ExpenseSplit.kt
│   │   │   └── Settlement.kt
│   │   └── dao/
│   │       ├── GroupDao.kt
│   │       ├── MemberDao.kt
│   │       ├── ExpenseDao.kt             # includes GroupExpenseTotal projection
│   │       ├── ExpenseSplitDao.kt
│   │       └── SettlementDao.kt          # includes GroupSettledAmount projection
│   ├── model/
│   │   └── BalanceModels.kt              # MemberBalance, DebtTransaction data classes
│   └── repository/
│       └── ExpenseRepository.kt          # all business logic + DB access
│
└── ui/
    ├── navigation/
    │   └── AppNavigation.kt              # NavHost, Routes object, slide transitions
    ├── screens/
    │   ├── splash/SplashScreen.kt
    │   ├── home/
    │   │   ├── HomeScreen.kt
    │   │   └── HomeViewModel.kt
    │   ├── group/
    │   │   ├── GroupScreen.kt            # 4-tab pager: Expenses / Members / Balances / Charts
    │   │   └── GroupViewModel.kt
    │   └── expense/
    │       ├── AddExpenseScreen.kt       # shared for add and edit modes
    │       └── AddExpenseViewModel.kt
    ├── components/
    │   └── Charts.kt                     # InteractivePieChart, HorizontalBarChart
    └── theme/
        ├── Color.kt                      # graphite dark palette + neon accents
        ├── Theme.kt                      # FairShareTheme (always dark)
        └── Type.kt                       # AppTypography (monospace throughout)

util/
├── CategoryExtensions.kt                 # displayName(), icon(), color(), formatCurrency(), nameToAvatarColor()
├── PdfExporter.kt                        # Android PdfDocument A4 renderer
└── SampleData.kt                         # hardcoded seed data for first-launch demo
```

---

## Database Schema

**DB name:** `split_expense_db`  **Version:** 2

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
| paidById | TEXT | member id — **no FK constraint** to members (intentional: historical record survives member deletion, balance calc skips orphaned ids) |
| category | TEXT | Category enum name |
| splitType | TEXT | SplitType enum name |
| date | INTEGER | epoch ms |
| notes | TEXT | default "" |

### `expense_splits`
| Column | Type | Notes |
|---|---|---|
| id | TEXT PK | UUID |
| expenseId | TEXT | FK → expenses.id ON DELETE CASCADE |
| memberId | TEXT | FK → members.id ON DELETE CASCADE |
| amount | REAL | rounded to 2 decimal places on write |
| percentage | REAL | set for PERCENTAGE splits only; 0.0 for EQUAL/AMOUNT |

### `settlements`
| Column | Type | Notes |
|---|---|---|
| id | TEXT PK | UUID |
| groupId | TEXT | FK → groups.id ON DELETE CASCADE |
| fromId | TEXT | member who paid — **no FK constraint** to members |
| toId | TEXT | member who received — **no FK constraint** to members |
| fromName | TEXT | denormalized snapshot of name at record time |
| toName | TEXT | denormalized snapshot of name at record time |
| amount | REAL | |
| date | INTEGER | epoch ms |

**FK gap (known, intentional):** `Settlement.fromId`/`toId` have no FK to `members`. The UI prevents deleting members who appear in any expense, split, or settlement via the `membersWithExpenses` set in `GroupUiState`. `calculateBalances` also skips orphaned IDs defensively.

### Migrations
- **v1 → v2:** Creates the `settlements` table (previously absent).

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
```

---

## Repository — Key Business Logic

File: `data/repository/ExpenseRepository.kt`

### `addExpense` / `updateExpense` / `deleteExpense`
All multi-step DB writes are wrapped in `db.withTransaction {}` to prevent partial-write corruption on process death. Split amounts are rounded to 2 decimal places (`round(x * 100).toDouble() / 100.0`) before storing to prevent sub-paisa floating-point residuals.

### `calculateBalances(members, expenses, splits, settlements)`
Builds `net: MutableMap<String, Double>` seeded **only from the `members` list**. Each step uses `if (id in net)` guards — orphaned IDs from deleted members are silently skipped rather than creating phantom map keys.

```
net formula per member:
  + amount for each expense where paidById == member.id
  - amount for each split where memberId == member.id
  + amount for each settlement where fromId == member.id (paid out)
  - amount for each settlement where toId == member.id   (received)
```

Positive `netBalance` → member is owed money.  
Negative `netBalance` → member owes money.

### `simplifyDebts(balances)`
Greedy min-creditor / max-debtor algorithm. Terminates when the max creditor balance < 0.01 or min debtor balance > -0.01. Loop bound is `N²` (N = member count), which is always sufficient for N-1 actual transactions. The 0.01 threshold is an intentional epsilon — stored split amounts are always 2-decimal-place integers so floating-point residuals stay well below this threshold.

### `categorySpending(expenses, splits, memberId?)`
Returns `Map<Category, Double>`. When `memberId == null` sums expense amounts by category (group view). When `memberId` is set, sums split amounts for that member.

### `computePendingPerGroup(groups, allMembers, allExpenses, allSplits, allSettlements)`
Called from `HomeViewModel`. Filters splits and settlements per group in-memory (all data is already loaded from DB flows). Pending = sum of positive net balances after settlement adjustment.

---

## ViewModels

### `HomeViewModel`
- **State:** `HomeUiState` — groups list, per-group totals/member counts/pending amounts/settled amounts/member lists.
- **Flows:** 7 source flows combined in two nested `combine()` calls (Compose `combine` takes max 5 args). Batch 1: groups + totals + counts. Batch 2: all members + all expenses + all splits. Batch 3: group settled amounts + all settlements.
- **Computation:** `computePendingPerGroup` runs on every combined emission.
- **stateIn:** `SharingStarted.WhileSubscribed(5_000)` with `HomeUiState()` initial value.

### `GroupViewModel`
- **State:** `GroupUiState` — group entity, members, expenses, balances, simplified debts, category spending, total, settlements, `membersWithExpenses`, `splitCountByExpenseId`, loading/export flags.
- **`membersWithExpenses: Set<String>`** — union of: expense `paidById` values, split `memberId` values, settlement `fromId` and `toId` values. Members in this set show a lock icon in the UI (cannot be deleted).
- **`isSettling: Boolean`** — private VM-level flag (not in state) that gates `recordSettlement` against double-taps. Reset in `finally`.
- **Export flow:** `exportPdf` fetches fresh splits from DB inside `Dispatchers.IO`, then calls `PdfExporter`. Stale UI state is not used for the split data to avoid TOCTOU inconsistency.
- **`GroupUiState.isExporting`** — survives `combine` re-emissions because the combine lambda reads `_uiState.value.copy(...)` which preserves fields not explicitly overwritten.

### `AddExpenseViewModel`
- **State:** `AddExpenseUiState` — all form fields plus `isLoading`, `error`, `saved`.
- **Edit mode:** `expenseId != null`. Two coroutines launch in `init`: one collects the member flow (sets `paidById` only if currently empty), one loads the existing expense. Guards prevent the member flow from overwriting the pre-filled `paidById` in edit mode.
- **`originalExpense: Expense?`** — held to preserve `id`, `date`, `groupId` on update.
- **Validation order in `saveExpense()`:**
  1. `amount.toDoubleOrNull()` — must parse
  2. `totalAmount > 0.0` — must be positive
  3. `description.isNotBlank()`
  4. `selectedMemberIds.isNotEmpty()`
  5. `paidById.isNotEmpty()`
  6. Split-type-specific checks (percentage sums to 100; each AMOUNT split ≥ ₹0.01; each PERCENTAGE computed amount ≥ ₹0.01)
  7. `isEditing && originalExpense == null` guard (expense deleted externally while editing)

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

`AddExpenseScreen` is reused for add and edit — the `expenseId` param controls mode.

Transitions: horizontal slide (300 ms) for push/pop. Splash fades out.

---

## Screens

### SplashScreen
Animated logo (spring scale + fade), "FairShare" title, tagline "Split bills. Not friendships.", feature pills ("Fair splits", "Settle up", "PDF export"). Exits to Home after ~2 s. Background: subtle grid on `#0D0D0D`.

### HomeScreen
- Search bar (filters by name + description).
- Group list (`LazyColumn`) — each card shows: emoji, name, member count, total spent, pending amount (settlements-adjusted), settled %, member avatar strip.
- FAB-equivalent: `+` icon button → "Create Group" dialog.
- "Create Group" dialog: name, description (optional), emoji picker (12 options).

### GroupScreen
4-tab `HorizontalPager`:

**Tab 1 — Expenses**  
Chronological `LazyColumn` of expense cards. Each card: date, description, category chip, amount, payer name, split count. Long-press or swipe reveals delete. Tapping opens edit. FAB at bottom → Add Expense.

**Tab 2 — Members**  
Member list. Each member: avatar, name, email, net balance badge, delete button (locked if `id in membersWithExpenses`). "Add Member" CTA at top → dialog with name, email, auto-assigned avatar colour (`nameToAvatarColor`).

**Tab 3 — Balances**  
Summary stats bar (total, settled %, settled amount). Per-member balance rows (+/- with colour coding). "Simplified Debts" section: minimum payments list. Each debt item has a "Mark settled" button → confirmation dialog → `recordSettlement`. Previously recorded settlements listed below with undo.

**Tab 4 — Charts**  
`InteractivePieChart` (category spending). `HorizontalBarChart` (per-member spend). Member spend table.

App bar: group emoji + name, overflow menu (Edit group, Delete group, Export PDF).

### AddExpenseScreen
Form fields in a scrollable column:
- Amount field (large, monospace, decimal keyboard, regex `^\d*\.?\d{0,2}$`).
- Description (outlined text field).
- "Paid By" exposed dropdown (avatar + name).
- Category selector (icon + label chips, 7 categories in a 4-per-row grid).
- Split type toggle (Equal / Percent / Amount).
- Member split section: checkboxes per member; for non-EQUAL types, inline number fields appear per selected member.
- Error banner (red, icon + message).
- Save button (disabled + spinner when `isLoading`).

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
AmberAccent  #FFB830   — secondary accent (unsettled indicator)
```

### Typography (`Type.kt`)
**All text uses `FontFamily.Monospace`** — this is the defining visual character of the app. No custom font is loaded; the system monospace face is used.

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

## PDF Export

File: `util/PdfExporter.kt`

- Format: A4 (595 × 842 pts), 40 pt margins.
- Page 1: header bar, group title + description, date range, stats row (total / expense count / member count), member balances table, minimum payments, category bar chart.
- Page 2+: expense detail — chronological, per-member split lines with percentage. New page auto-starts when block won't fit above `PH - 60f`.
- **Share column** = sum of raw `ExpenseSplit.amount` for that member (from `memberShareMap` pre-computed in `export()`), NOT derived from `netBalance` (which includes settlement adjustments).
- **Category bar widths** use the actual `grandTotal`; guarded with `if (grandTotal > 0.0)` inline to avoid division by zero without distorting percentages.
- File written to `context.cacheDir` as `FairShare_<GroupName>.pdf`. Served via `FileProvider` (authority = `${packageName}.provider`).
- Exported from `GroupViewModel.exportPdf()` on `Dispatchers.IO`. Splits are re-fetched from DB inside the IO block to avoid stale snapshot.

---

## Charts

File: `ui/components/Charts.kt`

### `InteractivePieChart` / `CategoryChart` / `DonutChart`
Donut chart drawn on `Canvas`. Tapping a slice highlights it (dims others, gap border around selected) and shows its amount/label/percentage in the hole. Tapping again deselects. Legend below is also tappable (same toggle). No translation — the chart stays centred.

### `HorizontalBarChart`
Simple horizontal bars with member names and amounts. Bar width = `value / maxValue`.

---

## Key Utilities

### `formatCurrency(amount: Double): String`
Returns `"₹%.2f".format(amount)`. The canonical currency formatter — use this, not inline `₹` interpolation (some older error strings in the VM do this, known inconsistency).

### `nameToAvatarColor(name: String): Long`
Deterministic colour from name hash. Returns a value from `avatarPalette`.

### `SampleData.insert(db)`
Seeds 3 groups (Goa Trip / Flat Expenses / Office Lunch) with members and EQUAL-split expenses. Called once on first launch if the DB is empty.

---

## Critical Invariants

1. **Multi-step DB writes must be transactional.** `addExpense`, `updateExpense`, `deleteExpense` are wrapped in `db.withTransaction {}`. Never add a multi-step DB write without a transaction.

2. **Split amounts are always ≥ ₹0.01 and rounded to 2 decimal places.** Enforced in `saveExpense()` validation and in the repository before storing.

3. **`calculateBalances` only credits/debits IDs present in the seeded `net` map.** Do not change the `?: 0.0` fallback pattern — it has been deliberately replaced with `if (id in net)` guards. The old pattern created phantom keys for deleted members.

4. **`paidById` must be non-empty before any DB write.** Validated in `saveExpense()`. The initial state is `""` and may remain so if the DB flow hasn't emitted yet.

5. **`recordSettlement` is idempotent-per-tap via `isSettling`.** Do not remove this guard — UUIDs on settlements are always distinct, so the DB-level `REPLACE` strategy provides no deduplication.

6. **Members with settlements cannot be deleted.** `membersWithExpenses` in `GroupUiState` includes `settlements.fromId` and `settlements.toId`. The lock icon in `MemberItem` reads this set. Do not remove settlements from this union.

7. **The PDF "Share" column must read from `memberShareMap` (split amounts sum), not from `paid - netBalance`.** The net balance includes settlement adjustments, which would corrupt the share figure.

8. **No network, no auth, no cloud.** The app is entirely local. All data lives in the Room SQLite database on-device.

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

No third-party DI (Hilt/Koin), no networking (Retrofit/OkHttp), no image loading (Coil), no analytics.

---

## Known Limitations / Future Work

- **No `@Update` for members** — `MemberDao` only supports insert (REPLACE) and delete. Renaming a member is not exposed in the UI; doing so would leave `Settlement.fromName`/`toName` stale (those are snapshots).
- **Settlement `fromId`/`toId` lack DB-level FK constraints** — enforced at the application layer only (UI gate + balance-calc skip). A future migration could add FKs with `RESTRICT`, requiring the same UI gate.
- **Duplicate SQL** — `getSplitsForGroup` and `getSplitsForGroupOnce` in `ExpenseSplitDao` share identical query bodies. Update both if the schema changes.
- **HomeViewModel loads all rows** — `getAllExpenses`, `getAllSplits`, `getAllSettlements` are full-table flows. Performance degrades at large scale (hundreds of expenses across many groups). A SQL-level pending aggregation query would eliminate the in-memory fan-out.
- **`formatCurrency` inconsistency** — `HomeScreen` has a private `formatAmount` helper with compact (k/L) display; `formatCurrency` uses full precision. These are intentionally different (home uses compact for space reasons) but are independent implementations.
- **No tests** — zero unit or instrumented tests exist. The balance calculation logic (`calculateBalances`, `simplifyDebts`) and ViewModel validation paths are high-value targets for unit tests.
- **`SampleData` bypasses transaction wrapping** — it writes expenses and splits in separate calls. This is acceptable for seed-only code but should not be used as a pattern for production writes.
- **`expense_splits.percentage` is 0.0 for EQUAL and AMOUNT splits** — only meaningful for PERCENTAGE splits. `PdfExporter` and all display code recalculate percentage from `amount / expense.amount * 100` rather than trusting the stored field. Do not rely on the stored `percentage` column for non-PERCENTAGE splits.
