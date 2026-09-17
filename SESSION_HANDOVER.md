# SESSION_HANDOVER.md

Everything a new session needs to continue this project without re-deriving anything.
**Read this first, then `TEST_REPORT.md` (what is proven) and `CHANGELOG.md` (what changed).**

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
                           .probe (downloads + build logs), .tmp
keys\                      signing material (NEVER committed, never inside the repo)
  linksi-enhanced-release.jks   private release key, 4096-bit RSA, sha256 1E:7F:FE:B4...:96
  KEYSTORE_CREDENTIALS.txt      alias + passwords. BACK THIS UP: losing it means no future update
  debug.keystore                standard android/androiddebugkey debug key
artifacts\baseline\        the untouched-baseline APK + sha256
artifacts\releases\        signed release APKs + sha256 + latest-build.json
evidence\                  emulator screenshots, logcat captures, a pulled database
research\                  DEPENDENCY_OPTIONS.md (1,036 lines) and ANDROID16_REQUIREMENTS.md (945)
scripts\                   environment/device/release helpers (outside the repo on purpose)
worktrees\                 per-task git worktrees (currently ytdlp and download-ui, both merged)
linksi-urlcleaner-verify\  throwaway JVM project used by repo\tools\run-urlcleaner-tests.ps1
```

Current state of the checkout:

| | |
|---|---|
| branch | `enhanced/integration` (clean tree) |
| versionCode / versionName | **22** / **3.1.1-enhanced.2** |
| `applicationId` | `com.linksi.app` (unchanged from upstream; debug suffix `.debug`) |
| minSdk / compileSdk / targetSdk | 26 / **36** / **34** (targetSdk 36 is an open item) |
| unit tests | **701, 0 failures** |
| baseline tag | `baseline-linksi-original` at `0f4af65` (upstream 3.1.1 + 2 commits) |

---

## 3. What is built and verified

Everything below has evidence in `TEST_REPORT.md`; that file is the authority, not this summary.

| Module | State |
|---|---|
| URL cleaner + "Clean URLs when saving" + clean-URL actions | **Done**, 82 tests, and the spec's Facebook Reel example verified **on a device** through the real share sheet |
| `normalizeUrl` whole-URL lowercasing | **Fixed** (it was destroying path/query case on every save) |
| Smart link detection + optional Accessibility service + floating bubble | **Implemented**; the accessibility service is verified binding on device. The bubble's overlay is **not** verified on hardware |
| Quick action panel | **Done and mounted** — reachable from the link options sheet, from the bubble, and screenshot-verified on device |
| Direct file downloader (images/PDFs/audio/video/archives) | **Done**, real download verified on device into MediaStore (`is_pending=0`) |
| Site extraction via yt-dlp (`youtubedl-android` 0.17.3 + FFmpeg) | **Implemented**; extraction verified on device (11 formats from a DASH manifest). **The FFmpeg merge path is NOT verified** — see §6 |
| Optional private server resolver | **Client done**, inert unless enabled and HTTPS |
| Enhanced Features settings screen (all modules togglable) | **Done**; lint passes with 0 errors |
| Signed release APKs | **Done**: `3.1.1-enhanced.2` arm64 36.14 MB / universal 119.65 MB, both hash-recorded |

---

## 4. How to build, test and run — exact commands

### Environment (this exact set; others fail)

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

### Build and test

```powershell
& "$repo\gradlew.bat" -p $repo :app:assembleDebug :app:testDebugUnitTest --console=plain --no-watch-fs
& "$repo\gradlew.bat" -p $repo :app:lintDebug --console=plain --no-watch-fs   # must stay at 0 errors
& "$repo\gradlew.bat" -p $repo :app:assembleDebugAndroidTest --console=plain --no-watch-fs
```

### Long builds: poll a log, never block, never buffer

```powershell
$log='E:\Deepseek\Linksi\local\.probe\build.log'
$p = Start-Process -FilePath "$repo\gradlew.bat" `
      -ArgumentList '-p',$repo,':app:assembleDebug','--console=plain','--no-watch-fs' `
      -RedirectStandardOutput $log -RedirectStandardError "$log.err" -WindowStyle Hidden -PassThru
for ($i=0; $i -lt 20; $i++) { Start-Sleep 20; if ($p.HasExited) { break }
  Write-Output (Get-Content $log -Tail 1) }
