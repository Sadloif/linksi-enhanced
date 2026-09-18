# TEST_REPORT.md

Test report for the Linksi Enhanced private build (specification section 44, testing plan section 104).

> **Current status is in §18 (2026-09-18): targetSdk 36, 729 green unit tests, lint/build green,
> Android 16 compatibility checks green, and the former watchdog/resume/notification gaps closed.**
> Sections 1–13 are retained as chronological evidence and describe earlier phases, not the current
> checkout.

- **Report date**: 2026-10-09
- **Phase**: 1 — baseline review and the URL cleaner module
- **Overall status**: **URL cleaner: PASS (82/82 unit tests executed on a real JVM). Baseline APK: NOT PRODUCED — blocked, see section 3.**

---

## 1. Build identification

| Field | Value |
|---|---|
| Repository | `https://github.com/AsukaAzure/Linksi` (public upstream) |
| Reviewed commit (baseline) | `0f4af65eb4fd87fcc77b79717071f975f7ccd122` |
| Baseline tag created | `baseline-linksi-original` |
| Branch | `master` (baseline) → `feature/url-cleaner` (phase-1 work) |
| `applicationId` | `com.linksi.app` |
| `versionCode` | `20` (unchanged — no release build produced) |
| `versionName` | `3.1.1` (unchanged) |
| `minSdk` | `26` |
| `compileSdk` / `targetSdk` | `34` / `34` |
| Supported ABI | Universal (no native libraries in the baseline) |
| APK filename | Baseline: `Linksi_baseline_3.1.1_vc20_debug.apk` (debug variant of the untouched revision). Enhanced debug build: `app-debug.apk`. |
| APK SHA256 | Baseline: `665F3EF1F952064C320F97BA746F7655E92AF79F57372C7457B1D61CC8D5CE1C`. Enhanced WIP debug build: `2A5FA472B932C66D661661B8A6B4DB5CCE91705F07B97A48199B3A84E47E68AD` (superseded by later commits). |
| APK size | Baseline 24,141,629 bytes (23.02 MB); enhanced WIP debug 24,314,468 bytes (23.19 MB) |
| Signing certificate fingerprint | Debug builds are signed with a local debug key. The private release keystore has since been generated: SHA256 `1E:7F:FE:B4:C5:F5:3C:3A:74:46:67:52:2A:8A:FB:E0:B6:97:B7:90:AA:9A:76:4B:6A:D6:CB:C6:20:FF:26:96`, valid to 2054-02-02. The *upstream* signing key remains unavailable, so in-place update of an installed official Linksi is still impossible. |

Note: upstream tags `v3.1.0` and `v3.1.1` point at the *same* commit (`f6b33dea…`), and the
reviewed commit is two commits past it with `versionName` already `3.1.1`.

---

## 2. What was tested, and how

### 2.1 Why a separate harness exists

The Android module cannot be configured on this machine because **no Android SDK is installed**
(no `ANDROID_HOME`, no `%LOCALAPPDATA%\Android\Sdk`), so `gradlew :app:test` cannot run. The URL
cleaner and the URL normalizer, however, are deliberately free of `android.*` imports and are pure
Kotlin/JVM code.

`tools/run-urlcleaner-tests.ps1` therefore builds a standalone Gradle **JVM** project
(`../linksi-urlcleaner-verify`), copies the *verbatim* source and test files from the Android
project into it, and executes the *verbatim* unit tests on a real JVM. Nothing is re-implemented or
translated: the same `.kt` files that ship in the app are the ones under test. The harness always
forces the tests to re-execute (`cleanTest`) and parses the JUnit XML afterwards, so a
`BUILD SUCCESSFUL` that skipped the tests cannot be mistaken for a pass.

### 2.2 Environment

| Item | Value |
|---|---|
| OS | Windows 11 (10.0.22631), amd64 |
| JDK | JetBrains Runtime **21.0.4** (OpenJDK 21.0.4+13), `javac` present, at `C:\Program Files\JetBrains\PyCharm Community Edition 2024.2.4\jbr` |
| Gradle | 8.13 (the repository wrapper, `gradle/wrapper/gradle-wrapper.properties:3`) |
| Kotlin | 2.0.21 (matches `build.gradle:5-7`) |
| Test framework | JUnit 4.13.2 (already declared at `app/build.gradle:127`) |
| Android SDK | **absent** |
| Devices / emulators | **none**; `adb` not installed |

### 2.3 Result

```
com.linksi.app.utils.UrlCleanerTest:    tests=60 failures=0 errors=0 skipped=0
com.linksi.app.utils.UrlNormalizerTest: tests=22 failures=0 errors=0 skipped=0
=== gradle exit code: 0 ; junit tests=82 failures=0 errors=0 ===
```

Verbatim from the harness summary line. Full console log:
`E:\Deepseek\linksi-urlcleaner-verify\test-run.log`; JUnit XML under
`E:\Deepseek\linksi-urlcleaner-verify\build\test-results\test\`.

### 2.4 Files under test

| File | Status |
|---|---|
| `app/src/main/java/com/linksi/app/utils/UrlCleaner.kt` | new |
| `app/src/main/java/com/linksi/app/utils/UrlNormalizer.kt` | new (URL helpers extracted from `MetadataFetcher.kt` so they are JVM-testable) |
| `app/src/main/java/com/linksi/app/utils/MetadataFetcher.kt` | modified (helpers removed, −50 lines; no other change) |
| `app/src/test/java/com/linksi/app/utils/UrlCleanerTest.kt` | new (60 tests) |
| `app/src/test/java/com/linksi/app/utils/UrlNormalizerTest.kt` | new (22 tests) |
| `tools/run-urlcleaner-tests.ps1` | new (verification harness) |

---

## 3. Baseline build: blocked

The specification's section 6.4 requires the untouched project to be built and its APK archived
before enhancement. **That could not be done here and is recorded as a blocker, not a pass.**

| Step | Status | Reason |
|---|---|---|
| `gradlew clean` / `test` / `lint` / `assembleDebug` | Not run | Android Gradle plugin requires an Android SDK; none is installed |
| Untouched APK archived + SHA256 | Not produced | as above |
| Untouched APK tested on a device | Not run | no device, no `adb` |
| Release baseline build | Not possible | `app/build.gradle:29` points at `../keystroke.jks`, which does not exist, and no `*.jks` is present anywhere |

To close this blocker: install the Android SDK (platform 34 + matching build-tools, licences
accepted), set `ANDROID_HOME` or add `local.properties` with `sdk.dir`, then run from the
repository root:

```text
gradlew.bat clean
gradlew.bat test
gradlew.bat lint
gradlew.bat assembleDebug
Get-FileHash app\build\outputs\apk\debug\app-debug.apk -Algorithm SHA256
```

Also record the APK size and ABI list at that point, then update sections 1 and 3 of this file.

---

## 4. URL cleaner test coverage

The specification's section 45 and the testing plan's sections 19-27 and 92 name the required
cases. Mapping (all executed and passing):

| Required case (spec §45 / plan) | Test | Result |
|---|---|---|
| HTTPS | `httpsUrlWithoutTrackingIsUnchanged` | PASS |
| HTTP | `httpUrlKeepsItsScheme` | PASS |
| Missing scheme | `missingSchemeIsRejected`, `supportedSchemeWithBrokenSeparatorIsMalformed` | PASS |
| Trailing slash | `oneTrailingSlashIsRemovedFromThePath`, `onlyOneTrailingSlashIsRemoved`, `rootTrailingSlashBecomesBareHost` | PASS |
| Facebook Reel tracking parameters (plan §19) | `facebookReelExampleFromSpecCleansExactly` — asserts the exact expected string `https://www.facebook.com/reel/1710485373378939` | PASS |
| Instagram URLs | `trackingParameterMatchingIsCaseInsensitive`, coverage of `igshid` via `everyKnownTrackingParameterIsRemoved` | PASS |
| TikTok share URLs | `oneTrailingSlashIsRemovedFromThePath` plus tracker removal coverage | PASS |
| Pinterest URLs | tracker removal coverage (`utm_*`) | PASS |
| Reddit URLs | `unknownParameterIsKept` (`context=3`-style parameters survive) | PASS |
| `utm` parameters (plan §20) | `utmParametersAreRemovedAndMeaningfulParameterIsKept` — asserts `id=42` survives | PASS |
| `fbclid`, `gclid`, `dclid`, `msclkid`, `igshid`, `mc_cid`, `mc_eid`, `yclid`, `_ga`, `_gl` (plan §21) | `everyKnownTrackingParameterIsRemoved` loops over the whole central registry | PASS |
| Mixed case paths (plan §22, mandatory) | `mixedCasePathAndQueryValueArePreserved`, `mixedCaseIsNeverLowercased` | PASS |
| Mixed case query values | `mixedCaseIsNeverLowercased`, `mixedCaseIsPreservedWhileTrackingIsRemoved` | PASS |
| Encoded values (plan §23) | `percentEncodedPathIsNotCorrupted`, `utf8PercentEncodedQueryIsNotCorrupted`, `plusAndEncodedAmpersandArePreserved`, `malformedPercentEscapeDoesNotCorruptTheUrl`, `internationalDomainNameIsAcceptedAndPreserved` | PASS |
| Fragments | `fragmentIsPreservedByDefault`, `fragmentOnlyUrlIsPreserved`, `hashFragmentTrackerIsKeptByDefault`, `fragmentIsRemovedOnlyWhenExplicitlyRequested` | PASS |
| Malformed URLs (plan §25) | `blankInputIsRejected`, `missingSchemeIsRejected`, `schemeWithoutHostIsRejected`, `nonHttpSchemeIsRejected`, `hostWithoutDotIsRejected`, `whitespaceInsideAuthorityIsRejected`, `rejectedInputsKeepTheOriginalStringIntact`, `cleanOrNullReportsFailure`, `hostileInputsNeverThrow` | PASS |
| Repeated parameters | `repeatedTrackingParametersAreAllRemoved`, `repeatedMeaningfulParametersAreKept` | PASS |
| Empty parameters | `emptyValueIsKeptByDefault`, `emptyValueIsRemovedWhenRequested`, `emptyQuerySegmentsAreDropped`, `queryWithNoParametersIsDropped` | PASS |
| Meaningful query parameters (plan §24) | `unknownParameterIsKept`, `parameterOrderIsPreserved`, `facebookUnknownParameterIsKept` | PASS |
| Long URLs (plan §92) | `veryLongUrlWithMixedParametersIsHandledSafely` (300-char path, 30 tracking parameters) | PASS |
| Facebook cleaning disabled elsewhere | `facebookParamsAreKeptOnNonFacebookHosts` | PASS |
| Idempotence | `cleaningIsIdempotent` | PASS |
| Failure isolation (spec §26 / plan §73) | `cleanOrSelfFallsBackToTheOriginalUrl`, `cleanOrNullReportsFailure` | PASS |
| `normalizeUrl` case preservation (spec §70.13) | `mixedCasePathAndQueryArePreservedExactly`, `wholeUrlIsNeverLowercased`, `authorityCaseIsPreserved` | PASS |
| `normalizeUrl` scheme handling | `schemeLessInputGetsHttps`, `httpIsUpgradedToHttps`, `uppercaseHttpSchemeIsRebuiltInsteadOfDoubled`, `uppercaseHttpsSchemeIsFoldedToLowerCase` | PASS |
| `normalizeUrl` encoding/dot-segments | `percentEncodingIsNotCorrupted`, `dotSegmentsAreResolved`, `dotSegmentsAreResolvedWithoutLosingCase`, `unparseableInputIsReturnedWithoutCaseFolding`, `normalisationIsIdempotent` | PASS |
| `extractDomain`, `isValidUrl` | 4 further tests | PASS |

### Defects found by actually running the tests

The first execution produced **81 tests, 2 failures**. Both were real and were fixed rather than
having the assertions weakened:

1. `fragmentIsRemovedOnlyWhenExplicitlyRequested` asserted the wrong expectation — the input also
   contained a tracking parameter that is removed by default, so the assertion was internally
   inconsistent. The test was rewritten to isolate fragment removal behind a *meaningful* parameter
   (`?id=42#section-2`), and it now also asserts the default-preserved case.
2. `nonHttpSchemeIsRejected` exposed a genuine classifier defect: `mailto:someone@example.com` was
   reported as `MISSING_SCHEME` because the parser required `://`. `UrlCleaner` now detects an
   explicit `scheme:` prefix and distinguishes `UNSUPPORTED_SCHEME` (`mailto:`, `ftp:`,
   `javascript:`) from `MALFORMED` (`http:/example.com`) and from `MISSING_SCHEME`
   (`example.com:8080/path`).

Final state after the fixes: **82 tests, 0 failures, 0 errors** (re-executed from clean).

---

## 5. Not tested (explicit gaps)

Nothing below has been verified and none of it may be reported as working:

1. **The Android app itself.** No APK was built, installed or launched. Compilation of the modified
   `MetadataFetcher.kt` and the new files against the Android toolchain has **not** been verified —
   only the pure-JVM subset was compiled.
2. **`gradlew :app:test`** in the real Android module (needs the SDK). The 82 tests are expected to
   pass there too since they use only the JUnit dependency already declared, but that is an
   expectation, not a result.
3. **`lint`** — never run.
4. **Every core-Linksi regression test** in testing plan sections 9-17 (link CRUD, folders, tags,
   notes, search, trash, import/export, settings persistence, security, share receiver).
5. **All device tests**: Android 16 / ColorOS 16 behaviour, edge-to-edge, predictive back,
   multi-manufacturer, rotations, process death.
6. **Any downloader, accessibility, bubble or server-resolver behaviour** — that code does not exist
   yet.
7. **Database migration testing** — no schema change was made in phase 1, and `exportSchema = false`
   (`LinksDatabase.kt:12`) means Room's migration test helper needs schema export enabled first.
8. **Privacy/network verification** of the baseline (logcat inspection, traffic capture).

---

## 6. Known limitations of the phase-1 code

1. `UrlCleaner` is **not yet wired into any save path** (dev-order step 12 of the specification).
   It is currently referenced only by its own tests. The intended seams are documented in
   `CODE_REVIEW.md` section 7.1.
2. `UrlCleaner` rejects a URL with no scheme rather than prefixing `https://`. Prefixing remains
   `normalizeUrl`'s job, so the two functions compose; the cleaner never invents a scheme.
3. `UrlCleaner` removes exactly the tracking parameters listed in the specification plus the
   `utm_*` family and three Facebook parameters. Deliberately conservative: `si`, `ref`, `source`,
   `mibextid` and similar are **kept**, because the specification forbids removing unknown
   parameters. Extending the list is a one-line change in the central registry.
4. Trailing-slash removal and fragment retention are opinionated defaults; both are documented in
   the source and the fragment case is switchable via `Options.removeFragment`.
5. `normalizeUrl` still performs the pre-existing `http://`→`https://` upgrade and trailing-slash
   removal. Those were left intact deliberately: they are existing behaviour, and changing them is
   a separate decision with dedupe consequences.
6. The `normalizeUrl` case fix **does not repair already-damaged data**: rows lowercased by
   `MIGRATION_11_12` (`LinksDatabase.kt:27`) stay lowercased. A consequence to expect is that a
   mixed-case URL saved before the fix will no longer dedupe against the same URL re-saved after
   it, because the stored legacy form is lowercased and the new form is not.

---

## 7. Reproduction

```powershell
# from the repository root, with a JDK 17+ available (the script defaults to the PyCharm JBR)
powershell -File .\tools\run-urlcleaner-tests.ps1

# optional overrides
powershell -File .\tools\run-urlcleaner-tests.ps1 -JavaHome 'C:\Program Files\Java\jdk-17'
```

The script prints a summary line in the form
`gradle exit code: 0 ; junit tests=82 failures=0 errors=0` and exits non-zero if any test fails,
if an error occurs, or if no tests ran at all.

---

## 8. Release recommendation

- **Phase 1 (URL logic): acceptable to keep.** 82 executed unit tests pass; the Facebook example
  required by the specification cleans exactly as specified; whole-URL lowercasing has been removed
  from the live code path.
- **No APK may be released from this state.** The minimum release gate (testing plan section 100)
  requires an APK that installs, launches and passes core regression tests, none of which has
  happened. The testing plan states plainly: do not declare the task complete without producing a
  tested APK.
- **Next actions, in order**: install the Android SDK and produce/archive the baseline APK; run
  `gradlew :app:test` in the real module; then wire the cleaner into the save path (step 12) with a
  core regression pass (step 13).

---

## 9. Addendum — the build environment was completed later the same day

Everything in sections 3 and 5 that says "blocked" or "not run" because of a **missing Android
SDK** has since been resolved. This addendum supersedes those statements; the sections are left in
place so the progression is honest.

### 9.1 What made it work

| Problem | Resolution |
|---|---|
| No Android SDK | Installed into the workspace at `E:\Deepseek\Linksi\toolchain\android-sdk`: `platforms;android-34/35/36`, `build-tools;34.0.0/35.0.0/36.0.0`, `platform-tools`. Google's own `sdkmanager` cannot reach `dl.google.com` in this sandbox (`IO exception while downloading manifest`), so the component zips were fetched directly from the repository manifest with the JDK's HTTP client — script: `E:\Deepseek\Linksi\scripts\install-sdk-packages.ps1`. |
| No `jlink` | The JetBrains Runtime bundled with PyCharm has no `jlink`, which AGP's `JdkImageTransform` requires. A real **Temurin JDK 17.0.20.1** was installed at `E:\Deepseek\Linksi\toolchain\jdk-17`. |
| AGP wrote `~/.android/debug.keystore` outside the workspace | `app/build.gradle` now supports an opt-in `DEBUG_KEYSTORE_PATH` override; unset, behaviour is unchanged. |
| Release signing key absent | A private 4096-bit RSA release keystore was generated **outside the repository** (`E:\Deepseek\Linksi\keys\linksi-enhanced-release.jks`, credentials in `KEYSTORE_CREDENTIALS.txt`). It is gitignored by construction and must be backed up; losing it means no future in-place update. |

### 9.2 Baseline APK archived (specification section 6.4)

Built from the untouched baseline tag `baseline-linksi-original` (`0f4af65`) in a throwaway
worktree, with only the debug signing override injected (the baseline revision predates it):

```text
file   : Linksi_baseline_3.1.1_vc20_debug.apk
size   : 24,141,629 bytes (23.02 MB)
sha256 : 665F3EF1F952064C320F97BA746F7655E92AF79F57372C7457B1D61CC8D5CE1C
```

Archived under `E:\Deepseek\Linksi\artifacts\baseline\` with a `.sha256` sidecar. It is a **debug** build:
no release build is possible from the baseline because the upstream signing key does not exist.

### 9.3 The unit tests now run in the real Android module

`gradlew :app:testDebugUnitTest` executes and passes. This closes the section 5 gap where the tests
only ran in a side JVM harness:

```text
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL

com.linksi.app.utils.UrlCleanerTest:                     tests=60  failures=0 errors=0
com.linksi.app.utils.UrlNormalizerTest:                  tests=22  failures=0 errors=0
com.linksi.app.enhanced.capability.RuntimeCapabilitiesTest: tests=40  failures=0 errors=0
com.linksi.app.enhanced.media.MediaSourceDetectorTest:      tests=33  failures=0 errors=0
com.linksi.app.enhanced.media.MediaFormatTest:              tests=36  failures=0 errors=0
com.linksi.app.enhanced.media.ExtractorRegistryTest:        tests=20  failures=0 errors=0
com.linksi.app.enhanced.download.DownloadModelsTest:        tests=29  failures=0 errors=0
com.linksi.app.enhanced.download.DownloadEngineTest:        tests=7   failures=0 errors=0
com.linksi.app.enhanced.download.FilenameSanitizerTest:     tests=48  failures=0 errors=0
com.linksi.app.enhanced.detect.UrlTextExtractorTest:        tests=33  failures=0 errors=0
com.linksi.app.enhanced.resolver.MediaResolverTest:         tests=20  failures=0 errors=0
com.linksi.app.enhanced.EnhancedModulesTest:                tests=12  failures=0 errors=0
TOTAL tests=360 failures=0 errors=0
```

The APK also assembles: `:app:assembleDebug` produces `app-debug.apk`, 23.19 MB.

### 9.4 Build environment recipe (reproducible)

```powershell
$env:JAVA_HOME='E:\Deepseek\Linksi\toolchain\jdk-17'                      # a real JDK: AGP needs jlink
$env:GRADLE_USER_HOME='E:\Deepseek\Linksi\local\.gradle-home-main'    # keep caches inside the workspace
$env:GRADLE_OPTS='-Djava.io.tmpdir=E:\Deepseek\Linksi\local\.tmp'
$env:DEBUG_KEYSTORE_PATH='E:\Deepseek\Linksi\keys\debug.keystore'
& .\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --console=plain --no-watch-fs
```

Do **not** set `ANDROID_USER_HOME`/`ANDROID_SDK_HOME`: AGP then fails during plugin application
(`Could not create provider for value source AndroidLocationsBuildService.AndroidDirectoryCreator`).

### 9.5 What is still genuinely untested

The gap list in section 5 stands, minus the build and unit-test items. There is still **no device
or emulator**, so nothing below has been verified: app launch, every core-Linksi regression test,
the share receiver, Android 16/ColorOS behaviour, edge-to-edge and predictive back, rotations,
process death, update-over-install, and every downloader/accessibility/bubble behaviour. The
minimum release gate (testing plan section 100) is **not** met, and no APK may be released as stable.

---

## 10. Addendum 2 — on-device verification on Android 16

Section 9.5 said there was no device. There is now: an **Android 16 (API 36) x86_64 emulator**
(`sdk_gphone64_x86_64`) running on the Windows Hypervisor Platform, driven with `adb`. Section 9.5's
"nothing below has been verified" is superseded by this section for the items listed here; the rest
of that list still stands.

### 10.1 Instrumented tests — 4 tests, 0 failures

`app/src/androidTest/java/com/linksi/app/CoreFlowsSmokeTest.kt`, run against the real `MainActivity`,
the real Hilt graph and the real Room database:

```text
com.linksi.app.CoreFlowsSmokeTest:....
Time: 15.741

OK (4 tests)
```

| Test | What it proves |
|---|---|
| `appLaunchesAndShowsTheHomeScreen` | the app starts on Android 16 without crashing |
| `savingATrackingHeavyUrlStoresTheCleanedForm` | `utm_*` removed, `?id=42` kept, all the way to the database |
| `theSpecificationFacebookReelExampleIsCleanedOnSave` | acceptance criterion 70.12, on a device |
| `mixedCaseInPathAndQueryIsPreservedOnSave` | acceptance criterion 70.13, on a device |

These assertions read the database through a second connection rather than trusting the UI, so they
fail if the save path silently stores something else.

### 10.2 Manual device verification

| Verified | Evidence |
|---|---|
| App installs and launches | `topResumedActivity=com.linksi.app.debug/com.linksi.app.MainActivity`, pid alive, no `FATAL EXCEPTION` |
| Onboarding renders | screenshot `linksi-home.png` ("Save Any Link", Skip/Next pager) |
| Share receiver works | `SEND`/`text/plain` intent → `mCurrentFocus=…ShareReceiverActivity`; screenshot `share-receiver.png` |
| Metadata fetching works on device | the share sheet showed live Facebook engagement data for the reel ("1.4M views · 55K reactions") |
| Shared link is stored **cleaned** | after tapping Save Link in the share sheet, the database row was `https://www.facebook.com/reel/1710485373378939` |
| Accessibility service binds | `dumpsys accessibility`: `Service[label=Linksi link detection (optional), eventTypes=[TYPE_VIEW_CLICKED, TYPE_WINDOW_STATE_CHANGED, TYPE_WINDOW_CONTENT_CHANGED, TYPE_VIEW_TEXT_SELECTION_CHANGED], notificationTimeout=100, requestA11yBtn=false]`, and the system started the service process |
| No crashes anywhere | `adb logcat -b crash` contains nothing for `linksi` across the whole session |

Final database contents after all of the above (pulled with `run-as` and queried with sqlite3):

```text
stored links: 3
  id=1 url='https://www.facebook.com/reel/1710485373378939'   (saved through the share sheet)
  id=2 url='https://example.com/article?id=42'                (tracking parameters removed)
  id=3 url='https://example.com/File?id=AbC123'               (case preserved)

CLEANED URL STORED: True
RAW TRACKING URL STORED (should be False): False
```

Evidence files: `E:\Deepseek\Linksi\evidence\` (`linksi-home.png`, `share-receiver.png`,
`linksi_db`, `logcat.txt`, `share-ui.xml`).

### 10.3 A caveat about `connectedDebugAndroidTest`

`:app:connectedDebugAndroidTest` fails in this environment with
`java.util.concurrent.ExecutionException: java.io.IOException: The system cannot find the path
specified` — a host-side path problem in AGP's test-result handling, not a test failure.
`gradle.properties` disables the Unified Test Platform to no avail. The same APKs and the same tests
pass when driven directly:

```powershell
gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r -t app\build\outputs\apk\debug\app-debug.apk
adb install -r -t app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb shell am instrument -w -e class com.linksi.app.CoreFlowsSmokeTest `
    com.linksi.app.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Expect the same on a normal developer machine to work through the Gradle task; the sandbox is the
variable here.

### 10.4 What is *still* untested (corrected list)

The emulator closes the gap on app launch, core save/share flow, URL cleaning on device and
accessibility-service binding. The following remain **unverified** and must not be claimed:

1. **Any physical device**, including the intended OPPO/ColorOS target, and every manufacturer
   listed in the testing plan.
2. **Android 8 through 15.** Only API 36 has been exercised.
3. **The bubble overlay on hardware.** Its geometry and timing are unit tested; the overlay window,
   drag/snap and the Android 14+/15 background-activity launch are not.
4. ~~**A real download.**~~ **Now verified** — see §10.5. MediaStore output and the download
   notifications: MediaStore is verified, the notification rendering is not.
5. ~~**The quick action panel** as rendered UI (not mounted on a screen yet).~~ **Now mounted and
   screenshot-verified** — see §10.5. The panel's live *in-progress* status card is still only
   unit-tested (the test file completes before a screenshot can be taken).
6. **The server resolver** against a live server.
7. **Reminders/notifications end to end** — known broken upstream (see CODE_REVIEW.md finding 4).
8. **Predictive back, edge-to-edge, rotation, process death, update-over-install, low storage and
   network interruption.**
9. **The minimum release gate (testing plan §100)** — device coverage of the core regressions is
   still incomplete, so the release APK is a **candidate for private use, not a stable release**.

---

## 11. Addendum 3 — the downloader is reachable and downloads for real

Everything the specification asks for in modules 5 to 7 (universal downloader, direct file
downloader, quick action panel) is now wired to a user-facing entry point and has been exercised on
the Android 16 emulator.

### 11.1 What is reachable now

| Surface | Path |
|---|---|
| Link options sheet | a "Download / quick actions" row opens the panel for that link — no bubble, no accessibility service required |
| Floating bubble / shared link | `QuickPanelActivity` renders the real `QuickActionPanel`; when opened from the link sheet the URL arrives as an intent extra and the clipboard is **not** read |
| Settings → Downloads | a downloads screen listing In progress / Finished from `DownloadEngine.observeAll()`, with cancel, retry and dismiss |

The download path is `ExtractorRegistry.analyse` → `DownloadEngine.enqueue` → WorkManager with a
`dataSync` foreground service → `MediaStore.Downloads`. Progress, size and speed come only from
`DownloadFormatting`; when the server does not report a total the UI shows an indeterminate bar
rather than inventing a percentage.

### 11.2 Device evidence

```text
$ gradlew :app:assembleDebug :app:testDebugUnitTest
BUILD SUCCESSFUL — 631 tests, 0 failures, 0 errors

$ adb shell am instrument -w -e class com.linksi.app.DownloadEngineInstrumentedTest \
      com.linksi.app.debug.test/androidx.test.runner.AndroidJUnitRunner
OK (1 test)   INSTRUMENTATION_STATUS_CODE: 0
```

`DownloadEngineInstrumentedTest` performs a **real** download through the production engine and then
asserts: the state reaches `Completed`, the file is readable, the byte count matches the reported
size, the location is a `content://` URI, and the published MediaStore row has `is_pending=0`. The
downloaded artifact was a 13,504-byte PNG that landed in `MediaStore.Downloads`. It skips (rather
than fails) only when the failure is a network-class error.

Screenshots in `E:\Deepseek\Linksi\evidence\`: `download-panel.png` (the live panel showing
"Original quality / PNG · 13.2 KB" and a DOWNLOAD row), `download-panel-article.png` (an article URL
correctly shows **no** download section), `link-options-sheet.png` (the entry row),
`downloads-screen.png` (the downloads list).

### 11.3 A caution learned the hard way

Running two instrumented test suites against one emulator **at the same time** produces
`INSTRUMENTATION_RESULT: shortMsg=Process crashed.` — each run reinstalls `com.linksi.app.debug`
under the other, so the test APK and the app APK stop matching. That is a harness artefact, not an
app defect, but it is indistinguishable from a real crash unless the logcat is read carefully.
**Serialise device test runs**: one suite at a time, reinstall before each.

---

## 12. Addendum 4 — site extraction (yt-dlp), and a release-only defect it exposed

### 12.1 What was added

`MediaExtractor` gained a second implementation behind the existing abstraction:
`YtDlpExtractor` (priority 50, below the direct-file extractor's 100), with `YtDlpInfoMapper`,
`YtDlpRuntime` (lazy, off-main-thread, failure-sticky initialisation), `YtDlpDownloader` (executes
yt-dlp, streams progress, then publishes through the existing `DownloadSink`/MediaStore path) and
`YtDlpProgressParser`, plus `YtDlpMediaSmokeTest` on device. `youtubedl-android` `library:0.17.3` and
`:ffmpeg:0.17.3` (GPL-3.0 — see DEPENDENCY_REVIEW §8) are now dependencies, with ABI splits
(`arm64-v8a` + universal), `useLegacyPackaging = true` and ProGuard keeps.

### 12.2 A real bug the agent found before I could

`YoutubeDL.init()` unpacks CPython and yt-dlp only. The `:ffmpeg` artifact has a **separate** entry
point, `com.yausername.ffmpeg.FFmpeg.init(context)`. Without calling both, `packages/ffmpeg` never
exists and every *merged* download fails with "ffmpeg not found" — after the app has otherwise looked
healthy. `YtDlpRuntime.ensureReady()` now calls both.

### 12.3 A release-only defect found by running the signed APK

Debug builds worked. The **signed release APK** — the actual deliverable — could not start the engine
at all:

```text
W YtDlpRuntime: the bundled yt-dlp engine could not be started
W YtDlpRuntime: java.lang.ExceptionInInitializerError
    at com.yausername.youtubedl_common.utils.ZipUtils.unzip
    at com.yausername.youtubedl_android.YoutubeDL.initPython
  Caused by: java.lang.RuntimeException: class ga.a is not a concrete class
    at ga.f.<clinit>
```

The release R8 mapping identified the obfuscated names:

```text
org.apache.commons.compress.archivers.zip.ExtraFieldUtils -> ga.f
org.apache.commons.compress.archivers.zip.AsiExtraField   -> ga.a
```

commons-compress registers its zip extra-field handlers by reflection, so R8 obfuscating or merging
those classes makes `ExtraFieldUtils`' static initialiser throw. The ProGuard rules kept
`org.apache.commons.io.**` but **not** `org.apache.commons.compress.**`. The consequence was the worst
possible shape for a bug: **every site download worked in debug and failed in release**, because all
five site extractors depend on that unpack step.

Two things about how this behaved are worth recording:

- **Failure isolation worked.** The app did not crash. `YtDlpRuntime` catches `Throwable` and reports a
  status, so the panel degraded to "the engine could not be started" and every other action kept
  working. That is specification section 26 doing its job.
- **No unit test could have caught it.** It only exists in a minified build, on a device.

**Fix and verification.** `-keep class org.apache.commons.compress.** { *; }` was added to
`proguard-rules.pro` and the release rebuilt. Reinstalling the signed APK and asking it to analyse a
YouTube URL now shows the Python interpreter executing:

```text
granted { execute } .../data/com.linksi.app/no_backup/youtubedl-android/packages/python/usr/lib/libpython3.11.so.1.0
granted { execute } .../packages/python/usr/lib/python3.11/lib-dynload/zlib.cpython-311.so
```

and no `ExceptionInInitializerError`. The panel then correctly detected the source as **YouTube** and
hid the DOWNLOAD row entirely, because extraction returned `LOGIN_REQUIRED` (YouTube blocks
datacentre IPs — `[youtube] … Please sign in`). Showing no download control for something that cannot
be downloaded is specification section 67 working as intended.

### 12.4 Release artifacts for this build

| Artifact | Size | SHA256 |
|---|---|---|
| `LinksiEnhanced_3.1.1-enhanced.2_arm64-v8a.apk` | 37,900,688 B (36.14 MB) | `ECA4E4A35B87…6D040` |
| `LinksiEnhanced_3.1.1-enhanced.2_universal.apk` | 125,458,401 B (119.65 MB) | `B49CA7619146…A71E3` |

The size is the bundled Python interpreter plus one FFmpeg per ABI; before this dependency the
release APK was 5.21 MB. `versionCode` is now 22.

### 12.5 Still unverified

- **The mux path on a device.** Downloading a video-only stream and merging it with FFmpeg was
  started on the emulator but the Python subprocess hung and had to be killed; no merged file was
  ever observed. The selector, mux flag, naming and progress parsing are unit tested; the merge
  itself is not verified end to end.
- **MediaStore publish of a *muxed* file**, and the notification/WorkManager progress path.
- **The five target sites themselves** (Instagram, Facebook, TikTok, Pinterest, Reddit) were probed
  only for "returns a value rather than throwing" — none was confirmed to extract real formats,
  because the emulator's IP is blocked by most of them. A physical device on a residential connection
  is required for that, and is the single most valuable next test.

---

## 13. Addendum 5 — first run on physical hardware (POCO X3 Pro)

The owner attached a **POCO X3 Pro** (`M2102J20SG`, codename *vayu*): **Android 13 (API 33), MIUI 14,
arm64-v8a, 4 KB pages, 197 GB free**. This is the first real-device execution of the project, and it
answers questions the x86_64 emulator never could.

### 13.1 Install

`INSTALL_FAILED_USER_RESTRICTED: Install canceled by user` came from **MIUI's package verifier**, not
from the APK or the signing key. Enabling *Developer options → Install via USB* alone was **not**
enough; what fixed it was:

```text
adb shell settings put global package_verifier_user_consent -1
adb shell settings put global package_verifier_enable 0
adb shell settings put secure install_non_market_apps 1
```

After that the **arm64 release APK installed and launched**: `MainActivity` focused, process alive, no
`FATAL EXCEPTION`, no `UnsatisfiedLinkError`. (The one scary-looking line,
`FeatureFlagsImplExport: NoClassDefFoundError ... boot class loader`, is an optional-class probe and is
benign.)

### 13.2 The arm64 native payload works — a genuine gap closed

`YtDlpMediaSmokeTest` on the phone:

```text
I YtDlpSmokeTest: unpacked: python=true ffmpeg=true yt-dlp=2985408 bytes
I YtDlpSmokeTest: native payload for arm64-v8a: [libffmpeg.so, libffmpeg.zip.so, libpython.so, libpython.zip.so]
I YtDlpSmokeTest: candidate OK: https://dash.akamaized.net/akamai/bbb_30fps/bbb_30fps.mpd -> bbb_30fps (11 formats)
```

**CPython, FFmpeg and yt-dlp all unpack and execute on real arm64 hardware.** Until now every
execution had been x86_64 on the emulator, so the payload shipped in the arm64 release APK had never
run anywhere. It does now, and extraction produces the same 11 correctly-flagged formats
(2160p down to 180p with `mux=true`, then audio-only with `mux=false`).

### 13.3 The five sites: reached and classified, but not yet *extracted*

On a **residential IP** every extractor was reached. The results are real, and two of them are real
blocks rather than artefacts of the test's fake IDs:

| Probe | Classified as | What yt-dlp actually said |
|---|---|---|
| Instagram | `LOGIN_REQUIRED` | *"Requested content is not available, rate-limit reached or login required. Use --cookies…"* |
| Pinterest | `EXTRACTOR_FAILED` | *"Unable to download JSON metadata: HTTP Error 403: Forbidden"* — Pinterest blocks yt-dlp |
| TikTok | `EXTRACTOR_FAILED` | *"Video not available, status code 100002"* (fake id) |
| Facebook | `MEDIA_GONE` | *HTTP Error 404* (fake id) |
| Reddit | `EXTRACTOR_FAILED` | *"Failed to parse JSON"* (fake subreddit) |

**Read this carefully: it does not prove extraction works for real content.** The test deliberately
uses *not-a-real* identifiers so it can run unattended, so these lines prove the extractors are
reached, that failures are converted into user-facing reasons, and that nothing throws. Two
conclusions do follow: **Instagram now requires cookies/login even for public posts**, and
**Pinterest returns 403 to yt-dlp**. Neither is fixable by configuration alone.

**Next step:** repeat the probes with a handful of *real, public* URLs per site, which distinguishes
"blocked" from "our wiring is wrong".

### 13.4 The stalled merge download is NOT an emulator artefact

The same failure reproduces on the phone over a good residential connection:

```text
I YtDlpSmokeTest: downloading video-only format bbb_30fps_320x180_200k at 180p to force a merge
W YtDlpSmokeTest: the merged download ... did not finish within 120s; treating the merge path as unverified on this device
E TestRunner: assumption failed: aVideoOnlyFormatIsMergedAndPublishedWhereTheUserCanFindIt
```

That is an important negative result: **open item 1 is a real defect, not a slow emulator.** On the
emulator the evidence was a downloaded video-only stream plus a stalled audio `.part`; the same shape
should be checked on the phone (`cache/ytdlp/work-*/`). Since the network is now demonstrably good,
the next suspects are the yt-dlp invocation itself — the format/merge selector, the child process's
stdout/stderr handling (a full pipe buffer deadlocks a chatty child), or `destroyProcessById`'s
`pstree`/`grep -oP` dependency on a ROM that may lack them.

### 13.5 A caveat about reading these test results

`am instrument` printed **`OK (5 tests)`** for both the emulator and the phone run, while one test had
actually been *skipped* by a failed JUnit assumption (`run finished: 5 tests, 0 failed, 0 ignored`).
**The summary line is not the result.** Read the test's own logcat lines.

---

## 14. Addendum 6 — the merge path is verified, and it was a deadline, not a defect

The previous addendum concluded that the stalled merge download was *"a genuine defect rather than an
emulator artefact"* and listed the suspects as the yt-dlp invocation, the child process's stream
handling, or the kill path. **That conclusion was wrong, and this addendum corrects it.** The merge
path works, on the emulator and on real hardware.

### 14.1 The measurement that settles it

Running the app's own **raw** yt-dlp invocation, with no deadline, on the same emulator and the same
CDN. Everything else was identical — same selector, same output template, same
`--socket-timeout 20 --retries 3`:

```text
[download] 100% of    5.03MiB in 00:00:59 at 86.18KiB/s
[Merger] Merging formats into "/data/local/tmp/ytdlp-work/bbb_30fps.mp4"
Deleting original file .../bbb_30fps.fbbb_a64k.m4a (pass -k to keep)
Deleting original file .../bbb_30fps.fbbb_30fps_320x180_200k.mp4 (pass -k to keep)
EXIT=0
```

The audio stream, which the test had reported as stalled at 3,672,160 bytes, downloaded all
5.03 MiB in **59 seconds**. It had never stalled; the test gave up first.

### 14.2 Then the same test, on the phone, with a deadline that fits the bytes

`YtDlpMediaSmokeTest.aVideoOnlyFormatIsMergedAndPublishedWhereTheUserCanFindIt` on the **POCO X3
Pro** (arm64, Android 13, residential connection), with `-e ytdlpDownloadTimeoutSeconds 900`:

```text
22:13:11  Destination: .../work-97f21ada/bbb_30fps.fbbb_30fps_320x180_200k.mp4   (15.5 MiB stream)
22:16:11  Destination: .../work-97f21ada/bbb_30fps.fbbb_a64k.m4a                 (audio starts, 3 min later)
22:18:04  [Merger] Merging formats into ".../work-97f21ada/bbb_30fps.mp4"
22:18:05  merge attempt finished in 296s (397 progress updates):
          Completed(location=content://media/external/downloads/1001336263,
                    bytes=21210202, displayName=linksi-ytdlp-smoke.mp4, mimeType=video/mp4)
          merged 21210202 bytes in 296s (71596 B/s)
