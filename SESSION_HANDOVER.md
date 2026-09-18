# SESSION_HANDOVER.md

Everything a new session needs to continue this project without re-deriving anything.
**Read this first, then `TEST_REPORT.md` (what is proven) and `CHANGELOG.md` (what changed).**

> **Updated 2026-09-18.** Sections 1–5 are the durable environment/layout facts. **§6 is the
> open-work list** and the only place that claims to be current. §7–8 record what was done and the
> bugs that only a device found. **§9 is a step-by-step first hour for the next session.** §10–12
> record device-verified state and the emulator's failure modes. §13–14 hold the goal text and a
> ready-to-paste prompt.
>
> The two newest changes are the **site-engine refresh** (§3, and `TEST_REPORT.md` §19) and the fact
> that **real Facebook links now extract on both devices** — read §19 before touching anything in
> `enhanced/media/ytdlp/`, because it records a trap that cost two failed attempts.

---

## 1. What this is

A private, enhanced fork of **Linksi** (`https://github.com/AsukaAzure/Linksi`), an offline-first
Android link manager. The governing documents are the two specifications the project owner supplied:
`LINKSI_ENHANCED_REVISED_SPEC.md` (what to build) and `LINKSI_ENHANCED_TESTING_PLAN.md` (how to
verify it). Both were provided as chat attachments; they are **not** in this repository. Their rules
are quoted inline throughout the docs here, so the work can continue without them.

The private fork lives on GitHub at **`Sadloif/linksi-enhanced`** (private, created via the API, no
fork relationship). Branches and tags up to `v3.1.1-enhanced.1` are already pushed. **Do not delete
anything already pushed.** The owner asked to defer further pushes until the work is final.

---

## 2. Where everything is

The project lives in one umbrella folder, `E:\Deepseek\Linksi\`:

```text
repo\                      the git checkout          (branch enhanced/integration)
toolchain\android-sdk\     Android SDK: platforms/build-tools 34/35/36, platform-tools, emulator,
                           system-images\android-36\google_apis\x86_64
toolchain\jdk-17\          Temurin JDK 17.0.20.1  <-- required; see §5
local\                     caches and scratch: .gradle-home-main, .gradle-verify-home, .android*,
                           .probe (downloads + build logs + diagnostics scripts), .tmp
keys\                      signing material (NEVER committed, never inside the repo)
  linksi-enhanced-release.jks   private release key, 4096-bit RSA, sha256 1E:7F:FE:B4...:96
  KEYSTORE_CREDENTIALS.txt      alias + passwords. BACK THIS UP: losing it means no future update
  debug.keystore                standard android/androiddebugkey debug key
artifacts\baseline\        the untouched-baseline APK + sha256
artifacts\releases\        signed release APKs + sha256 + latest-build.json
evidence\                  emulator screenshots, logcat captures, a pulled database
research\                  DEPENDENCY_OPTIONS.md, ANDROID16_REQUIREMENTS.md,
                           YTDLP_HANG_RESEARCH.md  <-- new this session, read it before touching
                                                       the download watchdog