```

Two traps this avoids: piping a build through `Select-Object -Last N` buffers every line until the
process exits (so a working build looks frozen), and a Gradle daemon inherits the output pipe so a
job can report "running" long after the build finished. **Watch the log's last task line**; same line
for minutes with no CPU means genuinely stuck.

### Release build (signed, archives every ABI split)

```powershell
powershell -File E:\Deepseek\Linksi\repo\tools\build-release.ps1
```

Runs tests and lint in **one Gradle invocation** and `assembleRelease` in a **separate** one — in a
single invocation lint's debug analysis reaches for release-variant KSP output that does not exist yet
and dies with `Unexpected failure during lint analysis ... Hilt_MainActivity.java`. Archives to
`artifacts\releases\` with per-ABI names and hashes.

### Emulator (Android 16 / API 36, x86_64, WHPX-accelerated)

```powershell
$env:ANDROID_SDK_ROOT='E:\Deepseek\Linksi\toolchain\android-sdk'
$env:ANDROID_HOME=$env:ANDROID_SDK_ROOT
$env:ANDROID_AVD_HOME='E:\Deepseek\Linksi\local\.android-avd'
$env:ANDROID_EMULATOR_HOME='E:\Deepseek\Linksi\local\.android-emulator'
& 'E:\Deepseek\Linksi\toolchain\android-sdk\emulator\emulator.exe' -avd linksi36 `
    -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot -no-metrics -port 5554
```

Boots in ~2–3 minutes. `netsimd` (the virtual radio) crash-loops noisily; the guest still works but
its network is unreliable for large transfers — this matters for §6.

### Device tests — `am instrument`, not `connectedDebugAndroidTest`

`:app:connectedDebugAndroidTest` fails in this environment with a host-path
`java.io.IOException: The system cannot find the path specified` (AGP's test-result handling, not a
test failure). Install the APKs and drive the runner directly:

```powershell
$adb='E:\Deepseek\Linksi\toolchain\android-sdk\platform-tools\adb.exe'
& $adb install -r -t "$repo\app\build\outputs\apk\debug\app-universal-debug.apk"
& $adb install -r -t "$repo\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
& $adb shell am instrument -w -e class com.linksi.app.CoreFlowsSmokeTest `
      com.linksi.app.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Suites: `CoreFlowsSmokeTest` (4, core save/share/cleaner), `DownloadEngineInstrumentedTest` (1, real
download), `YtDlpMediaSmokeTest` (5).

**Read the logcat, not just the summary line.** `am instrument` prints `OK (5 tests)` even when a
test was skipped by a failed JUnit assumption — `YtDlpMediaSmokeTest`'s merge test reports a *skip*
that way. `TestRunner: run finished: N tests, F failed, I ignored` plus the test's own log lines are
the truth.

**Run one suite at a time.** Two suites on one emulator reinstall `com.linksi.app.debug` under each
other and the mismatch surfaces as `INSTRUMENTATION_RESULT: shortMsg=Process crashed.` — a harness
artefact that looks exactly like a real crash.

---

## 5. Environment traps that already cost time

| Symptom | Cause and fix |
|---|---|
| `jlink executable ... does not exist` | PyCharm's JBR is not a full JDK. Use `toolchain\jdk-17` |
| `Could not create provider for value source AndroidLocationsBuildService...` | `ANDROID_USER_HOME`/`ANDROID_SDK_HOME` are set. Unset them |
| `Keystore file ... not found for signing config 'debug'` | Set `DEBUG_KEYSTORE_PATH`; AGP otherwise writes `~/.android`, which the sandbox denies |
| Lint crashes: `Unexpected failure during lint analysis of BubblePolicyTest.kt` | `checkTestSources = false` in `app/build.gradle`'s `lint` block (already set). `MissingTranslation` is also disabled — the baseline already failed on it |
| Dozens of phantom `Unresolved reference` for real declarations | Corrupted shared Kotlin daemon. Add `-Dkotlin.compiler.execution.strategy=in-process` |
| `sdkmanager` reports `IO exception while downloading manifest` | Its HTTP stack cannot reach Google here. Use `scripts\install-sdk-package.ps1`, which resolves archives from the repository manifests with the JDK's HTTP client. System images live in `sys-img\...\sys-img2-3.xml`, and their archive URLs are relative to *that* manifest's directory. Use `tar.exe`, not `Expand-Archive`, for the multi-GB Zip64 images |
| `git push` dies with `sh.exe: couldn't create signal pipe` | The sandbox blocks the named pipes git's sh needs for credential helpers. Push with `-c credential.helper= -c core.askPass=` and `GIT_TERMINAL_PROMPT=0` |
| PowerShell `Invoke-RestMethod` fails: `The underlying connection was closed` | schannel cannot handshake here. Use the JVM (`scripts\GitHubApi.java`) |
| A build reports FAILED though its log ends `BUILD SUCCESSFUL` | A PowerShell helper leaked `Tee-Object` output into its return value, so the exit code compared as an array. Already fixed in `build-release.ps1` |
| `"$name: not present"` / `"$sub:"` parse errors | PowerShell needs `${name}` before a colon |

