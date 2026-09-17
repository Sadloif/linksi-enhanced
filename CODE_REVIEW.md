# CODE_REVIEW.md

Mandatory full code review of the Linksi baseline (specification sections 6 and 7).

- **Review date**: 2026-10-09
- **Reviewed revision**: `0f4af65eb4fd87fcc77b79717071f975f7ccd122` — *"Fix: Fixed Import from browser"*
- **Repository**: `https://github.com/AsukaAzure/Linksi` (public), cloned to `E:\Deepseek\Linksi`
- **Branch**: `master` (default branch), 115 commits, tags `v1.0` … `v3.1.1`
- **Method**: static reading of every source file, plus `git` history archaeology. No Android SDK
  and no device were available, so **nothing in this review is runtime-verified**. Every claim
  carries a `file:line` reference; anything that could not be established from the source is
  marked `Unknown`.
- **Purpose**: establish the baseline that the enhanced version must preserve, and identify the
  defects that the enhancement work must not repeat.

---

## 1. Project identity (§6.1)

All values read directly from `app/build.gradle`, not guessed.

| Property | Value | Source |
|---|---|---|
| `applicationId` | `com.linksi.app` | `app/build.gradle:15` |
| `namespace` | `com.linksi.app` | `app/build.gradle:11` |
| `versionCode` | `20` | `app/build.gradle:18` |
| `versionName` | `3.1.1` | `app/build.gradle:19` |
| `minSdk` | `26` (Android 8.0) | `app/build.gradle:16` |
| `compileSdk` | `34` | `app/build.gradle:12` |
| `targetSdk` | `34` | `app/build.gradle:17` |
| Java / Kotlin target | `17` | `app/build.gradle:49-55` |
| `testInstrumentationRunner` | `androidx.test.runner.AndroidJUnitRunner` | `app/build.gradle:21` |

Build toolchain:

| Component | Version | Source |
|---|---|---|
| Android Gradle Plugin | 8.13.2 | `build.gradle:3-4` |
| Kotlin / Compose compiler plugin | 2.0.21 | `build.gradle:5-6` |
| KSP | 2.0.21-1.0.25 | `build.gradle:7` |
| Hilt | 2.52 | `build.gradle:8`, `app/build.gradle:98-99` |
| Gradle wrapper | 8.13 | `gradle/wrapper/gradle-wrapper.properties:3` |
| Compose BOM | 2024.10.00 | `app/build.gradle:76` |
| Room | 2.6.1 | `app/build.gradle:92-95` |

Build types (`app/build.gradle:36-46`):

- `release`: `minifyEnabled true`, ProGuard, signed with `signingConfigs.release`.
- `debug`: **already carries `applicationIdSuffix ".debug"`**, so the existing debug build
  installs side by side with a release build. The testing plan's section 3 requirement is
  therefore partly satisfied by the baseline already.

`gradle.properties`: `-Xmx2048m`, `android.useAndroidX=true`, `android.nonTransitiveRClass=true`,
`android.defaults.buildfeatures.buildconfig=true`.
`settings.gradle`: `google()`, `mavenCentral()`, `gradlePluginPortal()`,
`RepositoriesMode.FAIL_ON_PROJECT_REPOS`, and the `foojay-resolver-convention` plugin
(`settings.gradle:9`) which can auto-provision a JDK.

### Version/tag inconsistency (record this)