scripts\                   environment/device/release helpers (outside the repo on purpose)
worktrees\                 per-task git worktrees (currently ytdlp and download-ui, both merged)
linksi-urlcleaner-verify\  throwaway JVM project used by repo\tools\run-urlcleaner-tests.ps1
```

Current state of the checkout:

| | |
|---|---|
| branch | `enhanced/integration` |
| versionCode / versionName | **23** / **3.1.1-enhanced.3** (signed release built; see §3) |
| `applicationId` | `com.linksi.app` (unchanged from upstream; debug suffix `.debug`) |
| minSdk / compileSdk / targetSdk | 26 / **36** / **36** |
| unit tests | **747, 0 failures** |
| baseline tag | `baseline-linksi-original` at `0f4af65` (upstream 3.1.1 + 2 commits) |

**Two devices are normally attached**, and they answer different questions:

| | POCO X3 Pro (`69ef2e21`, codename *vayu*) | Emulator (`emulator-5554`) |
|---|---|---|
| OS / ABI | Android 13 (API 33), MIUI 14, arm64-v8a | Android 16 (API 36) userdebug, x86_64 |
| Worth for | the arm64 payload, **a residential IP the five target sites do not block**, MIUI limits | root access, fast iteration, API 36 behaviour |
| Gotcha | no root; unattended installs require the Developer-options state in §4.6 | its virtual radio (`netsimd`) crash-loops, so transfers are slow and occasionally die |

`adb devices -l` before assuming either is present. **Never install the same debug package to both at
once while a test is running** — they reinstall each other underneath and the mismatch looks exactly
like a crash (`INSTRUMENTATION_RESULT: shortMsg=Process crashed.`).

The full instrumented suite passes on **both** devices as of round 11 (`TEST_REPORT.md` §31): five
suites on the POCO, and those plus the two yt-dlp suites on the emulator. When running them through
`local\.probe\run-suites.ps1`, note that it reinstalls only the **test** APK — install the app APK
yourself after any change to app code, or the run will exercise the previous build.

---

## 3. What is built and verified

Everything below has evidence in `TEST_REPORT.md`; that file is the authority, not this summary.

| Module | State |
|---|---|
| URL cleaner + "Clean URLs when saving" + clean-URL actions | **Done**, 83 tests, verified through the real share sheet **and** in the stored database row on a device: a Reel shared with `utm_source`/`fbclid` is stored with neither. Evidence: `TEST_REPORT.md` §24 |
| `normalizeUrl` whole-URL lowercasing | **Fixed** (it was destroying path/query case on every save) |
| Smart link detection + optional Accessibility service + floating bubble | **DONE, including the pixels** (`TEST_REPORT.md` §25, §26, §38, §39). The service is bound with its four event types, `SYSTEM_ALERT_WINDOW` is granted, `BubbleService` logs `bubble shown as a TYPE_APPLICATION_OVERLAY window`, the overlay window is present with a surface and unobscured, **and a screenshot shows the bubble over the launcher** — the last open item in this module. The tap decision is pinned by 11 unit tests and the route a tap leads to has its own instrumented test |
| API 36 compatibility suite | **Runs on the emulator only.** `Android16CompatibilitySmokeTest` is 4/4 **skipped by assumption** on the POCO (Android 13). Do not quote it as phone evidence — the POCO's real contribution is `CoreFlowsSmokeTest` 4/4 (`TEST_REPORT.md` §25.5) |
| Quick action panel | **Done and mounted** — reachable from the link options sheet, from the bubble, and screenshot-verified on device |
| Direct file downloader (images/PDFs/audio/video/archives) | **Done**, real download verified on device into MediaStore (`is_pending=0`) |
| Site extraction via yt-dlp (`youtubedl-android` 0.17.3 + FFmpeg) | **Implemented**; extraction verified on device (11 formats from a DASH manifest) |
| **Merged download (video+audio through FFmpeg)** | **VERIFIED END TO END ON REAL HARDWARE** — 21,210,202 bytes merged and published to MediaStore on the POCO X3 Pro. Evidence: `TEST_REPORT.md` §14 |
| **Real Facebook links extract** | **VERIFIED ON BOTH DEVICES** — after the engine refresh, the specification's own Reel example yields 11 formats up to 1920p. Bundled 2024.09.27 read 0 of 9 real links; current release reads 5 of 9 (the other 4 are dead share links). Evidence: `TEST_REPORT.md` §19 |
| **Real TikTok and YouTube links extract** | **VERIFIED ON THE POCO (and YouTube on the emulator)** — the owner's own 16-link list driven through the app's extractor: **13 of 16, TikTok 6/6 and YouTube 10/10**, 33–178 formats each, up to 3840p, with titles, uploaders and durations (including a 65-minute video). The 3 failures are TikTok `Connection reset by peer` throttling on a second pass over links that had just worked. Evidence: `TEST_REPORT.md` §41 |
| **Site-engine refresh (`YtDlpUpdater`)** | **Done and verified on both devices** — fixed release URL, SHA-256 against the published `SHA2-256SUMS`, atomic swap, post-install probe with rollback, weekly at most, manual Check in settings. 15 unit tests. The **Check button was tapped on the POCO on 2026-09-18** and reported `Already up to date (2026.08.19)`; doing so found and fixed a defect where the settings row never started the engine (`TEST_REPORT.md` §40) |
| **Download watchdog (stall + hard limits)** | **Implemented and unit tested (20 tests)**, and the stall path is now proven on hardware with a loopback server that sends bytes then stalls — the watchdog stopped yt-dlp and returned `NETWORK`, `transient=true` (`TEST_REPORT.md` §18.1) |
| **Retry resumes instead of restarting** | **Verified on hardware.** A dropped connection resumes at the exact byte (`Resuming download at byte 2096128`), the scratch directory is kept across a transient failure, and the retry sent `Range` at the preserved length (`TEST_REPORT.md` §18.1, §22) |
| **`POST_NOTIFICATIONS` runtime request** | **Granted on the POCO and confirmed by device state** (`granted=true`, `USER_SET`, §18.3). The dialog has now been seen appearing in context on the API 36 emulator while tapping Download (§27) |
| Optional private server resolver | **Verified on the wire** (`TEST_REPORT.md` §29). A test HTTPS server captured the one request it makes: body is exactly `{"url": …}` with the link and nothing else, the API key travels as an `Authorization: Bearer` header and is absent from the request target, an `http://` configuration is `Skipped` **before** anything is sent, and a malformed body, an HTTP 500 and a refused connection each come back as `SERVER_UNAVAILABLE` values rather than exceptions |
| Enhanced Features settings screen (all modules togglable) | **Done**; lint passes with 0 errors |
| Signed release APKs | **CURRENT — rebuilt 2026-09-18 after the `YtDlpRuntime` fix**: `3.1.1-enhanced.3` / versionCode 23, signed, hashed and archived in `artifacts\releases\` (arm64 `E1A392D8AF46F4E70B21CDB4DF1AF10B665B13E0A693AB60C1C7705F6B49FA7E`, universal `7E9222EFEF8D2067F523B23FD0AEB1F3BE08FDCAEDE6C7FDC1A4B12C87E9FA7B`, both re-hashed and the signature verified with `apksigner`). Tested on the POCO (installs, launches, no R8 fault) and on the emulator (panel renders; a fresh install refreshes its own engine; a Facebook Reel publishes as a 6.78 MB MP4 with the panel reporting **Download complete**). Evidence: `TEST_REPORT.md` §20–§41 **Superseded hashes: arm64 `D3944FAC…`, universal `6BDA8FE0…` — do not quote those for the current build** |
| A completed download is never reported as failed | **Fixed, four times over** (`TEST_REPORT.md` §27.3, §35). MediaStore can refuse the publish, leave a stale row, **rename** the file to `clip (1).mp4`, or keep a row for a file that has been deleted; each produced either a false failure or a false success. `commit` now asks the **filesystem first** (name plus the exact written size) and the collection's rows only afterwards, and `PublishFallbackInstrumentedTest` constructs the collision on demand so the guard cannot regress silently |
| A page can no longer be saved as a file | **Fixed and verified on device** — a lying content type used to put an XML error page into Downloads with no error; the body's leading bytes are now checked for a document signature, and such a URL is handed to the site engine instead of failing (`TEST_REPORT.md` §20.3, §21) |
| A dropped connection resumes | **Fixed and measured** — yt-dlp restarted every in-run retry, so a 6.46 MiB transfer that reached 97.6% failed; `--continue` is now passed and the engine reports `Resuming download at byte 2096128` (`TEST_REPORT.md` §22) |

---

## 4. How to build, test and run — exact commands

### 4.1 Environment (this exact set; others fail)

```powershell
$env:JAVA_HOME='E:\Deepseek\Linksi\toolchain\jdk-17'          # NOT the PyCharm JBR: it has no jlink
$env:GRADLE_USER_HOME='E:\Deepseek\Linksi\local\.gradle-home-main'
$env:GRADLE_OPTS='-Djava.io.tmpdir=E:\Deepseek\Linksi\local\.tmp'
$env:DEBUG_KEYSTORE_PATH='E:\Deepseek\Linksi\keys\debug.keystore'
$repo='E:\Deepseek\Linksi\repo'
```

`local.properties` already points `sdk.dir` at the relocated SDK. **Do not set
`ANDROID_USER_HOME` or `ANDROID_SDK_HOME`** — AGP then fails during plugin application with
`Could not create provider for value source AndroidLocationsBuildService.AndroidDirectoryCreator`.

### 4.2 Build and test

```powershell
& "$repo\gradlew.bat" -p $repo :app:testDebugUnitTest --console=plain --no-watch-fs
& "$repo\gradlew.bat" -p $repo :app:assembleDebug :app:assembleDebugAndroidTest --console=plain --no-watch-fs
& "$repo\gradlew.bat" -p $repo :app:lintDebug --console=plain --no-watch-fs   # must stay at 0 errors
```

Counting the unit tests from the XML, which is the reliable way (the console summary is easy to
misread):

```powershell
$dir="$repo\app\build\test-results\testDebugUnitTest"; $t=0; $f=0
Get-ChildItem $dir -Filter 'TEST-*.xml' | ForEach-Object {
  $x=[xml](Get-Content $_.FullName); $t += [int]$x.testsuite.tests
  $f += [int]$x.testsuite.failures + [int]$x.testsuite.errors }