---

## 6. OPEN WORK — in priority order

1. **yt-dlp merged-download path is unverified.** A video-only + audio download stalls. Diagnosed so
   far: the **video-only stream downloads fine**, the **audio stream stalls** (a `bbb_a64k.m4a.part`
   file is left in `cache/ytdlp/work-*/`), page size is 4096 (so the 16 KB issue does not apply), and
   FFmpeg's libraries load. Prime suspect: this emulator's crash-looping `netsimd` radio cannot
   sustain a multi-MB transfer. **Next step:** prove or kill that theory by pushing a multi-MB file
   through the app's own OkHttp downloader (the image has no `wget`/`curl`), then, if the network is
   fine, capture the yt-dlp child process's output.
2. **The five target sites are unconfirmed** (Instagram, Facebook, TikTok, Pinterest, Reddit). The
   emulator's datacentre IP is blocked by them; the tests only prove the calls return values rather
   than throwing. **A physical device on a normal connection is required** — this is the single most
   valuable next test and the spec's acceptance criteria depend on it.
3. **targetSdk 34 → 36.** The spec targets Android 16. Research (`research\ANDROID16_REQUIREMENTS.md`)
   warns that at 36 predictive back is enforced (`onBackPressed` no longer dispatched) and the
   edge-to-edge opt-out is dead. Known affected spots: `ShareReceiverActivity` never calls
   `enableEdgeToEdge()`, and `OnboardingScreen` applies no insets. Compose `BackHandler` is
   predictive-back compatible, but this needs a device pass.
4. **Physical-device testing** (the owner's target is an OPPO Reno15 / ColorOS 16). Nothing has run on
   real hardware; the bubble overlay and Android 14+/15 background-activity launch are unverified.
5. **Final push + GitHub Release.** Scripts are written and working:
   `scripts\push-to-private-repo.ps1 -CreateRepo` and `scripts\create-github-release.ps1`
   (attaches the universal APK + sha256). Both need a token at
   `E:\Deepseek\Linksi\keys\github-token.txt` (classic, scopes `repo` + `workflow`).
   **The previous token is in an old transcript — do not reuse it.**
6. **Licence question (distribution only).** Upstream declares MIT but has never committed a
   `LICENSE` file, and this build now bundles GPL-3.0 code. Private use is unaffected; sharing an APK
   is not. See `LICENSE_REVIEW.md` and `DEPENDENCY_REVIEW.md` §8.

## 7. Things that are deliberately NOT done

- No `Media3` dependency; muxing is FFmpeg's job via yt-dlp.
- MediaStore resume is not implemented (app-specific storage resume is).
- Reminders are broken **upstream** and were not fixed (see `CODE_REVIEW.md` finding 4).
- APK builds are not byte-reproducible: absolute build paths feed into the output, so two builds of
  the same source produce different hashes.

---

## 8. Ready-to-paste prompt for the next session

> Continue the Linksi Enhanced private fork. Read `E:\Deepseek\Linksi\repo\SESSION_HANDOVER.md`
> first — it has the layout, the exact build/emulator commands, the environment traps, and the
> prioritised open-work list. Then read `TEST_REPORT.md` for what is actually verified.
>
> Start with open item 1: the yt-dlp merged-download path stalls on the emulator (the audio stream
> leaves a `.part` file). Determine whether the emulator's network is the cause by pushing a
> multi-MB file through the app's own downloader, and report what you find. Keep working through the
> open list in order. Do not push to GitHub until I say the work is final. Create a goal for this so
> you keep going between my messages.