TestRunner: run finished: 1 tests, 0 failed, 0 ignored
```

**This is the first completed merged download in the project's history**, and the first published on
real hardware: a video-only DASH stream paired with a separate audio stream by the bundled FFmpeg,
then committed through `MediaStoreSink` as a `content://` row with `is_pending` cleared — readable at
the location the user is shown, which the test then verifies byte-for-byte.

### 14.3 What the 120-second budget was hiding

| | |
|---|---|
| Bytes actually transferred | 20.6 MiB (15.5 MiB video + 5.03 MiB audio) |
| Measured throughput, phone | **71,596 B/s** |
| Measured throughput, emulator | ~85 KiB/s |
| Time the transfer needs | **~300 s** |
| The deadline the test used | **120 s** |

The old `DOWNLOAD_TIMEOUT_SECONDS = 120` could not have succeeded on any connection this project has
ever measured. It reported the merge path as "unverified" three times — once on the emulator and
twice on the phone — and every one of those was the deadline expiring mid-transfer, not a hang. The
emulator run *also* produced one genuine hang (see 14.4), which made the wrong explanation look
consistent and is why it survived two sessions.

`DOWNLOAD_TIMEOUT_SECONDS` is now
`DEFAULT_DOWNLOAD_TIMEOUT_SECONDS = 900`, documented as a stuck-process bound rather than a
performance budget, overridable per run with `-e ytdlpDownloadTimeoutSeconds <n>`. The test also logs
the elapsed time and the achieved throughput on every run, so the next deadline is derived from
measurement instead of a guess. `-e ytdlpFormatId <id>` and `-e ytdlpUrl <url>` were added for the
same reason: making a run repeatable without editing the test.

### 14.4 The emulator hang was real, but it is a different problem

One run *did* hang: the audio `.part` file froze at 3,672,160 bytes and stayed byte-identical for
**nine minutes** while the yt-dlp process remained alive. The same run's video stream had logged
`[SSL] record layer failure (_ssl.c:2580). Retrying (1/3)...` on the way, and the emulator's virtual
radio (`netsimd`) crash-loops. So the emulator has two separate failure modes — slow, and
occasionally a dead connection — and only the second is a hang.

That second mode is a real product risk on any mobile network, and the app handled it badly: there
was **no bound of any kind** on a site-engine download. `YtDlpDownloader.download()` waited forever,
and cancelling the coroutine did not reliably stop the child. See §15 for the fix.

### 14.5 The five sites, restated

Unchanged from §13.3, and still the most valuable open test: extraction reaches every one of the five
targets on a residential IP, two are genuine blocks (Instagram demands cookies/login even for public
posts; Pinterest answers 403 to yt-dlp), and **none has been confirmed to extract real content**,
because the probe deliberately uses fake identifiers. That requires real public URLs, which is a
human input, not a code change.

---

## 15. Addendum 6b — the download watchdog, and two things the wrapper gets wrong

### 15.1 The app could not stop a stuck download

`YtDlpDownloader` had no stall detection and no hard deadline. A hung yt-dlp therefore produced a
progress bar frozen forever with a Cancel button that may not work — worse for the user than a clear
failure they can retry.

`YtDlpDownloadWatchdog.kt` now bounds every site-engine download:

| | |
|---|---|
| Stall limit | **60 s with no change in bytes transferred** (scaled up to 8× for large files) |
| Hard limit | **30 min** overall, for start-up that never produces a byte, or a transfer that trickles |
| Checked every | 5 s |

Progress is measured in **bytes, not output**. yt-dlp announces destinations, fragment counts and
post-processing steps without transferring anything, so a callback is not evidence of progress: only
a change in the reported byte count is. Eighteen unit tests pin the rule, including the two cases
that a naive timer gets wrong:

- a byte count which goes **backwards** must not be read as forward motion. It happens legitimately
  when yt-dlp moves from the video stream to the audio one, and it is precisely the stream that hung
  in the real incident;
- silence **after a stream reports 100%** is post-processing, not a dead socket. See §15.5 for how
  that was learned.

### 15.5 The rule was wrong twice, and only a device said so

Both corrections came from running the tests on hardware, not from reading the code, which is the
argument for the device tests existing at all.

| Version | What it did | How it failed | Fix |
|---|---|---|---|
| v1 | "No bytes for N seconds" | yt-dlp spends its first ~25 s fetching the page and parsing the DASH manifest before any media moves, so the rule killed healthy downloads **before they began** | A transfer that has not started has not *stalled*; only the hard limit applies until the first byte |
| v2 | "No bytes for N seconds, after the first byte" | yt-dlp printed `[download] 100%` and then spent **2 min 48 s** quiet in FFmpeg's postprocessor while it rewrote a 75 MiB file — the rule killed a download that was about to succeed | Silence is only a stall while a stream is **mid-transfer**; reaching the reported total ends that window |

v3, the current rule, therefore tracks three things: whether bytes ever moved, whether the stream
under way has reached its total, and when the last byte arrived. A second stream resets the clock,
because its byte count legitimately restarts from near zero.

The production limits (60 s / 30 min) were never the problem — the device failures were the rule
firing on ordinary start-up and post-processing, which the production limit would eventually have
done too on a slow connection. That is why this is a correctness fix rather than a tuning change.

Verified separately: `tests: 729, failures: 0` for the whole unit suite (701 baseline + 20 watchdog +
8 notification-permission, §16).

### 15.2 `destroyProcessById` cannot work, and the research says why

The wrapper's child-kill path is broken three independent ways:

1. It builds `pstree -p <id> | grep -oP '\(\K[^)]+' | xargs kill` and passes the library's **own
   arbitrary string process id** (`"linksi-ytdlp-<uuid>"`), not an OS pid. The command is literally
   `pstree -p <uuid>`, so it can never work for any caller — a real bug shipped in 0.17.3.
2. `pstree` does not exist on Android: toybox has no `pstree`, and AOSP's symlink list does not
   create one.
3. toybox `grep` has `-o` but not `-P`.

Net effect: only `Process.destroy()` (SIGTERM to the direct python child) runs. Descendant processes
are not killed by the wrapper. Upstream tracks this (#261, #263, and open PR #367 which replaces the
one-liner with a `/proc/.../children` walk).

The app does **not** try to reproduce that fix with its own `kill`: guessing pids from `ps` output
inside an app process risks signalling something it does not own. Instead the app relies on the two
mechanisms that do work — SIGTERM to the interpreter, and the interpreter's own cleanup closing the
pipe FFmpeg reads — and `YtDlpInterruptedDownloadTest` checks the **outcome** on a device: after a
cancel, no child of this app may still be running the bundled interpreter.

### 15.3 A retry could not resume, while the code claimed it could

`YtDlpDownloader.download()` deleted its scratch directory in a `finally` block on **every** exit
path, and the directory was named after the attempt (`work-<uuid>`). yt-dlp keeps its progress in a
`.part` file plus a `.ytdl` marker and skips what they describe — including an already-complete video
file in a two-stream download — but all of that was being thrown away. A flaky network could
re-download the same bytes forever without ever finishing, and the watchdog's own message ("it can be
retried and will continue where it left off") was false when it was written.

Now:

- The scratch directory name is a **digest of the download's identity** (URL + format selector +
  requested name), so the same download always maps to the same directory and two different ones
  never collide. Unit tested, including that a hostile title cannot escape the directory.
- After a **transient** failure the partial download is **kept**, so the retry resumes.
- After a success, or a permanent failure (unsupported site, media gone, no storage), the directory
  is deleted as before.
- A stale sweep at the start of each download reclaims unfinished scratch directories older than an
  hour, so keeping partial files cannot grow without bound in the app's cache.
- `--fragment-retries 10` and `--force-ipv4` were added: one refused DASH fragment should not abandon
  a 160-fragment stream, and IPv4 is the maintainer-endorsed workaround for stalled CDN connections.

### 15.4 yt-dlp's own options cannot bound this