"unit tests: $t, failures+errors: $f"
```

### 4.3 Long builds: run them as background jobs and poll, never block

A Gradle build here takes 30–90 s; a device test can take 5–10 minutes. **Do not sit in a long
blocking wait.** Start the command with `run_in_background: true`, then check it with a short poll.
Two traps this avoids: piping a build through `Select-Object -Last N` buffers every line until the
process exits (so a working build looks frozen), and a Gradle daemon inherits the output pipe so a
job can report "running" long after the build finished.

### 4.4 Release build (signed, archives every ABI split)

```powershell
powershell -File E:\Deepseek\Linksi\repo\tools\build-release.ps1
```

Runs tests and lint in **one Gradle invocation** and `assembleRelease` in a **separate** one — in a
single invocation lint's debug analysis reaches for release-variant KSP output that does not exist yet
and dies with `Unexpected failure during lint analysis ... Hilt_MainActivity.java`. Archives to
`artifacts\releases\` with per-ABI names and hashes.

### 4.5 Emulator (Android 16 / API 36, x86_64, WHPX-accelerated)

```powershell
$env:ANDROID_SDK_ROOT='E:\Deepseek\Linksi\toolchain\android-sdk'
$env:ANDROID_HOME=$env:ANDROID_SDK_ROOT
$env:ANDROID_AVD_HOME='E:\Deepseek\Linksi\local\.android-avd'
$env:ANDROID_EMULATOR_HOME='E:\Deepseek\Linksi\local\.android-emulator'
& 'E:\Deepseek\Linksi\toolchain\android-sdk\emulator\emulator.exe' -avd linksi36 `
    -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot -no-metrics -port 5554
```

Boots in ~2–3 minutes. It may already be running — check first. It is **root** (`adb root` reports
already-root; `su 0` works), which is how the raw yt-dlp experiments in §4.8 are possible.

### 4.6 Device tests — `am instrument`, not `connectedDebugAndroidTest`

`:app:connectedDebugAndroidTest` fails in this environment with a host-path
`java.io.IOException: The system cannot find the path specified` (AGP's test-result handling, not a
test failure). Install the APKs and drive the runner directly:

```powershell
$adb='E:\Deepseek\Linksi\toolchain\android-sdk\platform-tools\adb.exe'
$repo='E:\Deepseek\Linksi\repo'
# emulator (x86_64):
$apk='app\build\outputs\apk\debug\app-universal-debug.apk'
# POCO X3 Pro (arm64):
# $apk='app\build\outputs\apk\debug\app-arm64-v8a-debug.apk'

& $adb -s <serial> install -r -t "$repo\$apk"
& $adb -s <serial> install -r -t "$repo\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
& $adb -s <serial> shell am instrument -w -e class com.linksi.app.CoreFlowsSmokeTest `
      com.linksi.app.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Suites and what each is for:

| Suite | Tests | Purpose |
|---|---|---|
| `CoreFlowsSmokeTest` | 4 | core save/share/cleaner |
| `DownloadEngineInstrumentedTest` | 1 | a real download through the production engine |
| `YtDlpMediaSmokeTest` | 6 | engine present, lazy init, live extraction, **the merged download**, fake-site probes and one configurable real-target probe |
| `YtDlpInterruptedDownloadTest` | 4 | cancellation, legacy public-stream resume, deterministic transient resume, deterministic watchdog |
| `Android16CompatibilitySmokeTest` | 4 | targetSdk 36, Share Receiver, Quick Panel, onboarding navigation-bar inset |

`YtDlpMediaSmokeTest` accepts instrumentation arguments, which is how its deadline is set:

```powershell
& $adb -s <serial> shell am instrument -w `
  -e class 'com.linksi.app.YtDlpMediaSmokeTest#aVideoOnlyFormatIsMergedAndPublishedWhereTheUserCanFindIt' `
  -e ytdlpDownloadTimeoutSeconds 900 `
  -e ytdlpFormatId bbb_30fps_640x360_1000k `
  com.linksi.app.debug.test/androidx.test.runner.AndroidJUnitRunner
```

**Read the logcat, not just the summary line.** `am instrument` prints `OK (N tests)` even when a
test was skipped by a failed JUnit assumption — that is how a *skip* hid this project's biggest open
item for two sessions. Also note the runner's own line, `run finished: N tests, F failed, I ignored`,
and the test's own `Log.i` lines.

**Run one suite at a time.** Two suites on one device reinstall `com.linksi.app.debug` under each
other and the mismatch surfaces as a crash.

For unattended installs on this MIUI 14 POCO, Developer options now has *Install via USB* enabled,
*Verify apps over USB* disabled and *MIUI optimization* disabled. The last switch was hidden until
*Reset to default values* was tapped repeatedly. This exact setup was verified by an unattended
`adb install -r` returning `Success` without an on-device prompt. Turning MIUI optimization back on
will restore the per-install confirmation.

### 4.7 Which APK goes on which device

