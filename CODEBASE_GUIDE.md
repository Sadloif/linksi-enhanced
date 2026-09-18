# Linksi Enhanced — codebase guide

A reading guide for a reviewer. It says where the code is, how it is layered, which parts are the
original upstream app and which are the enhanced additions, and why the significant design decisions were
taken. It deliberately does not repeat the test evidence — `TEST_REPORT.md` is the authority for what is
proven, and `CHANGELOG.md` for what changed.

Everything here was read out of the working tree at commit `6ff3784` on branch `enhanced/integration`
(2026-09-18). If a number here disagrees with the tree, trust the tree.

---

## 1. Where the code is

| What | Absolute path |
|---|---|
| **Git checkout (the code)** | `E:\Deepseek\Linksi\repo` |
| Build outputs | `E:\Deepseek\Linksi\repo\app\build\outputs\` |
| JDK 17 (required) | `E:\Deepseek\Linksi\toolchain\jdk-17` |
| Android SDK | `E:\Deepseek\Linksi\toolchain\android-sdk` |
| Gradle cache | `E:\Deepseek\Linksi\local\.gradle-home-main` |
| Signing keys (not in git) | `E:\Deepseek\Linksi\keys\` |
| Signed release APKs | `E:\Deepseek\Linksi\artifacts\releases\` |
| Device test scripts (not in git) | `E:\Deepseek\Linksi\local\.probe\` |

The umbrella folder `E:\Deepseek\Linksi\` holds five siblings — `repo\`, `toolchain\`, `local\`,
`keys\`, `artifacts\`. **Only `repo\` is version controlled.** The scripts under `local\.probe\` are
diagnostics written during testing and are deliberately outside the repo, so they cannot be mistaken for
product code.

`repo\` is a fork. **Baseline Linksi is upstream code**; everything under
`app/src/main/java/com/linksi/app/enhanced/` is new. That separation is the single most useful thing to
know before reviewing a diff.

---

## 2. Top-level layout

```
repo/
├── app/                      the single Gradle module
│   ├── build.gradle          version, signing, ABI splits, lint config
│   └── src/
│       ├── main/             96 Kotlin files, ~27,000 lines  (product code)
│       ├── test/             32 files, ~7,400 lines          (JVM unit tests)
│       ├── androidTest/      13 files, ~3,100 lines          (on-device tests)
│       └── debug/            debug-only resources (loopback network policy)
├── tools/                    build-release.ps1 and helpers
├── gradle/                   version catalog
├── build.gradle              root build script
├── gradle.properties         -Xmx2048m for the Gradle daemon (see §9)
└── *.md                      the documentation set
```

**Documentation set** — read in this order:

| File | Purpose |
|---|---|
| `SESSION_HANDOVER.md` | continuity: current state, environment traps, open work |
| `TEST_REPORT.md` | what is proven, with the measurement for each claim |
| `CHANGELOG.md` | what changed, per release |
| `CODEBASE_GUIDE.md` | this file — structure and design |
| `CODE_REVIEW.md` | known issues found by review, with reasoning |
| `BUILD_AND_RELEASE.md` | how to produce a signed APK |
| `README_ENHANCED.md` | user-facing description of the enhanced features |
| `DEPENDENCY_REVIEW.md`, `LICENSE_REVIEW.md`, `UPSTREAM_UPDATE_GUIDE.md` | the named subjects |

---

## 3. Overall architecture

The app is a single-module Android application. It uses **Hilt** for injection, **Room** for storage,
**WorkManager** for downloads, **OkHttp** for network calls, and **Jetpack Compose** for UI.

### 3.1 Baseline layer (upstream)

```
com.linksi.app
├── data/       Room entities, DAOs, database, repositories
├── di/         Hilt modules
├── domain/     models and use cases
├── ui/         Compose screens, theme, navigation
├── worker/     baseline WorkManager workers
└── utils/      URL handling, helpers
```

### 3.2 Enhanced layer (new)

```
com.linksi.app.enhanced
├── capability/   RuntimeCapabilities - what this device can do
├── detect/       SmartLinkDetector, LikelyCopyDetector, ClipboardUrlReader, UrlTextExtractor
├── service/      LinksiAccessibilityService
├── bubble/       BubbleService, BubblePolicy, BubbleSettings
├── media/        MediaExtractor, ExtractorRegistry, MediaSource, MediaFormat
│   ├── direct/   DirectFileExtractor (and DirectFileClassifier, in the same file)
│   └── ytdlp/    YtDlpExtractor, YtDlpDownloader, YtDlpRuntime, YtDlpUpdater,
│                 YtDlpDownloadWatchdog, YtDlpInfoMapper, YtDlpProgressParser
├── download/     DownloadEngine, WorkManagerDownloadEngine, DownloadWorker, DownloadSink,
│                 MediaStoreSink, AppStorageSink, DirectFileDownloader,
│                 FilenameSanitizer, DownloadNotifications, DownloadNotificationPermission
├── resolver/     MediaResolver, HttpMediaResolver, ResolverResponseParser
├── ui/           QuickPanelActivity, QuickActionPanel, QuickAction, DownloadUiEntryPoint,
│                 DownloadsScreen, DownloadsViewModel, DownloadUrlMetadata
└── di/           Hilt bindings for the above
```

Every file in that tree is new work; `git log --follow` on any of them will show it. Note that
`ExtractorRegistry` lives in `media/MediaExtractor.kt` and `DirectFileClassifier` inside
`media/direct/DirectFileExtractor.kt` — neither has its own file, despite being named types.

**Every enhanced feature is optional and off by default**, and none of them is required for saving,
sharing or organising links. That constraint comes from the specification and it shapes the code: the
enhanced layer is wired in through interfaces (`DownloadUiEntryPoint`, `MediaResolver`) so that the
baseline layer never depends on a concrete enhanced implementation.

---

## 4. The seams — where to review first

These five abstractions carry the design. If you review only part of the codebase, review these.

### 4.1 `MediaExtractor` and `ExtractorRegistry`

*`enhanced/media/MediaExtractor.kt`*

```kotlin
interface MediaExtractor {
    val id: String
    val displayName: String
    val priority: Int get() = 0
    fun supports(source: MediaSource, url: String): Boolean
    fun isAvailable(capabilities: RuntimeCapabilities): Boolean
    suspend fun analyze(url: String, source: MediaSource): MediaExtractionResult
}
```

This is the extractor abstraction the specification asks for. `ExtractorRegistry` picks candidates by
priority, consults `isAvailable` first, and **catches a throwing extractor into a value** rather than
letting it escape — an optional module must not be able to crash the app.

There are two implementations: `DirectFileExtractor` (a file at the end of the URL, with
`DirectFileClassifier` deciding whether a URL looks like one) and `YtDlpExtractor` (a site that needs an
engine). The direct one wins when a URL looks like a file; the engine handles pages.

### 4.2 `DownloadEngine`

*`enhanced/download/DownloadEngine.kt`*

An interface, not a class, on purpose: the UI observes `Flow<DownloadState>` and never touches
WorkManager. `observeAll()` backs the Downloads screen; `enqueue`/`cancel`/`retry`/`dismiss` are the
whole surface. The Android implementation is `WorkManagerDownloadEngine` — WorkManager plus a typed
foreground notification. Because nothing outside that one class names WorkManager, the engine is
testable and replaceable.

### 4.3 `MediaResolver`

*`enhanced/resolver/MediaResolver.kt`*

The optional private-server fallback. `DisabledMediaResolver` is the default binding, so a build with no
server configured behaves exactly as if the feature did not exist. The specification requires that the
server is asked **only** when local extraction has already failed, and that an `http://` address is
rejected *before* anything is sent — both are visible in the resolver, not just in the tests.