Research (recorded in `research\YTDLP_HANG_RESEARCH.md`) confirms the watchdog is the right layer.
`--socket-timeout` does reach `socket.settimeout`, so a *completely silent* read does raise and
become retryable — but the downloader reads in blocks of up to 4 MiB and every arriving byte restarts
the timer, so a **trickling** connection blocks forever with no output and a frozen `.part`. That is
[yt-dlp #8579](https://github.com/yt-dlp/yt-dlp/issues/8579), open and unfixed, and the maintainer's
own comment is *"would be nice if we could fix the http downloader so it errors instead of hanging
like that though"*. DNS resolution is separately unbounded. No yt-dlp flag can end a silent hang.

---

## 16. Addendum 6c — `POST_NOTIFICATIONS` is now requested (open item 6)

Upstream declares the permission but never asks for it, so on Android 13+ every download notification
was silently dropped: `DownloadNotifications` checks the permission and declines to post rather than
failing. The download itself worked, so nothing looked broken.

Requested at runtime now, from **two** in-context triggers and never at launch (specification
section 33): when the user switches *Download notifications* on, and at the first download from the
quick action panel while that setting is on. Granted or already-asked in the same visit means no
dialog. The answer is discarded either way — the download is enqueued regardless, and
`DownloadNotifications`' own failure policy is untouched. Below API 33 the permission does not exist,
so `isGranted` short-circuits to true and no dialog is ever shown. The worker itself never asks: it
has no UI. Eight pure unit tests cover the decision rule across the API boundary.

This was implemented by a delegated subagent working in this same checkout; its verification was
`DownloadNotificationPermissionTest` (8 tests, 0 failures) plus a lint run with 0 errors and an
unchanged issue count, and the whole suite was then re-run by the main session (§17).

---

## 17. Addendum 6d — verification totals for this session

| Check | Result |
|---|---|
| `:app:testDebugUnitTest` | **729 tests, 0 failures** (701 baseline + 20 watchdog + 8 notification permission) |
| `:app:lintDebug` | 0 errors, 185 issues — identical to before these changes |
| Merge path on the POCO X3 Pro | **PASS** — 21,210,202 bytes merged and published (§14.2) |
| Merge path, raw yt-dlp, emulator | **PASS** — `EXIT=0`, `[Merger]` produced the file (§14.1) |
| Cancellation, resume and watchdog on a device | `YtDlpInterruptedDownloadTest` — 3 tests. **Status: partial.** Cancellation and resume passed on the emulator. The watchdog test was fixed twice and then its re-run was stopped by the session owner after ~7 minutes because the emulator's radio was in an SSL-retry storm (`[SSL] record layer failure ... Retrying (1/3)` repeating several times a second); no device run has yet *observed the watchdog fire*. The rule itself is covered by 20 unit tests and by the two device-found bugs recorded in §15.5 |

The three earlier reports of an unverified merge path are **superseded**, not deleted: §12.5 and
§13.4 are kept because the reason they were wrong (a 120-second deadline against a ~300-second
transfer) is itself a finding worth not repeating.

---

## 18. Addendum 7 — closed device gaps, Android 16, and real Facebook probes (2026-09-18)

### 18.1 Deterministic watchdog, resume and cancellation on the POCO

The former public-network tests were replaced/supplemented with loopback servers in
`YtDlpInterruptedDownloadTest`, then run on the POCO X3 Pro (Android 13, arm64):

| Check | Result |
|---|---|
| Caller cancellation | **PASS** — returned in 9.631 s instead of waiting ~230 s for the stream; no interpreter survived |
| Real watchdog path | **PASS** — server sent 62,914 bytes then stalled; watchdog stopped yt-dlp after the configured interval and returned `NETWORK`, `transient=true`, exact stall detail (15.185 s) |
| Real transient-resume branch | **PASS** — first connection dropped; scratch was kept; retry sent `Range` at exactly byte 258,048 and published a byte-identical 4 MiB payload (4.638 s) |

This closes the old §17 “partial” result. The public-stream cancellation/resume cases remain useful
as supplemental coverage, but correctness no longer depends on a CDN behaving predictably.

### 18.2 Android 16 target migration

The app now compiles and targets API 36. `ShareReceiverActivity` and `QuickPanelActivity` opt into
edge-to-edge, the Quick Panel no longer forces decor fitting, and onboarding applies safe-drawing
insets to page content and bottom controls.

On the Android 16/API 36 emulator:

| Suite | Result |
|---|---|
| Installed package target | **PASS** — `targetSdk=36` |
| `CoreFlowsSmokeTest` | **PASS — 4/4** |
| `Android16CompatibilitySmokeTest` | **PASS — 4/4**: target, Share Receiver content, explicit-URL Quick Panel content, onboarding control above navigation bar |

An independent review caught that the first Quick Panel smoke test used the wrong intent-extra name;
it now launches through `QuickPanelActivity.intentFor(...)`. The first onboarding-bounds assertion
also correctly failed because it measured the padded container rather than the visible button; the
final assertion measures the button users interact with and passes on API 36.

### 18.3 Notification permission and MIUI install state

The owner granted notification permission to the installed Linksi package on the POCO. Device state
confirms `POST_NOTIFICATIONS: granted=true` with `USER_SET`. A fresh debug package remained ungranted,
confirming this was the release package's real runtime permission and not a stale debug result.

MIUI's Android package-verifier flags were already disabled, but MIUI still required an on-device
confirmation for each ADB install. After the owner disabled the hidden **MIUI optimization** switch,
an arm64 debug reinstall completed unattended in 2.3 s with `Success`.

### 18.4 Five real Facebook URLs

The supplied Linksi export contained nine Facebook links and no links for the other four primary
targets. Five real public Facebook links were passed independently to the production extractor on
the POCO residential connection. Every instrumentation run completed (`OK (1 test)`, no crash or
orphan process), but all five returned `MediaExtractionResult.Unsupported` with zero formats in
3.286–7.287 s.

This is useful negative evidence: Facebook is no longer “untested with real IDs.” The bundled
extractor does not currently support these real Reel/share URL forms. The next implementation task
is a best-effort, throttled update of yt-dlp through the wrapper's supported stable-channel updater,
followed by the same five probes. Instagram, TikTok, Pinterest and Reddit still need real public
sample URLs.

### 18.5 Final local gates

| Check | Result |
|---|---|
| `:app:testDebugUnitTest` | **729 tests, 0 failures, 0 errors** |
| `:app:lintDebug` | **BUILD SUCCESSFUL**; report generated |
| `:app:assembleDebug :app:assembleDebugAndroidTest` | **BUILD SUCCESSFUL** |
| Crash/orphan check after Facebook probes | **PASS** — no Linksi crash, instrumentation, Python or FFmpeg process remained |

---

## 19. Addendum 8 — the engine refresh, and real Facebook Reels now extract

§18.4 named the next task as *"a best-effort, throttled update of yt-dlp … followed by the same five
probes"*. This addendum is that work, with one deliberate deviation: **the wrapper's own stable-channel
updater is not used**, because it can leave the app with no engine at all (see 19.3).

### 19.1 Root cause, measured

All nine links from the owner's export were run against two engines, the pinned one on the device and
a current release on the host over the same network:

| Engine | Extracted |
|---|---|
| bundled **2024.09.27** (what `youtubedl-android` 0.17.3 ships) | **0 of 9** |
| current release (**2026.08.19**) | **5 of 9** |

The four that fail on both are dead share links (`/share/v/…`, `/share/r/…`): the latest yt-dlp
answers `Unsupported URL: …?_fb_noscript=1` for each, and even Facebook's own crawler user-agent
returns a 66 KB page shell with no video id. They cannot be extracted by any engine and would have to
be re-shared from the app. The five that do work include **`reel/1710485373378939`, the
specification's own worked example.** The gap was therefore a year-old extractor, not a test artefact.

### 19.2 Verified on the emulator, end to end

The pinned engine was restored from the APK payload and the update bookkeeping cleared, so one run
exercised download → verify → swap → extract:

```text
YtDlpUpdater: staged 3072469 bytes (installed copy is 2985408 bytes)
YtDlpUpdater: checksum verified against the published digest
YtDlpUpdater: version probe of yt-dlp (3072469 bytes) exit=0 out=2026.08.19
YtDlpUpdater: site engine updated: 2024.09.27 -> 2026.08.19
YtDlpSmokeTest: real Facebook probe of https://www.facebook.com/reel/1710485373378939/ -> Success(...)
YtDlpSmokeTest: extracted '1.4M views · 56K reactions | Jenny Squibb | Breakfast Ideas …'
                with 11 formats: 1920p, 1280p, 1280p, 1280p, 1280p, 1280p, 1280p, 1280p,
                Original quality, Original quality, Audio only
```

A second refresh in the same run correctly reported `AlreadyCurrent(2026.08.19)` with
`digests differ: false`, so the check is not downloading a 3 MB asset on every use.

### 19.3 Why not the wrapper's `updateYoutubeDL()`

Reading its bytecode showed a defect that is invisible from the outside and destructive: it **deletes
the whole `yt-dlp/` directory before copying the new binary in**, and its failure recovery calls
`init_ytdlp`, which decides what to do by asking whether that directory exists. It does exist, having
just been emptied, so the recovery silently does nothing. A download that fails at the wrong moment
therefore leaves the app with **no engine at all, permanently**, and every later site download reports
the engine unavailable. It also checks through the GitHub API — 60 unauthenticated requests an hour.

`YtDlpUpdater` keeps the useful part and drops the risk:

| Property | Why it matters |
|---|---|
| Fixed `/releases/latest/download/yt-dlp` URL | No API call, no rate limit |
| SHA-256 checked against the published `SHA2-256SUMS` | The only proof the bytes are the released ones; a truncated body and an HTML error page both look like "some bytes" |
| Zipapp shape check (shebang + `PK\x03\x04`) | Rejects HTML before anything else reads it |
| Staged download + atomic rename, old file kept | A dropped connection cannot destroy a working engine, and the engine path is never empty |
| Post-install probe with rollback | An engine that will not run, or that reports the version it replaced, is put back exactly as it was |
| Once a week, only when a download already needs the engine | Never startup work (specification §26); a failed check does not retry on every download |
| Manual **Check** in Settings → Enhanced features → Site engine | Shows the installed version; refresh on demand |

### 19.4 A trap worth recording: the pre-install probe lies

Two device runs were spent on an updater that downloaded **authentic, checksum-verified** bytes and
then refused to install them, because the version probe of the staged file reported the *installed*
version. The pattern is reproducible: the same asset reports the old version while it sits under a
temporary name beside the old engine, and reports its own version the instant it is renamed into
place. The resulting rule is now encoded in the code: **decide from the digest, verify from the
version only after the swap.**

The bug also produced a useful side finding — the updater's own log line, not the test assertion, is
what exposed it, which is why the diagnostics were kept rather than removed once it worked.

### 19.5 The extractor's error text is no longer thrown away

`YtDlpExtractor` classified yt-dlp's stderr into a user-facing `MediaError` and discarded it, so the
app could say only that a link was `Unsupported` while yt-dlp knew exactly why. The raw output is now
logged, with URL query strings redacted. That one line —
`ERROR: [facebook] 1710485373378939: No video formats found!` — is what turned a year-old mystery into
a one-line diagnosis, and it is the reason §18.4 was able to say "the extractor does not support these
URL forms" instead of "Facebook refused us".

### 19.6 Totals for this addendum

| Check | Result |
|---|---|
| `:app:testDebugUnitTest` | **744 tests, 0 failures** (729 + 15 for the updater: zipapp shape, digest decision, version ordering) |
| Engine refresh on the emulator | **PASS** — downloaded, checksum-verified, swapped, re-probed |
| Real Facebook Reel through the app's extractor | **PASS** — 11 formats, up to 1920p |
| `POST_NOTIFICATIONS` on the POCO | **PASS** (device-reported, §18.3) |

Still outstanding: the same refresh has **not** been observed on the POCO. Its network returned a file
of the published size that the app's probe read as the *installed* version; the digest check and
post-install rollback mean that can only end in a safe refusal, but which of those it does there is
unmeasured, and Instagram, TikTok, Pinterest and Reddit still need real sample URLs.

---

## 20. Addendum 9 — signed release built and run under R8, and a junk-file defect it exposed

### 20.1 The release

`3.1.1-enhanced.3`, **versionCode 23** — the first build carrying the download watchdog, the site-engine
refresh, the runtime notification permission, the page-signature guard and `targetSdk` 36.

| Artifact | Size | SHA-256 |
|---|---|---|
| `LinksiEnhanced_3.1.1-enhanced.3_arm64-v8a.apk` | 37,915,528 B | `14C0354320CE5D717CAEF62784E2BE524CF4B00949C90376ACFBFE12B96BDB99` |
| `LinksiEnhanced_3.1.1-enhanced.3_universal.apk` | 125,473,245 B | `994605A31B25B9CD31926CA0E357863E65AE75D2D7043A358898D18A2720C729` |

`versionCode` had to move past 22 even though the previous archive was never distributed: builds of 22
already exist on both test devices, and an equal `versionCode` cannot install over them.

### 20.2 The signed build works under R8

Installed and launched on the POCO X3 Pro and on the API 36 emulator, with no `FATAL EXCEPTION`, no
`ClassNotFoundException` and no `NoSuchMethodError`. Its quick action panel renders correctly with a
cleaned URL, and on the emulator it **completed a real download end to end**:

```text
WM-WorkerWrapper: Starting work for com.linksi.app.enhanced.download.DownloadWorker
WM-WorkerWrapper: Updating progress … download.bytes : 4252
MediaProvider: Moving /storage/emulated/0/Download/.pending-… to /…/1710485373378939.vndwapxh
WM-WorkerWrapper: Worker result SUCCESS
```

This matters because release-only failures in this project have been real twice before: the R8/Room
crash in §12.3 and the `commons-compress` `ExceptionInInitializerError` that broke site downloads in
release while debug worked. Both were found by running the signed build, not by a test.

The `-keep` rules that make the engine survive R8 are in `app/proguard-rules.pro` and were not
changed. The updater needed no new rules.

### 20.3 The defect that run exposed: a web page saved as a downloaded file

The successful-looking download above was **not** a video. The 4,252-byte file began
`<?xml version="1.0" encoding="utf-8"?>` — it was Facebook's error document, saved into the user's
Downloads collection as `1710485373378939.vndwapxh`, with **no error shown to the user**.

The path that produced it: the site engine could not read the link (its `Unsupported` result), so the
worker fell through to the direct downloader, which classifies a URL as a file from its **response
content type**. Facebook served the error document as a non-page type, `DirectFileClassifier`'s
`PAGE_LIKE_TYPES` check passed, and the bytes went straight into MediaStore.

**Fix:** the body's leading bytes are now inspected before anything is written.
`DirectFileClassifier.looksLikeMarkup` looks only for *openers* — `<?xml`, `<!doctype`, `<html`,
`<rss`, `<feed`, `<svg` — because no media container starts with one, so the cost of a false positive
is a clear failure rather than a file that will not play. `DirectFileDownloader.stream` peeks the first
2 KB with OkHttp's `Response.peekBody`, which leaves them in place for the copy that follows.

Verified on the emulator against the same link:

```text
DownloadWorker: download clip-…-direct-… failed: EXTRACTOR_FAILED
                (the server returned a page rather than a file)
```

and the Downloads directory stays clean. Two unit tests cover the signature and — more importantly —
that JFIF/PNG/`ftyp`/`ID3`/`%PDF` openings are **not** mistaken for documents, since refusing a genuine
download would be the worse failure.

### 20.4 A false start worth recording

The first attempt to verify this guard "passed" for the wrong reason: the APK being tested had been
built **before** the guard existed, so an unaffected build was installed and the junk file appeared
exactly as before. The lesson is the same one this project keeps relearning — check that the artifact
under test is newer than the edit, not merely that it installed.

### 20.5 Totals

| Check | Result |
|---|---|
| `:app:testDebugUnitTest` | **746 tests, 0 failures** (744 + 2 for the page signature) |
| `:app:lintDebug` | **0 errors** |
| `assembleRelease` (tests + lint, then R8) | **BUILD SUCCESSFUL**, both ABIs signed and hash-recorded |
| Signed release on the POCO | **PASS** — installs, launches, no R8-related fault |
| Signed release on the emulator | **PASS** — panel renders, download completes, MediaStore write succeeds |
| Page-signature guard | **PASS** — the junk file no longer appears; the failure is reported instead |

Nothing has been pushed and no tag has been created.

---

## 21. Addendum 10 — the guard alone still failed the user, and now the download works

§20.3 stopped a web page being saved as a file. That was necessary and not sufficient: the user who
tapped *Download* on a Facebook Reel still got a **failure**, when the app contained an engine that
could fetch the video.

### 21.1 Why the engine was never consulted

The panel offered exactly one format — *Original quality*, which is the direct downloader's marker —
so the request never satisfied `needsSiteEngine`, and the direct path was taken. The direct probe
believed Facebook's content type, so the extractor was never asked, `yt-dlp` was never started, and
`/data/data/com.linksi.app/no_backup/…/yt-dlp/` **did not exist on the device after the attempt** -
which is how the omission was proved rather than inferred:

```text
DownloadWorker: download clip-…-direct-… failed: EXTRACTOR_FAILED
                (the server returned a page rather than a file)
```

A failure is the honest report, but it is not the right outcome: the same URL yields a playable video
through the app's own extractor.

### 21.2 The fallback

`DirectFileDownloader` marks that one outcome with the constant `NOT_A_FILE_DETAIL`, and
`DownloadWorker` now recognises it and hands the request to the site engine before giving up:

```text
DownloadWorker: download clip-…-direct-… was not a file after all; trying the site engine
MediaProvider: Moving /storage/emulated/0/Download/.pending-… to /…/1710485373378939.mp4
WM-WorkerWrapper: Worker result SUCCESS
```

The result, on the emulator, through the whole production chain:

| | |
|---|---|
| Before | a 4,252-byte XML error page, or a reported failure |
| After | `1710485373378939.mp4`, **6,777,555 bytes**, beginning `\0\0\0 ftypisom` |

Only that one detail triggers the hand-over. A stalled or refused transfer still goes through the
ordinary retry policy, because re-running it through a whole Python interpreter would be slower and no
more likely to succeed — the fallback is for "this URL is not a file", not for "this transfer failed".

### 21.3 Why the device test was the only thing that could find this

Three separate verifications were already green when this defect existed: the updater refreshed the
engine correctly, the extractor read the URL correctly, and the guard refused the page correctly. What
was broken was **the wiring between them** — and no unit test covers `DownloadWorker`'s routing because
it needs Hilt, WorkManager, a sink and a real extractor. It took tapping Download on a real link in a
build that contained the fix.

The general lesson, which this project has now paid for three times: verify the **user's path**, not
the components. The updater and the extractor were each proven; pressing the button was not.

### 21.4 Totals

| Check | Result |
|---|---|
| `:app:testDebugUnitTest` | **746 tests, 0 failures** |
| Facebook Reel through the panel, emulator | **PASS** — 6,777,555-byte MP4 published to Downloads |
| Site-engine fallback | **PASS** — `was not a file after all; trying the site engine` |

---

## 22. Addendum 11 — a dropped connection used to restart the transfer, not resume it

### 22.1 What the earlier failures were actually made of

Running the full flow on the emulator produced a failure that the earlier addenda had attributed to the
network alone. The child's own output showed the mechanism:

```text
[download]  89.9% of 6.46MiB at 1.62MiB/s ETA 00:00
[download] Got error: [SSL] record layer failure (_ssl.c:2580). Retrying (2/3)...
[download] Destination: …/…Liam Layton.mp4        <- started again
[download]  97.6% …
[download] Got error: [SSL] record layer failure (_ssl.c:2580). Retrying (3/3)...
[download] Destination: …                          <- started again
DownloadWorker: failed: EXTRACTOR_FAILED (Giving up after 3 retries)
```

Every retry re-created the destination and began the transfer from the beginning, so a 6.46 MiB file
that had reached **97.6%** still failed after three attempts. On a link that drops a connection every
few megabytes — the test emulator's virtual radio, and any flaky mobile connection — that makes a
download of any size impossible however many times it is retried.

### 22.2 The fix, with the measurement that justifies it

yt-dlp continues a partial file only when told to, so `--continue` was added to the invocation. The
apparatus for proving it was the engine's own, on the device:

```text
--- after pass 1 (interrupted at 6s) ---
-rw-rw-rw- 1 root root 2096128 … out.mp4.part
=== pass 2: same command ===
[download] Resuming download at byte 2096128
[download] 100% of    6.46MiB in 00:00:05 at 1.11MiB/s
--- after pass 2 ---
-rw-rw-rw- 1 root root 6777555 … out.mp4
```

It resumes **at the exact byte**, and the finished file is **6,777,555 bytes — identical to the size of
the download the app itself completed** earlier in this session. So the CDN honours range requests, and
the flag does what it claims.

### 22.3 What is verified, and what is not

Honest split, because the distinction matters here:

- **Verified:** `--continue` is in the app's flag set; the engine resumes at the recorded byte; the CDN
  supports it; the app's scratch directory is kept after a transient failure so a retry has something
  to resume from; the watchdog ends a wedged attempt instead of hanging.
- **Verified:** with the flag, the app's own attempts now reach **96.5–99.4%** instead of restarting
  from the beginning.
- **Not verified:** a *completed* download through the app on the emulator's radio. It kept dropping
  the connection inside the last few per cent, and yt-dlp's three in-run retries were exhausted before
  the transfer finished. The same download completes on the phone (§21.2) and completed on the emulator
  earlier in the session when the radio was better behaved.

The remaining failures are environmental, and saying so is only credible because the mechanism is now
measured rather than assumed: the difference between "the network is bad" and "the app cannot recover
from a bad network" is exactly what the resume evidence above settles.

### 22.4 A caution about the diagnostic scripts

Two attempts to prove this through the ad-hoc `local\.probe` runner failed on **quoting bugs in the
harness** — an apostrophe inside a here-string that the device shell then parsed as an unterminated
quote, and one inside a generated comment. Both produced plausible-looking output (`'flags' is not a
valid URL`) that could easily be mistaken for a product bug. The working pattern remains the one in
`SESSION_HANDOVER.md` §4.8: generate the script with no free-form prose in it, push it, run it, and
read the whole output rather than a filtered tail.

---

## 23. Addendum 12 — three retries was too few, and a completed download could be reported as failed

### 23.1 The retry count, measured rather than guessed

§22 left the download incomplete on the emulator's radio and attributed it to the network. That was
only half true: the same link needs **more retries than yt-dlp's default of three**, and that is a
setting, not weather. The app's own flag set was run against the same Reel, twice, changing nothing but
the retry count:

| `--retries` | Outcome | Errors survived | Elapsed |
|---|---|---|---|
| **3** (the default, and what the app used) | **INCOMPLETE** — `.part` at 6,688,788 of 6,777,555 bytes | 4 | 9 s |
| **10** | **COMPLETE** — 6,777,555 bytes | 8 | 9 s |

Three retries did not fail because the link was unusable; it failed **88 KB short of the end**. Raising
the count to 10 costs almost nothing because every retry resumes rather than restarts (§22), so the
extra attempts re-fetch a few hundred kilobytes each instead of the whole file. The bound on a truly
dead network is unchanged: the stall watchdog ends an attempt that transfers nothing, and
`--socket-timeout` bounds each connection.

With the change, the app's own download survives the drops:

```text
YtDlpDownloader: [download] Got error: [SSL] record layer failure. Retrying (1/10)...
YtDlpDownloader: [download] Got error: [SSL] record layer failure. Retrying (2/10)...
YtDlpDownloader: [download] Got error: [SSL] record layer failure. Retrying (3/10)...
YtDlpDownloader: [download] 100% of 6.46MiB in 00:00:05 at 1.23MiB/s
```

### 23.2 A completed download reported as a failure

With the transfer fixed, a new problem appeared: **`Worker result FAILURE` while the finished
6,777,555-byte MP4 sat in Downloads**. The underlying exception, once the sink logged it instead of
discarding it:

```text
android.database.sqlite.SQLiteConstraintException: UNIQUE constraint failed: files._data
  at MediaStoreSink$Handle.commit(MediaStoreSink.kt:108)
```

A leftover MediaStore row already claimed that file path, so flipping `IS_PENDING` to 0 was rejected.
The row was left behind by the *earlier* attempt at the same name — in this flow, always, because the
direct download opens a sink for `…vndwapxh`, is refused by the page guard (§20.3) and aborts, and the
site engine then publishes `…mp4`.

`commit` now: publishes; if that is refused, reads the path MediaStore recorded, deletes any **other**
row claiming that exact path, and publishes again. It reports failure only when the entry is genuinely
absent or still hidden. The result:

```text
DownloadWorker: download …-direct-… was not a file after all; trying the site engine
MediaStoreSink: publishing content://media/external/downloads/80 threw   <- conflict cleared, retried
WM-WorkerWrapper: Worker result SUCCESS
```

`DownloadEngineInstrumentedTest` — the test that asserts exactly this contract, including
`is_pending=0` — still passes (`run finished: 1 tests, 0 failed, 0 ignored`), so the happy path was not
disturbed.

### 23.3 What this sequence demonstrates

Four defects were found in one user action, each hidden behind the one before it:

| # | Defect | Revealed by |
|---|---|---|
| 1 | the site engine was never tried | tapping Download in the release build |
| 2 | retries restarted instead of resuming | reading the child's own output |
| 3 | three retries was too few for the link | measuring 3 against 10 |
| 4 | a completed download was reported as failed | noticing `SUCCESS`-sized file with `FAILURE` result |

Each was invisible to the check that had passed before it, and three of the four were invisible to unit
tests by construction — `DownloadWorker`'s routing and `MediaStoreSink`'s publish both need a device.
The practical rule this leaves: after fixing a defect, **re-run the same user action and read the
outcome**, because the next defect is usually standing where the last one was.

### 23.4 Totals

| Check | Result |
|---|---|
| `:app:testDebugUnitTest` | **746 tests, 0 failures** |
| `DownloadEngineInstrumentedTest` (MediaStore contract) | **PASS** — 1 test, 0 failed |
| Facebook Reel through the panel, emulator | **PASS** — 6,777,555-byte MP4, `Worker result SUCCESS` |
| Retry resilience | **PASS** — survives 3+ connection drops in one attempt |

---

## 24. Addendum 13 — the baseline Linksi flow, and the URL cleaner, verified end to end on the POCO

The specification's first requirement is that the fork **keeps baseline Linksi working**. That had been
asserted from the baseline review but never exercised as a user action on real hardware with a real
link. This addendum closes it, and in doing so proves that the URL cleaner is not merely unit tested but
actually **wired into the save path** — which is a different claim.

### 24.1 The whole flow, on the POCO release build

A share intent was delivered to the app exactly as a browser would send it, carrying a Facebook Reel
with two tracking parameters attached:

```powershell
adb shell am start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT 'https://www.facebook.com/reel/1566279901580684/?utm_source=test&fbclid=abc123' \
  -n com.linksi.app/com.linksi.app.ui.screens.ShareReceiverActivity
```

| Step | Observed |
|---|---|
| Share receiver | opened the save sheet |
| Cleaned URL | `https://www.facebook.com/reel/1566279901580684` — **both trackers removed** |
| Metadata | real title fetched from Facebook |
| Controls | *Save to folder*, *Edit link*, *Reminder*, *Expiry*, *Note*, *Tags* all present |
| Save Link | sheet closed, main list focused |
| Main list | `facebook.com`, the title, and **"Just now"** — the link persisted and renders |

No `FATAL EXCEPTION` at any point. This is the baseline save path working on a real device, through the
real entry point, with the cleaner applied to a real tracking-laden URL.

### 24.2 The stored row, not just the display

Showing a cleaned URL proves nothing about what was saved: the sheet could display one string and store
another. The debuggable build was used so the row itself could be read — the share intent was repeated
with different trackers (`?utm_source=round4&fbclid=trackme99`), and the database was pulled and queried:

| id | stored `url` |
|---|---|
| 4 | `https://www.facebook.com/reel/28178846218433472` ← shared as `…?utm_source=round4&fbclid=trackme99` |
| 3 | `https://example.com/File?id=AbC123` |
| 2 | `https://example.com/article?id=42` |
| 1 | `https://www.facebook.com/reel/1710485373378939` |

Two other things are visible in that table and worth keeping:

- **The stored row is clean**, with no query string at all once the only parameters were trackers. The
  cleaner runs on the save path, not just in the preview.
- **`File?id=AbC123` kept its capitalisation.** That is the `normalizeUrl` whole-URL-lowercasing defect
  fixed earlier in the project; this is independent confirmation that later saved links are no longer
  being case-folded.

A regression test now pins the exact shared URL in `UrlCleanerTest`
(`aLinkSharedIntoTheAppIsStoredCleaned`), because this is the case the unit suite could not distinguish
on its own: the tests would all pass with the cleaner implemented but never called.

### 24.3 Totals

| Check | Result |
|---|---|
| `:app:testDebugUnitTest` | **747 tests, 0 failures** (746 + the shared-URL cleaner case) |
| Baseline save flow, POCO release build | **PASS** — share → clean → metadata → save → list |
| URL cleaner on the save path | **PASS** — read from the app's own SQLite row, trackers absent |
| Baseline case preservation | **PASS** — `File?id=AbC123` stored with its case intact |

Still open from this addendum: the same flow was driven on the **emulator** for the database read
(the release build on the POCO is not debuggable), so the row-level evidence is from the emulator while
the UI-level evidence is from the phone. Both are real devices; neither is the owner's ColorOS target.

---

## 25. Addendum 14 — the accessibility service binds on MIUI, and where the API 36 suite really runs

Two modules had been "implemented, unverified on hardware" since the first session: the accessibility
service and the floating bubble. This round got as far as the service and stopped at a real limit.

### 25.1 The service binds and stays bound on the POCO

```
Bound services:{Service[label=Linksi link detection (optional), feedbackType[FEEDBACK_GENERIC],
  capabilities=1, eventTypes=[TYPE_VIEW_CLICKED, TYPE_WINDOW_STATE_CHANGED,
  TYPE_WINDOW_CONTENT_CHANGED, TYPE_VIEW_TEXT_SELECTION_CHANGED], notificationTimeout=100]}
Crashed services:{}
```

That is the module's foundation working on a second manufacturer (MIUI 14 / Android 13, arm64):
the service is bound by the system, holds the four event types it declares, and the app's own label and
disclosure are what the platform reports.

The overlay permission was granted and verified independently of the app:

```
appops set com.linksi.app.debug SYSTEM_ALERT_WINDOW allow
SYSTEM_ALERT_WINDOW: allow
```

The three switches the bubble depends on were then enabled **through the real settings UI**, and the
Compose state was confirmed from Android's own view hierarchy rather than from the app's logs:

```
Smart Link Detection      checked=true
Floating Linksi Bubble    checked=true
Accessibility assistance  checked=true
Download notifications    checked=true   (already on)
```

### 25.2 A false alarm worth documenting

`dumpsys accessibility` first reported the service under **`Crashed services`**. It was not a defect:
the app had been `force-stop`ped a moment earlier, and Android records a stopped service that way. After
launching the app and re-enabling the service it went to `Bound services` with `Crashed services:{}` and
stayed there. **Anyone debugging this module should launch the app before enabling the service**, or
the first thing they will read is a crash that is not one.

### 25.3 A trap of my own making, also worth documenting

Tapping the *Accessibility assistance* switch took me to **Android's** accessibility settings rather
than toggling anything. That is the app behaving as designed — `openAccessibilitySettings` runs when
the service is not enabled *system-side* — but it cost a navigation cycle to work out, because the
design intent ("send the user to the right page rather than pretend") and the symptom ("my tap opened
settings") look like different things until you know the rule.

### 25.4 The bubble itself is still unverified, and the reason is concrete

Rendering the bubble needs a real link-copy gesture. This ROM has **no clipboard helper** (`which
clipper clipboard` finds nothing; only `am` is present), no Chrome, and `set-clipboard`-style commands
do not exist. Driving the in-app browser by intent merely re-triggered the share receiver. So the
overlay's appearance remains a **manual** check, and the device is deliberately left in a state where
the owner can perform it in about ten seconds:

1. The debug build is installed on the POCO with the accessibility service **enabled and bound**.
2. Its three switches are on and `SYSTEM_ALERT_WINDOW` is granted.
3. Copy any link in any app; the bubble should appear, snap, auto-dismiss, and open the panel on tap.

This is the one item in the project that is verified up to the boundary of a human gesture and no
further.

### 25.5 The API 36 suite does not run on the POCO — and its results say so

Running the instrumented suites on the POCO produced a result worth recording precisely, because the
"OK (4 tests)" summary line hides it:

| Suite | POCO result |
|---|---|
| `CoreFlowsSmokeTest` | **PASS — 4 tests, 0 failed, 0 ignored** (baseline save/share on MIUI) |
| `Android16CompatibilitySmokeTest` | **4 tests, 0 failed, 0 ignored — but all four were *skipped* by failed assumptions** |
| `DownloadEngineInstrumentedTest` | 1 test, 0 failed, 0 ignored |

The four API 36 checks (`installedApplicationTargetsAndroid16`, Share Receiver and Quick Panel
edge-to-edge, onboarding insets) skip on Android 13 because the device is not API 36. Earlier
documentation quoted this suite as evidence of API 36 behaviour without saying **where it ran**; it
only means anything on the API 36 emulator. The POCO's genuine contribution is `CoreFlowsSmokeTest`:
the baseline save path working on a second manufacturer.

### 25.6 Totals

| Check | Result |
|---|---|
| `CoreFlowsSmokeTest` on the POCO (MIUI 14 / Android 13) | **PASS — 4/4**, none skipped |
| Accessibility service on the POCO | **PASS** — bound, four event types, no crash |
| `SYSTEM_ALERT_WINDOW` on the POCO | **PASS** — granted, verified via `appops` |
| Bubble switches via the real settings UI | **PASS** — all three on, state read from the view hierarchy |
| Bubble overlay rendering | **NOT VERIFIED** — needs a real link-copy gesture; device prepared for it |
| `Android16CompatibilitySmokeTest` | **N/A on the POCO** — 4/4 skipped, API 36 only |

---

## 26. Addendum 15 — the bubble's window is added; how far remote verification reaches, and where it stops

The bubble's overlay was the last claim in the project without any hardware evidence. This round
established three things: the window **is** added, the two obvious ways of verifying that remotely are
**both blind**, and one of my own test assertions was a **false pass** that had to be removed.

### 26.1 What is proven

Driven through `BubbleService.show()` — the exact entry point the link detector calls — on the POCO
(Android 13, MIUI 14, arm64), with a freshly cleared log buffer:

```text
BubbleOverlayTest: BubbleService.show returned true
BubbleService:     bubble shown as a TYPE_APPLICATION_OVERLAY window
BubbleOverlayTest: bubble stopped
run finished: 1 tests, 0 failed, 0 ignored
```

`Settings.canDrawOverlays` was granted (`appops` verified independently), `show()` returned true, and
the service reached the line after `WindowManager.addView` — which is only printed when the view was
accepted. `addBubbleView` catches every failure and logs it instead, so "shown" and "refused" are now
distinguishable rather than both being silent.

### 26.2 What is not proven, and the two red herrings

**Pixels.** Whether a human sees the circle is still unverified, because that needs a real link-copy
gesture: this ROM has no clipboard helper and no Chrome, and an instrumented run cannot long-press a
foreign app's WebView. The device is left ready for a ten-second check.

Two remote checks that look authoritative and are **not**:

| Check | What it showed | Why it is useless here |
|---|---|---|
| `dumpsys window windows` | `TYPE_APPLICATION_OVERLAY` lines: **0** | It does not list overlay windows for an app. The first version of the test failed against a working bubble because of this |
| `dumpsys SurfaceFlinger --list` | only first-party layers (wallpaper, magnification, display cutout) | The overlay layer was not among them either, while the app's *activity* layer was seen in an earlier run |

Both were written down because each cost a rebuild-and-rerun cycle to rule out, and both look like
"the overlay is missing" when they are really "this is the wrong instrument".

### 26.3 A silent failure path, fixed

Every gate in `BubbleService.onCreate` — no foreground notification, permission revoked, window
refused — ended in `stopSelf()` with **no log at all**, and `addBubbleView` swallowed its exception.
A bubble that did not appear was therefore indistinguishable from a copy that was never detected,
which is precisely the debugging position this project kept finding itself in. All four paths now log
the reason. This was found by needing the diagnosis, not by looking for a bug, and it is the kind of
change that would have saved hours earlier in the project.

### 26.4 A false pass of my own, caught and removed

The second version of the test asserted that the service log contained "bubble shown as a
TYPE_APPLICATION_OVERLAY window" — and it **passed**, even though that line came from the *previous*
run: the device's logcat buffer is not scoped to a test, and the timestamps in the assert's own output
gave it away (`02:00:25` inside a run that started `02:01:15`). The assertion was removed rather than
repaired, because any assertion over a shared, process-global log is the same trap. The line is logged
for a human to read in a cleared buffer instead, which is how §26.1's evidence was captured.

That is the third time in this project a check has been green for the wrong reason, and the second
time it was a log-based check. The pattern is worth stating plainly: **a test that reads shared state
must first prove the state is its own.**

### 26.5 Totals

| Check | Result |
|---|---|
| `BubbleOverlayInstrumentedTest` on the POCO | **PASS** — `show()` true, window added, no refusal logged |
| `SYSTEM_ALERT_WINDOW` on the POCO | **PASS** — `appops` reports `allow` |
| Accessibility service on the POCO | **PASS** — bound, four event types (addendum 14) |
| Bubble visible on screen | **NOT VERIFIED** — needs a real copy gesture; device prepared |
| `dumpsys window windows` / `SurfaceFlinger --list` as overlay evidence | **Unusable** on this ROM — recorded so it is not tried again |

---

## 27. Addendum 16 — the notification dialog in context, and a third false failure from MediaStore

### 27.1 The permission dialog, driven for real

The code, the decision rule and the two triggers were already in place and unit tested (addendum 6c);
what was missing was seeing it happen. `POST_NOTIFICATIONS` was revoked with `pm revoke`, the app was
restarted (the "already asked" guard is deliberately in-memory), and the quick panel was opened on a
site link:

```text
BubbleOverlayTest … (unrelated run, cleared buffer)
pm revoke → POST_NOTIFICATIONS: granted=false
tap DOWNLOAD →
  "Allow Linksi to send you notifications?"   [Allow] [Don't allow]
  mCurrentFocus=Window{… com.google.android.permissioncontroller/…GrantPermissionsActivity}
```

The focused window is Android's own permission controller, so this is the platform dialog and not an
app-drawn imitation. It appeared **on the download tap**, not at launch, which is the specification's
feature-based permission rule (§33) working as described.

### 27.2 A third false failure: the file was published under a name MediaStore chose

With the permission denied, the download completed and the app **again reported failure** while the
6,777,555-byte MP4 sat in Downloads. The cause is visible in MediaStore's own rows:

| _id | display name | is_pending | _size |
|---|---|---|---|
| 75 | `1710485373378939.vndwapxh` | 0 | 4252 |
| 77 | `1710485373378939.mp4` | 0 | 6777555 |
| 82 | **`1710485373378939 (1).mp4`** | 0 | 6777555 |

When the requested name is taken, MediaStore **renames** the new entry — `clip.mp4` becomes
`clip (1).mp4` — and moving a pending file into a name that already exists then fails with
`SQLiteConstraintException: UNIQUE constraint failed: files._data`. Row 82 is a phantom: it carries a
full size and `is_pending=0`, yet no such file exists on disk. The user's real file is row 77, complete
and visible.

`commit` now, before declaring failure, looks for a **visible** entry holding this file — matched on the
requested name or MediaStore's de-duplicated form, and requiring `IS_PENDING = 0`, because a pending
row is still hidden and therefore not a published file. Finding one, it removes the stuck row and
returns the working one:

```text
MediaStoreSink: publishing content://media/external/downloads/84 failed but the file is
                already published as content://media/external/downloads/77
WM-WorkerWrapper: Worker result SUCCESS
```

`DownloadEngineInstrumentedTest` — which asserts `is_pending=0` on the happy path — still passes.

### 27.3 The pattern, now on its fourth occurrence

Every one of these was a **download that succeeded being reported as a failure**:

| Round | Mechanism | What the user saw |
|---|---|---|
| 3 | stale row blocked the publish (`UNIQUE constraint`) | "download failed", file in Downloads |
| 6 | same, but the conflict-clearing could not help | "download failed", file in Downloads |
| 7 | MediaStore renamed the file; the publish target moved | "download failed", file in Downloads |

Three different MediaStore behaviours, one symptom. The lesson is not "fix MediaStore"; it is that
**a publish failure must never be taken at face value when the artifact may already be in place** —
which is now what the code does, in that order.

### 27.4 Totals

| Check | Result |
|---|---|
| `:app:testDebugUnitTest` | **747 tests, 0 failures** |
| `:app:lintDebug` | **0 errors** |
| `DownloadEngineInstrumentedTest` on the emulator | **PASS** — 1 test, 0 failed |
| Permission dialog in context | **PASS** — platform dialog on the download tap, not at launch |
| Publish fallback | **PASS** — a renamed/duplicated entry resolves to the working file, `SUCCESS` reported |

---

## 28. Addendum 17 — cleartext to loopback was blocked, so loopback tests could pass for the wrong reason

### 28.1 What the direct downloader does, across the types the spec names

The direct path had only ever been exercised against **one small PNG on `google.com`**, which cannot
tell "handles images" apart from "handles anything". A loopback server now serves four types with
honest content types, and the test asserts the classification, the derived extension, the byte count
and the readability of the published location:

```text
image/png       -> Completed(bytes=516,  displayName=linksi-typed-png.png, mimeType=image/png)
application/pdf -> Completed(bytes=410,  displayName=linksi-typed-pdf.pdf, mimeType=application/pdf)
application/zip -> Completed(bytes=304,  displayName=linksi-typed-zip.zip, mimeType=application/zip)
audio/mpeg      -> Completed(bytes=355,  displayName=linksi-typed-mp3.mp3, mimeType=audio/mpeg)
text/html       -> Unsupported        (refused as a file, so the site engine gets its turn)
```

That last line is the inverse case from addendum 9: an honest `text/html` must never reach the
downloader, and it does not.

### 28.2 The finding: those tests only worked after a debug-only policy change

The first run of this test **failed**, and the reason is more interesting than the test:

```text
java.net.UnknownServiceException: CLEARTEXT communication to 127.0.0.1 not permitted
                by network security policy
```

The app sets `usesCleartextTraffic="false"` and a `network_security_config` that permits cleartext
nowhere — including loopback. Several instrumented tests drive the real downloader against a loopback
HTTP server, because that is the only way to control a response body and its `Content-Type`. With
cleartext blocked, every such request failed **before any byte moved**, and the failure arrived as an
ordinary `DirectDownloadResult.Failed(...)` — indistinguishable, at the call site, from a genuine
network failure.

The consequence is the uncomfortable one: **a loopback test can pass while proving nothing**, because
the outcome it expects may be exactly the outcome a blocked socket produces. That is the fourth
time in this project that a check has been green for the wrong reason, and the second time the cause
was an environment rule rather than the code.

`src/debug/res/xml/network_security_config.xml` now permits cleartext for `127.0.0.1` and `localhost`
**in the debug variant only**; the release build keeps its original policy, so nothing about the app's
behaviour on a real network changes.

### 28.3 The existing loopback tests were re-run, and hold up

This cast doubt backwards over the watchdog and resume tests added in an earlier round, which also use
loopback servers. They were re-run with the fix in place — and they pass on their own merits, not on a
blocked socket:

```text
YtDlpInterruptTest: cancelled after 8119ms; surviving interpreter processes: []
YtDlpDownloader: stopping the yt-dlp process: the download made no progress and was stopped
                 (bytes=62914, elapsed=10017ms)
YtDlpDownloader: yt-dlp: [download] Resuming download at byte 709632
YtDlpInterruptTest: local resumed attempt: Downloading(bytesDownloaded=4194304, totalBytes=4194304)
run finished: 4 tests, 0 failed, 0 ignored
```

The stall really ran for ten seconds and really was stopped at 62,914 bytes; the resume really
continued at byte 709,632 and really reached 4 MiB. Those results stand, and now they stand for the
right reason.

### 28.4 The habit this argues for

A test that expects a *failure* must prove the failure came from the thing under test. Every loopback
test in this suite is now in that category, and the honest way to keep them there is the one used
here: make the environment stop blocking the traffic, then check the mechanism's own fingerprints in
the log — `surviving interpreter processes: []`, `Resuming download at byte 709632` — rather than
trusting the result code alone.

### 28.5 Totals

| Check | Result |
|---|---|
| `:app:testDebugUnitTest` | **747 tests, 0 failures** |
| `:app:lintDebug` | **0 errors** |
| `DirectFileDownloaderInstrumentedTest` (new) | **PASS — 2 tests**: four types downloaded, a page refused |
| `YtDlpInterruptedDownloadTest` (re-run) | **PASS — 4 tests, 0 failed** on real transfers |
| Release cleartext policy | **Unchanged** — the exemption is `src/debug` only |

---

## 29. Addendum 18 — the optional server resolver's privacy contract, captured on the wire

The last module with no verification of any kind. It had been recorded as *"client done, inert unless
enabled and HTTPS"*, which asserts the configuration gate and nothing about what the client actually
**sends**. The specification's rules here are about what leaves the device, and the only way to check
those is to be the server that receives it — which round 8's loopback fix made possible.

### 29.1 What was captured

A real HTTPS server (self-signed, test-only) recorded the single request the resolver made with an API
key configured:

```text
captured body: {"url":"https:\/\/www.facebook.com\/reel\/1710485373378939\/"}
headers:        Authorization: Bearer secret-token-value
request line:   POST /resolve
```

Parsed rather than string-matched, and the assertions are on the parsed object: **exactly one field**,
named `url`, whose value is the link. Nothing about the clipboard, the database or saved links is
present — the three things the specification names as forbidden (sections 25.2, 25.3, 25.8). The API
key travels as a bearer header and is asserted **absent from the request target**, so it cannot appear
in a proxy or access log.

A well-formed answer is mapped rather than discarded:

```text
resolve -> Success(MediaInfo(title=Resolved clip, uploader=someone, durationSeconds=42,
                    formats=[MediaFormat(id=mp4-720, label=720p, extension=mp4, height=720,
                    videoCodec=avc1, fileSizeBytes=12345678, requiresMuxing=true)]))
```

### 29.2 Degradation is a value, never an exception

Three failure modes, all delivered as `SERVER_UNAVAILABLE` results:

| Server behaviour | Result |
|---|---|
| body is not JSON | `Failure(SERVER_UNAVAILABLE)` |
| `HTTP 500` | `Failure(SERVER_UNAVAILABLE)` |
| connection refused (port closed) | `Failure(SERVER_UNAVAILABLE)`, cause `ConnectException` |

And the gate is proved to act **before** anything is sent, not after:

```text
plain http -> Skipped(reason=The resolver address must use HTTPS)
```

That ordering is the point: a resolver configured with `http://` must not send the user's link in the
clear and then fail. `isUsable` requires `enabled` **and** an `https://` base URL, and the result is
`Skipped` rather than `Failure` because nothing was attempted.

### 29.3 Two things the test itself got wrong first

Recorded because both are the same class of mistake this project keeps meeting:

- **A literal URL match.** The body is `{"url":"https:\/\/www…"}` — JSON escapes the slashes, so
  `body.contains(TARGET)` failed against a perfectly correct body. The assertion now parses the JSON.
- **A response the parser does not accept.** The first fixture omitted `"ok": true` and used
  `format_id` instead of `id`. The parser treats a missing `ok` as a refusal *by contract*, so the
  test failed and the code was right. Both the fixture and its comment now carry the wire contract's
  real field names.

Neither was an app defect, and saying so matters: a test that fails against correct code is a bug in
the test, and the temptation is to "fix" the code instead.

### 29.4 Totals

| Check | Result |
|---|---|
| `:app:testDebugUnitTest` | **747 tests, 0 failures** |
| `:app:lintDebug` | **0 errors** |
| `ServerResolverInstrumentedTest` (new) | **PASS — 3 tests**: request captured, degradation, HTTPS gate |
| Optional server resolver | **Verified**: only the link is sent, the key is a header, failures are values, HTTP is refused before sending |

---

## 30. Addendum 19 — my own cleanup was deleting the user's file, and only release mode caught it

### 30.1 The defect

Round 7 added "clear a conflicting row, then retry the publish" to `commit`, and round 7's device run
passed. Running the **release** build afterwards failed the same download, and the log showed why:

```text
MediaStoreSink: publishing content://media/external/downloads/91 threw
                SQLiteConstraintException: UNIQUE constraint failed: files._data
DownloadWorker: failed: NO_STORAGE (the finished download could not be published)
```

**No "already published" line**, although the file was in Downloads and its MediaStore row was
present, visible and correct. The reason is the order of operations:

1. Publish fails on the unique constraint.
2. `clearConflictingRows(path)` deletes every *other* row claiming that path — and the row at that path
   is usually **the user's own completed download**.
3. The retry now fails against the emptied path.
4. The "is it already published?" lookup then finds **nothing**, because the row it needed had just
   been deleted by step 2.

So the cleanup was destroying the very evidence it was meant to fall back on, deleting the user's
Downloads entry, and reporting failure for a download that had succeeded. The corrected order asks
whether the file is already published **before touching anything**; clearing residue is only for the
case where nothing published holds the name.

After the fix, the same situation behaves correctly:

```text
MediaStoreSink: publishing content://media/external/downloads/92 failed but the file is
                already published as content://media/external/downloads/77
WM-WorkerWrapper: Worker result SUCCESS
```

and row 77 — the user's entry — is still there afterwards, which was the point.

### 30.2 Why only the release run found it

The debug runs in round 7 exercised the fallback path with a *fresh* name, where the conflicting row
was residue rather than the user's file. The release run had rows left over from earlier downloads, so
the conflicting row **was** the good one. The difference was state, not configuration — which is a
reminder that "it passed on the device" is a statement about the state it ran in.

### 30.3 A near-miss worth recording

Reading the release stack trace, I first concluded the artifact was stale — the same mistake round 8
made. This time the check was quick and decisive: the source contained the fix at line 133, the
release APK's `classes.dex` contained the fallback string, and the source edit (02:10) predated the
build (02:15). R8 rewrites line numbers, so `MediaStoreSink.kt:137` in the trace is a mapping artefact
and says nothing about which code ran. **Compare the source to the artifact's DEX, never to the line
numbers in a minified stack trace.**

### 30.4 The bubble's remaining gap, restated

One more attempt was made at the bubble's pixels: the app's own in-app browser was opened on
`example.com` (its "Learn more" link is exposed in the view hierarchy), the accessibility service was
re-bound after a `force-stop` had silently disarmed it (`Bound services:{}`), and a 1.2-second
long-press was delivered on the link. No text selection appeared and no bubble window existed
afterwards. **A real copy gesture still cannot be produced remotely on this ROM**, so that item
remains exactly where it was: verified up to the window manager, needing an eye for the pixels.

### 30.5 Totals

| Check | Result |
|---|---|
| `:app:testDebugUnitTest` | **747 tests, 0 failures** |
| `:app:lintDebug` | **0 errors** |
| `DownloadEngineInstrumentedTest` (MediaStore contract) | **PASS** — 1 test, 0 failed |
| Publish ordering on the release build | **PASS** — `already published as …/77`, `Worker result SUCCESS`, user's row intact |
| Bubble visible on screen | **NOT VERIFIED** — second attempt failed for the same environmental reason |

---

## 31. Addendum 20 — the instrumented suite on the POCO, and two ways a device run can lie

Round 11 ran the whole instrumented suite on the owner's phone (POCO X3 Pro, Android 13 / MIUI 14,
arm64). Everything passes there, but the run needed two corrections along the way — both of which
produced *plausible-looking results* that were not results.

### 31.1 The final state

| Suite | POCO result |
|---|---|
| `CoreFlowsSmokeTest` | **PASS — 4/4**, none skipped (baseline save/share on MIUI) |
| `BubbleOverlayInstrumentedTest` | **PASS — 1/1** (overlay window added) |
| `DirectFileDownloaderInstrumentedTest` | **PASS — 2/2** (PNG, PDF, ZIP, MP3 downloaded; a page refused) |
| `ServerResolverInstrumentedTest` | **PASS — 3/3** (request captured; degradation; HTTPS gate) |
| `DownloadEngineInstrumentedTest` | **PASS — 1/1** (MediaStore contract, `is_pending=0`) |
| `YtDlpMediaSmokeTest`, `YtDlpInterruptedDownloadTest` | run separately on this device in earlier rounds |

The POCO evidence for the four file types, which until now existed only on the emulator:

```text
image/png       -> Completed(bytes=516, displayName=linksi-typed-png.png, mimeType=image/png)
application/pdf -> Completed(bytes=410, displayName=linksi-typed-pdf.pdf, mimeType=application/pdf)
application/zip -> Completed(bytes=304, displayName=linksi-typed-zip.zip, mimeType=application/zip)
audio/mpeg      -> Completed(bytes=355, displayName=linksi-typed-mp3.mp3, mimeType=audio/mpeg)
text/html       -> Unsupported
```

### 31.2 Lie one: a comma-joined class list runs *something else*

The first attempt passed five class names as one comma-separated string to `am instrument -e class`.
The runner does not split that: it treats the whole string as one unknown class and **falls back to its
own selection**, which produced a confident-looking result:

```text
Tests run: 8,  Failures: 2
```

Eight tests across an unidentified subset, with two failures that had nothing to do with the suites
named. The helper script now splits on commas and invokes one class at a time — which is also the
handover's existing rule about running one suite at a time, for a different underlying reason.

### 31.3 Lie two: a stale APK reports the *fixed* defect

With the classes running properly, `DirectFileDownloaderInstrumentedTest` still failed on the POCO with
exactly the error the cleartext exemption was added to remove:

```text
java.net.UnknownServiceException: CLEARTEXT communication to 127.0.0.1 not permitted
```

That looked like the exemption not working on Android 13, which would have been a real finding. It was
not: the **built** debug APK contained the exemption (verified by unzipping it and reading
`network_security_config.xml`), but the POCO still had an *older* install, because the helper script
reinstalls only the **test** APK — the app under test is assumed to be current.

After reinstalling the app APK, the same suite passes on the same device. This is round 8's lesson in a
different costume: **the artifact under test must be proven newer than the change**, and a test-only
script does not do that for the app it tests. Both facts are now recorded, and the helper script's
assumption is stated rather than implied.

### 31.4 Totals

| Check | Result |
|---|---|
| Instrumented suite on the POCO | **PASS across five suites** (see 31.1) |
| `:app:testDebugUnitTest` | **747 tests, 0 failures** |
| `:app:lintDebug` | **0 errors** |
| Release APK on the POCO | installed and launched, no R8 fault |

---

## 32. Addendum 21 — closure check, and why the four remaining site URLs cannot be self-served

### 32.1 The objective's deliverables, checked against the artifacts

| Deliverable | State |
|---|---|
| Documentation set | **all present** — `TEST_REPORT.md` (1,979 lines), `CHANGELOG.md`, `SESSION_HANDOVER.md`, `README_ENHANCED.md`, `BUILD_AND_RELEASE.md`, `CODE_REVIEW.md`, `LICENSE_REVIEW.md`, `DEPENDENCY_REVIEW.md`, `UPSTREAM_UPDATE_GUIDE.md` |
| Unit tests | **747, 0 failures** |
| Instrumented suites | **8** — core flows, API 36 compatibility, direct file downloader, server resolver, download engine, bubble overlay, yt-dlp media, yt-dlp interruption |
| Signed release | `3.1.1-enhanced.3` / vc 23, and **newer than the newest source edit** (`MediaStoreSink.kt` 02:32:14, artifact 02:36:07) |
| `Android16CompatibilitySmokeTest` on API 36 | **PASS 4/4, none skipped** — confirmed on the emulator, where it is meant to run |

That last artifact-freshness check is the one rounds 8 and 11 both taught the hard way, and it is now
the first thing to compare before believing any device result.

### 32.2 The four missing sites genuinely cannot be self-served

An attempt was made to avoid asking for URLs by finding real public posts: a web search returned only
downloader-service pages and datasets with no verifiable video id, and four candidate URLs were then
put through the current yt-dlp directly:

| Candidate | Result |
|---|---|
| `pinterest.com/pin/1100214429913124` | `HTTP Error 404` — the pin does not exist |
| `reddit.com/r/aww/comments/1c5k2xq` | `HTTP Error 404` — the post does not exist |
| `instagram.com/p/CqLM8O4J8Xn` | "Instagram sent an empty media response" — login-gated |
| `tiktok.com/@tiktok/video/7106594312292453675` | "Unexpected response from webpage request" — bot protection |

The two 404s are the informative ones: a *fabricated* id is indistinguishable from a blocked site until
you check, and both were rejected as non-existent rather than blocked. So the honest position is
unchanged and cannot be improved from here — **Instagram, TikTok, Pinterest and Reddit need real public
URLs from the owner**, and the earlier finding still holds that Instagram requires cookies/login even
for public posts and Pinterest answers 403 to yt-dlp.

### 32.3 The suite helper can no longer mislead

`local\.probe\run-suites.ps1` now installs **both** the app and the test APK before running, and prints
the app APK's build time. Round 11's stale-app failure came from the script installing only the test
APK while assuming the app was current; the assumption is now an action, and `-SkipInstall` exists for
deliberately re-running an unchanged pair. Validated on the emulator after the change.

### 32.4 Totals

| Check | Result |
|---|---|
| Documentation set | **complete** |
| `:app:testDebugUnitTest` | **747 tests, 0 failures** |
| `Android16CompatibilitySmokeTest` on API 36 | **PASS — 4 tests, 0 failed, 0 ignored** |
| Remaining open items | the four site URLs (owner input) and the bubble's pixels (a copy gesture) |

---

## 33. Addendum 22 — a slow transfer is not a stalled one, proved on both devices

### 33.1 The risk the watchdog has to avoid

The watchdog stops a transfer that reports no new bytes for its stall limit. Unit tests pin that
arithmetic, but the opposite failure — **killing a slow-but-healthy download** — is the one a user
would actually suffer from, and it had never been exercised over a real socket. A link that trickles
data slowly is exactly the shape a false positive would take.

A loopback server now sends 512 KiB in 4 KiB chunks with a 60 ms pause between each, so the whole
transfer takes roughly eight seconds while never going quiet for long:

| Device | Result |
|---|---|
| API 36 emulator | `Completed(bytes=524288, displayName=linksi-slow.bin) in 8398ms` |
| POCO X3 Pro (Android 13) | `Completed(bytes=524288, displayName=linksi-slow.bin) in 7739ms` |

The test also asserts the transfer really was slow (`elapsed > 2s`), so it cannot pass by transferring
instantly and proving nothing.

### 33.2 What else it incidentally covers

The body is served as `application/octet-stream` and saved as `linksi-slow.bin` — an extension with no
media meaning and a MIME type that says nothing. MediaStore accepted both, and the published row was
read back byte-for-byte. That is the same path a user's `.bin`, `.apk` or unlabelled download takes.

### 33.3 Totals

| Check | Result |
|---|---|
| Slow transfer, emulator | **PASS** — 524,288 bytes in 8.4 s, completed not stopped |
| Slow transfer, POCO | **PASS** — 524,288 bytes in 7.7 s |
| `:app:testDebugUnitTest` | **747 tests, 0 failures** |
| Instrumented suites | **9** |

---

## 34. Addendum 23 — "Not enough storage is available" for a file that is right there

### 34.1 What the user saw

Running the shipped flow on the signed release — panel, Download, wait — produced a complete
6,777,555-byte file in Downloads **and** this on screen:

```text
DOWNLOAD
  Original quality
  Not enough storage is available.        [Done]  [Try again]
  Not enough storage is available.
```

So the user is told their storage is full, offered a pointless retry, and left to find the file
themselves. Storage was never the problem: the publish was refused with
`UNIQUE constraint failed: files._data`, which `MediaStoreSink` already translates into `NO_STORAGE`
because that is the nearest user-facing reason it has.

### 34.2 Why the existing fallback did not catch it

Round 7 added "look for an already-published copy under the requested name or MediaStore's
de-duplicated form", and this run's row **did** exist and should have matched:

| _id | display name | is_pending | _size |
|---|---|---|---|
| 77 | `1710485373378939.mp4` | 0 | 6777555 |
| 82 | `1710485373378939 (1).mp4` | 0 | 6777555 |

The same request against the **debug** build, minutes later and with the same rows present, took the
fallback and succeeded:

```text
MediaStoreSink: publishing content://media/external/downloads/96 failed but the file is
                already published as content://media/external/downloads/77
```

and the panel then showed **"Download complete"** with an *Open Downloads* button. Same code, same
rows, different outcome — so this is non-determinism in MediaStore's insert/publish sequence, not a
logic error that reading the source would reveal.

Before concluding that, two checks were made that had been learned the hard way: the source contained
the fix, and the **release DEX** contained the fallback's own log string (so the artifact was not
stale). Both passed, which is what made this a real defect rather than a rebuild.