- POCO X3 Pro: `app-arm64-v8a-debug.apk` for tests, `artifacts\releases\...arm64-v8a.apk` for a
  release check.
- Emulator: `app-universal-debug.apk` (it is x86_64; the arm64 split will not install).

### 4.8 Driving the bundled yt-dlp by hand — the fastest way to learn anything about it

This session's breakthrough came from running the app's *own* yt-dlp outside the app, so experiments
cost seconds instead of a 90-second rebuild. On the emulator only (needs root):

```powershell
# E:\Deepseek\Linksi\local\.probe\ytdlp-device-run.ps1
powershell -File E:\Deepseek\Linksi\local\.probe\ytdlp-device-run.ps1 `
  -LogName myrun -TimeoutSeconds 300 `
  -YtDlpArgs '-F https://dash.akamaized.net/akamai/bbb_30fps/bbb_30fps.mpd'
```

It resolves the native library directory at run time (the path contains an install-time hash that
**changes on every reinstall** — do not hardcode it), pushes a shell script to the device and runs it
as root with the environment `YoutubeDL.init` sets:

| variable | value |
|---|---|
| `PYTHONHOME` / `HOME` | `<base>/packages/python/usr` |
| `SSL_CERT_FILE` | `<base>/packages/python/usr/etc/tls/cert.pem` |
| `LD_LIBRARY_PATH` | `<base>/packages/python/usr/lib:<base>/packages/ffmpeg/usr/lib:<nativeLibraryDir>` |
| `TMPDIR` | the app cache dir |

where `<base>` = `/data/data/com.linksi.app.debug/no_backup/youtubedl-android`.

Three traps that cost time here:
1. **The payload is a Python script, not an ELF binary.** `file` says `python3 script`, it starts
   `#!/usr/bin/env python3`, and it must be run as
   `<nativeLibraryDir>/libpython.so <base>/yt-dlp/yt-dlp <args>`. Executing it directly fails with
   `No such file or directory`, and through `timeout` with `Permission denied`.
2. **`run-as` cannot exec it.** The `runas_app` SELinux domain is denied `exec()`. `run-as` is fine
   for *reading* files (with `sh -c`, and note it does not re-expand globs), useless for running the
   payload. The app itself has no such problem.
3. **`%(title)s` in an output template is shell syntax to the device shell** (`unexpected '('`), so
   the script builder substitutes a placeholder and quotes it.

