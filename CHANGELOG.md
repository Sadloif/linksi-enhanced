# Changelog

All notable changes to **Linksi Enhanced** — this private fork of
[Linksi](https://github.com/AsukaAzure/Linksi) by AsukaAzure — are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project uses
the versioning conventions described in [BUILD_AND_RELEASE.md](BUILD_AND_RELEASE.md) §5 (never
decrease `versionCode`; the baseline is `versionCode` 20 / `versionName` 3.1.1).

**Nothing in the `Unreleased` section has been released.** The build environment is now complete: the
app assembles, `:app:testDebugUnitTest` runs **378 tests with 0 failures**, `:app:lintDebug` passes
with **0 errors**, and a signed release APK is produced from `enhanced/integration`. See
[TEST_REPORT.md](TEST_REPORT.md) §9 for the evidence and §9.5 for what is still untested.

---

## [Unreleased]

Nothing yet. Everything below shipped in `3.1.1-enhanced.1`.

---

## [3.1.1-enhanced.1] - 2026-10-09

First private enhanced release. Private use only: the licence question in
[LICENSE_REVIEW.md](LICENSE_REVIEW.md) still blocks redistribution.

**Release artifact**

```text
apk        : LinksiEnhanced_3.1.1-enhanced.1_universal.apk
size       : 5,464,184 bytes (5.21 MB)
sha256     : 415994017A84CD296C6CB562B2DF95663ECC76C6D0171F0C2197BC0FCF7DA274
versionCode: 21
versionName: 3.1.1-enhanced.1
applicationId: com.linksi.app   (unchanged, so a future build can update it in place)
signed with: CN=Linksi Enhanced (private), 4096-bit RSA, APK Signature Scheme v2
```

**Verified on an Android 16 (API 36) x86_64 emulator**: the release APK installs, launches (no
crash, `MainActivity` resumed) and renders; the debug build passes 4 on-device flow tests and its
database contains only cleaned URLs. Full evidence in [TEST_REPORT.md](TEST_REPORT.md) §10.

### Fixed

- **A spurious "Update Available" prompt in the private build.** The release APK told the user that
  upstream `v3.1.1` was newer than the installed `v3.1.1-enhanced.1`. `MainActivity.isNewerVersion`
  split the version on `.` and mapped `1-enhanced` to `0`, so the installed build looked *older*
  than the release it was built from. The identical check in `SettingsViewModel` used `toInt()` and
  threw, so the two screens silently disagreed. Both now compare only the leading numeric portion.
  Found by installing and running the release APK, not by any test.

### Added

Everything below is private fork work **on top of** upstream 3.1.1.

### Added

- **Baseline code review** — `CODE_REVIEW.md` (704 lines, 2026-10-09). A full static review of
  upstream commit `0f4af65` (`Fix: Fixed Import from browser`): project identity, architecture,
  feature inventory with per-feature status, 25 ranked findings across Critical/High/Medium/Low, an
  Android 15/16 readiness pass, integration points for the new modules, and preservation rules for the
  enhancement. The baseline was tagged `baseline-linksi-original` at that commit.
- **Licence review** — `LICENSE_REVIEW.md` (174 lines, 2026-10-09). Records that upstream *declares*
  MIT in `README.md` but that **no `LICENSE` file has ever existed anywhere in its git history**, and
  answers the eleven required determinations. Consequence: **private development is unblocked;
  redistribution is blocked pending confirmation of the licence text.**
- **Dependency research summary** — `DEPENDENCY_REVIEW.md`. Per-dependency records (name, version,
  repository, licence, purpose, native code, ABI, APK size impact, Android compatibility, maintenance
  status, reason selected, alternative considered) for the existing dependency set and for the
  candidate media/download dependencies, plus the forbidden list, the build constraints that follow
  from Media3 and WorkManager, and the verification gaps that remain.
- **Build and release guide** — `BUILD_AND_RELEASE.md`. Prerequisites, SDK components, Windows build
  commands, output paths, environment-driven release signing, SHA256 generation, versioning rules,
  APK naming, in-place-update rules and the known environment gotchas.
- **Upstream update guide** — `UPSTREAM_UPDATE_GUIDE.md`. The review-first workflow for integrating
  future upstream Linksi changes, including the ten-point review report, cherry-pick vs merge,
  conflict resolution and the database-version collision trap.
- **CI workflow** — `.github/workflows/android-build.yml`. Checkout → JDK 17 (temurin) → Gradle with
  caching → `chmod +x gradlew` → unit tests → lint → debug APK → signed release when the signing
  secrets exist and an unsigned release when they do not → APK, test-report and lint-report uploads.
  The upstream workflow, `.github/workflows/android.yml`, is left untouched.
- **Deterministic URL cleaner** — `app/src/main/java/com/linksi/app/utils/UrlCleaner.kt` (302 lines).
  Removes a central registry of tracking parameters (`utm_*`, `fbclid`, `gclid`, `dclid`, `msclkid`,
  `igshid`, `mc_cid`, `mc_eid`, `yclid`, `_ga`, `_gl`, three Facebook parameters) and the Facebook
  Reel case, removes exactly one trailing slash, strips empty query segments, and never touches
  unknown/meaningful parameters. Percent-encoding, international domains, fragments and parameter
  order are preserved; failures never mutate the input (`cleanOrSelf` returns the original,
  `cleanOrNull` reports the reason). Classifies `MISSING_SCHEME`, `UNSUPPORTED_SCHEME`, `MALFORMED`
  and `INVALID_HOST` separately.
- **URL cleaner test suite** — `app/src/test/java/com/linksi/app/utils/UrlCleanerTest.kt` (60 tests)
  and `UrlNormalizerTest.kt` (22 tests). **82 tests, 0 failures, 0 errors, executed on a real JVM**
  (see *Verified* below).
- **Standalone JVM verification harness** — `tools/run-urlcleaner-tests.ps1` (151 lines). Builds a
  throwaway Gradle JVM project, copies the *verbatim* sources and tests into it, forces re-execution
  (`cleanTest`) and parses the JUnit XML, so a skipped run cannot be mistaken for a pass.
- **"Clean URLs when saving" setting** — the `auto_clean_urls` DataStore preference
  (`DataStoreExtensions.kt:45`), its toggle in the settings UI
  (`ThemeSettingsScreen.kt`), and the save-path hook in `HomeViewModel.addLink`
  (`HomeViewModel.kt:233`). The cleaner is applied immediately before `normalizeUrl`, so cleaning and
  storage identity stay separate and any parse failure falls back to the original URL.
- **Enhanced module contracts** — Android-free contracts, merged into `enhanced/integration` and
  covered by 278 of their own unit tests:
  - `enhanced/capability/RuntimeCapabilities.kt` — `RuntimeCapabilities`, `CapabilityReport`,
    `Capability`, `CapabilityStatus`, `CapabilityNames`: what the device can actually do (SDK level,
    ABIs, notification/overlay permission, accessibility, server-resolver configuration) and a
    report that distinguishes available / needs-permission / unavailable.
  - `enhanced/media/MediaSource.kt` — the `MediaSource` enum and `MediaSourceDetector`
    (host/URL classification, direct-file detection, primary targets).
  - `enhanced/media/MediaFormat.kt` — `MediaFormat`, `MediaInfo`, `MediaExtractionResult`,
    `MediaError`: the format list, direct URLs, and the `requiresMuxing` flag that tells the download
    stage whether separate video/audio streams must be combined.
  - `enhanced/media/MediaExtractor.kt` — the app-owned `MediaExtractor` interface
    (`supports`/`isAvailable`/`analyze`) and `ExtractorRegistry`, which orders candidates by priority
    and skips extractors that are unavailable on this device — the seam required by the
    specification, and the place where the ABI/init fallback lives.
  - `enhanced/download/DownloadModels.kt` — `DownloadDestination`, `DownloadRequest`,
    `DownloadState` (sealed: queued/downloading/completed/failed/cancelled) and `DownloadFormatting`
    helpers.
  - `enhanced/download/DownloadEngine.kt` — the `DownloadEngine` interface (enqueue, cancel, retry,
    dismiss, observe) plus active-work helpers.
  - `enhanced/download/FilenameSanitizer.kt` — filesystem-safe filenames, uniqueness handling,
    MIME-to-extension mapping and a `isSafe` predicate.
  - `enhanced/detect/UrlTextExtractor.kt` — extracts the first/all HTTP(S) URLs from arbitrary shared
    text, strips trailing punctuation, and exposes `isActionableUrl`; this is the intended replacement
    for the share-receiver fallback that currently persists whole shared text as a URL.
  - `enhanced/resolver/MediaResolver.kt` — the server-resolver contract: `MediaResolver`,
    `ServerResolverConfig` (enabled/base URL/API key/timeout, plus a usability check and an explicit
    disclosure string) and a `DisabledMediaResolver` default so the feature is inert until configured.
  - `enhanced/EnhancedModules.kt` — `EnhancedFeatureDefaults` and `EnhancedPreferenceKeys`.
  - Matching unit tests under `app/src/test/java/com/linksi/app/enhanced/`.

- **Optional private server resolver** — `enhanced/resolver/HttpMediaResolver.kt` and
  `ResolverResponseParser.kt` (18 tests). Inert unless explicitly enabled *and* pointed at an HTTPS
  address; sends exactly one thing, the URL the user asked about, never clipboard or accessibility
  content; carries the API key in the `Authorization` header rather than the query string so it cannot
  leak through proxy or access logs; every failure is a returned value, so a dead server can only
  disable the download action.
- **Enhanced Features settings screen** — `ui/screens/EnhancedSettingsScreen.kt` plus the module
  preferences in `DataStoreExtensions.kt` and `SettingsViewModel.kt`: one place to enable smart
  detection, the floating bubble, accessibility assistance, download notifications and the private
  server. Permission-dependent toggles send the user to the relevant Android settings page instead of
  pretending the permission was granted, and the screen includes the section 58 accessibility
  disclosure, the section 25.1 server disclosure and the section 68 download error strings.
- **Signed release tooling** — `tools/build-release.ps1`: unit tests, lint and a signed
  `:app:assembleRelease`, archived as `LinksiEnhanced_<version>_universal.apk` with a SHA256 sidecar
  and a JSON build record (branch, commit, versionCode, versionName, size, hash). The signing password
  is read from the credentials file, passed via environment variables only, never printed, and cleared
  before the script exits.
- **Private release signing key** — 4096-bit RSA, generated **outside** the repository
  (`E:\Deepseek\Linksi\keys\linksi-enhanced-release.jks`, SHA256 `1E:7F:FE:B4:...:96`, valid to 2054-02-02)
  with credentials in a sibling file. It must be backed up: losing it means no future build can be
  installed as an update, and it is never committed.

### Changed

- **`normalizeUrl` no longer lowercases whole URLs.** The `try`/`catch` branches in the old
  `MetadataFetcher.normalizeUrl` ended in `result.lowercase()` (at `MetadataFetcher.kt:482,484` in
  the baseline worktree, before the helpers were extracted); because a Kotlin `try` expression
  evaluates to its last expression, **every saved URL was stored lowercased**, destroying the case of
  paths and query strings (`https://example.com/File?id=AbC123` was stored as
  `https://example.com/file?id=abc123`). The URL helpers were extracted into
  `app/src/main/java/com/linksi/app/utils/UrlNormalizer.kt` so they are JVM-testable,
  `MetadataFetcher.kt` lost ~50 lines of helpers, and case is now preserved end to end.
- **`UrlCleaner` is a separate, opt-in step ahead of `normalizeUrl`** rather than part of it, so
  cleaning is visible in the UI and reversible in behaviour, and a cleaning failure cannot lose the
  link.
- Repository documentation added in this change: the four documents listed under *Added*, plus this
  changelog.

### Fixed

- Whole-URL lowercasing on the save path, in the live code (`MetadataFetcher.kt:482,484` in the
  baseline worktree). **This does not repair already-damaged data** — rows lowercased by the shipped
  `MIGRATION_11_12` (`LinksDatabase.kt:27`) stay lowercased. A consequence to expect: a mixed-case URL
  saved before the fix will no longer dedupe against the same URL re-saved after it, because the
  stored legacy form is lowercased and the new form is not.
- A genuine defect found by running the new tests rather than by reading the code: `mailto:` URLs were
  misclassified as `MISSING_SCHEME`, because the parser required `://`. `UrlCleaner` now detects an
  explicit `scheme:` prefix and separates `UNSUPPORTED_SCHEME` from `MALFORMED` and `MISSING_SCHEME`.
  (Details in [TEST_REPORT.md](TEST_REPORT.md) §4.)

### Verified

What has actually been executed, and how:

| Item | Result |
|---|---|
| URL cleaner + URL normalizer unit tests | **PASS — 82 tests, 0 failures, 0 errors**, re-executed from clean on JVM 21.0.4 via `tools/run-urlcleaner-tests.ps1` |
| Facebook Reel example required by the specification | **PASS** — cleans to exactly `https://www.facebook.com/reel/1710485373378939` |
| `normalizeUrl` case preservation | **PASS** — 22 normalizer tests include whole-URL-lowercasing regression coverage |
| Dependency licence scan of the resolved graph | **NOT RUN** — needs a configured Android module |
| Android build (`test`, `lint`, `assembleDebug`, `assembleRelease`) | **NOT RUN** — no APK has been produced |
| Anything on a device or emulator | **NOT RUN** — no device, no `adb` |

### Not done yet — do not report these as working

- **The APK has not been built.** No APK, no SHA256, no APK size, no ABI list, no signing certificate
  fingerprint. [TEST_REPORT.md](TEST_REPORT.md) §3 records this as a blocker, and the same is still
  true: `gradlew :app:test` / `lint` / `assembleDebug` have never run against this code, so even the
  compilation of the modified `MetadataFetcher.kt` and the enhanced modules against the Android
  toolchain is unverified. Only the pure-JVM subset has ever been compiled.
- **Downloader implementation pending.** No download engine implementation, no resumable/HTTP-`Range`
  download logic, no WorkManager or user-initiated-data-transfer job, no foreground service, no
  progress notification, no MediaStore/SAF output, no ABI/init fallback exercised on a 32-bit device.
  Only the interfaces and models exist, and only on `feature/enhanced-modules`.
- **Accessibility service pending.** No `AccessibilityService`, no service declaration, no
  user-facing enablement flow, no privacy disclosure.
- **Bubble pending.** No overlay/bubble UI, no `SYSTEM_ALERT_WINDOW` handling, no interaction with the
  foreground-service rules that Android 15 applies to overlays.
- **Quick panel pending.** No share-sheet/quick-panel surface for media, no format picker, no
  download-progress UI.
- **Server resolver pending.** `MediaResolver` is a contract plus a `DisabledMediaResolver`; there is
  no HTTP implementation, no endpoint, no authentication handling, no privacy disclosure in the app.
- **Media extractor implementations pending.** No `DirectMediaExtractor`, no `YtDlpExtractor`, and no
  Media3 dependency in the build at all — therefore also no `compileSdk` 36 bump, no
  `media3-transformer`/`media3-muxer` muxing stage, and no measurement of any APK size impact.
- **No dependency has been added** — nothing in [DEPENDENCY_REVIEW.md](DEPENDENCY_REVIEW.md) is in
  `app/build.gradle`. WorkManager is still 2.9.0 (the 2.11.x move for the `dataSync` timeout fix has
  not been made) and `compileSdk` is still 34.
- **Database work not started.** Schema version is still **12**. The `originalUrl` preservation column
  discussed in `CODE_REVIEW.md` §7.2 does not exist; `exportSchema = false` still blocks Room's
  migration test helper; migration upgrade paths have never been tested.
- **Core Linksi regressions untested** — link CRUD, folders, tags, notes, search, trash,
  import/export, settings persistence, security, share receiver, and every Android 16 device behaviour
  (edge-to-edge, predictive back, multi-manufacturer, rotations, process death).
- **Licence not resolved.** Redistribution remains blocked: the MIT text upstream promises still does
  not exist in its repository, so the notice obligation cannot currently be satisfied
  ([LICENSE_REVIEW.md](LICENSE_REVIEW.md) §4).

---

## [3.1.1] — baseline (upstream)

The unmodified upstream release that this fork starts from. **No changes by this project.**

### Base

- Upstream repository: `https://github.com/AsukaAzure/Linksi`
- Reviewed commit: **`0f4af65`** — *"Fix: Fixed Import from browser"*, tagged locally
  `baseline-linksi-original`
- Version: **`versionName` 3.1.1, `versionCode` 20**
- Package: `com.linksi.app`; `minSdk` 26, `compileSdk`/`targetSdk` 34
- Note recorded by the baseline review: upstream tags `v3.1.0` and `v3.1.1` both point at commit
  `f6b33dea`, and the reviewed commit is **two commits past** that tag with `versionName` already
  `3.1.1` — so the baseline is "3.1.1 plus two unreleased commits".
- Toolchain: AGP 8.13.2, Kotlin 2.0.21, KSP 2.0.21-1.0.25, Gradle wrapper 8.13, Compose BOM
  2024.10.00, Room 2.6.1, Hilt 2.52.

### Upstream feature set as reviewed (static reading only, nothing runtime-verified)

Link saving with metadata fetch, share-sheet receiver, folders (nested), tags, notes, favourites,
read/unread, pinning, search, sorting, filtering, trash with 30-day retention, bulk actions, expiry,
JSON/CSV/HTML import and export, browser-bookmark import, an in-app browser, AI link organisation,
app lock and folder lock with biometrics, screenshot protection, Material You theming and dark mode.
Reminders are advertised but **broken end to end** (`CODE_REVIEW.md` §2.20); the README's claims about
Jetpack Navigation and working reminders are not true of the code.

### Known upstream defects carried into the fork (not yet fixed)

The ranked findings in `CODE_REVIEW.md` §4, summarised: plaintext PIN in an unencrypted,
backup-eligible DataStore (Critical); whole-URL lowercasing plus the irreversible `MIGRATION_11_12`
(Critical — the lowercasing is fixed in this fork, the already-damaged data is not); app-lock bypass
through the exported `ShareReceiverActivity`; non-functional reminders; the AI organiser uploading the
full URL inventory to third-party providers; Gemini API keys in request URLs and all five provider
keys in plaintext; non-URL text persisted as links through the share-receiver fallback; main-thread
WebView metadata fallback and a WebView leak; main-thread export; an uncancelled import scope; expiry
hard-deleting and bypassing the bin; unvalidated imports, unescaped HTML export and CSV formula
injection; URLs written to logcat; full social URLs disclosed to a hard-coded third-party scraper and
every domain to Google's favicon service; no certificate pinning; tags stored as a comma-joined
column; `getAllLinksSync()` loading the whole table; and a set of dead/unused permissions,
dependencies and code paths.

**None of these is fixed except where this changelog says so above.**

[Unreleased]: https://github.com/AsukaAzure/Linksi/compare/v3.1.1...HEAD
[3.1.1]: https://github.com/AsukaAzure/Linksi/releases/tag/v3.1.1