- `v3.1.0` and `v3.1.1` **both point at the same commit** `f6b33dea…`.
- `HEAD` (`0f4af65`) is **two commits past** that tag: `f59fdfd` *"Fix: Snackbar message"`,
  `0f4af65` *"Fix: Fixed Import from browser"*.
- `versionName` is already `3.1.1` and `versionCode` already `20` at `HEAD`.

So the baseline is "version 3.1.1 plus two unreleased commits". The enhanced version must take a
`versionCode` **greater than 20** (specification section 38).

---

## 2. Architecture (§6.2)

### 2.1 Application class

`LinksApplication.kt:17-55` — `@HiltAndroidApp`, implements `Configuration.Provider` to inject
`HiltWorkerFactory` into WorkManager (`:18-26`), schedules `BinCleanupWorker` on every process
start (`:31`), and installs a global Coil `ImageLoader` with a 30%-of-heap memory cache and a
150 MB disk cache in `cacheDir/image_cache` (`:34-53`), with `respectCacheHeaders(false)` (`:49`).

### 2.2 Activities

| Activity | Role | Evidence |
|---|---|---|
| `MainActivity` | Single-activity Compose host: splash gating, theme/locale/`FLAG_SECURE`, lock gate, onboarding gate, import progress overlay, update check | `MainActivity.kt:63-220`, `:242-352` |
| `ui.screens.ShareReceiverActivity` | Share-target bottom sheet; a **second** `AppCompatActivity` with its own `HomeViewModel`; `excludeFromRecents`, transparent theme; calls `finish()` after save/dismiss | `AndroidManifest.xml:39-57`, `ShareReceiverActivity.kt:51-77` |

`MainActivity` never reads its own `intent` (no `getIntent()` call), so a share into Linksi never
navigates to or notifies the main list. The two activities are fully decoupled.

### 2.3 Compose screens

Compose is the entire UI. Screens (all under `ui/screens/`): `HomeScreen`, `FolderScreen`
(containing `FoldersScreen`, `FolderListScreen`, `FolderDetailScreen`), `SettingsScreen`,
`ThemeSettingsScreen`, `SecuritySettingsScreen`, `AiSettingsScreen`, `AiOrganizerScreen`,
`ImportExportScreen`, `TrashBinScreen`, `InAppBrowser`, `LockScreen`, `OnboardingScreen`,
`ShareReceiverActivity`, plus `TopBar`. Reusable components in `ui/components/`

### 2.4 Navigation

**There is no Jetpack Navigation.** A grep for `NavHost|rememberNavController|NavController|composable(|navArgument|deepLink|startDestination` over the whole module returns **zero matches**, even though `README.md:68` advertises "Navigation: Jetpack Compose Navigation".

Actual navigation:

- Top-level branch on `isLocked` then `onboardingComplete` (`MainActivity.kt:148-174`).
- Every "screen" is an overlay toggled by boolean state via `AnimatedVisibility`:
  settings `HomeScreen.kt:552-558`, folders `:560-566`, browser `:593-628`; settings sub-pages
  nested inside one composable at `SettingsScreen.kt:485-605`.
- Folder depth uses a hand-rolled back stack: `folderStack = remember { mutableStateListOf<Folder>() }` (`FolderScreen.kt:78`), breadcrumbs `:1052-1082`, pop `:99-107`.
- Back handling is 13 `BackHandler` call sites, ordering by registration: `HomeScreen.kt:126,129,132,139`; `FolderScreen.kt:99,1027`; `InAppBrowser.kt:72`; `SettingsScreen.kt:62,66,490,510,526,542,580,601`; `ImportExportScreen.kt:34`; `Dialogs.kt:278`.
- The only deep links are `ShareReceiverActivity`'s intent filters (`AndroidManifest.xml:45-56`).

**Consequence for the enhancement**: any new screen (quick panel, downloader, format picker) must
follow this overlay-state pattern or introduce navigation deliberately. Predictive back is not
enabled (`enableOnBackInvokedCallback` is absent from the manifest), so Android 16 will use the
legacy back path; see §5.

### 2.5 ViewModels

| ViewModel | Scope | Used by |
|---|---|---|
| `HomeViewModel` | Activity-scoped, **shared** | `HomeScreen`, `FolderScreen`, and `ShareReceiverActivity` (each Activity gets its own instance) |
| `SettingsViewModel` | `SettingsScreen` | all settings sub-pages |
| `AiOrganizerViewModel` | `AiOrganizerScreen` | AI organise flow |
| `TrashBinViewModel` | `TrashBinScreen` | bin operations |

`AddLinkSheet` is rendered only in `HomeScreen.kt:656-681`, yet `FolderScreen.kt:1102` sets
`showAddLinkDialog`; that works only because both screens resolve the same Activity-scoped
`HomeViewModel`. This is an undocumented coupling to preserve.

### 2.6 Repositories

`data/repository/LinkRepository.kt` — the single `@Singleton` repository (`:11-16`). It injects
`LinkDao`, `FolderDao`, `MetadataCacheDao` and exposes `Flow`-based reads plus suspend writes. It
maps entities to domain models (`:239-291`) and applies `normalizeUrl` on insert/update/lookup
(`:40,43,196,200`). There is no separate downloader/network repository.

### 2.7 Dependency injection

Hilt throughout. `di/AppModule.kt:16-47` is the only module: it builds the Room database
(`"linksi_db"`), registers all eleven migrations, and provides the three DAOs. WorkManager is
Hilt-injected via `HiltWorkerFactory` (`LinksApplication.kt:20-26`) with the default initializer
removed in the manifest (`AndroidManifest.xml:60-69`). No qualifiers, no `@Binds`, no test
modules.

### 2.8 Room database

`data/db/LinksDatabase.kt:9-13` — `@Database(entities = [LinkEntity, FolderEntity, MetadataCacheEntity], version = 12, exportSchema = false)`.
Database file name `"linksi_db"` (`di/AppModule.kt:23`).

`exportSchema = false` means there are **no schema JSON files**, so Room's migration testing
support cannot be used as-is. Any future migration work must either enable schema export or test
migrations manually (testing plan section 74).

Note the stray import `androidx.work.impl.Migration_1_2` (`LinksDatabase.kt:7`) — an internal
WorkManager class that is unused, because the companion object defines its own `MIGRATION_1_2`.
Harmless but it should be removed (Low).

### 2.9 Entities

| Entity | Table | Notes |
|---|---|---|
| `LinkEntity` | `links` | 19 columns; FK to `folders.id` with `onDelete = CASCADE`; indices on `folderId` and `url` (`Entities.kt:5-38`) |
| `FolderEntity` | `folders` | self-referencing FK `parentId` → `folders.id`, `CASCADE`; index on `parentId`; `isLocked` flag (`:40-61`) |
| `FolderWithCount` | — | projection: folder + `link_count` + `latest_images` (`:63-67`) |
| `MetadataCacheEntity` | `metadata_cache` | keyed by `url` (`:69-78`) |

`tags` is stored as a **comma-joined `TEXT` column** (`Entities.kt:34`), split on read
(`LinkRepository.kt:255`) and joined on write (`:277`). A tag containing a comma therefore cannot
round-trip. `Link.tags` is `List<String>` in the domain model (`Models.kt:23`).

### 2.10 DAOs

`data/db/Daos.kt` — `LinkDao` (`:6-123`), `FolderDao` (`:125-184`), `MetadataCacheDao` (`:186-199`).
Notable queries:

- Every list/search query filters the bin **and** folder locks in SQL:
  `WHERE l.inBin = 0 AND (:isFolderLockEnabled = 0 OR f.isLocked IS NULL OR f.isLocked = 0)`
  (`:12,26,34,43,58`). Folder locking is therefore enforced at the data layer, not the UI.
- `searchLinks` uses `LIKE '%'||:query||'%'` over url/title/description/tags/domain (`:39-53`);
  `note` is **not** searchable.
- `insertLink` uses `OnConflictStrategy.REPLACE` (`:64-65`), which can silently replace a row.
- Dedupe relies on `getLinkByUrl` doing an **exact string match** (`:103-104`).
- `getAllLinksSync()` (`:121-122`) loads the whole table into memory; used by `getFolderTree`
  (`LinkRepository.kt:166-187`) on every folder open.

### 2.11 Migrations

Eleven migrations `MIGRATION_1_2` … `MIGRATION_11_12` are defined in `LinksDatabase.kt:20-195` and
all registered in `di/AppModule.kt:24-36`. They are hand-written SQL `ALTER`/table-rebuild
migrations, not destructive fallbacks — good. `MIGRATION_10_11` correctly recreates the table and
copies every column (`:40-74`).

**`MIGRATION_11_12` is a problem** (`LinksDatabase.kt:20-39`):

```sql
CREATE INDEX IF NOT EXISTS index_links_url ON links(url)
UPDATE links SET url = LOWER(url)                              -- destroys case, irreversibly
UPDATE links SET url = SUBSTR(url, 1, LENGTH(url) - 1) WHERE url LIKE '%/'   -- strips trailing /
UPDATE links SET url = 'https://' || SUBSTR(url, 8) WHERE url LIKE 'http://%'  -- forces https
```

This has already shipped, so **every user who migrated from schema ≤ 11 has permanently lost the
case of every saved URL**, and any HTTP-only link now points at `https://`. The specification
forbids exactly this behaviour (sections 9.2 and 73.11). The enhanced version must not repeat it,
and cannot repair the data that is already damaged.

### 2.12 Settings storage

A single **unencrypted** DataStore: `preferencesDataStore(name = "linksi_settings")`
(`ui/screens/DataStoreExtensions.kt:11`). Keys (defaults at the read site):

- **Display/sort**: `folder_view_mode`, `folder_sort_option`, `folder_links_view_mode`,
  `home_view_mode`, `home_sort_option`, `show_quick_filters` (`:12-16,29`);
  `use_in_app_browser` is declared ad-hoc in `SettingsViewModel.kt:75`, default `true`.
- **Theme/language**: `app_language` (default system), `app_theme` (`"system"`), `app_amoled`
  (`false`), `app_dynamic_color` (**`true`**) (`:25-28`, `MainActivity.kt:69-85`).
- **AI**: `ai_enabled`, `ai_selected_model`, `ai_key_openai|anthropic|gemini|deepseek|grok`
  (**plaintext**), `discovered_ai_models`, `ai_last_session` (`:17-24`, `AiModelRegistry.kt:16`).
- **Security**: `security_lock_enabled` (`false`), `security_biometric_enabled` (`false`),
  `security_pin` (**plaintext**, `:34`), `security_lock_delay` (`0L`), `security_universal_lock`,
  `security_folder_lock_enabled`, `last_app_pause_time` (`0L`), `global_prevent_screenshot`
  (`:32-41`).
- **Misc**: `trash_bin_enabled`, `export_include_locked`, `skip_update_until`,
  `onboarding_complete` (`:39-42`, `OnboardingPreference.kt:9`).

There is no versioned settings schema and no migration path for preferences.

### 2.13 Networking

| Client | Where | Notes |
|---|---|---|
| Jsoup | `MetadataFetcher.kt:100-104,304-312,397-402` | all metadata scraping; per-call `timeout(5000/10000/15000)`; `ignoreHttpErrors(true)`, `followRedirects(true)` |
| `java.net.HttpURLConnection` | `AiOrganizerService.kt:212-247` | AI providers; `connectTimeout 30000`, `readTimeout 60000`; never `disconnect()`ed |
| Android `WebView` | `MetadataFetcher.kt:196-258` | last-resort metadata fallback, on the **main** thread, bounded by `withTimeoutOrNull(10000)` |
| OkHttp | **declared and imported but never instantiated** | `app/build.gradle:116`, `LinksApplication.kt:13` |
| Volley | **declared; only a dead import remains** | `app/build.gradle:94`, `ui/components/LinkCards.kt:50` |

No retries or backoff anywhere. No certificate pinning; `network_security_config.xml:2-8` trusts
system CAs only and `cleartextTrafficPermitted="false"` (with `usesCleartextTraffic="false"` in
`AndroidManifest.xml:21`) — cleartext correctly blocked. A **hard-coded third-party scraper**
`https://link-metadata-scraper.vercel.app/api/scrape` is used for social domains
(`MetadataFetcher.kt:30,392-402`) and every saved domain is disclosed to
`https://www.google.com/s2/favicons` (`:88,235,377,427`).

### 2.14 Metadata extraction

`MetadataFetcher.fetch()` (`MetadataFetcher.kt:58-89`) runs on `Dispatchers.IO` and tries, in
order: Reddit oEmbed/`.json` (`:63-70,96-194`), the hosted Vercel scraper for
`SOCIAL_MEDIA_DOMAINS` (`:36-56,72-73,392-435`), a local Jsoup scrape (`:75,302-385`), and finally
a `WebView` (`:79-84,196-258`). Results are cached in `metadata_cache` with a 7-day cleanup
(`LinkRepository.kt:233-236`). Bot-challenge titles are filtered by `BLOCKED_TITLE_PATTERN`
(`:53-56,317,417`). Failure returns a domain-only record with a Google favicon (`:86-89`).
The WebView instance is **never destroyed on the success path** (`:224-240`; only the timeout path
calls `stopLoading()`+`destroy()` at `:253-257`) — a leak.

### 2.15 Share receiver

`ShareReceiverActivity` handles `ACTION_SEND` (`text/plain`) and `ACTION_VIEW` (`http`/`https`)
(`AndroidManifest.xml:45-56`). It reads `Intent.EXTRA_TEXT` (`:94`) or `intent.dataString` (`:106`),
extracts with `Regex("https?://[^\\s]+")` and — critically — **falls back to the entire shared
text when no URL matches** (`:112-115`), which is then saved through `normalizeUrl`, producing
`https://<arbitrary text>`. The URL is passed to the ViewModel untrimmed and unvalidated
(`:644-654`). It performs **no app-lock check** even though app lock is a product feature, so it
can be launched over the lock screen with a `VIEW` intent.

### 2.16 Browser implementation

`ui/screens/InAppBrowser.kt` — a `WebView` sheet with `javaScriptEnabled = true` (`:253`),
progress/back/forward/reload, an internal-vs-external scheme router (`:281-335`) and `ACTION_VIEW`
hand-off (`:154,317,327`). The overlay animation and dismiss logic are **duplicated** in
`HomeScreen.kt:570-628` and `FolderScreen.kt:226-280`. Its `FLAG_SECURE` handling is inverted:
it clears the flag on dispose only when protection is *disabled* (`:61-70`) and never reacts to the
setting changing while the sheet is open.

### 2.17 File storage

- Room database in app-private storage (`di/AppModule.kt:23`).
- Coil disk cache in `cacheDir/image_cache`, 150 MB (`LinksApplication.kt:40-45`).
- Import/export exclusively through the **Storage Access Framework**
  (`contentResolver.openInputStream/openOutputStream`, `ImportExportManager.kt:74,136,251`,
  `SettingsViewModel.kt:248,267,286`) behind `ActivityResultContracts.CreateDocument`
  (`ImportExportScreen.kt:25-28`).
- **No `MediaStore`, no `getExternalFilesDir`, no downloads directory, no storage permissions.**
  The downloader module will introduce all of this from scratch.

### 2.18 Import/export

`utils/ImportExportManager.kt` (pure functions) + `utils/BackgroundImportManager.kt`
(orchestration). Formats: JSON (`:16-62` export, `:73-132` import), CSV (`:233-247`, `:250-298`,
hand-rolled RFC-4180-ish parser `:300-339`), Netscape bookmarks HTML out (`:342-382`) and in
(`:135-230`, with Chrome container-folder detection). JSON import validates `app == "Linksi"`
(`:78`); **no format validates URLs**; HTML export interpolates `${link.url}` and folder names
unescaped (`:356,365,377`); CSV export escapes quotes only (`:384`), leaving formula injection
open. Duplicate handling normalises then skips existing rows, restoring binned ones
(`BackgroundImportManager.kt:133-162`). The import coroutine scope is `SupervisorJob() +
Dispatchers.Main` and is **never cancelled** (`:34-40`). Export serialisation and
`openOutputStream().write()` run on the **main thread** (`SettingsViewModel.kt:239-294`).

### 2.19 WorkManager jobs

Exactly one: `worker/BinCleanupWorker.kt` — `@HiltWorker`, unique periodic work, 1-day interval,
`setRequiresBatteryNotLow(true)`, `ExistingPeriodicWorkPolicy.KEEP` (`:31-43`), calling
`repository.cleanBin(30)` to hard-delete binned links older than 30 days (`:20`,
`LinkRepository.kt:66-69`). Despite the comment in `app/build.gradle:121`, **no background
metadata fetching exists**.

### 2.20 Notifications

`utils/ReminderUtils.kt` defines the notification channel `linksi_reminders` (`:14`) and
`ReminderReceiver` (`:72-99`, correctly `exported="false"` in `AndroidManifest.xml:71-72`), posting
with `linkId.toInt()` as both request code and notification id (`:90-91`). Exact alarms are used
where permitted with a documented fallback (`:49-57`).

But the feature is **broken end to end**:

- `HomeViewModel.scheduleNotification`/`cancelNotification` are empty stubs (`HomeViewModel.kt:644-650`).
- `ReminderUtils.scheduleReminder`/`cancelReminder` (`:33,60`) are **never called** anywhere.
- Channel ids disagree: `"link_reminders"` (`HomeViewModel.kt:636`) vs `"linksi_reminders"` (`ReminderUtils.kt:14`).
- `POST_NOTIFICATIONS` is declared (`AndroidManifest.xml:8`) but **never requested at runtime**, so
  on Android 13+ nothing can be posted anyway.
- `RECEIVE_BOOT_COMPLETED` is declared (`:7`) but **no `BootReceiver` exists**, so alarms would be
  lost on reboot even if they were scheduled.
- `USE_EXACT_ALARM` (`:10`) is unused and is a Play-policy-restricted permission.

`README.md:48` and `:184` claim reminders work. They do not.

### 2.21 Security features

`utils/SecurityManager.kt` (66 lines) is the whole security implementation:

- **App lock**: gated on `security_lock_enabled`, a non-empty PIN, and `lastAppPauseTime`
  (`:49-65`); `lastPauseTime == 0L` locks (`:58`); a `Long.MAX_VALUE` sentinel from
  `SettingsViewModel.kt:364,385,397` is neutralised by `if (lastPauseTime > currentTime) return false`
  (`:61`), which makes the lock **clock-dependent** (moving the clock back keeps the app unlocked).
  `MainActivity.onPause` persists the pause time **asynchronously** (`:226-231`), so a fast
  background/foreground race can skip the delay.
- **PIN storage: plaintext.** `SECURITY_PIN = stringPreferencesKey("security_pin")`
  (`DataStoreExtensions.kt:34`), written verbatim (`SettingsViewModel.kt:382`), compared with `==`
  (`LockScreen.kt:43`). No hashing, salt, KDF, `EncryptedSharedPreferences` or Keystore use
  anywhere in the repo. `android:allowBackup="true"` with **no `dataExtractionRules` /
  `fullBackupContent`** (`AndroidManifest.xml:15`) puts the PIN and the AI API keys in scope for
  cloud/ADB backup.
- **Biometrics**: `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` (`SecurityManager.kt:15,43`), launched
  from `LockScreen.kt:54-64,195-208` and `Dialogs.kt:258-265`. `onAuthenticationFailed` is **not
  overridden** (`:28-38`), so there is no app-side attempt counter.
- **Folder lock**: flag per folder + a global `security_folder_lock_enabled`, enforced in SQL
  (`Daos.kt:12,26,34,43,58`) and re-authenticated before opening/picking
  (`FolderScreen.kt:113-115,152-158,176-184`, `Dialogs.kt:255-276`). This is the strongest part of
  the security model.
- **Screenshot protection**: global `FLAG_SECURE` only (`MainActivity.kt:105-111`). The per-link
  `Link.preventScreenshot` column/field exists and round-trips through DB and import/export
  (`Models.kt:26`, `Entities.kt:37`, `ImportExportManager.kt:56,126`) but **is never read by any
  UI code** — dead data.
- **Bypass**: `ShareReceiverActivity` is exported and consults no lock state, so
  `adb shell am start -a android.intent.action.VIEW -d <url>` (or any app) opens the save sheet
  over the lock and can write links.

### 2.22 URL normalization (the seam the URL cleaner must respect)

`normalizeUrl` lives at `utils/MetadataFetcher.kt:459-486`:

```kotlin
fun normalizeUrl(url: String): String {
    val trimmed = url.trim()
    if (trimmed.isBlank()) return ""
    var normalized = trimmed
    if (normalized.startsWith("http://")) {              // case sensitive scheme test
        normalized = "https://" + normalized.substring(7)
    } else if (!normalized.startsWith("https://")) {
        normalized = "https://$normalized"               // any schemeless input, incl. mailto:/ftp:
    }
    return try {
        val uri = java.net.URI(normalized).normalize()
        var result = uri.toString()
        if (result.endsWith("/")) { result = result.substring(0, result.length - 1) }
        result.lowercase()                               // <-- this IS the returned value
    } catch (e: Exception) {
        normalized.lowercase()                           // <-- and so is this
    }
}
```

**Verified behaviour:**

1. **It lowercases the whole URL.** The `try` block's last expression is `result.lowercase()`, and
   a Kotlin `try` expression evaluates to its last expression, so that lowercased string is what
   the function returns. The `catch` branch lowercases too. Therefore
   `https://example.com/File?id=AbC123` is stored as `https://example.com/file?id=abc123` on every
   save. (One automated review pass misread line 482 as a discarded expression; it is not
   discarded.) This is the defect the specification calls out in sections 9.2 and 73.11, and
   migration 11→12 applied the same damage in SQL.
2. `http://` is unconditionally upgraded to `https://`, and **any** schemeless string is prefixed
   with `https://` — including `mailto:`, `ftp:`, `file:` and arbitrary shared text, which is how
   the share-receiver fallback can persist garbage.
3. The scheme test is case sensitive, so `HTTP://example.com/a` becomes
   `https://HTTP://example.com/a` (and then usually fails to parse, returning that string as-is).
4. One trailing `/` is stripped from **any** path, so `/dir/` and `/dir` collapse to one identity
   and can dedupe distinct resources.
5. `URI.normalize()` resolves only `.`/`..` path segments; it does not touch case or encoding.

Call sites: `LinkRepository.kt:40,43` (insert/update), `:196,200` (dedupe lookup and fetch),
`HomeViewModel.kt:227` (`addLink`), `BackgroundImportManager.kt:134` (import), and inside
`MetadataFetcher.kt:59,440,449`. Dedupe (`isUrlAlreadySaved`) is an exact string comparison of
normalised values, so normalisation semantics and duplicate detection are coupled.

Also in this package: `extractDomain` (`:438-444`) and `isValidUrl` (`:446-457`), both pure JVM
helpers that call `normalizeUrl`.

### 2.23 Existing tests

**There are none.** `app/src/` contains only `main/`; there is no `test/` or `androidTest/`
directory, and `git ls-files` confirms no test file has ever been committed. `junit:junit:4.13.2`,
`androidx.test.ext:junit` and `espresso-core` are declared (`app/build.gradle:127-129`) but unused.
The CI workflow (`.github/workflows/android.yml`) runs `assembleRelease` only — no `test`, no
`lint`, and it is `workflow_dispatch`-only. `README.md:169-173` documents `./gradlew test`, which
currently executes zero tests.

The first unit tests in the project's history are the URL cleaner tests added in this phase.

### 2.24 Build configuration

- Wrapper committed (`gradlew`, `gradlew.bat`, `gradle/wrapper/*`); always use it
  (`gradlew.bat` on Windows). Gradle 8.13, AGP 8.13.2, Kotlin 2.0.21.
- `settings.gradle` uses `FAIL_ON_PROJECT_REPOS`, so all repositories must stay declared centrally.
- `local.properties` (SDK path) is gitignored (`.gitignore:17`) and absent, as expected.
- Only the `:app` module exists.

### 2.25 Signing configuration

`app/build.gradle:27-34`: a single `release` signing config read from **environment variables** —
`KEYSTORE_PATH` (default `"../keystroke.jks"` — note the typo, and the file does not exist),
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. No keystore is committed (`.gitignore:20-21`
excludes `*.jks`/`*.keystore`), and none was found in the working tree.

`.github/workflows/android.yml:26-35` decodes `secrets.RELEASE_KEYSTORE_BASE64` into
`app/release.jks` and passes the four values as env vars.

**Consequences for the enhancement (specification §36):**

- The original signing key is **not available** in this environment, and there is no evidence it is
  available at all. An in-place update of the official Linksi install is therefore **not possible**
  unless the owner of that key comes forward.
- Side-by-side installation is the safe path. The baseline debug build already uses the `.debug`
  suffix (`app/build.gradle:43`); the testing plan's suggested `.enhanced` suffix would be an
  addition.
- Any private release build needs a **new, backed-up keystore** whose identity must never change
  afterwards (`versionCode` must always increase).

---

## 3. Existing feature inventory (§6.3)

Status is verified from source. Nothing is runtime-tested.

| Feature | Status | Evidence / note |
|---|---|---|
| Link saving | **Implemented** | `AddLinkSheet.kt:636-653` → `HomeScreen.kt:665-679` → `HomeViewModel.kt:215-256` |
| Metadata retrieval | **Implemented** | `MetadataFetcher.kt:58-89`; debounce `AddLinkSheet.kt:136-174`; refresh `HomeViewModel.kt:354-391` |
| Share to Linksi | **Implemented** (flawed) | `AndroidManifest.xml:45-49`; regex fallback returns whole shared text `ShareReceiverActivity.kt:112-115` |
| Folders | **Implemented** | `FolderScreen.kt:73-293`, `Dialogs.kt:84-134` |
| Nested folders | **Implemented** | `Models.kt:38`, `HomeViewModel.kt:147-162`, `FolderScreen.kt:1308-1333`, recursive picker `Dialogs.kt:378-414` |
| Tags | **Implemented** (comma storage limits) | `LinkCards.kt:1960`, `HomeViewModel.kt:177-191`; tags normalised to lowercase/dashes at input `LinkCards.kt:2093` |
| Notes | **Implemented** | `LinkCards.kt:1671`, `HomeViewModel.setNote:487` |
| Favorites | **Implemented** | `LinkCards.kt:485-488,764-768`, `HomeViewModel.kt:309`, chip `HomeScreen.kt:775-783` |
| Unread/read status | **Partially implemented** | Auto mark-read on open (`HomeScreen.kt:478,520`) and an unread filter exist, but manual toggle is dead: `onMarkRead = { }` (`LinkCards.kt:376,836`) |
| Pinning | **Implemented** | max-5 guard `HomeViewModel.kt:458-468`; pinned-first sort `:538-548` |
| Search | **Implemented** | `HomeScreen.kt:386-422` → `Daos.kt:39-53`; `note` not searched; folder search is in-memory `FolderScreen.kt:1031-1041` |
| Filtering | **Partially implemented** | Only ALL/FAVORITES/UNREAD chips (`HomeScreen.kt:766-792`); `FilterOption.READ` and `HAS_REMINDER` (`Models.kt:53`) are unreachable; no tag/domain filters |
| Sorting | **Implemented** | `Dialogs.kt:154-203`, `HomeViewModel.kt:538-549` |
| Trash | **Partially implemented** | Soft delete + restore + empty exist; retention is only a hint string plus `BinCleanupWorker`; no bulk UI, no per-item age, no retention control. **Expiry hard-deletes and bypasses the bin** (`HomeViewModel.kt:510-518`) |
| Bulk actions | **Implemented** (limited) | Home: select-all, move, delete (`HomeScreen.kt:300-381`, `HomeViewModel.kt:551-597`); duplicated in `FolderScreen.kt:1167-1251`. No bulk tags/note/reminder/favourite/pin/read/share |
| Reminders | **Broken** | Empty stubs `HomeViewModel.kt:644-650`; `ReminderUtils` never called; channel mismatch; `POST_NOTIFICATIONS` never requested; no boot receiver |
| Expiration | **Partially implemented** | Picker `LinkCards.kt:1742-1930`, storage `HomeViewModel.setExpiry:493`; 60 s poll hard-deletes `:510-518`; no UI shows upcoming expiry |
| Import | **Implemented** | JSON + bookmarks HTML (`ImportExportScreen.kt:100-117`, `ImportExportManager.kt:73-230`); no URL validation |
| Export | **Implemented** | JSON/CSV/HTML (`ImportExportScreen.kt:60-96`); unescaped HTML, CSV formula injection, main-thread I/O |
| Built-in browser | **Implemented** | `InAppBrowser.kt`, toggle `SettingsScreen.kt:194-220` |
| AI categorization | **Implemented** (privacy-sensitive) | `AiOrganizerViewModel.kt:242-323`; uploads every link's title/URL/domain + folder taxonomy to the selected provider (`AiOrganizerService.kt:32-39`), including locked folders |
| App lock | **Implemented** (weak) | `MainActivity.kt:114-120,148-153,233-240`; plaintext PIN; bypassable via `ShareReceiverActivity` |
| Folder lock | **Implemented** | SQL-enforced (`Daos.kt`), auth at `FolderScreen.kt:113-184`, `Dialogs.kt:255-276` |
| Biometrics | **Implemented** | `SecurityManager.kt:12-47`, `LockScreen.kt:54-64`; no `onAuthenticationFailed` handler |
| Screenshot protection | **Partially implemented** | Global `FLAG_SECURE` only (`MainActivity.kt:105-111`); per-link flag exists but is never enforced |
| Theme | **Implemented** | `Theme.kt:70-102`, `ThemeSettingsScreen.kt` |
| Dark mode | **Implemented** | `Theme.kt:70-74` + AMOLED option |
| Grid/list layouts | **Implemented** | `HomeScreen.kt:705,903-909`; folders `FolderScreen.kt:473-614,948` |

---

## 4. Findings, ranked

### Critical

1. **Plaintext PIN in an unencrypted, backed-up DataStore.** `DataStoreExtensions.kt:34`,
   `SettingsViewModel.kt:382`, `LockScreen.kt:43`; `allowBackup="true"` with no backup rules
   (`AndroidManifest.xml:15`). Any app-lock guarantee is weaker than it appears.
2. **Whole-URL lowercasing on the save path, plus the already-shipped destructive migration.**
   `MetadataFetcher.kt:482,484`; `LinksDatabase.kt:27`. Irreversible for existing users; explicitly
   forbidden by the specification.

### High

3. **App lock bypassable through the exported `ShareReceiverActivity`** (`AndroidManifest.xml:39-57`,
   `ShareReceiverActivity.kt:50-77`).
4. **Reminders are non-functional** (empty stubs, unused scheduler, channel mismatch, no
   notification permission, no boot receiver) while the README advertises them.
5. **AI organiser uploads the complete URL inventory** (title, full URL with query string, domain,
   folder taxonomy) to third-party providers, including links inside locked folders, with no
   disclosure (`AiOrganizerService.kt:32-39`, `AiOrganizerViewModel.kt:258-264`,
   `LinkRepository.kt:18`).
6. **Gemini API keys are placed in the request URL query string** (`AiOrganizerService.kt:127,292`),
   and all five provider keys are stored in plaintext (`DataStoreExtensions.kt:20-24`).
7. **Unconditional `http://`→`https://` rewrite, schemeless prefixing of arbitrary text, and
   trailing-slash stripping** in `normalizeUrl` (`MetadataFetcher.kt:459-486`), combined with the
   share-receiver whole-text fallback (`ShareReceiverActivity.kt:112-115`), can persist a
   non-URL as a saved link.

### Medium

8. **Main-thread WebView metadata fallback** with 10 s timeouts, run 6-8 at a time during bulk
   import (`MetadataFetcher.kt:196-258,281`, `BackgroundImportManager.kt:175`) — ANR/jank risk —
   plus a WebView leak on the success path.
9. **Main-thread export** of the whole library (`SettingsViewModel.kt:239-294`).
10. **Never-cancelled import scope** and a progress denominator that changes mid-flight
    (`BackgroundImportManager.kt:34-40,161-169`).
11. **Expiry hard-deletes, bypassing the bin** (`HomeViewModel.kt:510-518`).
12. **Import does not validate URLs**; HTML export does not escape; CSV export allows formula
    injection (`ImportExportManager.kt:180-201,280-294,356,365,377,384`).
13. **URLs (including query strings) written to logcat** on every metadata failure
    (`MetadataFetcher.kt:318,382,405,412,418,432`).
14. **Full URLs of social links disclosed to a hard-coded third-party scraper** and every domain to
    Google's favicon service (`MetadataFetcher.kt:30,88,235,377,392-402,427`).
15. **No certificate pinning** for any endpoint (`network_security_config.xml:4-6`).
16. **Tags stored as a comma-joined TEXT column** (`Entities.kt:34`) — commas cannot round-trip.
17. **`getAllLinksSync()` loads the whole table** for every folder-tree build
    (`Daos.kt:121-122`, `LinkRepository.kt:166-187`).

### Low

18. Declared-but-unused permission `ACCESS_NETWORK_STATE` (`AndroidManifest.xml:6`); policy-restricted
    and unused `USE_EXACT_ALARM` (`:10`); declared-but-inert `RECEIVE_BOOT_COMPLETED` (`:7`).
19. Dead/duplicated code: `BulkActionBar` (`HomeScreen.kt:980-1034`), `StatsBar` (`:708-734`),
    unused `TrashBinViewModel` multi-select API (`TrashBinViewModel.kt:70-116`), unused
    `HomeViewModel` members (`:36,38,39,474,530,536,610`), duplicated browser overlay in
    `HomeScreen`/`FolderScreen`, triplicated snackbar routing, and an update check implemented
    twice (`MainActivity.kt:242-352`, `SettingsViewModel.kt:168-211`).
20. Unused dependencies/imports: OkHttp (`app/build.gradle:116`, `LinksApplication.kt:13`), Volley
    (`app/build.gradle:94`, `LinkCards.kt:50`), `androidx.work.impl.Migration_1_2`
    (`LinksDatabase.kt:7`), `SecureFlagPolicy` (`AddLinkSheet.kt:53`).
21. `exportSchema = false` (`LinksDatabase.kt:12`) blocks Room migration testing.
22. Unchecked `as Activity` / `as FragmentActivity` casts (`Theme.kt:107`, `LockScreen.kt:57,199`).
23. `HomeScreen.kt:99-106` auto-scrolls to top whenever the link count changes.
24. Per-link `preventScreenshot` is dead data; manual read/unread toggle is dead;
    `FilterOption.READ`/`HAS_REMINDER` are unreachable; grid mode silently drops "delete tag
    globally" (`HomeScreen.kt:897` vs `LinkCards.kt:526-549`).
25. A declared dependency comment claims WorkManager is "for background metadata fetch"
    (`app/build.gradle:121`); no such job exists.

### Contradictions between the README and the code

`README.md` advertises Jetpack Compose Navigation (`:68`), working reminders (`:48,184`),
"encryption" of locked folders (`strings.xml:259`), and `./gradlew test` (`:169-173`). None of
those are true. The enhancement must not inherit these claims: `README_ENHANCED.md` has to
describe actual behaviour.

---

## 5. Android 15/16 readiness (§6.2 item 25, specification sections 27-28)

- **Edge to edge**: `enableEdgeToEdge()` is called **only** in `MainActivity.kt:61`.
  `ShareReceiverActivity` does not call it but does use `navigationBarsPadding()`
  (`:208`). `themes.xml:5-6` makes both bars transparent.
- **Insets**: 8 padding call sites in total; `statusBarsPadding` only in `LockScreen.kt:89`,
  `imePadding` only in `AddLinkSheet.kt:187` and `LinkCards.kt:1695`. `OnboardingScreen` applies
  **no** insets at all (`OnboardingScreen.kt:95-113,182`), so its bottom controls can sit under the
  gesture bar.
- **Predictive back**: not enabled (`enableOnBackInvokedCallback` absent). All back handling is
  `BackHandler`. Adding the flag later will change the effective ordering of the 13 handlers.
- **Deprecated at targetSdk 34+**: `window.statusBarColor` / `navigationBarColor`
  (`Theme.kt:108-109`) — deprecated in API 35 and no-ops once edge-to-edge is enforced.
  `LocalClipboardManager` (`AddLinkSheet.kt:82`) is deprecated in favour of `LocalClipboard`.
- **targetSdk is 34, not 36.** The specification's Android 16 target (API 36) requires a
  deliberate `compileSdk`/`targetSdk` bump, which should be an isolated change with its own test
  pass, not bundled with feature work.
- **16 KB page size**: no native libraries exist today, so this is currently a non-issue; it
  becomes relevant when a media extractor with native code is added (specification section 31).

---

## 6. Baseline build (§6.4)

### Status: **BLOCKED in this environment — no APK was built**

The baseline APK could not be produced here, and this is recorded rather than papered over:

| Requirement | Status on this machine |
|---|---|
| JDK 17+ | **Available**: JetBrains Runtime 21.0.4 with `javac` at `C:\Program Files\JetBrains\PyCharm Community Edition 2024.2.4\jbr` |
| Gradle | **Available** via the committed wrapper (8.13 verified running on JBR 21) |
| Android SDK (platform 34, build-tools) | **Missing** — no `ANDROID_HOME`, no `ANDROID_SDK_ROOT`, no `%LOCALAPPDATA%\Android\Sdk` |
| `local.properties` | Absent (gitignored) |
| Release keystore | **Missing** — `app/build.gradle:29` points at `../keystroke.jks`, which does not exist, and no `*.jks` is present |
| Device / emulator | None; `adb` not installed |

The Android Gradle plugin cannot even be configured without an SDK, so `test`, `lint` and
`assembleDebug` all fail before compilation.

### Commands to run once an SDK is installed

```text
# Windows (from the repository root)
set ANDROID_HOME=C:\path\to\android-sdk
gradlew.bat clean
gradlew.bat test
gradlew.bat lint
gradlew.bat assembleDebug
```

To archive the baseline, additionally record `Get-FileHash app\build\outputs\apk\debug\app-debug.apk -Algorithm SHA256`,
the APK size, the commit (`0f4af65`) and the ABI list. The specification's baseline tag has been
created locally as `baseline-linksi-original` at that commit.

A release baseline cannot be built at all without the original signing key (see §2.25).

### What was verified instead

Because the URL handling code is pure JVM logic, the phase-1 URL cleaner and URL normalizer were
compiled and executed for real on JBR 21 using a standalone Gradle JVM project
(`E:\Deepseek\linksi-urlcleaner-verify`, driven by `E:\Deepseek\run-urlcleaner-tests.ps1`):
**82 tests, 0 failures**. See `TEST_REPORT.md`. This proves the new logic and the `normalizeUrl`
fix; it does **not** prove that the Android app still assembles.

---

## 7. Integration points for the new modules

Recorded now so the next phase does not have to rediscover them.

### 7.1 Where a URL cleaner must hook in

| Path | Raw read | Choke point for cleaning |
|---|---|---|
| Manual add | `AddLinkSheet.kt:232-235` (only `.trim()` at `:640`) | `HomeViewModel.addLink` → `HomeViewModel.kt:227` → `LinkRepository.insertLink` (`:39-40`) |
| Share sheet | `ShareReceiverActivity.kt:94,106` (regex at `:112-115`) | same `addLink`/repository path |
| Edit link | **no URL field exists** (`LinkEditSheet.kt:41-45,230-299`) | — |
| Import | `BackgroundImportManager.kt:134` | repository or import loop |
| Metadata fetch | `MetadataFetcher.kt:59` | `normalizeUrl` itself |

**Recommendation**: keep `normalizeUrl` as the single storage-identity function and apply
`UrlCleaner` as a separate, opt-in step immediately before it, so that cleaning is visible in the
UI and reversible in behaviour. Two viable seams:

1. **ViewModel level** (`HomeViewModel.addLink`) — allows a preview/diff in the quick panel, but
   misses the import path unless the import loop is also updated.
2. **Repository level** (`LinkRepository.insertLink`/`updateLink`) — guarantees every write,
   including import, but hides the change from the UI.

Doing both (clean for display, clean again at the write boundary) is the safest and matches the
specification's "failures must not lose the link" rule, since `UrlCleaner.cleanOrSelf` returns the
original on any parse failure.

### 7.2 Original-URL preservation (specification §9.6)

The `links` table has no `cleanedUrl`/`originalUrl` column, and the specification says not to
change the database if a simpler safe approach exists. Options, in order of preference:

1. Store the **cleaned** URL in `url` and put the original in the existing `note` field — rejected:
   it would overwrite user notes.
2. Store the cleaned URL in `url` and record the original only in the metadata cache or a log —
   loses it.
3. Add a nullable `originalUrl TEXT` column in a **new, tested, non-destructive migration**
   (13→14). `exportSchema = false` means the migration must be hand-verified (testing plan §74).

Because `MIGRATION_11_12` is a cautionary example, option 3 must be accompanied by a real
migration test before it ships.

### 7.3 Constraints discovered

- The settings surface is one unencrypted DataStore with no schema version; new settings keys are
  additive and safe, but a "clean URLs automatically" default of **enabled** (specification §9.5)
  changes save behaviour for existing users and must be explicit in release notes.
- Any new settings UI must be reached through the existing `SettingsScreen` overlay pattern
  (`SettingsScreen.kt:485-605`) or the navigation model must be replaced deliberately.
- `ShareReceiverActivity` is a separate Activity with its own `HomeViewModel`; any state the quick
  panel needs (folder list, tags) must be reachable from that ViewModel or from the repository.
- Base is 100% Kotlin/Compose already; adding a downloader must not introduce a startup dependency
  on it (specification §7.9).

---

## 8. Preservation rules for the enhancement (derived from §7)

1. Keep `applicationId = com.linksi.app` for any build intended to replace the existing install
   (`app/build.gradle:15`).
2. Never add a destructive migration. Migration 12 is the precedent to avoid, and database version
   is now **12**.
3. Do not change the meaning of `url` (dedupe depends on it) without accepting a duplicate
   regression for rows already lowercased by migration 11→12.
4. Do not touch the SQL folder-lock predicate in `Daos.kt` — it is the only working access control.
5. Keep `ShareReceiverActivity`'s two intent filters intact (`AndroidManifest.xml:45-56`).
6. Keep `binIn`/`deletedAt`, `isPinned`, `isFavorite`, `isRead`, `reminderAt`, `expiresAt`, `tags`,
   `note` semantics.
7. Any new optional module must be inert at startup: `LinksApplication.onCreate` currently only
   schedules the bin worker and configures Coil — new services, overlay or downloader code must not
   be added there unconditionally.
8. `README_ENHANCED.md` must not repeat the baseline README's inaccurate feature claims.