`local\.probe\` also holds the earlier scratch scripts (`ytdlp-monitor.ps1`, `ytdlp-raw-run.ps1`) and
the run logs `ytdlp-merge1/2/3.log`, which are the raw evidence for `TEST_REPORT.md` §14.

---

## 5. Environment traps that already cost time

| Symptom | Cause and fix |
|---|---|
| `jlink executable ... does not exist` | PyCharm's JBR is not a full JDK. Use `toolchain\jdk-17` |
| `Could not create provider for value source AndroidLocationsBuildService...` | `ANDROID_USER_HOME`/`ANDROID_SDK_HOME` are set. Unset them |
| `Keystore file ... not found for signing config 'debug'` | Set `DEBUG_KEYSTORE_PATH`; AGP otherwise writes `~/.android`, which the sandbox denies |
| Lint crashes: `Unexpected failure during lint analysis of BubblePolicyTest.kt` | `checkTestSources = false` in `app/build.gradle`'s `lint` block (already set). `MissingTranslation` is also disabled — the baseline already failed on it |
| Dozens of phantom `Unresolved reference` for real declarations | Corrupted shared Kotlin daemon. Add `-Dkotlin.compiler.execution.strategy=in-process`. **`SESSION_HANDOVER.md` v1 documented this but the commands were missing the flag, which cost an agent a failed build this session** |
| Two Gradle invocations at once die with `Accessing unreadable inputs ... dirty-sources.txt` on `compileDebugKotlin` | Two agents (or a subagent and the main session) sharing one daemon. **Serialise Gradle; retry once unchanged rather than changing configuration** |
| `BUILD FAILED` with `Gradle build daemon disappeared unexpectedly` | The daemon was killed by memory pressure (`insufficient memory for the Java Runtime Environment ... malloc failed`), not a compile error — the crash log `hs_err_pid*.log` lands in `E:\Deepseek\`, one level above the repo. Serialise Gradle and retry on a fresh daemon; the same script then succeeds unchanged (`TEST_REPORT.md` §36) |
| `sdkmanager` reports `IO exception while downloading manifest` | Its HTTP stack cannot reach Google here. Use `scripts\install-sdk-package.ps1`, which resolves archives from the repository manifests with the JDK's HTTP client. System images live in `sys-img\...\sys-img2-3.xml`, and their archive URLs are relative to *that* manifest's directory. Use `tar.exe`, not `Expand-Archive`, for the multi-GB Zip64 images |
| `git push` dies with `sh.exe: couldn't create signal pipe` | The sandbox blocks the named pipes git's sh needs for credential helpers. Push with `-c credential.helper= -c core.askPass=` and `GIT_TERMINAL_PROMPT=0` |
| PowerShell `Invoke-RestMethod` fails: `The underlying connection was closed` | schannel cannot handshake here. Use the JVM (`scripts\GitHubApi.java`) |
| A build reports FAILED though its log ends `BUILD SUCCESSFUL` | A PowerShell helper leaked `Tee-Object` output into its return value, so the exit code compared as an array. Already fixed in `build-release.ps1` |
| `"$name: not present"` / `"$sub:"` parse errors | PowerShell needs `${name}` before a colon |
| Long `adb shell` one-liners come back as `The module 'ffmpeg' could not be loaded` or `Unexpected token` | PowerShell is mangling nested quotes and `su 0 sh -c '...'`. **Push a shell script to the device and run that** (§4.8) — this is the pattern that works |
| A device run reports `Tests run: N` for suites you did not name | `am instrument -e class A,B,C` does **not** split on commas: the runner treats the string as one unknown class and silently falls back to its own selection. Invoke one class per run (`local\.probe\run-suites.ps1` now splits for you) |
| A just-fixed defect still fails on a device | Check the **installed** app, not just the test APK. `local\.probe\run-suites.ps1` now installs both (and prints the app APK's build time); before round 12 it reinstalled only the test APK, so a stale app build survived and reproduced the old bug (`TEST_REPORT.md` §31.3) |
| A site URL "fails" and you are unsure whether it exists | A fabricated id returns **404**, a blocked site returns a login/bot error. Check the wording before concluding the app is at fault — four candidate URLs in round 12 turned out not to exist at all (`TEST_REPORT.md` §32.2) |
| `Process crashed while executing <test>` with a library exception in the log | Not always a crash in the code: an uncaught coroutine exception on Android kills the process. Check whether the exception should have been collected as a value (§8.3) |
| A loopback test "passes" but moves no bytes | `UnknownServiceException: CLEARTEXT communication to 127.0.0.1 not permitted` — the release policy blocks cleartext everywhere, including loopback, and the failure looks like an ordinary `Failed(...)` result. `src/debug/res/xml/network_security_config.xml` permits loopback **in the debug variant only**; if a loopback test misbehaves, check the mechanism's own log fingerprints (`Resuming download at byte …`) rather than the result code (`TEST_REPORT.md` §28) |
| A version probe of a yt-dlp file under a **temporary name** reports the *installed* version | Measured twice on the emulator, with an authentic checksum-verified asset. **The version string is only trustworthy at the canonical `yt-dlp/yt-dlp` path.** Decide updates from the file digest, and verify from the version *after* the swap — the code does exactly that now, and two device runs were lost to the opposite order (`TEST_REPORT.md` §19.4) |
| `lint` fails with `NewApi: … readNBytes` | minSdk is 26 and several convenient `java.io`/`java.util` methods need 33+. Use a plain read loop |
| On MIUI, the accessibility service sits in `Binding services` forever with `Crashed services:{}` and nothing in logcat | An earlier `am force-stop` of the app parks the bind permanently, and `settings put secure accessibility_enabled 1` + `enabled_accessibility_services` cannot recover it. **Launch the app first, wait for the process, then enable** — it binds immediately (`TEST_REPORT.md` §39.4). A user flipping the Settings toggle is unaffected, because the app's process is alive behind the Settings screen |
| `dumpsys window windows` "does not show overlay windows" on MIUI | **Wrong — it does.** The package lands on the `mOwnerUid=` line and `ty=APPLICATION_OVERLAY` several lines later inside one very long `mAttrs=` line, and MIUI lists framework windows first (the app overlay was `Window #7`). Find the block by its header, then read forward with `-Context 0,14` (`TEST_REPORT.md` §39.3). `SurfaceFlinger --list` genuinely is useless for this |
| An `am instrument` run ends `INSTRUMENTATION_RESULT: shortMsg=Process crashed.` | Check whether **you** killed it: an `am force-stop` issued while the test is running tears the process down and reports exactly this. The run's own logcat still shows its passes (`TEST_REPORT.md` §39) |
| `tools\build-release.ps1` fails at `:app:packageRelease` with `Unable to allocate 17024776 bytes` / `OutOfMemoryError`, or the daemon disappears | The release packaging (two ABI splits, one 125 MB) needs most of the daemon's 2 GB, and `build-release.ps1` runs `:app:testDebugUnitTest :app:lintDebug` **in the same daemon first**, so packaging starts on a heap the tests already filled. Run `powershell -File tools\build-release.ps1 -SkipChecks` (then run the gates separately) — it succeeded immediately on the first try after four failures. **Do not try to raise the heap: `-Xmx4096m` cannot even start on this machine** (`os::commit_memory … The paging file is too small`, G1 virtual space). 2 GB is the ceiling, not a modest default |
| `:app:validateSigningRelease FAILED` right after `:app:preBuild` | You invoked `gradlew :app:assembleRelease` directly, so `KEYSTORE_PATH`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD` were never set. Those are supplied by `tools\build-release.ps1`, which reads them from `E:\Deepseek\Linksi\keys\`. Always build releases through the script |
| `:app:compileReleaseKotlin FAILED` with dozens of phantom `Unresolved reference` for declarations that certainly exist (`LinksTheme`, `buildPanelState`, `panelLabelFor`, …) while the **debug** variant compiles the same source cleanly | **Corrupted Kotlin daemon, not a code error.** Do not edit the source. Stop every daemon, then set `GRADLE_OPTS` to include `-Dkotlin.compiler.execution.strategy=in-process` and retry — that fixed it immediately on 2026-09-18 after the release build died once. Proving the source is fine first is cheap: `gradlew :app:compileDebugKotlin` succeeding while release fails is the signature |
| `Markdown`/`TEST_REPORT.md` "file has not been read" when editing after a long gap | The session is tracking a stale read. Re-read the file (or a slice of it) before editing; the file itself is fine |
| A link test fails with "the detector did not recognise a real video link as media" | Almost certainly a **dead link, not a defect**. `MediaExtractionResult.Unsupported` means either "no backend handles this URL" **or** "the engine refused it as not media" — a short link that redirects to the site's home page looks exactly like the latter. `realLinkUrls` entries can be retired at any time; check the engine's own message (`ERROR: Unsupported URL: …`) before touching the classifier. Don't repeat the mistake of asserting the detector failed when the engine refused (`TEST_REPORT.md` §42.4) |
| A test's expectation looks wrong but the code looks right | Check the test first. Twice this session the *test* was the defect, both times found only by running against real inputs: an assertion that a dead link must be an app failure (`TEST_REPORT.md` §42.4), and a per-item `assumeTrue` that aborted a whole batch on one unreachable site (`TEST_REPORT.md` §41.4) |
| `IllegalArgumentException: Invalid column data` querying a `MediaStore.Downloads` row | `MediaColumns.DATA` / `_data` is **not queryable** on this ROM. Derive the path from `Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)` plus the display name instead. Note that MediaStore renames on collision, so allow for `name (n).ext` (`TEST_REPORT.md` §44.2) |
| An instrumented test fails after someone rewrote the code it guards | **Do not "fix" the test to match the new behaviour.** `PublishFallbackInstrumentedTest` failed exactly this way: a refactor that removed the disk fallback made a completed download report `NO_STORAGE`, and the unchanged test was right. Two real properties can be in tension (`TEST_REPORT.md` §44) |
| A file on disk appears to belong to this download but the fallback declines it | Check the modification time, not just name and size. Three same-named, same-sized files can coexist in Downloads; only the one written during this handle's lifetime is ours |

---

## 6. OPEN WORK — in priority order

> **Read this first — the checkout may be shared.**
>
> On 2026-09-18 the owner ran **two agents against this one checkout at the same time**. The second
> writer's work is on the **optional server resolver** (`DataStoreMediaResolver`,
> `MediaResolverFallback`, `resolveLocalThenPrivateServer`), plus hardening of `MediaStoreSink`,
> `YtDlpUpdater` and the download sinks, with new tests. As of this handover it is **committed**, but
> read the rules below before you build or trust anything.
>
> **Rules for a shared checkout:**
>
> 1. **`git status` before you build, and again before you commit.** If the tree is dirty with files you
>    did not change, do not `git add -A` — stage your own paths explicitly, or ask the owner.
> 2. **Do not `git stash` another writer's work** without backing it up first. A patch-and-copy backup
>    pattern that works is in `local\.probe\concurrent-writer-backup\`.
> 3. **Run the instrumented suites, not just the unit tests.** The second writer's refactor *built* and
>    *passed 772 unit tests* while silently changing user-visible behaviour that only a device test
>    caught — twice (`TEST_REPORT.md` §44 and §45). A green unit run is not a green tree.
> 4. **When a device test fails after someone rewrote the code it guards, do not edit the test to match.**
>    One of the two failures above was a real regression; the other was a genuinely fragile test. Decide
>    which by reading the failure, not by making it pass.
>
> **Artifact caution:** the APKs built at 07:50 on 2026-09-18 (arm64 `A2C1EC0D…`, universal `19E9F570…`)
> contain two of that writer's production files from before the build and **must not be presented as the
> verified build**. `artifacts\releases\latest-build.json` is the authority for what the current artifacts
> are.

The watchdog, true transient resume, notification permission and target-SDK items from the previous
handover are **closed with device evidence** (§10, §11 and `TEST_REPORT.md` §18).

**The floating bubble is now closed as well.** Its pixels were confirmed on the physical POCO on
2026-09-18 (`TEST_REPORT.md` §39): the overlay window is present, has a surface, is ready for display
and is not obscured, and the screenshot shows the purple-haloed Linksi mark floating over the launcher.
With that, **every module in the specification has been verified end-to-end on a real device, and no
link in any module is unproven.** What remains below is depth, breadth and the release itself — not
missing functionality.

Real-content support is also complete. On 2026-09-18 the owner supplied real links for every named site
and `RealSiteLinksInstrumentedTest` drove them through the app's own extractor on the POCO:

| Site | Result | Failures |
|---|---|---|
| YouTube | **10 / 10** | — |
| TikTok | **6 / 6** (first pass) | later passes: `NETWORK` throttling |
| Instagram | **4 / 5** | one post `LOGIN_REQUIRED` |
| Reddit | **4 / 5** | one short link redirects to Reddit's home page |
| Pinterest | **4 / 5** | one short link redirects to Pinterest's home page |
| Facebook | **5 / 9 live links** | the other 4 are dead share links (`TEST_REPORT.md` §19.2) |

Every failure was a property of the link or the network, not the app. Evidence: `TEST_REPORT.md`
§41–§42. Probe any new list with:

```powershell
adb -s 69ef2e21 shell am instrument -w `
  -e class com.linksi.app.RealSiteLinksInstrumentedTest `
  -e realLinkUrls "url,url,…" `
  com.linksi.app.debug.test/androidx.test.runner.AndroidJUnitRunner
```