### 4.4 `DownloadSink`

*`enhanced/download/DownloadSink.kt`*

Where finished bytes go. Two implementations: `MediaStoreSink` (the user's Downloads collection) and
`AppStorageSink` (app-private). This is the most defect-prone area in the project — see §7.1.

### 4.5 `YtDlpRuntime`

*`enhanced/media/ytdlp/YtDlpRuntime.kt`*

Owns the one genuinely expensive and irreversible thing: starting the bundled Python engine. Two design
points worth a reviewer's attention:

1. **It must not run at app start.** The specification forbids optional modules doing startup work, and
   `YoutubeDL.init` unpacks ~11 MB of CPython plus the yt-dlp payload. So the runtime starts lazily, on
   first use, behind a `Mutex` with a double-checked flag.
2. **Failure is a value, not an exception.** `YtDlpInitStatus` is `NotStarted` / `Ready` / `Failed`.
   Nothing from this class escapes as a throw, so an engine that cannot start disables site downloads
   and nothing else.

---

## 5. Main flows

### 5.1 Saving a link (baseline, unchanged)

`ShareReceiverActivity` → URL cleaner (if enabled) → repository → Room. The cleaner is the one enhanced
feature that touches a baseline path, and it is behind a preference.

### 5.2 Downloading a link

```
UI (QuickPanelActivity / Downloads screen)
  → DownloadEngine.enqueue(DownloadRequest)
    → DownloadWorker (WorkManager, foreground)
      → ExtractorRegistry.analyze()            pick an extractor
      → YtDlpDownloader / DirectFileDownloader run it
      → DownloadSink.commit()                  publish to MediaStore
    → DownloadState flows back to the UI
```

`DownloadWorker` contains one fallback worth knowing about: if the direct downloader reports
`NOT_A_FILE_DETAIL` — the server returned a page rather than a file — the worker retries through the
site engine instead of failing.

### 5.3 The floating bubble

```
System copy event
  → LinksiAccessibilityService (TYPE_VIEW_TEXT_SELECTION_CHANGED etc.)
  → SmartLinkDetector / LikelyCopyDetector      decide if this is a link
  → BubbleService (foreground, TYPE_APPLICATION_OVERLAY)
  → tap  → QuickPanelActivity with NO intent extra
           → QuickPanelActivity takes window focus, then reads the clipboard
           → shows the CLEANED url
```

The clipboard is read **only once the panel has window focus**, so there is no silent background
scraping. That is a privacy promise from the specification, and the focus gate is enforced in
`ClipboardUrlReader` rather than in the caller.

### 5.4 The site engine refresh

```
Settings → Enhanced features → Site engine → Check
  → YtDlpRuntime.refreshEngine()
      → ensureReady()                        start the engine first (see §7.2)
      → YtDlpUpdater.refreshIfStale(force = true)
          → download /releases/latest/download/yt-dlp
          → verify SHA-256 against SHA2-256SUMS
          → stage, probe by running, atomic rename, roll back on failure
```

The refresh normally also happens automatically after `ensureReady()` succeeds, but at most weekly.

---

## 6. Build system

*`app/build.gradle`, `tools/build-release.ps1`*

| Setting | Value |
|---|---|
| `applicationId` | `com.linksi.app` (debug variant adds `.debug`) |
| `minSdk` / `targetSdk` / `compileSdk` | 26 / 36 / 36 |
| Kotlin / AGP / Gradle | 2.0.21 / 8.13.2 / wrapper 8.13 |
| Annotation processing | KSP |
| ABI splits | `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86` + a universal APK |
| Signing | release keystore from `E:\Deepseek\Linksi\keys\` |

Two environment facts that will otherwise cost you a build:

- **`JAVA_HOME` must be `toolchain\jdk-17`.** A JetBrains runtime is not a full JDK and fails with
  `jlink executable ... does not exist`.
- **Do not set `ANDROID_USER_HOME` or `ANDROID_SDK_HOME`** — Hilt's Gradle plugin fails to resolve.

To produce the signed release APKs:

```powershell
$env:JAVA_HOME='E:\Deepseek\Linksi\toolchain\jdk-17'
$env:GRADLE_USER_HOME='E:\Deepseek\Linksi\local\.gradle-home-main'
$env:GRADLE_OPTS='-Djava.io.tmpdir=E:\Deepseek\Linksi\local\.tmp'
$env:DEBUG_KEYSTORE_PATH='E:\Deepseek\Linksi\keys\debug.keystore'
powershell -File tools\build-release.ps1
```

The script archives both APKs to `artifacts\releases\` and writes `latest-build.json` with sizes and
SHA-256 digests. **`-SkipChecks` skips the unit tests and lint** — see §9 for why you may need it.

---

## 7. Design decisions a reviewer should know

### 7.1 Publishing a download is judged by the filesystem, not by MediaStore

This was the hardest area in the project and the source of several real defects. `MediaStoreSink.commit`
now does, in order:

1. check the **filesystem** for the finished file (name plus exact written size),
2. reconcile the MediaStore row to what was found,
3. clear only stale residue if nothing was published,
4. and only then report failure.

The reason is that platform bookkeeping and the filesystem disagree in practice, in both directions:
MediaStore can refuse the publish, keep a row for a file that has been deleted, or silently **rename**
the file to `clip (1).mp4` on a collision. Every one of those produced a false failure or a false
success at some point. **When the row and the file disagree, believe the file.**

### 7.2 Optional modules initialise lazily — including from the Settings screen

`YtDlpRuntime` does not start the engine at app start (§4.5), so anything that asks the engine a
question must initialise it first. The Settings screen initially did not, and the row was inert
(`TEST_REPORT.md` §40). The rule that came out of it: **if a call runs the engine, it goes through
`ensureReady()` first**, and only a user-initiated action surfaces the failure reason while a passive
read degrades quietly.

### 7.3 The download watchdog measures bytes, not output

`YtDlpDownloadWatchdog` stops a download that transfers nothing for 60 s (scaled up to 8× for large
files) and imposes a 30-minute hard limit. It counts **bytes**, not yt-dlp output lines, because yt-dlp
prints destinations and post-processing steps without transferring anything. Two earlier versions were
wrong: one killed downloads during the ~25 s start-up before any bytes flowed, the other killed them
during FFmpeg post-processing where silence is legitimate. It now tracks whether bytes have been seen
and whether the stream under way reached its reported total.

This exists because **yt-dlp hangs indefinitely on a silent socket** — an upstream issue that the
library's own options do not bound. The watchdog is the app's own defence, not a wrapper feature.

### 7.4 Cancellation is turned into a value

An uncaught coroutine cancellation on Android **kills the process**. So a stop the app itself caused
(watchdog, or the user's cancel) is converted into an ordinary failure value, and only a cancellation
this code did not cause is re-thrown. The download work runs in a `supervisorScope` so that a child's
exception reaches the collector instead of being replaced by a `CancellationException` — before that
fix, a plain HTTP 404 looked exactly like the user pressing Cancel.

### 7.5 Direct downloads refuse to save a web page

`DirectFileDownloader` peeks the first 2 KB and rejects a body that starts with a document signature
(`<?xml`, `<!doctype`, `<html`, `<rss`, `<feed`, `<svg`). Without it, a server that lies about its
content type put an XML error page into the user's Downloads with no error shown. The worker then hands
that URL to the site engine (see §5.2).

### 7.6 Whole-URL lowercasing was removed

`normalizeUrl` used to lowercase the entire URL, destroying path and query case on every save. It now
lowercases only the scheme and host. Worth knowing because "the app changed my link" reports look like
data loss.

---

## 8. Testing strategy

| Layer | Location | Run with |
|---|---|---|
| JVM unit tests (747) | `app/src/test/` | `gradlew :app:testDebugUnitTest` |
| On-device tests (12 suites, 33 tests) | `app/src/androidTest/` | `adb shell am instrument`, or `local\.probe\run-suites.ps1` |
| Lint | — | `gradlew :app:lintDebug` |

**Unit tests** cover the pure logic: the URL cleaner, the bubble tap/drag decision, format mapping,
command-line construction, version comparison, the watchdog policy, the notification-permission rules
and the resolver's request/response contract.

**On-device tests** cover everything that needs a real platform: MediaStore publishing, the real
MediaStore collision, the accessibility service binding, the overlay window, the clipboard focus gate,
a real download through the production engine, a genuinely stalled transfer, cancellation, and real
third-party links through the app's own extractor (`RealSiteLinksInstrumentedTest`).

Three lessons that are baked into the test code, because ignoring them produced false results earlier:

1. **A test that expects a failure must prove the failure came from the thing under test.** A loopback
   test once "passed" while moving no bytes, because cleartext to `127.0.0.1` was blocked by policy and
   the refusal looked like an ordinary result. The debug variant now permits loopback explicitly.
2. **Check that the artifact under test is newer than the edit.** A stale APK reproduced fixed bugs more
   than once. Compare against the installed build, never against a line number in a stack trace.
3. **A site refusing this network is not an app defect.** `RealSiteLinksInstrumentedTest` separates the
   two, and records per-link outcomes rather than printing a single verdict — see §10.

---

## 9. Traps that cost time (read before building)

| Symptom | Cause |
|---|---|
| `tools\build-release.ps1` fails at `:app:packageRelease` with `Unable to allocate …` / `OutOfMemoryError` | The script runs `testDebugUnitTest` and `lintDebug` **in the same Gradle daemon** before packaging, so packaging starts on a heap the tests already filled. Run it with `-SkipChecks` and run the gates separately. **Do not raise `-Xmx` past 2048m** — `-Xmx4096m` cannot even start on this machine (`The paging file is too small`) |
| `:app:validateSigningRelease FAILED` immediately | You called `gradlew :app:assembleRelease` directly. The keystore environment variables come from `tools\build-release.ps1`; build releases through the script |
| `Gradle build daemon disappeared unexpectedly` | Memory pressure, not a compile error. A crash dump `hs_err_pid*.log` is left behind (gitignored). Retry on a fresh daemon |
| Dozens of phantom `Unresolved reference` for real declarations | Corrupted shared Kotlin daemon. Serialise Gradle runs; retry once unchanged rather than changing configuration |
| `am instrument -e class A,B,C` reports tests you did not name | It does **not** split on commas — the runner falls back to its own selection. Invoke one class per run (`local\.probe\run-suites.ps1` splits for you) |

There is a fuller table in `SESSION_HANDOVER.md` §5.

---

## 10. Known limitations — do not review these as bugs

These are deliberate, or external, and are recorded here so a reviewer does not spend time on them.

| Item | Status |
|---|---|
| **Real-link coverage per site** | Measured on the POCO on 2026-09-18 with the owner's own links, through the app's extractor: YouTube 10/10, TikTok 6/6 on a first pass, Instagram 4/5, Reddit 4/5, Pinterest 4/5. The failures are `LOGIN_REQUIRED` (one Instagram post), `NETWORK` throttling (TikTok, after repeated passes), and short links that redirect to a site's **home page** (one Reddit and one Pinterest link), which is why there is no media to find. All are properties of the links and the sites, not of the app. `TEST_REPORT.md` §41 |
| Reminders | Broken **upstream**, not fixed |
| Room schema migration | Schema 12 with no migrations written |
| Licence | MIT is promised in `README.md` but not committed; the build bundles GPL-3.0 components. `LICENSE_REVIEW.md` covers it. Unresolved by design until the owner decides |
| `destroyProcessById` in the yt-dlp wrapper | Cannot be fixed from here: it passes the library's own UUID to `pstree` (absent on Android) and uses `grep -oP` (absent from toybox). The app works around it and checks the outcome |
| APK builds | Not byte-reproducible — absolute build paths feed the output, so two builds of the same source differ in hash |
| MediaStore resume | Not implemented; app-specific storage resume is |
| API 36 depth | Predictive-back ordering through every nested sheet, rotation, cutouts, IME and tablet/foldable layouts are untested. Compatibility depth, not known failures |

---

## 11. Reading order for a review

1. **§4, the seams** — `MediaExtractor`, `DownloadEngine`, `MediaResolver`, `DownloadSink`,
   `YtDlpRuntime`. These carry the architecture.
2. **`MediaStoreSink.commit`** — the most defect-prone code in the project, and the place where the
   reasoning is least obvious.
3. **`YtDlpDownloadWatchdog`** and **`YtDlpDownloader`** — concurrency and cancellation, where the
   subtle bugs were.
4. **`RealSiteLinksInstrumentedTest`** — shows how the enhanced work is verified against real sites, and
   documents why `Unsupported` is recorded rather than failed.
5. **`DOWNLOAD` path in `DownloadWorker`** — the fallback from a lying content type to the site engine.
6. Then the baseline layer, which is upstream code and largely unchanged.

`CODE_REVIEW.md` holds the issues already found by review, with the reasoning, so that a fresh reviewer
can spend their time elsewhere.
