# BUILD_AND_RELEASE.md

Reproducible build and release instructions for the Linksi Enhanced private build.

- **Document date**: 2026-09-17
- **Applies to**: this repository (`com.linksi.app`, branch `chore/github-actions` and its descendants)
- **Baseline**: upstream Linksi 3.1.1, `versionCode` 20 — see [CHANGELOG.md](docs/CHANGELOG.md)
- **Honesty note first**: **no APK has been produced from this repository yet.**
  [TEST_REPORT.md](TEST_REPORT.md) records that as a blocker, and the same is still true today. Every
  command below is written to be runnable, but only the ones explicitly marked *verified* have been
  executed in this environment.

---

## 1. Prerequisites

| Requirement | Version | Notes |
|---|---|---|
| JDK | **17 or higher** | The build sets `sourceCompatibility`/`targetCompatibility`/`jvmTarget` to 17 (`app/build.gradle:48-55`), so a JDK 17 is the minimum. A JDK 21 also works. `settings.gradle:9` adds the `foojay-resolver-convention` plugin, which can auto-provision a toolchain JDK if one is missing. |
| Gradle | **8.13 — use the committed wrapper only** | `gradle/wrapper/gradle-wrapper.properties:3` pins `gradle-8.13-bin.zip`. Use `gradlew.bat` (Windows) / `./gradlew`. Never a system Gradle. |
| Android SDK | platforms + build-tools + platform-tools, see below | The Android Gradle Plugin cannot even be *configured* without an SDK. |
| Network | `google()`, `mavenCentral()`, `gradlePluginPortal()`, `services.gradle.org` | `settings.gradle:1-17`. |
| Disk | a few GB | Gradle caches, the SDK and the build outputs. |

### 1.1 Android SDK components

Installed with `sdkmanager` (or manually, section 7.1):

```text
platforms;android-34
platforms;android-35
platforms;android-36
build-tools;34.0.0
build-tools;35.0.0
build-tools;36.0.0
platform-tools
```

- **34** is what the repository compiles against today (`app/build.gradle:12`).
- **36** is required the moment Media3 is added (`compileSdk` must rise to 36 for Media3 ≥ 1.5.0, and
  to 36 specifically for 1.11.x) — see [DEPENDENCY_REVIEW.md](docs/DEPENDENCY_REVIEW.md) section 4.1.
  Platform 35 is included because Media3 1.5.0–1.10.x need it.
- Licences must be accepted (`sdkmanager --licenses`, or the hash files under `<sdk>/licenses/`).

### 1.2 Pointing Gradle at the SDK

Either set an environment variable:

```powershell
$env:ANDROID_HOME = 'E:\path\to\android-sdk'
```

or create `local.properties` in the repository root (gitignored by `.gitignore:17`):

```properties
sdk.dir=E:\\path\\to\\android-sdk
```

### 1.3 The environment that actually works on this machine *(verified)*

| Item | Value |
|---|---|
| JDK | JetBrains Runtime **21.0.4** (a full JDK, `javac` present) at `C:\Program Files\JetBrains\PyCharm Community Edition 2024.2.4\jbr` |
| Android SDK | `<repo-parent>\toolchain\android-sdk`, containing `platforms/android-34`, `android-35`, `android-36`, `build-tools/34.0.0`, `35.0.0`, `36.0.0`, `platform-tools`, `cmdline-tools`, `licenses` |
| Gradle user home | `<repo-parent>\local\.gradle-home` (must be inside the workspace — see section 7.2) |
| Android SDK path variable | `ANDROID_HOME=<repo-parent>\toolchain\android-sdk` (or `local.properties`; neither is committed) |

A usable shell preamble for this machine:

```powershell
$env:JAVA_HOME         = 'C:\Program Files\JetBrains\PyCharm Community Edition 2024.2.4\jbr'
$env:ANDROID_HOME      = '<repo-parent>\toolchain\android-sdk'
$env:GRADLE_USER_HOME  = '<repo-parent>\local\.gradle-home'
Set-Location <repo-parent>\repo
```

---

## 2. Build commands (Windows)

Run from the repository root. `gradlew.bat` is the wrapper committed in the repository.

```powershell
# 1. clean — remove previous outputs
.\gradlew.bat clean

# 2. unit tests (JVM, no device needed)
.\gradlew.bat test

# 3. Android lint
.\gradlew.bat lint

# 4. debug APK (installs side by side with a release build)
.\gradlew.bat assembleDebug

# 5. release APK (signed only if the signing environment variables are set, section 3)
.\gradlew.bat assembleRelease
```