As of 2026-09-18, **the specification's functional work is complete and every named site is proven with
real links.** What follows is what remains: two items that need the owner or his hardware, and three that
are depth or external.

1. **Physical-device testing on the owner's actual target** (OPPO Reno15 / ColorOS 16). The POCO X3
   Pro has now run the release APK, the instrumented suites, a real merged download, real extraction
   from all five named sites and the engine refresh; **nothing has run on ColorOS**, and the bubble
   overlay plus Android 14+/15 background-activity launch are still unverified there. This is the
   highest-value remaining item, and it needs the owner's phone.
2. **Broaden Android 16 UI coverage.** API 36 now passes target-SDK, core-flow, Share Receiver,
   Quick Panel and onboarding-inset checks. Still untested are predictive-back ordering through every
   nested sheet/dialog, gesture-vs-3-button navigation, cutouts, rotation, IME, and tablet/foldable
   layouts. These are compatibility-depth items, not known failures.
3. **Push + GitHub Release — the owner's call, and he has said "not yet".** The signed release is
   built, hashed and archived (`artifacts\releases\LinksiEnhanced_3.1.1-enhanced.3_*`), and both scripts
   work (`scripts\push-to-private-repo.ps1 -CreateRepo`, `scripts\create-github-release.ps1`). Both need
   a token at `E:\Deepseek\Linksi\keys\github-token.txt` (classic, scopes `repo` + `workflow`). **The
   previous token is in an old transcript — do not reuse it.** Do not push until the owner says the work
   is final. **The tree is committed** on `enhanced/integration` (which has no upstream); check
   `git status` rather than trusting any hash written here, since later edits become uncommitted again.
