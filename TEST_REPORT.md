# TEST_REPORT.md

Test report for the Linksi Enhanced private build (specification section 44, testing plan section 104).

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
| No Android SDK | Installed into the workspace at `E:\Deepseek\android-sdk`: `platforms;android-34/35/36`, `build-tools;34.0.0/35.0.0/36.0.0`, `platform-tools`. Google's own `sdkmanager` cannot reach `dl.google.com` in this sandbox (`IO exception while downloading manifest`), so the component zips were fetched directly from the repository manifest with the JDK's HTTP client — script: `E:\Deepseek\install-sdk-packages.ps1`. |
| No `jlink` | The JetBrains Runtime bundled with PyCharm has no `jlink`, which AGP's `JdkImageTransform` requires. A real **Temurin JDK 17.0.20.1** was installed at `E:\Deepseek\jdk-17`. |
| AGP wrote `~/.android/debug.keystore` outside the workspace | `app/build.gradle` now supports an opt-in `DEBUG_KEYSTORE_PATH` override; unset, behaviour is unchanged. |
| Release signing key absent | A private 4096-bit RSA release keystore was generated **outside the repository** (`E:\Deepseek\keys\linksi-enhanced-release.jks`, credentials in `KEYSTORE_CREDENTIALS.txt`). It is gitignored by construction and must be backed up; losing it means no future in-place update. |

### 9.2 Baseline APK archived (specification section 6.4)

Built from the untouched baseline tag `baseline-linksi-original` (`0f4af65`) in a throwaway
worktree, with only the debug signing override injected (the baseline revision predates it):

```text
file   : Linksi_baseline_3.1.1_vc20_debug.apk
size   : 24,141,629 bytes (23.02 MB)
sha256 : 665F3EF1F952064C320F97BA746F7655E92AF79F57372C7457B1D61CC8D5CE1C
```

Archived under `E:\Deepseek\baseline-artifacts\` with a `.sha256` sidecar. It is a **debug** build:
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
$env:JAVA_HOME='E:\Deepseek\jdk-17'                      # a real JDK: AGP needs jlink
$env:GRADLE_USER_HOME='E:\Deepseek\.gradle-home-main'    # keep caches inside the workspace
$env:GRADLE_OPTS='-Djava.io.tmpdir=E:\Deepseek\.tmp'
$env:DEBUG_KEYSTORE_PATH='E:\Deepseek\keys\debug.keystore'
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

Evidence files: `E:\Deepseek\emulator-evidence\` (`linksi-home.png`, `share-receiver.png`,
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
4. **A real download.** The engine, sinks and extractor are unit tested and compile, but no file has
   been downloaded on a device, so MediaStore output and the download notifications are unverified.
5. **The quick action panel** as rendered UI (not mounted on a screen yet).
6. **The server resolver** against a live server.
7. **Reminders/notifications end to end** — known broken upstream (see CODE_REVIEW.md finding 4).
8. **Predictive back, edge-to-edge, rotation, process death, update-over-install, low storage and
   network interruption.**
9. **The minimum release gate (testing plan §100)** — device coverage of the core regressions is
   still incomplete, so the release APK is a **candidate for private use, not a stable release**.