Useful variants:

```powershell
.\gradlew.bat :app:testDebugUnitTest        # only the debug unit-test variant
.\gradlew.bat :app:test --tests "com.linksi.app.utils.*"
.\gradlew.bat assembleDebug --console=plain
.\gradlew.bat assembleRelease -x lint       # skip lint for a fast local release build
```

### 2.1 Where the APKs land

| Build | Path |
|---|---|
| Debug | `app\build\outputs\apk\debug\app-debug.apk` |
| Release | `app\build\outputs\apk\release\app-release.apk` |
| Unit-test XML report | `app\build\test-results\testDebugUnitTest\*.xml` (and `testReleaseUnitTest\`) |
| Unit-test HTML report | `app\build\reports\tests\testDebugUnitTest\index.html` |
| Lint report | `app\build\reports\lint-results-debug.html` (and `lint-results-debug.xml`) |

The debug APK's package is **`com.linksi.app.debug`** because `debug` carries
`applicationIdSuffix ".debug"` (`app/build.gradle:43`), so it installs alongside a release build
instead of replacing it.

### 2.2 ABI splits are not configured

`app/build.gradle` defines no `splits { abi { … } }` block, so a single **universal** APK is
produced. `LinksiEnhanced_<version>_arm64.apk` (section 5) therefore has to be produced by adding
ABI splits, not by renaming the universal artifact. This matters as soon as a dependency with native
code is added: the MIT yt-dlp wrapper is `arm64-v8a` + `x86_64` only and is ~60–80 MB, and
`dev.ffmpegkit-maintained:ffmpeg-kit-full` is `arm64-v8a` only.

---

## 3. Signing a release build

`app/build.gradle:27-34` reads the release signing configuration from **environment variables**:

| Variable | Meaning | Default if unset |
|---|---|---|
| `KEYSTORE_PATH` | Path to the `.jks` keystore | `../keystroke.jks` (a typo, and the file does not exist) |
| `KEYSTORE_PASSWORD` | Store password | `""` (empty) |
| `KEY_ALIAS` | Key alias inside the keystore | `""` (empty) |
| `KEY_PASSWORD` | Key password | `""` (empty) |

The `release` build type always references that signing config (`app/build.gradle:38-41`), and
`minifyEnabled true` with `proguard-rules.pro` is applied.

### 3.0 Debug builds need `DEBUG_KEYSTORE_PATH` in a sandbox

`assembleDebug` fails with `AccessDeniedException: %USERPROFILE%\.android\debug.keystore.lock`
whenever the session file sandbox denies writes to the real user home — the keystore itself is
readable, but AGP needs to create a sibling `.lock` file. `app/build.gradle` supports an escape
hatch for exactly this; point debug signing at the keystore this workspace already owns:

```powershell
$env:DEBUG_KEYSTORE_PATH     = '<repo-parent>\keys\debug.keystore'
$env:DEBUG_KEYSTORE_PASSWORD = 'android'
$env:DEBUG_KEY_ALIAS         = 'androiddebugkey'
$env:DEBUG_KEY_PASSWORD      = 'android'
```

The alias is `androiddebugkey` (verified with `keytool -list`). Keeping debug signing on this fixed
keystore is what lets a debug APK install **over** an existing one as an in-place upgrade; a debug
build signed by a different key is rejected by Android with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`,
and the only way out is uninstalling, which destroys the user's database.

### 3.0.1 `assembleDebug` can silently leave a stale APK — always check the build clock

Gradle marks `assembleDebug` **UP-TO-DATE** when only the version metadata changed, so an APK from
an earlier commit can survive on disk and be installed as if it were current. This actually happened:
a 14:53 debug APK carrying `versionCode 23` was installed at 19:24, *after* the 18:51 merge, and the
device dutifully reported `versionName=3.1.1-enhanced.3` — the pre-merge build, dressed up as the
deliverable. Two habits prevent a repeat:

1. Before installing, assert the APK's identity rather than trusting its path:
   `aapt2 dump badging <apk> | Select-String '^package:'` must show the `versionCode`/`versionName`
   you expect.
2. Compare the APK's `LastWriteTime` against the commit you think it contains
   (`git log -1 --format=%ci`). An APK older than its source is not that source.

The cheap way to tell what is really inside an APK without installing it is to scan the dex for a
symbol you know is new — see §7.5.

### 3.1 Generating a private keystore (first time only)

Run once, store the file **outside the repository tree** and back it up:

```powershell
keytool -genkeypair -v `
  -keystore <repo-parent>\keys\linksi-enhanced-release.jks `
  -alias linski-enhanced `
  -keyalg RSA -keysize 4096 -validity 10000 `
  -storetype JKS
```

Rules that must survive every future release:

1. **Never lose this keystore and never change its identity.** Android only accepts an in-place
   update signed by the same certificate.
2. **Never commit it.** `.gitignore:20-21` excludes `*.jks` and `*.keystore`.
3. Record the certificate fingerprint once and compare it on every release (section 5).

> A keystore exists in this environment at `<repo-parent>\keys\linksi-enhanced-release.jks` with
> credentials recorded in `<repo-parent>\keys\KEYSTORE_CREDENTIALS.txt` (alias `linksi-enhanced`).
> Those files are **outside this git worktree and must never be committed**. Treat the password as
> a secret; it is deliberately not reproduced in this document.

### 3.2 Building the signed release *(verified command shape)*

```powershell
$env:KEYSTORE_PATH     = '<repo-parent>\keys\linksi-enhanced-release.jks'
$env:KEYSTORE_PASSWORD = '<store password>'
$env:KEY_ALIAS         = 'linksi-enhanced'
$env:KEY_PASSWORD      = '<key password>'

.\gradlew.bat clean
.\gradlew.bat assembleRelease
```

A signed APK still lives at `app\build\outputs\apk\release\app-release.apk`.

### 3.3 Building when the signing key is unavailable

The release build type has no `signingConfig` fallback, so `assembleRelease` with unusable signing
variables may fail configuration/validation rather than silently produce an unsigned APK. Three
workable routes, in order of least damage:

1. **Build debug instead.** `.\gradlew.bat assembleDebug` produces an installable, debug-signed APK
   (`com.linksi.app.debug`). This is the correct artifact for side-by-side testing and it never
   touches the release signing config. *(This is the recommended route for a local build without a
   keystore.)*
2. **Use the Android Studio debug keystore** as a throwaway release signer:

   ```powershell
   $env:KEYSTORE_PATH     = "$env:USERPROFILE\.android\debug.keystore"
   $env:KEYSTORE_PASSWORD = 'android'
   $env:KEY_ALIAS         = 'androiddebugkey'
   $env:KEY_PASSWORD      = 'android'
   .\gradlew.bat assembleRelease
   ```

   The result is installable but **not distributable** and cannot update a release signed with the
   private key.
3. **Temporarily drop the signing config**: comment out the `signingConfig signingConfigs.release`
   line in `app/build.gradle:40`. This yields an **unsigned** `app-release-unsigned.apk`. It edits a
   build file, so it must never be committed — and the workflow in
   `.github/workflows/android-build.yml` performs the equivalent step on CI *without* modifying the
   checked-in build file (it writes a temporary Gradle init script that clears the signing config).

---

## 4. Generating a SHA256 for an APK

```powershell
Get-FileHash .\app\build\outputs\apk\release\app-release.apk -Algorithm SHA256 |
    Format-List Algorithm, Hash, Path

# hash + size + versionCode + commit in one record, for the release notes / TEST_REPORT
$apk = '.\app\build\outputs\apk\release\app-release.apk'
$hash = Get-FileHash $apk -Algorithm SHA256
[pscustomobject]@{
    File        = (Split-Path $apk -Leaf)
    SHA256      = $hash.Hash
    Bytes       = (Get-Item $apk).Length
    Commit      = (git rev-parse HEAD)
    VersionCode = 21          # must match app/build.gradle
    ABI         = 'universal' # no splits are configured
} | Format-List
```

Also record the signing certificate fingerprint, which is what an in-place update depends on:

```powershell
& "$env:JAVA_HOME\bin\keytool.exe" -list -v `
  -keystore <repo-parent>\keys\linksi-enhanced-release.jks -alias linski-enhanced |
  Select-String 'SHA256:'
```

---

## 5. Versioning and naming rules

| Rule | Detail |
|---|---|
| **Never decrease `versionCode`** | Android rejects an in-place update whose `versionCode` is not strictly greater than the installed one. The baseline is **`versionCode` 20**. |
| Baseline identity | upstream 3.1.1, `versionCode` 20 (`app/build.gradle:18-19`) |
| First enhanced release | must be `versionCode` **≥ 21**; `versionName` should communicate the private line (for example `3.2.0-enhanced.1`) |
| `applicationId` | **must stay `com.linksi.app`** for any build intended to update an existing install (`app/build.gradle:15`) |
| Signing certificate | **must be identical** to the certificate of the installed build |
| Database version | the Room schema is at **version 12**. Any migration must be additive and tested — never destructive (see [CHANGELOG.md](docs/CHANGELOG.md) and `CODE_REVIEW.md` §2.11) |

### 5.1 APK naming convention

```text
LinksiEnhanced_<versionName>_universal.apk     # e.g. LinksiEnhanced_3.2.0-enhanced.1_universal.apk
LinksiEnhanced_<versionName>_arm64.apk         # only once ABI splits are configured
```

`gradlew` itself produces `app-release.apk`; rename on copy, and hash the renamed file so the
recorded SHA256 matches the distributed filename:

```powershell
$version = '3.2.0-enhanced.1'
$out = "<repo-parent>\artifacts\releases\LinksiEnhanced_${version}_universal.apk"
New-Item -ItemType Directory -Force -Path (Split-Path $out) | Out-Null
Copy-Item .\app\build\outputs\apk\release\app-release.apk $out -Force
Get-FileHash $out -Algorithm SHA256
```

### 5.2 In-place update requirements

An APK can update an existing installation only when **all three** of these hold:

1. the same `applicationId` (`com.linksi.app`);
2. the same signing certificate;
3. a strictly higher `versionCode`.

If any one fails, Android reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (signature/versionCode) or
installs a second app (different `applicationId`).

### 5.3 What to do when the original upstream signing key is unavailable

The original Linksi release key is **not available** to this project — see `CODE_REVIEW.md` §2.25
and `LICENSE_REVIEW.md`. An in-place update *of the official upstream Linksi install* is therefore
**not possible**, and no amount of rebuilding changes that. Two honest options:

**Option A — side-by-side install (recommended, and already supported).** Build the debug variant,
which already carries `applicationIdSuffix ".debug"` (`app/build.gradle:43`):

```powershell
.\gradlew.bat assembleDebug
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

Both apps coexist: the original stays installed and untouched, and the enhanced build lives beside
it (with its own Room database and its own DataStore, because both are per-package). This is the
safe path while the licence question is open.

**Option B — export / backup / verify / uninstall / install / import.** Use this only when the
intent is to *replace* the upstream install on the same device, accepting that the two apps cannot
share data automatically:

1. **Export** from the old app: *Settings → Import/Export → Export*, and save the JSON/CSV/HTML file
   via the Storage Access Framework to a location outside app-private storage.
2. **Backup** the exported file somewhere durable, and verify it opens and contains the expected
   link count (`CODE_REVIEW.md` notes export does not escape HTML and that import does not validate
   URLs — inspect before trusting it).
3. **Verify** the replacement APK: `Get-FileHash` (section 4), confirm `versionCode`/`versionName`,
   and confirm the signing certificate is the one you intend to keep.
4. **Uninstall** the old app. This deletes its Room database, its DataStore (including the PIN and
   AI keys) and its Coil cache. There is no undo.
5. **Install** the new APK (`adb install .\app\build\outputs\apk\release\app-release.apk`).
6. **Import** the exported file through the new app's *Settings → Import/Export → Import*, then
   check the counts and spot-check links.

Steps 4-6 destroy and rebuild local state; they cannot migrate the plaintext PIN or the AI API
keys, and import restores links/folders only to the extent the export format carries them.

---

## 6. Continuous integration

`.github/workflows/android-build.yml` runs checkout → JDK 17 (temurin) → Gradle (with caching) →
`chmod +x gradlew` → `test` → `lint` → `assembleDebug` → signed or unsigned `assembleRelease` →
upload the APK and the test/lint reports. It triggers on `workflow_dispatch` and on pushes to
`main`, `master` and `release/**`.

The pre-existing `.github/workflows/android.yml` is the **upstream** workflow and is deliberately
left untouched.

---

## 7. Known environment gotchas

These are real problems that cost time on this machine. They are recorded so the next agent does not
rediscover them.

### 7.1 `sdkmanager` cannot download anything here — the SDK was installed by hand

`cmdline-tools`' bundled HTTP stack **cannot reach `dl.google.com`** in this sandbox, and the
Windows schannel credential path used by `curl` / `Invoke-WebRequest` fails with
`SEC_E_NO_CREDENTIALS`. The SDK was therefore populated manually by
`<repo-parent>\scripts\setup-android-sdk.ps1` and `<repo-parent>\scripts\install-sdk-packages.ps1`, which:

1. download Google's repository manifests (`repository2-3.xml`, `repository2-1.xml`) and the
   command-line tools with a small **JDK `HttpClient`** helper (`<repo-parent>\local\.probe\Download.java`),
   which does work;
2. resolve each required package (`platforms;android-34/35/36`,
   `build-tools;34.0.0/35.0.0/36.0.0`, `platform-tools`) out of those manifests and download its
   archive directly;
3. unzip it into `<repo-parent>\toolchain\android-sdk\<component>`;
4. write the standard licence-hash files into `<repo-parent>\toolchain\android-sdk\licenses\`
   (`android-sdk-license`, `android-sdk-preview-license`, `android-googletv-license`) so AGP accepts
   the SDK without an interactive `--licenses` run.

Consequences to remember: `sdkmanager --list` may report failures or hang; `sdkmanager` cannot be
used to add a component that is not in the manual installer's list; and any new SDK component has to
be added the same way (or the machine's network path has to be fixed first).

### 7.2 Gradle needs `GRADLE_USER_HOME` inside the workspace

The session file sandbox denies writes outside the workspace, so Gradle's default user home
(`%USERPROFILE%\.gradle`) is not writable. Always set:

```powershell
$env:GRADLE_USER_HOME = '<repo-parent>\local\.gradle-home'
```

The repositories in this workspace already have populated Gradle homes: `<repo-parent>\local\.gradle-home`,
`.gradle-home-main` and `.gradle-verify-home`. Without `GRADLE_USER_HOME` set, Gradle fails while
creating its caches/daemon directories rather than during the build, which is misleading.

Related, smaller environment facts:

- The daemon needs a writable `java.io.tmpdir`; the URL-test harness sets
  `GRADLE_OPTS=-Djava.io.tmpdir=<workspace>\.tmp` for exactly this reason
  (`tools/run-urlcleaner-tests.ps1:122`).
- `--no-watch-fs` avoids Gradle's native file watcher, which some sandboxes deny
  (`tools/run-urlcleaner-tests.ps1:128-131`).
- A missing `local.properties` is normal here (it is gitignored); use `ANDROID_HOME` instead or
  create the file locally.

### 7.3 The URL-logic tests do not need the Android SDK

`tools/run-urlcleaner-tests.ps1` compiles and runs the *verbatim* `UrlCleaner`/`UrlNormalizer`
sources and their tests in a throwaway Gradle **JVM** project, because those files have no
`android.*` imports. It reports `junit tests=82 failures=0 errors=0` on success and exits non-zero if
no test ran ([TEST_REPORT.md](TEST_REPORT.md) §2). Use it when only the URL logic changed; it is not a
substitute for `gradlew :app:test`, which compiles the whole module.

### 7.4 Other build-configuration facts worth knowing

- `settings.gradle:12` sets `FAIL_ON_PROJECT_REPOS`; all repositories must stay declared centrally in
  `settings.gradle`. A dependency whose artifact is only on JitPack cannot be resolved without an
  explicit `settings.gradle` change.
- `gradle.properties:1` caps the daemon at `-Xmx2048m`. A large native payload or a big lint run may
  need more; raise it deliberately.
- `app/build.gradle` now sets `compileSdk 36` / `targetSdk 36` / `minSdk 26`, so the Android 16
  target is **done** (the fork deliberately stays on 36 where upstream moved to 34).
- Native-library packaging (`android:extractNativeLibs`, or `packaging { jniLibs { useLegacyPackaging
  = true } }`) becomes a real decision as soon as a dependency with native code is added — mandatory
  for the GPL `youtubedl-android` route, and a compliance question for any FFmpeg option. See
  [DEPENDENCY_REVIEW.md](docs/DEPENDENCY_REVIEW.md) §3.

### 7.5 Reading an APK's real contents without installing it

To prove a class or string actually shipped — the fastest way to catch a stale artifact (§3.0.1) —
open the APK as a zip and search the `classes*.dex` payloads as raw bytes. A DEX stores string
constants in plain ASCII, so a substring search needs no disassembler:

```powershell
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path 'app\build\outputs\apk\debug\app-arm64-v8a-debug.apk').Path)
foreach ($e in $zip.Entries | Where-Object { $_.Name -like 'classes*.dex' }) {
  $ms = New-Object System.IO.MemoryStream; $e.Open().CopyTo($ms)
  $txt = [System.Text.Encoding]::ASCII.GetString($ms.ToArray())
  if ($txt.Contains('EnhancedLinkActions')) { "PRESENT in $($e.Name)" }
  $ms.Dispose()
}
$zip.Dispose()
```

Do **not** try to answer this with `dumpsys package`: it lists manifest components (activities,
services, providers) and not general classes, so a `grep` for an ordinary class name returns nothing
even when the class is present. That false negative is what made an earlier round misjudge which
build was installed.