4. **The wrapper's process kill cannot be fixed from here.** `destroyProcessById` passes the
   library's own UUID to `pstree` (which does not exist on Android), and `grep -oP` is not in toybox,
   so only `Process.destroy()` — SIGTERM to the direct python child — ever runs, and descendants
   survive. The app works around it (SIGTERM, plus the interpreter closing FFmpeg's pipe) and checks
   the outcome in `YtDlpInterruptedDownloadTest`; a real fix means moving off 0.17.3 or vendoring
   upstream PR #367. Recorded in `TEST_REPORT.md` §15.2 and `research\YTDLP_HANG_RESEARCH.md` §4.
7. **`POST_NOTIFICATIONS` is granted on the POCO and confirmed by device state**, but the dialog in
   context (enable the setting → prompt; deny → download still works) has not been walked through on
   a device. §11 has the short manual script.

The owner explicitly asked this session to assume full licence rights, so licensing is not tracked as
an implementation blocker in this open list.

## 7. Things that are deliberately NOT done

- No `Media3` dependency; muxing is FFmpeg's job via yt-dlp.
- MediaStore resume is not implemented (app-specific storage resume is).
- Reminders are broken **upstream** and were not fixed (see `CODE_REVIEW.md` finding 4).
- APK builds are not byte-reproducible: absolute build paths feed into the output, so two builds of
  the same source produce different hashes.
- The app does **not** try to kill yt-dlp's descendants itself. Guessing PIDs from `ps` output inside
  an app process risks signalling something it does not own; the reasoning is in `TEST_REPORT.md`
  §15.2.
- `--downloader-args "ffmpeg_i:-rw_timeout 30000000"` (the only yt-dlp-side *real* read timeout,
  per `research\YTDLP_HANG_RESEARCH.md`) is **not** set. It is a candidate, not a decision.

---

## 8. What this session changed, and why it matters

The short version: **open item 1 was not a defect.** Three sessions reported a "stalled merge
download"; two of those were a test deadline expiring mid-transfer, and one was a genuine
intermittent emulator hang. Full evidence in `TEST_REPORT.md` §14.

### 8.1 The fact that settles it

The identical yt-dlp invocation, with no deadline, completed:

```text
[download] 100% of    5.03MiB in 00:00:59 at 86.18KiB/s     <- the "stalled" audio stream
[Merger] Merging formats into ".../bbb_30fps.mp4"
EXIT=0
```

and the same test on the POCO, with `-e ytdlpDownloadTimeoutSeconds 900`:

```text
15.5 MiB video stream + 5.03 MiB audio stream -> 296 s at 71,596 B/s
[Merger] Merging formats into ".../work-97f21ada/bbb_30fps.mp4"
Completed(location=content://media/external/downloads/1001336263, bytes=21210202,
          displayName=linksi-ytdlp-smoke.mp4, mimeType=video/mp4)
run finished: 1 tests, 0 failed, 0 ignored
```

**First merged download in the project's history, and the first published on real hardware.** The old
120 s deadline could not have succeeded on any connection this project has measured. The deadline is
now 900 s, documented as a *stuck-process bound*, overridable per run, and every run logs elapsed time
and throughput so the next deadline comes from measurement.

### 8.2 Code added or fixed because of it

| Change | File |
|---|---|
| Download watchdog: 60 s no-bytes stall limit (scaled to 8× for large files), 30 min hard limit, checked every 5 s | `enhanced/media/ytdlp/YtDlpDownloadWatchdog.kt` (new) |
| Retry resumes: scratch directory named from a SHA-256 of the download's identity, kept after a transient failure, deleted on success/permanent failure, stale sweep after an hour | `enhanced/media/ytdlp/YtDlpDownloader.kt` |
| `--fragment-retries 10`, `--force-ipv4` | same |
| yt-dlp's stdout **and** stderr go to logcat with every URL stripped of its query string | same |
| Failure-reason loss fixed (`coroutineScope` → `supervisorScope`) | same |
| Uncaught-exception crash fixed (`launch` → `async`/`await`) | same |
| Progress callback guarded so it can never kill yt-dlp's reader thread | same |
| Cancellation no longer crashes the process; a watchdog stop becomes a retryable failure value | same |
| `POST_NOTIFICATIONS` requested at runtime (2 in-context triggers, never at launch) | `enhanced/download/DownloadNotificationPermission.kt` (new) + `EnhancedSettingsScreen.kt`, `QuickPanelActivity.kt` |
| `-e ytdlpDownloadTimeoutSeconds`, `-e ytdlpFormatId`, `-e ytdlpUrl` | `YtDlpMediaSmokeTest.kt` |
| Device tests for cancel / resume / watchdog | `YtDlpInterruptedDownloadTest.kt` (new) |

### 8.3 Four bugs that only a device found

Worth internalising, because none was visible in review or in the unit tests:

1. **A user-initiated Cancel crashed the app process.** The library's `CanceledException` was
   re-thrown as a `CancellationException` out of `download()`, and an uncaught coroutine cancellation
   in the worker's scope kills the process.
2. **A failed download lost its reason.** With `coroutineScope`, a thrown `YoutubeDLException` (a
   plain 404 from the source) cancelled the scope before the failure could be collected, so the caller
   saw a *cancellation* — a 404 looked like the user pressing Cancel.
3. **An uncollected `launch` exception is fatal.** Even with `supervisorScope`, a failing `launch`
   reports its exception as uncaught, which Android turns into a process kill. It must be `async` +
   `await` for the exception to be collectable.
4. **The watchdog rule was wrong twice.** v1 killed downloads during yt-dlp's ~25 s manifest
   start-up; v2 killed them during FFmpeg post-processing, where yt-dlp is legitimately silent for
   minutes after a stream reports 100%. v3 additionally tracks whether the stream under way reached
   its total, and resets the clock when a second stream begins. Details in `TEST_REPORT.md` §15.5.

**The lesson for the next session is the same one that closed item 1:** when a download appears to
stall, measure bytes and elapsed time before concluding anything, and prefer a device run over a
theory.

---

## 9. A concrete first hour for the next session

1. `adb devices -l`. Both devices are normally attached; the POCO is the valuable one for network work.
2. Read §3's table and `TEST_REPORT.md` §18 before relying on the older addenda.
3. Run `:app:testDebugUnitTest` and confirm **747 tests, 0 failures**. If the number differs, read the
   diff before trusting anything else.
4. Start with §6.1: design a best-effort, throttled yt-dlp refresh using the wrapper's official
   `updateYoutubeDL(..., UpdateChannel.STABLE)` API, keep the bundled engine as the offline fallback,
   and add tests around the refresh decision. Re-run the five saved Facebook cases on the POCO.
5. Ask the owner for 2–5 public Instagram, TikTok, Pinterest and Reddit URLs. Do not ask for more
   Facebook URLs unless the refreshed engine produces a URL-specific result.
6. Do not repeatedly reinstall on the POCO. MIUI 14 requires an on-device confirmation on this build
   even though Android's two ADB verifier flags are already off. Build and iterate on the emulator,
   then ask for one accepted POCO install only when the APK actually changed.

---

## 10. Watchdog and resume verification — CLOSED on the POCO

The former two gaps are now deterministic Android tests backed by loopback HTTP servers, so neither
depends on a public CDN or the emulator's unstable radio.

### 10.1 The watchdog was observed firing on real hardware

`aDownloadThatStallsAfterTransferringBytesIsStoppedByTheWatchdog` sent 62,914 bytes and then held the
socket open. On the POCO the watchdog stopped yt-dlp after the configured no-progress interval and
returned exactly `NETWORK`, `transient=true`, with the stall detail. The run completed in 15.185 s.
This proves the production watchdog path, not a 404 substitute.

### 10.2 Transient failure resumes at the exact preserved byte

`aTransientFailureKeepsScratchAndTheNextAttemptResumes` uses a local server whose first request drops
mid-stream. The app returned a transient failure and kept the scratch files. The second request sent
`Range` starting at exactly byte **258,048**, then published a 4 MiB file whose bytes matched the
server payload. The run completed in 4.638 s. This directly proves the production "keep scratch after
a transient failure" branch.

### 10.3 User cancellation is prompt

Cancellation now stops the interpreter and unwinds in 9.631 s on the POCO, rather than waiting about
230 s for the transfer to finish. No interpreter/FFmpeg process survived any focused run.

---

## 11. POCO notification and unattended-install state

The owner granted Linksi notification permission on the Android 13 POCO. `dumpsys package
com.linksi.app` confirms `POST_NOTIFICATIONS: granted=true` with `USER_SET`; the dialog/device gap is
closed for the installed release package. The new debug package correctly started ungranted after a
fresh install, so package histories are not being confused.

MIUI 14's package-verifier flags were already off, but it still showed a separate confirmation for
every ADB install. The owner exposed the hidden Developer-options switch (tap *Reset to default
values* repeatedly) and turned **MIUI optimization off**. A subsequent streamed reinstall of the
arm64 debug APK completed with `Success` in 2.3 s and required no on-device input. Keep *Install via
USB* enabled and *Verify apps over USB* disabled for unattended test installs.

---

## 12. Reference: the emulator's two failure modes

Useful when a download misbehaves there, because they are different problems:

| Mode | Evidence | What it means |
|---|---|---|
| Slow | 80–220 KiB/s, varying | Normal for this virtual radio. Never conclude a stall from speed alone |
| Connection dies | `[download] Got error: [SSL] record layer failure (_ssl.c:2580). Retrying (1/3)...`, and one run froze mid-stream at 3,672,160 bytes for 9+ minutes | The radio (`netsimd`, which crash-loops) dropped the connection. Real, intermittent, and the reason the watchdog exists |

The second mode is what the watchdog is for. The first mode is why every deadline in this project must
be derived from measured throughput — the 120 s deadline that hid item 1 for two sessions came from
exactly this mistake.

---

## 13. The goal — recreate it in the new session

Goals are **session-scoped**: the object below cannot be transferred, only recreated. It was paused
(disarmed) at the end of this session so it would stop auto-continuing there, and note that at one
point it had disappeared entirely — `get_goal` returned `null` for a goal created active minutes
earlier, with no completion, block or edit from the session. **If that happens again, recreate it and
say so in the reply.**

| | |
|---|---|
| previous goal id | `goal-d8f713dc-5185-489f-9e7f-c45d19dd02d8` (reference only) |
| phase when paused | paused (disarmed), 0 of 40 rounds used |
| suggested round budget | 40 |

**Objective, verbatim — pass this exact text to `create_goal`:**

```text
Deliver the Linksi Enhanced private fork per LINKSI_ENHANCED_REVISED_SPEC.md: keep baseline
Linksi working, add isolated optional modules (URL cleaner integration, smart link detection +
accessibility + floating bubble, quick action panel, universal media downloader with extractor
abstraction, direct file downloader, optional server resolver), build and test a signed APK, and
produce the required documentation set.
```

Three rules to carry over with it:

- **Completion is evidence-based.** `TEST_REPORT.md` is the record of what is actually verified; an
  item may only move off the open list with device- or build-level evidence, not by assertion. The
  remaining items are in §6, so the goal should be created **active**, not complete.
- **Do not push to GitHub** until the owner says the work is final. Everything already pushed stays as
  it is — never delete or force-push over it. The release APKs must be rebuilt first anyway.
- **Do not block on long commands.** Start builds and device runs as background jobs and poll; a
  device run can take ten minutes and a single `wait` that long has already been interrupted twice.

---

## 14. Ready-to-paste prompt for the next session

> Continue the Linksi Enhanced private fork. Read `E:\Deepseek\Linksi\repo\SESSION_HANDOVER.md`
> first — it has the layout, the exact build/emulator/device commands, the environment traps, the goal
> text to recreate, and the prioritised open-work list. Then `TEST_REPORT.md` §19 (the site-engine
> refresh and real Facebook extraction) and `research\YTDLP_HANG_RESEARCH.md`.
>
> Create the goal using the verbatim objective in §13, with a 40-round budget. If `get_goal` reports
> that no goal exists, recreate it and tell me — and note that a paused goal cannot be resumed by you;
> only I can do that from the GUI.
>
> Done and device-verified: the merged download, the watchdog, transient resume, prompt cancellation,
> notification permission, targetSdk 36, and the site-engine refresh. **Real Facebook Reels now
> extract on both the emulator and the POCO** — 11 formats up to 1920p, including the specification's
> own example — after the refresh took the engine from 2024.09.27 to 2026.08.19.
>
> The remaining acceptance gap is the other four sites: I still need to give you 2–5 real public
> Instagram, TikTok, Pinterest and Reddit URLs. **Ask me for them first**, then run the real-target
> probe against each. While waiting, the two small leftovers in §6.4 are quick.
>
> Iterate on the emulator and reinstall the POCO only when the APK changes. Unattended ADB installs
> work after MIUI optimization was turned off. Do not push to GitHub until I say the work is final,
> and rebuild the signed release first — the archived APKs predate all of this.