### 34.3 The check that cannot be argued with

`commit` now, before declaring failure, looks at the **filesystem**: a file in the public Downloads
directory whose name matches the requested one (or MediaStore's de-duplicated form) **and whose size
equals the bytes just written**. Matching on the size means an older file of the same name cannot be
mistaken for this download, and a partial file cannot be reported as complete.

The reasoning behind adding a third fallback rather than fixing the second: publishing is a *move the
platform performs on our behalf*, and it has now failed in three distinct ways — a stale row, a renamed
file, and a constraint rejection with the correct row present. Each time, the bytes were in place and
the app reported failure. A check that reads the bytes themselves is the only one that does not depend
on the collection's bookkeeping being consistent.

### 34.4 Totals

| Check | Result |
|---|---|
| Signed release, full panel flow, emulator | **PASS after the fix** — "Download complete" with *Open Downloads* |
| `:app:testDebugUnitTest` | **747 tests, 0 failures** |
| MediaStore publish fallbacks | three: already-published row, cleared residue, **file on disk** |
| Release artifact | rebuilt; the previously shipped build contained the first two fallbacks, and the run above is the evidence that they are not always sufficient |

---

## 35. Addendum 24 — the row was trusted over the file, and it claimed a file that was not there

### 35.1 What the forced test showed

Round 14 added a filesystem check but the passing run took the *earlier* row-based fallback, so the new
code had never executed. It was forced this round by deleting the downloaded files while leaving their
MediaStore rows in place — a state a user reaches by deleting from a file manager, or by an app
clearing its Downloads.

The result was wrong in a new way:

```text
MediaStoreSink: publishing content://media/external/downloads/97 failed but the file is
                already published as content://media/external/downloads/77
panel:          Download complete
on disk:        (nothing — the file had been deleted)
```

The app reported success and handed back a location resolving to **a file that does not exist**,
because `findPublishedCopy` ran first and a MediaStore row is a *claim* that outlives the file it
describes. Bookkeeping was believed over the filesystem.

### 35.2 The corrected priority

`commit` now asks the filesystem **first**, and a row only afterwards:

1. A file in public Downloads with the requested name (or MediaStore's de-duplicated form) **and the
   exact size just written** → report its path. Evidence.
2. Otherwise, a visible published row under either name → report its URI. Bookkeeping, but better than
   nothing when the bytes are somewhere.
3. Otherwise clear residue and retry publishing once.
4. Otherwise fail.

The size comparison is what makes step 1 safe: an older file with the same name cannot be mistaken for
this download, and a partial file cannot be reported as complete. That was the original reason the
check was added, and it is unchanged.

Re-running the same forced scenario now takes the first branch, and its own log line is the proof:

```text
publishing content://media/external/downloads/98 failed but the finished file is on disk at
  /storage/emulated/0/Download/1710485373378939.mp4
```

### 35.3 The pattern, stated once more

Four rounds have now produced the same symptom — *a completed download reported as a failure, or a
success pointing at nothing* — from four different MediaStore behaviours: a stale row, a renamed file,
a constraint rejection with the correct row present, and a phantom row for a deleted file.

The general rule this leaves: **when a platform's bookkeeping and the filesystem disagree, the
filesystem is the one the user can see**, so it is the one to believe. The remaining value of the row
checks is only to point at bytes that live somewhere unexpected.

### 35.4 Totals

| Check | Result |
|---|---|
| Forced phantom-row scenario | **PASS after the fix** — disk branch runs, path reported, file present |
| `DownloadEngineInstrumentedTest` (MediaStore contract) | **PASS** — 1 test, 0 failed |
| `:app:lintDebug` | **0 errors** |
| Publish fallback order | filesystem → published row → cleared residue → fail |

---

## 36. Addendum 25 — a crashed build daemon, and the release that followed

The release rebuild for addendum 24 failed with:

```text
Gradle build daemon disappeared unexpectedly (it may have been killed or may have crashed)
JVM crash log found: file:///E:/Deepseek/hs_err_pid27104.log
```

and the crash log is unambiguous about the cause:

```text
# There is insufficient memory for the Java Runtime Environment to continue.
# Native memory allocation (malloc) failed to allocate 1815056 bytes. Error detail: Chunk::new
```

That is the host running out of memory while the daemon had several Gradle invocations in flight —
an environment failure, not a build failure. Re-running the same script on a fresh daemon succeeded
without changing a line:

```text
BUILD SUCCESSFUL in 22s    (tests + lint)
BUILD SUCCESSFUL in 2m 24s (assembleRelease)
  apk : LinksiEnhanced_3.1.1-enhanced.3_arm64-v8a.apk
  sha256 : D3944FAC2707C3A3F92B07D5EC802F19AD70C3588813F7FB5FE6E5327D3075CB
  apk : LinksiEnhanced_3.1.1-enhanced.3_universal.apk
  sha256 : 6BDA8FE0632E1697A543E1CF88C5269D9B04D8D8D3769A958689C0E9A15BCF50
```

> **These two digests are superseded.** `src/main` changed afterwards (the `YtDlpRuntime` fix in §40), so
> the current artifacts are arm64 `E1A392D8AF46F4E70B21CDB4DF1AF10B665B13E0A693AB60C1C7705F6B49FA7E`
> and universal `7E9222EFEF8D2067F523B23FD0AEB1F3BE08FDCAEDE6C7FDC1A4B12C87E9FA7B`. `latest-build.json`
> is the authority; this block stays because it records what that build actually printed.

**Worth knowing for the next session:** a daemon crash and a compile error both present as
`BUILD FAILED`, and only the crash log distinguishes them. `hs_err_pid*.log` lands in
`E:\Deepseek\` (the parent, not the repo). The fix is to serialise Gradle invocations and retry once,
which is the same advice the handover already carries for the shared-daemon case.

---

## 37. Addendum 26 — the filesystem fallback, made deterministic

Addendum 24's fallback was verified by forcing it through the running app, which is convincing once but
cannot be repeated on demand: the state that triggers it depends on MediaStore's mood. A guard that
cannot be reached deliberately can silently regress, so this round built the state on purpose.

### 37.1 The constructed collision

`PublishFallbackInstrumentedTest`:

1. Deletes any file of the target name, so the only obstacle is what it inserts next.
2. Inserts a **visible** MediaStore entry claiming that name — a legitimate API call, not a hack.
3. Serves 64 KiB over loopback and downloads it through the real `DirectFileDownloader` and
   `MediaStoreSink`.

The downloader's own insert is therefore given a de-duplicated name, and promoting it to the requested
name collides on `files._data`. The run's own log is the whole chain:

```text
blocking entry: content://media/external/downloads/100
MediaStoreSink: publishing content://media/external/downloads/101 failed but the finished file
                is on disk at /storage/emulated/0/Download/linksi-fallback-probe.mp4
outcome: Completed(location=/storage/emulated/0/Download/linksi-fallback-probe.mp4, bytes=65536)
file on disk: /storage/emulated/0/Download/linksi-fallback-probe.mp4 exists=true len=65536
```

Note what the assertion checks: not just that the outcome is `Completed`, but that **the reported
location resolves to the bytes that were written** — 65,536 read back from wherever it points. That is
the property addendum 24 was about, and it is now pinned rather than observed once.

The test also cleans up after itself: the blocking entry and the file it created are removed, so a
later run does not inherit the contrived state.

### 37.2 Totals

| Check | Result |
|---|---|
| `PublishFallbackInstrumentedTest` (new) | **PASS** — collision constructed, disk fallback taken, location resolves |
| `:app:testDebugUnitTest` | **747 tests, 0 failures** |
| `:app:lintDebug` | **0 errors** |
| Instrumented suites | **10** |
| Release artifact | current — newest `src/main` 02:58:15, artifact 03:04:27 (the only newer files are `androidTest`, which cannot affect it) |

---

## 38. Addendum 27 — the clipboard path, which the bubble depends on and nothing tested

### 38.1 The gap

Tapping the floating bubble starts `QuickPanelActivity` with **no intent extra**, so the panel reads the
clipboard once as it takes window focus (specification section 11.1). That is a different route from the
link options sheet, which passes the URL in a `PANEL_URL` extra — and it was the untested one. Underneath
it sits a **privacy promise** rather than an implementation detail: the clipboard is never read without
window focus, so there is no silent background scraping.

Nothing in the project tested `ClipboardUrlReader` at all. (The tap *decision* is well covered — 11 unit
tests on `BubblePolicy.isTap`/`isDrag`, including the slop boundary and that `isDrag` is exactly
`!isTap` — so what was missing was the route the tap leads to, not the tap itself.)

### 38.2 What was verified, on the emulator

With the raw share-sheet form on the clipboard — a Reel plus `utm_source=clipboard_test&fbclid=xyz789`:

| Check | Evidence |
|---|---|
| The focus gate | `ClipboardUrlReader.read(context, isFocused = false)` → `readable=false`, `url=null`, with a perfectly good link on the clipboard |
| Non-link text | `read(context, isFocused = true)` on "just some notes, no link here" → `url=null`, so the panel cannot offer a bogus URL |
| The panel opens with no extra | `panel start -> true`, and the intent is asserted to carry no `PANEL_URL` |
| The panel shows the **cleaned** clipboard link | its own view hierarchy reads `https://www.facebook.com/reel/1710485373378939` — no query string, although the clipboard held two trackers |

That last row is the whole route in one line: clipboard → panel → cleaned URL, with no extra passed. It
also independently re-confirms the URL cleaner on the clipboard entry point, as addendum 13 did for the
share sheet.

**Verified up to this point, the bubble module is complete short of pixels:** the tap decision (unit
tests), the window being added (`BubbleOverlayInstrumentedTest` on the POCO), and now the route its tap
leads to. What remains is whether the circle is *visible*, which needs a human eye on this ROM — nothing
in the pipeline is untested.

### 38.3 A note on the panel's stale message

The dump above also shows "Not enough storage is available", left from an earlier failed attempt in the
same process — the view model had not been recreated. It is *not* a new failure: the panel shows the
previous attempt's status until a new download starts, which is existing behaviour and worth knowing
before reading a panel dump out of context.

### 38.4 Totals

| Check | Result |
|---|---|
| `ClipboardPanelInstrumentedTest` (new) | **PASS — 3 tests** |
| `:app:testDebugUnitTest` | **747 tests, 0 failures** |
| `:app:lintDebug` | **0 errors** |
| Instrumented suites | **11** |
| Release artifact | current — no `src/main` change this round |

---

## 39. Addendum 28 — the bubble's pixels, confirmed on the physical device

### 39.1 The last open question in the bubble module, closed

Addendum 27 ended with exactly one thing unproven about the floating bubble: whether the circle is
actually *visible* to a person. Every mechanism underneath it was already proven — the tap/drag decision
(11 unit tests), the overlay window being added (`BubbleOverlayInstrumentedTest` on the POCO), and the
clipboard route its tap leads to (`ClipboardPanelInstrumentedTest`). Visibility was the residue, and it
was parked because this ROM's `dumpsys` output had been judged unable to show overlay windows.

That judgement was wrong, and this addendum corrects it (section 39.3). Visibility is now proven three
independent ways.

### 39.2 The evidence

`BubbleVisibleForHumanCheck` (a test that holds the bubble for `VISIBLE_SECONDS = 300` so a person can
look at the screen) was run on the POCO X3 Pro. The service logged its success path:

```
09-18 03:20:07.687  ActivityManager: Background started FGS: Allowed
    [callingPackage: com.linksi.app.debug; intent: Intent { cmp=…/BubbleService (has extras) };
     targetSdkVersion:36; callerTargetSdkVersion:36; code:ACTIVITY_STARTER]
09-18 03:20:07.727  4051  4051 I BubbleService: bubble shown as a TYPE_APPLICATION_OVERLAY window
```

`dumpsys window windows` then showed the window itself:

```
Window #7 Window{4d41744 u0 com.linksi.app.debug}:
  mOwnerUid=10271 showForAllUsers=false package=com.linksi.app.debug appop=SYSTEM_ALERT_WINDOW
  mAttrs={(900,722)(156x156) gr=TOP START CENTER … ty=APPLICATION_OVERLAY fmt=TRANSLUCENT
    fl=NOT_FOCUSABLE NOT_TOUCH_MODAL HARDWARE_ACCELERATED}
  Requested w=156 h=156 mLayoutSeq=5291
  mBaseLayer=111000 mToken=WindowToken{da44057 type=2038 …}
  mViewVisibility=0x0 mHaveFrame=true mObscured=false
  mHasSurface=true isReadyForDisplay()=true canReceiveKeys()=false
```

| What it settles | Evidence |
|---|---|
| The process is allowed to start the FGS | `Background started FGS: Allowed`, `code:ACTIVITY_STARTER`, `targetSdkVersion:36` |
| It takes the overlay route, not the accessibility route | window type `APPLICATION_OVERLAY` (2038), `appop=SYSTEM_ALERT_WINDOW` |
| It does not steal input | `NOT_FOCUSABLE`, `NOT_TOUCH_MODAL`, `canReceiveKeys()=false` |
| It is actually drawn and not covered | `mHasSurface=true`, `isReadyForDisplay()=true`, `mObscured=false`, `mViewVisibility=0x0` |
| It is on screen, at a sane place and size | 156×156 px at (900,722), sublayer 0 — a 72 dp circle in the upper-right of a 1080×2400 display |
| **A human can see it** | screenshot `E:\Deepseek\Linksi\local\.probe\shots\bubble.png` — the purple-haloed Linksi mark floating over the launcher, upper right |

The screenshot is the decisive one, because it is the same composited framebuffer the user's eye gets.
Every check above can in principle be satisfied by an invisible window; the screenshot cannot.

### 39.3 Correction — overlay windows *do* appear in `dumpsys window windows` on this ROM

An earlier session note asserted that `dumpsys window windows` and `SurfaceFlinger --list` do not show
overlay windows on this MIUI build, and told the next reader not to use them as evidence. **That is
wrong**, and it is worth understanding why, because the bad note would have blocked this proof.

`dumpsys window windows` emits every window. The original reader filtered for the app's package or for the
word "Bubble", and on this ROM the interesting lines are not adjacent to those strings: the package name
appears on the `mOwnerUid=` line, while `ty=APPLICATION_OVERLAY` appears several lines later inside
`mAttrs=`, and the dump runs the attribute block together on one long line. A `Select-String` with a
`-Context` window narrower than the gap, or a filter that expects the type on the same line as the
package, finds nothing and reports absence of evidence as evidence of absence.

The reliable query is to locate the block by the window header first, then read forward:

```powershell
adb -s <serial> shell dumpsys window windows > wins.txt
Select-String -Path wins.txt -Pattern 'Window #\d+ Window\{.* com\.linksi\.app' -Context 0,14
```

Note also that MIUI prefixes the window list with framework windows (`Window #1..#6` here) and the app's
overlay was `Window #7`; a filter that only inspects the first few entries will miss it.

`SurfaceFlinger --list` remains useless for this — that part of the old note stands.

### 39.4 The MIUI accessibility trap, isolated

While attempting this round the accessibility service was found stuck:

```
Bound services:{}
Enabled services:{{com.linksi.app.debug/…LinksiAccessibilityService}}
Binding services:{{com.linksi.app.debug/…LinksiAccessibilityService}}
Crashed services:{}
```

Enabled, binding, never bound — and with **no crash and no error in logcat**. The service is declared
correctly in the installed manifest, the app process was alive, and the overlay permission was granted.
The cause was an earlier `am force-stop` of the app in the same session: on MIUI a force-stopped app
leaves the accessibility bind permanently pending, and `settings put secure accessibility_enabled 1`
plus `enabled_accessibility_services` does **not** recover it.

Recovery, verified:

1. `adb shell monkey -p com.linksi.app.debug -c android.intent.category.LAUNCHER 1` — launch the app.
2. Wait for the process (a few seconds; do not race it).
3. `adb shell settings put secure enabled_accessibility_services <pkg>/<service>` and
   `settings put secure accessibility_enabled 1`.
4. Re-check `dumpsys accessibility`.

Result:

```
Bound services:{Service[label=Linksi link detection (optional), feedbackType[FEEDBACK_GENERIC],
  capabilities=1, eventTypes=[TYPE_VIEW_CLICKED, TYPE_WINDOW_STATE_CHANGED,
  TYPE_WINDOW_CONTENT_CHANGED, TYPE_VIEW_TEXT_SELECTION_CHANGED], notificationTimeout=100,
  requestA11yBtn=false]}
Binding services:{}
Crashed services:{}
```

So the rule for this device family is: **enable the accessibility service only while a freshly launched
app process is alive.** Enabling it against a dead or force-stopped process parks the service in
`Binding services` forever, which looks exactly like a bug in the app and is not one.

This affects developer setup, not end users — a user flips the Settings toggle with the app's process
alive behind the Settings screen. But it matters for anyone automating enablement over `adb`, and it
explains the round-5 observation that the service bound fine on the same device after the app had just
launched.

### 39.5 Totals

| Check | Result |
|---|---|
| Bubble visible on the physical MIUI device | **PASS — screenshot + window dump + logcat** |
| Accessibility service bound on the POCO | **PASS — after fresh app launch** |
| `:app:testDebugUnitTest` | **747 tests, 0 failures** |
| `:app:lintDebug` | **0 errors** |
| Instrumented suites | **11** (12 after `RealSiteLinksInstrumentedTest` was added — see §41.7) |
| Release artifact | current — no `src/main` change this round |

With the pixels confirmed, **every module in the specification is now verified end-to-end on a real
device**, and the bubble module has no unproven link left.

---

## 40. Addendum 29 — the site-engine settings row was inert on a fresh process

### 40.1 What was tested, and the defect it exposed

The handover listed the manual **Check** button in *Settings → Enhanced features → Site engine* as
"compiled and unit tested but never tapped on a device". Tapping it found a real defect.

On the POCO, with the app force-stopped first so the engine was genuinely unstarted:

| State | What the row showed |
|---|---|
| On opening the screen | `Version not reported yet` |
| After tapping **Check** | `Could not update, still on the installed version: instance not initialized` |

The button was inert, and the reason was specific rather than generic.

### 40.2 Root cause

`YtDlpRuntime` deliberately does not start the engine at app start (specification section 26 forbids
optional modules doing startup work), so `status` is `NotStarted` until something calls `ensureReady()`.
But the two methods the settings screen uses went straight to `YtDlpUpdater`:

```kotlin
suspend fun engineVersion(): String? = updater.installedVersion()
suspend fun refreshEngine(): YtDlpRefreshResult = updater.refreshIfStale(force = true)
```

`YtDlpUpdater.askVersion` runs the engine (`<python> <engine> --version`) through
`YoutubeDL.getInstance().execute(...)`, and the wrapper refuses to run anything before
`YoutubeDL.init(context)`. So:

- `installedVersion()` swallowed the failure into `null` → the row fell back to "not reported yet",
  which reads as "not measured yet" rather than "the engine is not started";
- `refreshIfStale(force = true)` propagated the raw message → **`instance not initialized`** reached
  the user's screen.

Every other caller was fine, because every other caller drives a download, and a download calls
`ensureReady()` first. The settings screen asks *about* the engine without ever using it, which is the
one path that skipped initialisation.

### 40.3 The fix

Both methods now initialise first, and they differ in how they report failure, on purpose:

- `engineVersion()` returns `null` if the engine will not start, so the row degrades to "not reported
  yet" instead of blocking the screen with an error for an optional module.
- `refreshEngine()` returns `YtDlpRefreshResult.Failed(<the real reason>)`, because this is a
  user-initiated action: "it could not start" must reach the screen rather than arrive as a generic
  update error.

Initialising here is not startup work — it is only reached when the user opens the screen and asks
about the engine.

### 40.4 Verified on the POCO, after the fix

Same procedure: fresh install, `force-stop`, launch, navigate to the row.

| Check | Evidence |
|---|---|
| The row reports the version without being asked | `Version 2026.08.19`, where it previously said "not reported yet" |
| The engine starts on that screen | `YtDlpUpdater: version probe of yt-dlp (3072469 bytes) exit=0 out=2026.08.19` |
| The engine started does not re-check needlessly | `YtDlpRuntime: site engine 2026.08.19; refresh: Skipped(reason=the engine was checked 0 hours ago)` |
| Tapping **Check** forces a real refresh | `staged 3072469 bytes (installed copy is 3072469 bytes)` → `checksum verified against the published digest` → `candidate reports 2026.08.19, installed reports 2026.08.19, digests differ: false` |
| The user sees the outcome | the row reads **`Already up to date (2026.08.19)`** |

That last sequence is worth reading closely, because it is the whole refresh contract in five log
lines: the download happens, the **published digest** is what authorises the swap (not the size, and
not the version string), and an engine that is already current is reported as current **without being
reinstalled**.

### 40.5 A second handover item closed by the same run

The handover also listed "the weekly refresh's *not due yet* path is proven only by the second refresh
in one run reporting `AlreadyCurrent`". The `Skipped(reason=the engine was checked 0 hours ago)` line
above is that path, observed directly: `ensureReady()` reached its automatic `refreshIfStale()` call,
found the interval had not elapsed, and returned without any network traffic. Both engine-refresh items
on the open list are now closed with device evidence.

### 40.6 Totals

| Check | Result |
|---|---|
| Defect found and fixed | `YtDlpRuntime.engineVersion`/`refreshEngine` did not start the engine |
| **Check** button on a device | **PASS — restores the version and reports `Already up to date (2026.08.19)`** |
| Weekly "not due yet" path | **PASS — `Skipped(... checked 0 hours ago)`** |
| `:app:testDebugUnitTest` | **747 tests, 0 failures** |
| `:app:lintDebug` | **0 errors** |
| Release artifact | rebuilt — `src/main` changed, so the previous APKs are superseded |

This is the third defect this session that only a device could find (after the publish-fallback order
and the clipboard route), and it is the clearest argument for the rule in section 39.3: an unverified
path is not a working path.

---

## 41. Addendum 30 — the owner's real links, through the app's own extractor

### 41.1 What was blocked, and how it was unblocked

The five-site acceptance criterion needed real public links. Earlier rounds could not self-serve them:
guessed ids return 404, and Instagram, TikTok and Pinterest answer a scripted client with a challenge
page (`TEST_REPORT.md` §32). The owner supplied a real list on 2026-09-18 — **6 TikTok links and 10
YouTube links** — which is what this addendum covers.

Link rot is expected, so the list is captured here rather than treated as permanent: the point is that
the *app* was driven with real URLs, not that these particular URLs will work forever.

### 41.2 Result — 13 of 16 links extracted, on the physical POCO

Run with `RealSiteLinksInstrumentedTest`, which drives the app's own `YtDlpExtractor` (not a script
around it) on the owner's list. The engine was `2026.08.19` on `arm64-v8a`.

| Site | Readable | Formats offered | Best quality seen |
|---|---|---|---|
| **TikTok** | **6 / 6** | 4 – 8 per link | 1280p |
| **YouTube** | **10 / 10** | 33 – 178 per link | **3840p** |

Everything the mapper is responsible for came through: title, uploader, duration and the format list.

| Link | Extracted | Formats |
|---|---|---|
| `tiktok.com/@sza_jarral/video/7675148855209512214` | `#onthisday` by `sza_jarral`, 205 s | 8 (up to 1026p) |
| `tiktok.com/@emaankhan.official22/video/7671734941704768775` | by `emaankhan.official22`, 15 s | 8 (up to 1280p) |
| `tiktok.com/@the.emanofficial/video/7676523077575920916` | `TikTok video #7676523077575920916`, 39 s | 8 (up to 1280p) |
| `tiktok.com/@fatimaqueenf19/video/7559831138752285960` | `#fatime #qeeum …`, 4 formats | 4 |
| `youtube.com/shorts/i0VEon0agBE` | *The Navy's Logistical Nightmare in the Iran War*, 141 s | 42 (up to 1920p) |
| `youtube.com/shorts/l1s7aOhPSGo` | *Florida's Malpractice Loophole Law*, 63 s | 47 (up to **3840p**) |
| `youtube.com/shorts/6qR1BBKr4WE` | *Would you like to be friends with him?*, 174 s | **162** |
| `youtube.com/shorts/-_ijMxon8iY` | *Why does Hal's ring die…*, 73 s | 88 |
| `youtube.com/shorts/12fXUIDxa6k` | *Crucial RAM Owners are Screwed*, 92 s | 47 (up to **3840p**) |
| `youtube.com/watch?v=LoLYw--s-5w` | *Did Google just kickstart the intelligence explosion?*, 298 s | 43 |
| `youtube.com/watch?v=7K_sA6o1dOE` | *How to lose $35 Billion Dollars Betting on AI*, 1342 s | **172** |
| `youtube.com/watch?v=Z4K18yTHUs0` | *Did the Yemeni Houthis target Makkah?…*, **3893 s** | 33 |
| `youtube.com/watch?v=6vnD5t5OIwo` | *My Thoughts on Resident Evil Movie*, 1099 s | **178** |
| `youtube.com/watch?v=gR0fnx_tnik` | *…Mehfal Mein Fight…*, 1570 s | 43 |

The 3893-second (65-minute) link matters as much as the format counts: a video that long proves the
extraction path is not quietly truncating a playlist or a preview.

### 41.3 The three failures were the network, and the app said so

Three TikTok links failed on the **second** pass over the same list:

```
ERROR: [TikTok] 7686308354867645729: Unable to download webpage:
  [Errno 104] Connection reset by peer (caused by TransportError('[Errno 104] Connection reset by peer'))
```

All three are identical, and all three had already extracted successfully minutes earlier on the first
pass — which is what makes them conclusive. TikTok throttled the repeated automated requests; the app
had already proved it can read those exact links. The app classified them as
`MediaError.NETWORK`, which is the correct reading, and `RealSiteLinksInstrumentedTest` records a site
refusal separately from an app defect for exactly this reason.

This is also why the result is reported as **13 of 16** rather than a bare tick: the three are recorded,
not hidden, and are not the app's fault.

### 41.4 The same link can pass and fail seconds apart — which is the strongest evidence available

The clearest demonstration came from the **emulator**, where both tests ran in one instrumentation
session over the same four URLs:

| Link | `theOwnersRealLinksExtract…` | `everyNamedSiteIsEitherReadable…` |
|---|---|---|
| `tiktok.com/@sza_jarral/video/7675148855209512214` | **OK** — `#onthisday`, 8 formats | `EXTRACTOR_FAILED` |
| `tiktok.com/@emaankhan.official22/video/7671734941704768775` | **OK** — 8 formats, 1280p | `EXTRACTOR_FAILED` |
| `youtube.com/shorts/i0VEon0agBE` | OK — 42 formats | OK — 42 formats |
| `youtube.com/watch?v=LoLYw--s-5w` | OK — 43 formats | OK — 43 formats |

Two TikTok links extracted cleanly in the first test and were refused minutes later in the second, on
the same device, in the same run, with the same engine. YouTube passed both times, every time, on both
devices. That combination — same bytes, same code, different outcome, and only for the site with bot
protection — is what rules out an app defect and points at throttling from TikTok's side.

The emulator's per-link test therefore reports **4 of 4** while the site test reports TikTok 0/2, and
both readings are correct for the moment they were taken. This is exactly why the test separates a
site refusal from an app failure and reports the counts rather than a single verdict.

### 41.5 What this does and does not close

**Closed:** the extractor reads real links from **three** of the specification's named sites with full
metadata — TikTok (6/6), YouTube (10/10, a site the spec lists among its supported targets) and
Facebook, which §19.2 proved earlier on both devices. TikTok was previously the least-covered site, so
this is the largest single gain in real-content coverage so far.

**Not closed:** Instagram, Pinterest and Reddit still have **no real link** tested. The owner's list did
not include them, and they cannot be self-served from here (§32.2). They remain an owner-supplied-input
gap, now smaller than it was.

### 41.6 Totals

| Check | Result |
|---|---|
| Real links extracted through the app, POCO | **13 of 16** (TikTok 6/6, YouTube 10/10) |
| Real links extracted through the app, emulator | **4 of 4** (TikTok 2/2, YouTube 2/2) |
| `RealSiteLinksInstrumentedTest` on the POCO | **PASS — 2 tests, 169 s** |
| `RealSiteLinksInstrumentedTest` on the emulator | **PASS — 2 tests, 38 s** |
| POCO failures | 3, all `NETWORK` (`Connection reset by peer`), all previously extracted — site throttling |
| `:app:testDebugUnitTest` | **747 tests, 0 failures** |
| `:app:lintDebug` | **0 errors** |
| New file | `app/src/androidTest/java/com/linksi/app/RealSiteLinksInstrumentedTest.kt` |

The test takes its URLs from `-e realLinkUrls "url,url,…"`, so a future session can probe a fresh list
without editing source — which matters, because these links will rot.

### 41.7 The final gate, run in full after every change this round

| Suite (POCO X3 Pro) | Result |
|---|---|
| `CoreFlowsSmokeTest` | 4 / 4 |
| `Android16CompatibilitySmokeTest` | 4 / 4 (skipped by assumption on Android 13 — emulator evidence only) |
| `DownloadEngineInstrumentedTest` | 1 / 1 |
| `BubbleOverlayInstrumentedTest` | 1 / 1 |
| `ClipboardPanelInstrumentedTest` | 3 / 3 |
| `DirectFileDownloaderInstrumentedTest` | 2 / 2 |
| `PublishFallbackInstrumentedTest` | 1 / 1 |
| **`RealSiteLinksInstrumentedTest`** (new) | **2 / 2** |
| `ServerResolverInstrumentedTest` | 3 / 3 |
| `SlowTransferInstrumentedTest` | 1 / 1 |
| `YtDlpInterruptedDownloadTest` | 4 / 4 |
| `YtDlpMediaSmokeTest` | 7 / 7 |
| **Total** | **33 tests, 0 failed, 12 suites** |

| Gate | Result |
|---|---|
| `:app:testDebugUnitTest` | **747 tests, 32 suites, 0 failures, 0 errors, 0 skipped** |
| `:app:lintDebug` | **0 errors** (180 warnings, 4 hints — all pre-existing baseline) |
| Release artifacts | arm64 `E1A392D8…`, universal `7E9222EF…`; both re-hashed and signature-verified (v2 scheme, 4096-bit RSA) |

`BubbleVisibleForHumanCheck` is deliberately not in that list: it holds the bubble on screen for 300 s
for a human to look at, so it is a visual aid rather than a pass/fail suite.

---

## 42. Addendum 31 — all five named sites, and a test-quality bug found while running them

### 42.1 The second batch of links

The owner supplied the remaining sites on 2026-09-18 — **5 Instagram Reels, 5 Reddit share links and 5
Pinterest short links** — completing the set. They were run through `RealSiteLinksInstrumentedTest` on
the POCO in one batch of 20 links alongside TikTok and YouTube samples.

### 42.2 Result — every one of the five named sites now has real-link evidence

| Site | Readable | Formats | Note |
|---|---|---|---|
| **Instagram** | **4 / 5** | 12 – 13, up to 2560p | one post is `LOGIN_REQUIRED` |
| **Reddit** | **4 / 5** | 17 – 20 | one short link redirects to Reddit's home page |
| **Pinterest** | **4 / 5** | 5 – 7 | one short link redirects to Pinterest's home page |
| **TikTok** | 0 / 3 this pass | — | `NETWORK` — throttled after the earlier successful passes (§41.2) |
| **YouTube** | **2 / 2** | 42 | — |

Titles, uploaders and durations all came through. Some highlights:

| Link | Extracted |
|---|---|
| `instagram.com/reel/DdBla0_of1w/` | *Video by _.my_things_* by Neha — 13 formats up to **2560p** |
| `reddit.com/r/therewasanattempt/s/mkWrTJXPHb` | *To harass people outside of an abortion clinic*, 13 s, 20 formats |
| `reddit.com/r/ImTheMainCharacter/s/9x2Efr6KBw` | *Tourist disrespecting staff in a Thai hotel*, **593 s**, 17 formats |
| `pin.it/3IuEwLXrU` | *Beautiful Moments*, 7 formats up to 1920p |
| `pin.it/5VBC9lvMU` | *Slow motion ❤️‍🔥*, 6 formats |

**The specification's five named targets — Instagram, Facebook, TikTok, Pinterest and Reddit — now all
have real-link evidence through the app's own extractor.** That is the acceptance criterion, and it is
met on the physical device.

### 42.3 The two dead links, and why they are conclusive

`reddit.com/r/ClaudeAI/s/5Fsbd5cn6i` and `pin.it/4gCIGTrkw` both came back `Unsupported`. The engine's own
message names the reason:

```
YtDlpExtractor: engine refused PINTEREST: ERROR: Unsupported URL: https://www.pinterest.com/?<redacted>
```

The short link **redirects to the site's home page**, so there is no pin or post to extract. The app's
detector had classified the URL correctly as `PINTEREST`; the link, not the code, was the problem.

### 42.4 A test-quality bug this exposed, and the fix

The first run did not record those two links — it **failed the whole suite**:

```
java.lang.AssertionError: the detector did not recognise a real video link as media:
  https://pin.it/4gCIGTrkw (source was PINTEREST)
```

That assertion was wrong on two counts, and it is worth spelling out because it is a mistake that
flatters neither the code nor the test:

1. **The detector had recognised it perfectly.** `MediaSourceDetector` returned `PINTEREST`. The
   `Unsupported` value came from the *engine*, which had refused to extract - a different component with
   a different meaning.
2. **The app's own code documents the ambiguity.** `YtDlpExtractor` states that yt-dlp's
   `"Unsupported URL"` is *the site's answer* and is reported as `Unsupported`, which is the same value
   returned for a URL no backend supports. The test asserted knowledge it could not have.

So a dead share link was reported as an app defect. `RealSiteLinksInstrumentedTest` now records that
outcome as a per-link refusal — `no extractable media (dead or redirected link)` — and keeps the site
counts honest. A genuinely broken app still fails, because the run requires **at least one** link to
extract.

This is the second time this session that a test's own expectation was the defect rather than the code
(cf. §41.4), and both were found by running against real inputs rather than fixtures.

### 42.5 Totals

| Check | Result |
|---|---|
| Specification's five named sites with real-link evidence | **5 of 5** — Instagram, Facebook, TikTok, Pinterest, Reddit |
| Best single-site pass | YouTube **10 / 10** |
| Links extracted across all passes | 13 (§41) + 4/5/4/0/2 (§42) = **27 successful extractions** |
| Failures across all passes | `NETWORK` throttling, `LOGIN_REQUIRED` ×1, dead redirects ×2 — none an app defect |
| `RealSiteLinksInstrumentedTest` | **2 tests**; the `Unsupported` handling was corrected this round |
| `:app:testDebugUnitTest` | **747 tests, 0 failures** |
| `:app:lintDebug` | **0 errors** |
| New file | `CODEBASE_GUIDE.md` — structure and design for a reviewer |

Instagram, Pinterest and Reddit were previously reported as an unfixable gap because they cannot be
self-served from this machine (§32.2). With real links they took one batch to prove. **The gap was never
the code; it was the input.**

---

## 43. Addendum 32 — Pinterest share links, and a cleaner rule they justified

### 43.1 What the owner sent, and the two questions it raised

The owner supplied Pinterest's **expanded share form**, which is what Pinterest's own share sheet
produces — the same three pins as his short links, but with a path segment and tracking parameters:

```
https://www.pinterest.com/pin/558164947591272325/sent/?invite_code=redacted…&sender=redacted&sfo=1
                        └── pin id ──┘ └────┘ └── per-share secret ──┘ └─ sharer id ─┘ └ flag ┘
```

Two questions follow, and they have different answers:

1. Can the extractor handle the `/sent/` path and the extra parameters? — **yes**.
2. Should the URL cleaner strip them? — **the parameters yes, the path segment no**.

### 43.2 What the engine actually needs — measured, not assumed

Each pin was probed in three forms through the app's own extractor on the POCO:

| Form | Result |
|---|---|
| Full share URL, parameters and `/sent/` intact | *Slow motion ❤️‍🔥*, `actress lunatic`, 16 s, 6 formats |
| `/sent/` kept, all three parameters removed | **identical** |
| Canonical `/pin/<id>/`, no `/sent/`, no parameters | **identical** |

All three produced the same title, uploader, duration and format list, so **none of the three
parameters nor the `/sent/` segment is load-bearing**. More importantly, nothing had to be *added* to
`YtDlpExtractor` — it already handled the share form, which is a useful result in itself.

### 43.3 The defect: the cleaner was leaving Pinterest's tracking on

`UrlCleaner` removes only *known* tracking parameters — an explicit list, plus the `utm_` family, plus a
**Facebook-only** set applied when the host is Facebook. Pinterest's names were in none of those, so a
pinned link kept `invite_code=…` forever. `invite_code` is a per-share secret, and the specification's
whole purpose for the cleaner is that a saved link is the clean one.

The fix follows the existing Facebook precedent exactly:

```kotlin
val PINTEREST_TRACKING_PARAMETERS: Set<String> = setOf("invite_code", "sender", "sfo")
private val PINTEREST_HOSTS: Set<String> = setOf("pinterest.com", "pin.it")
```

`isTrackingParameter` gained an `isPinterestHost` flag, and the duplicated host-matching logic became one
`matchesHost` helper, so `isFacebookHost` and `isPinterestHost` cannot drift apart. The `endsWith(".$it")`
subdomain test is retained, which is what stops `notpinterest.com` from matching — asserted directly.

**The `/sent/` path segment is deliberately preserved.** It does not affect extraction, and rewriting a
path is a larger and riskier change than dropping a query parameter; only a whole path segment with no
content role would be safe to remove, and this one is a verb, not an identifier. A test asserts it is
preserved so a future change to that behaviour has to be deliberate.

### 43.4 Seven unit tests, and one expectation of mine that was wrong

`UrlCleanerTest` gained seven cases: the exact owner-supplied URL shape, the `pin.it` short form, the
same parameter names on a **non-Pinterest** host (which must be left alone — the reason the rule is
host-scoped), `/sent/` preservation, host detection including the `notpinterest.com` negative and that
the two host sets do not leak into each other, the predicate scoping, and idempotence.

Two of them failed on the first run, and **the tests were wrong, not the code**: I had written the
expected output with a trailing slash, but `UrlCleaner` removes a trailing slash as a general rule for
every URL. The actual output was `…/sent` without it. Corrected, and the trailing-slash removal is now
noted in the test so the next reader does not repeat it. `UrlCleanerTest` is **68 tests** in total.

### 43.5 Totals

| Check | Result |
|---|---|
| Pinterest share form extracts | **PASS — 3 forms × 3 pins, all extracted identically** |
| Extractor changes needed | **none** — the share form already worked |
| Cleaner change | `invite_code`, `sender`, `sfo` removed on Pinterest hosts only |
| New unit tests | **7** (`UrlCleanerTest`, now 68 tests) |
| `:app:testDebugUnitTest` | **754 tests, 0 failures** |
| `:app:lintDebug` | **0 errors** |
| Release artifacts | rebuilt — `src/main` changed |

This is the fourth defect found by driving the app with real input rather than fixtures, and the second
one this session where the *test's* expectation was the thing at fault. Both patterns are recorded as
traps in `SESSION_HANDOVER.md` §5.

---

## 44. Addendum 33 — a regression caught by the device test, and `DATA` being unqueryable

### 44.1 What happened

A concurrent writer refactored `MediaStoreSink` while this session was paused. The refactor **removed the
filesystem-first publish fallback** and replaced it with a stricter check that trusts only the handle's
own row. The motivation was legitimate, and it was a hole in the previous code:

> The earlier `publishedFileOnDisk` matched *any* Downloads row of the same name and size, so on a
> collision it could hand back a **different operation's file**. The new writer added a test for exactly
> that — `sameSizeRowsAndHigherCollisionSuffixesCannotClaimAnotherOperation`.

But removing the fallback also removed the recovery, and the very next device run caught it.
`PublishFallbackInstrumentedTest` had **not** been changed, and still expected the old, correct behaviour:

```
AssertionError: a download whose bytes are complete must not be reported as a failure:
  Failed(error=NO_STORAGE, detail=the finished download could not be published, httpCode=null, transient=false)
```

The bytes were complete and on disk. The user was told **"Not enough storage is available."** That is
precisely the defect this sink has now been fixed for twice (§27.3, §35), and it is the clearest
illustration in this report of why an existing device test must not be discarded when the implementation
behind it is rewritten.

### 44.2 Root cause of the missing recovery: `DATA` cannot be queried

The first attempt to restore the fallback kept the previous approach of reading the row's `DATA` column
to locate the file. On this device that threw:

```
DIAG query failed: java.lang.IllegalArgumentException: Invalid column data
```

`MediaStore.MediaColumns.DATA` maps to `_data`, which is not a queryable column through the `Downloads`
collection on this ROM. The path therefore has to be **derived**, not looked up.

### 44.3 Why every bound in the restored fallback is load-bearing

A second diagnostic listed the Downloads directory during the failing test:

```
DIAG downloadsDir=/storage/emulated/0/Download canRead=true now=1789702391586
DIAG file=linksi-fallback-probe (2).mp4 len=65536 mtime=1789702149817   (241 s old)
DIAG file=linksi-fallback-probe (3).mp4 len=65536 mtime=1789702249097   (143 s old)
DIAG file=linksi-fallback-probe.mp4     len=65536 mtime=1789702391545   (41 ms old)
```

**Three files, identical name stem, identical size.** That measurement settles the design:

| Bound | Why it is needed |
|---|---|
| Name is the requested one **or** the provider's `name (n).ext` collision form | MediaStore renames on collision, so the requested name is not always the file's name |
| Exact committed byte size | A partial file must never be reported as complete |
| Modified at or after the handle opened | Without this, the two older same-size files above would be claimed as this download's |

No single bound is sufficient — **both stale files pass the name and size tests**. It is the conjunction
that identifies our file, which is simultaneously the safety property the concurrent writer was asking
for and the recovery this session needed.

### 44.4 Verified on the POCO

| Test | Result |
|---|---|
| `aFinishedFileIsReportedFromDiskWhenTheCollectionRefusesToPublishIt` | **PASS** — a complete file is reported from disk again |
| `sameSizeRowsAndHigherCollisionSuffixesCannotClaimAnotherOperation` | **PASS** — a same-size file from another operation is still not claimed |

Both properties hold at once, which is the point: the earlier code had the first without the second, and
the refactor had the second without the first.

### 44.5 Totals

| Check | Result |
|---|---|
| Regression found by an existing device test | yes — a completed download reported as `NO_STORAGE` |
| Root cause of the missing recovery | `DATA` is not a queryable column on this ROM |
| Fix | bounded fallback: name (or collision suffix) + exact size + written-this-handle |
| `PublishFallbackInstrumentedTest` | **PASS — 2 of 2** |
| `:app:testDebugUnitTest` | **772 tests, 0 failures** (35 suites, including the concurrent writer's 10 new tests) |

### 44.6 A note on the shared checkout

The refactor arrived in a **dirty working tree written by a second agent**, uncommitted and unreviewed by
this session. Its code builds and its unit tests pass, but it had silently changed a user-visible
behaviour that an instrumented test was guarding. `SESSION_HANDOVER.md` §6 carries the full warning and
the artifact consequences. The short version: **only a device test suite catches a behaviour change in an
uncommitted branch**, so it must be run before any tree is treated as a release candidate.

---

## 45. Addendum 34 — a second device failure, and this time the test was at fault

### 45.1 The failure

Running the instrumented suites over the same shared tree produced a second failure, in a different
suite:

```
ClipboardPanelInstrumentedTest > aReusedSingleTopPanelShowsTheNewExplicitUrl FAILED
java.lang.AssertionError: Failed to perform checkIsDisplayed check:
  Expected at most 1 node but found 5 nodes that satisfy
  (Text + EditableText contains 'https://example.com/first.mp4' (ignoreCase: false))
```

It failed on the **first** URL, before the reuse behaviour under test was even reached, and it reproduced
when the class was run alone — so it was not cross-test contamination.

### 45.2 It is the panel's layout, not a defect

The test asserted `onNodeWithText(first).assertIsDisplayed()`, which requires that exactly **one** node
holds that string. The panel deliberately renders the cleaned URL in more than one place:

| Where | Line |
|---|---|
| The URL preview (`UrlPreview`) | `QuickActionPanel.kt:137` |
| The subtitle of the "Clean URL" action row | `QuickActionPanel.kt:171` |
| The download row's subtitle, when the link has no formats | `QuickActionPanel.kt:171` region |

The test's URLs are `https://example.com/first.mp4` and `…/second.mp4` — no query string, so the cleaner
is a **no-op and `cleanedUrl == url`**. Every one of those places therefore holds the identical string,
and the count the assertion demands cannot hold. The failure is a fact about the panel's layout, not a
change in behaviour.

### 45.3 Why it surfaced now

The panel's URL-rendering code was not changed by the concurrent writer — their `QuickPanelActivity`
work added `onNewIntent`/`acceptIntent` so a reused `singleTop` panel picks up a new URL, which is the
behaviour this very test is about. The assertion is simply fragile: it passed while some other factor
kept the node count at one (Compose's semantics merging, or the download row not being composed), and any
change to what is rendered can tip it over. **A test that depends on how many nodes happen to contain a
string is a test that breaks for reasons unrelated to its subject.**

### 45.4 The fix — make the assertion precise, not looser

The temptation is to swap `onNodeWithText` for `onAllNodesWithText` and assert "at least one". That would
make the test pass while removing its value: it would no longer distinguish the preview from any other
place the URL appears.

Instead the preview now carries a stable tag:

```kotlin
/** Identifies the URL **preview** inside the panel's view hierarchy. */
const val PANEL_URL_TAG = "quick_panel_url_preview"     // QuickActionPanel.kt
```

and the test asserts against that node specifically — that it contains the first URL, then the second
after the reuse, and no longer contains the first. The property under test (a reused panel shows the new
URL and not the old one) is unchanged and is now checked where it is actually meaningful.

### 45.5 Totals

| Check | Result |
|---|---|
| `ClipboardPanelInstrumentedTest` | **PASS — 5 of 5** (was 4 of 5) |
| Was it a product defect? | **No** — the panel legitimately shows the URL in several places |
| Was the test at fault? | **Yes** — it asserted a node count it could not rely on |
| Fix | a `testTag` on the URL preview; assertions target it |
| `PublishFallbackInstrumentedTest` | **PASS — 2 of 2** (§44) |

This is the **third** time this session that an instrumented run revealed the *test* rather than the
code, and the first where the remedy was a more precise assertion rather than a corrected expectation.
All three are recorded as traps in `SESSION_HANDOVER.md` §5.

---

## 46. Addendum 35 — final verification of the delivered objective

### 46.1 What this section is for

Everything above is a record of individual findings. This section answers a different question: **at
commit `94a99a1`, is the objective actually delivered, and is that provable from a clean tree?** Every
number below was taken in one pass against that commit, not carried forward from an earlier addendum.

`OBJECTIVE_VERIFICATION.md` carries the clause-by-clause matrix (objective clause → implementation →
test → evidence). This section carries the raw gate results.

### 46.2 The gates, re-run in one pass

| Gate | Result |
|---|---|
| `:app:testDebugUnitTest` | **772 tests, 35 suites, 0 failures, 0 errors, 0 skipped** |
| `:app:lintDebug` | **0 errors**, 180 warnings, 4 hints — all pre-existing baseline |
| Instrumented suites on the POCO | **13 suites, 0 failures** |
| Instrumented tests | **39** |

The instrumented run, suite by suite, on the physical POCO X3 Pro:

| Suite | Result |
|---|---|
| `CoreFlowsSmokeTest` | 4 / 4 |
| `Android16CompatibilitySmokeTest` | 4 / 4 (skipped by assumption on Android 13 — emulator evidence only) |
| `DownloadEngineInstrumentedTest` | 1 / 1 |
| `BubbleOverlayInstrumentedTest` | 1 / 1 |
| `ClipboardPanelInstrumentedTest` | 5 / 5 |
| `DirectFileDownloaderInstrumentedTest` | 2 / 2 |
| `PublishFallbackInstrumentedTest` | 2 / 2 |
| `RealSiteLinksInstrumentedTest` | 2 / 2 |
| `ServerResolverInstrumentedTest` | 3 / 3 |
| `SlowTransferInstrumentedTest` | 1 / 1 |
| `StorageIntegrityInstrumentedTest` | 3 / 3 |
| `YtDlpInterruptedDownloadTest` | 4 / 4 |
| `YtDlpMediaSmokeTest` | 7 / 7 |

### 46.3 The artifact, checked against the tree rather than assumed

| Check | Result |
|---|---|
| Build record commit | `94a99a1` |
| `git rev-parse HEAD` | `94a99a1` — **matches** |
| Tree state | **clean**, `uncommitted: no` |
| Newest file under `app/src` | 09:02:38 |
| arm64 APK built | 09:12:26 — **newer than the source**, so it contains every source change |
| Debug APK used for the device runs | 09:03:24 — also newer than the source |
| arm64 digest | `474A37E4…` — MATCH against its `.sha256` |
| universal digest | `51FCF54A…` — MATCH against its `.sha256` |
| Signature | verifies, APK Signature Scheme v2, 4096-bit RSA |

That last group is the part that is easy to get wrong and worth doing every time: an artifact whose
recorded commit, tree state and source timestamps do not line up is not evidence of anything.

### 46.4 Totals

| Check | Result |
|---|---|
| Objective clauses with implementation, test and device evidence | **all** (see `OBJECTIVE_VERIFICATION.md`) |
| Unit tests | **772 / 0 failures** |
| Lint | **0 errors** |
| Instrumented suites | **13 / 13**, 39 tests, 0 failures |
| Signed APK | built from the committed tree, digests and signature verified |
| Documentation set | **11 documents** |

### 46.5 What remains, stated plainly

Completing the objective is not the same as having no limits, and the honest qualification is:

- **Nothing has run on the owner's actual phone** (OPPO Reno15 / ColorOS 16). Every device result is a
  POCO X3 Pro (Android 13 / MIUI 14) or an API 36 emulator. This is the highest-value remaining work and
  it needs the owner's hardware.
- API 36 UI depth (predictive-back ordering through nested sheets, rotation, cutouts, IME,
  tablet/foldable) is untested — compatibility depth, not known failures.
- The licence question is unresolved by the owner's own direction (`LICENSE_REVIEW.md`).
- Reminders are broken upstream; Room schema 12 has no migrations; MediaStore resume is deliberately
  not implemented.
