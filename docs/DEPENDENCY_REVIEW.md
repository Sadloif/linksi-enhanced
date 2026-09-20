# DEPENDENCY_REVIEW.md

Dependency review for the Linksi Enhanced private build (specification section 54).

- **Review date**: 2026-09-17
- **Reviewed revision**: `8e7be16` (branch `chore/github-actions`). Its `app/build.gradle` is the
  same dependency set as the reviewed baseline commit `0f4af65` (`git log --stat` shows the
  intervening private commits touched `DataStoreExtensions.kt`, `HomeViewModel.kt`,
  `SettingsScreen.kt`, `SettingsViewModel.kt`, `ThemeSettingsScreen.kt`, `strings.xml`,
  `MetadataFetcher.kt`, the new `UrlCleaner.kt`/`UrlNormalizer.kt` and tests — **not
  `app/build.gradle`**)
- **Baseline app**: `com.linksi.app`, `versionCode` 20, `versionName` 3.1.1, `minSdk` 26,
  `compileSdk`/`targetSdk` 34
- **Primary source of truth for the media/download candidates**:
  `DEPENDENCY_OPTIONS.md` (1036 lines, 2026-09-17). Everything in
  section 3 below is a *summary* of that document, not a replacement for it; where this file
  compresses, the research file carries the evidence and the confidence markers.
- **Method**: the versions and coordinates in section 1 were read out of `app/build.gradle`;
  the licence column is the licence each artifact *publishes*, cross-checked against
  [LICENSE_REVIEW.md](LICENSE_REVIEW.md) section 5. **A resolved-dependency licence scan has not
  been run** (see section 6).
- **Status**: **no dependency has been added to the build yet.** This document is a decision record,
  not a description of what is currently shipping.

> Section 1 covers what is already in the build. Section 3 covers what the media/download work is
> allowed to add. Sections 5 and 6 record the forbidden list and the verification gaps.

---

## 1. Existing dependencies (already in `app/build.gradle`)

All version strings below are quoted from `app/build.gradle:68-133`. Nothing is dynamic: there is
**no `latest` and no `+`** anywhere in the file, and the project does **not** use a version catalog
(`gradle/libs.versions.toml` does not exist). This is the property the specification requires and it
must be preserved by every new dependency.

### 1.1 Shipped in the APK

| Name | Version | Repository | Licence | Purpose | Native code | Supported ABI | APK size impact | Android compatibility | Maintenance | Reason selected | Alternative considered |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `androidx.core:core-ktx` | 1.12.0 | Google Maven | Apache-2.0 | Kotlin extensions over `androidx.core`; `ServiceCompat`, notification helpers | None | n/a | small (< 200 KB) | minSdk 26 fine | AndroidX, active | Baseline dependency; unchanged | none needed |
| `androidx.core:core-splashscreen` | 1.0.1 | Google Maven | Apache-2.0 | Splash-screen gating in `MainActivity` | None | n/a | small | API 23+ backport | AndroidX, stable/frozen at 1.0.1 | Baseline dependency; unchanged | none needed |
| `androidx.lifecycle:lifecycle-runtime-ktx` | 2.7.0 | Google Maven | Apache-2.0 | Lifecycle scopes, `repeatOnLifecycle` | None | n/a | small | minSdk 26 fine | AndroidX, active | Baseline dependency; unchanged | none needed |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | 2.7.0 | Google Maven | Apache-2.0 | `viewModel()` in Compose | None | n/a | small | minSdk 26 fine | AndroidX, active | Baseline dependency; unchanged | none needed |
| `androidx.lifecycle:lifecycle-runtime-compose` | 2.7.0 | Google Maven | Apache-2.0 | `collectAsStateWithLifecycle` | None | n/a | small | minSdk 26 fine | AndroidX, active | Baseline dependency; unchanged | none needed |
| `androidx.activity:activity-compose` | 1.8.2 | Google Maven | Apache-2.0 | `ComponentActivity` + `setContent`; `enableEdgeToEdge()` | None | n/a | small | minSdk 26 fine | AndroidX, active | Baseline dependency; unchanged | none needed |
| `androidx.compose:compose-bom` | 2024.10.00 | Google Maven | Apache-2.0 (BOM metadata) | Pins every `androidx.compose.*` artifact below | None | n/a | none (BOM) | n/a | AndroidX, active | Baseline; the only *dynamic-by-design* coordinate, but the BOM itself is a pinned version — allowed | per-artifact explicit versions (more churn) |
| `androidx.compose.ui:ui`, `ui-graphics`, `ui-tooling-preview` | via BOM 2024.10.00 | Google Maven | Apache-2.0 | Compose UI runtime and graphics | None | n/a | a few MB | Compose requires minSdk 21+ | AndroidX, active | Baseline dependency; unchanged | — |
| `androidx.compose.material3:material3` | via BOM 2024.10.00 | Google Maven | Apache-2.0 | Material 3 components (the whole UI) | None | n/a | a few MB | minSdk 21+ | AndroidX, active | Baseline dependency; unchanged | Material 2 (rejected: M3 is the app's design language) |
| `androidx.compose.material3:material3-adaptive-navigation-suite` | via BOM 2024.10.00 | Google Maven | Apache-2.0 | Adaptive navigation scaffolding | None | n/a | small | minSdk 21+ | AndroidX, active | Baseline dependency; unchanged | — |
| `androidx.compose.material:material-icons-extended` | via BOM 2024.10.00 | Google Maven | Apache-2.0 | Extended icon set | None | n/a | **large (several MB if not shrunk)**; R8 removes unused icons in release | minSdk 21+ | AndroidX, active | Baseline dependency; unchanged | per-icon vectors (smaller, more work) |
| `androidx.compose.animation:animation` | via BOM 2024.10.00 | Google Maven | Apache-2.0 | Overlay animations used by every "screen" transition | None | n/a | small | minSdk 21+ | AndroidX, active | Baseline dependency; unchanged | — |
| `androidx.graphics:graphics-shapes` | 1.0.1 | Google Maven | Apache-2.0 | Expressive shape morphing | None | n/a | small | minSdk 26 fine | AndroidX, stable | Baseline dependency; unchanged | — |
| `com.google.android.material:material` | 1.12.0 | Google Maven | Apache-2.0 | XML themes (`themes.xml`) and the splash/lock themes | None | n/a | small | minSdk 26 fine | Google, active | Baseline dependency; unchanged | pure-Compose theming (would require theme rewrites) |
| `androidx.navigation:navigation-compose` | 2.7.7 | Google Maven | Apache-2.0 | **Declared but unused** — `CODE_REVIEW.md` §2.4 records zero `NavHost` matches; the app hand-rolls navigation | None | n/a | small | minSdk 26 fine | AndroidX, active | Present in the baseline; kept to avoid churn | removing it (rejected for now: out of scope for the docs change) |
| `androidx.hilt:hilt-navigation-compose` | 1.2.0 | Google Maven | Apache-2.0 | **Declared but unused** (no `NavHost`) | None | n/a | small | minSdk 26 fine | Google, active | As above | as above |
| `androidx.room:room-runtime` + `room-ktx` | 2.6.1 | Google Maven | Apache-2.0 | The single Room database (`linksi_db`, schema version 12) | None | n/a | ~0.5–1 MB | minSdk 26 fine | AndroidX, active | Baseline; the data layer depends on it | SQLDelight (rejected: full rewrite) |
| `androidx.room:room-compiler` (KSP) | 2.6.1 | Google Maven | Apache-2.0 | KSP code generation, build-time only | None | n/a | none in APK | — | AndroidX, active | Baseline | KAPT (slower) |
| `com.android.volley:volley` | 1.2.1 | Google Maven | Apache-2.0 | **Declared; only a dead import remains** (`LinkCards.kt:50`) | None | n/a | small | minSdk 26 fine | Legacy Archive | Present in the baseline | **Removal is the correct end state**; out of scope for this change |
| `com.google.dagger:hilt-android` | 2.52 | Google Maven | Apache-2.0 | DI container — the app is Hilt throughout | None | n/a | ~1 MB | minSdk 26 fine | Google, active | Baseline; `di/AppModule.kt` and entry points depend on it | manual DI / Koin (rejected: rewrite) |
| `com.google.dagger:hilt-android-compiler` (KSP) | 2.52 | Google Maven | Apache-2.0 | Build-time annotation processing | None | n/a | none in APK | — | Google, active | Baseline | — |
| `io.coil-kt:coil-compose` | 2.6.0 | Maven Central | Apache-2.0 | Favicon/thumbnail loading with a 150 MB disk cache | None | n/a | ~1 MB | minSdk 26 fine | Coil 2.x is mature; 3.x exists but is a different API | Baseline | Coil 3 (rejected: migration risk, no benefit here) |
| `androidx.datastore:datastore-preferences` | 1.0.0 | Google Maven | Apache-2.0 | The single unencrypted preferences store (`linksi_settings`) | None | n/a | small | minSdk 26 fine | AndroidX, active | Baseline; every setting, including the new `auto_clean_urls` key, lives here | `EncryptedSharedPreferences` (would change the storage format) |
| `androidx.biometric:biometric` | 1.1.0 | Google Maven | Apache-2.0 | `BiometricPrompt` for app/folder lock | None | n/a | small | minSdk 26 fine | AndroidX, stable | Baseline | — |
| `org.jsoup:jsoup` | 1.17.2 | Maven Central | **MIT** | HTML parsing for link metadata | None | n/a | ~0.4 MB | Java 8+; fine on API 26 | Very active | Baseline; `MetadataFetcher` depends on it | regex/`WebView` only (rejected: brittle) |
| `com.squareup.okhttp3:okhttp` | 4.12.0 | Maven Central | Apache-2.0 | **Declared and imported but never instantiated** (`LinksApplication.kt:13`) | None | n/a | ~0.8 MB | minSdk 26 fine | Square, active | Present in the baseline; **also the version `media3-datasource-okhttp` pins** (section 3) | removal (out of scope); keeping it is the safer local choice once Media3 arrives |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.7.3 | Maven Central | Apache-2.0 | Coroutines/Flow on the main dispatcher | None | n/a | small | minSdk 26 fine | JetBrains, active | Baseline | — |
| `androidx.work:work-runtime-ktx` | **2.9.0** | Google Maven | Apache-2.0 | `BinCleanupWorker` (the only worker) | None | n/a | ~0.5 MB | minSdk 26 fine | AndroidX, active | Baseline — **but this version must move for the downloader, see section 4.2** | pinning at 2.9.0 (rejected: carries the `dataSync` ANR bug) |
| `androidx.hilt:hilt-work` | 1.2.0 | Google Maven | Apache-2.0 | `HiltWorkerFactory` injection into WorkManager | None | n/a | small | minSdk 26 fine | Google, active | Baseline | — |
| `androidx.hilt:hilt-compiler` (KSP) | 1.2.0 | Google Maven | Apache-2.0 | Build-time worker injection codegen | None | n/a | none in APK | — | Google, active | Baseline | — |
| Kotlin standard library (`org.jetbrains.kotlin:kotlin-stdlib`) | 2.0.21 (implicit, from the Kotlin plugin) | Maven Central | Apache-2.0 | Kotlin runtime | None | n/a | ~1.7 MB (R8-shrunk) | — | JetBrains, active | Implicit via `org.jetbrains.kotlin.android` 2.0.21 | — |
| `kotlin-parcelize` runtime | 2.0.21 (plugin) | Maven Central | Apache-2.0 | Generated `Parcelable` support | None | n/a | small | — | JetBrains, active | Baseline plugin (`app/build.gradle:7`) | — |

### 1.2 Test-only (never shipped in the APK)

| Name | Version | Repository | Licence | Purpose | Shipped? |
|---|---|---|---|---|---|
| `junit:junit` | 4.13.2 | Maven Central | **EPL-1.0** | The unit-test framework; the 82 URL tests and the enhanced-module tests use it | **No** — `testImplementation` only |
| `androidx.test.ext:junit` | 1.1.5 | Google Maven | Apache-2.0 | Instrumentation runner base | **No** — `androidTestImplementation` |
| `androidx.test.espresso:espresso-core` | 3.5.1 | Google Maven | Apache-2.0 | UI instrumentation | **No** — `androidTestImplementation` |
| `androidx.compose.ui:ui-test-junit4` | via BOM 2024.10.00 | Google Maven | Apache-2.0 | Compose UI tests | **No** |
| `androidx.compose.ui:ui-tooling`, `ui-test-manifest` | via BOM 2024.10.00 | Google Maven | Apache-2.0 | Previews and test manifest | **No** — `debugImplementation` (debug builds only) |

### 1.3 Build tooling (not app code, but licence-bearing)

| Name | Version | Repository | Licence | Notes |
|---|---|---|---|---|
| Android Gradle Plugin (`com.android.application` / `com.android.library`) | 8.13.2 | Google Maven | Apache-2.0 | `build.gradle:3-4`; build-time only |
| Kotlin Android plugin + Compose compiler plugin | 2.0.21 | Maven Central / Gradle Plugin Portal | Apache-2.0 | `build.gradle:5-6` |
| KSP (`com.google.devtools.ksp`) | 2.0.21-1.0.25 | Maven Central | Apache-2.0 | `build.gradle:7` |
| Hilt Gradle plugin | 2.52 | Maven Central | Apache-2.0 | `build.gradle:8` |
| `org.gradle.toolchains.foojay-resolver-convention` | 0.10.0 | Gradle Plugin Portal | Apache-2.0 | `settings.gradle:9`; can auto-provision a JDK |
| Gradle wrapper (`gradle-wrapper.jar`, `gradlew`, `gradlew.bat`) | 8.13 | services.gradle.org distribution | Apache-2.0 | Keep the existing headers (`LICENSE_REVIEW.md` §6.1) |

**Repository configuration note.** `settings.gradle:11-17` sets
`repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)` with exactly two repositories,
`google()` and `mavenCentral()`. A new dependency whose artifact is only on JitPack or only in a
vendor repository **cannot be resolved without changing `settings.gradle`**, which is a deliberate
decision, not a side effect. `io.github.junkfood02.youtubedl-android` is on Maven Central whose
JitPack coordinates are dead for every version ≥ 0.15.0 (`DEPENDENCY_OPTIONS.md` §3); Media3 is on
**Google Maven, not Maven Central** (`DEPENDENCY_OPTIONS.md` §7).

---

## 2. The licence conclusion (summarised from the research)

**Every Android in-app extractor library in this space is GPL-3.0, but yt-dlp itself is not.** That
is the single finding that drives the whole dependency strategy
(`DEPENDENCY_OPTIONS.md` §1).

| Component | Licence | Copyleft? |
|---|---|---|
| `youtubedl-android` (library, all versions) | **GPL-3.0** | **Yes — viral on the APK** |
| `NewPipeExtractor` | **GPL-3.0-or-later** | **Yes — viral on the APK** |
| FFmpeg as bundled by `youtubedl-android` (Termux build) | **GPL-3.0** | **Yes — viral on the APK** |
| Seal (app) / YTDLnis (app) | **GPL-3.0** | Yes — reference reading only, no code copying |
| **yt-dlp itself** | **Unlicense (public domain)** | **No** |
| CPython (the interpreter yt-dlp needs) | PSF-2.0 | No |
| Chaquopy (in-process CPython for Android) | MIT | No |
| **AndroidX Media3 / ExoPlayer** | **Apache-2.0** | No |
| **`dev.ffmpegkit-maintained:yt-dlp-android`** | **MIT** | **No** |
| `dev.ffmpegkit-maintained:ffmpeg-kit-full` | LGPL-3.0 | Weak, library-level — conditional, see §9a of the research |
| `junit:junit` | EPL-1.0 | No (test-only, not distributed) |

**What GPL-3.0 would actually cost.** Linking a GPL-3.0 library makes the APK a combined work: the
entire app would have to be licensed GPL-3.0, its Corresponding Source conveyed to every recipient,
Installation Information provided, and no further restrictions imposed
(`DEPENDENCY_OPTIONS.md` §2). The `MediaExtractor` abstraction is
good engineering but **does not launder the licence** — the abstraction makes a GPL component
*replaceable later*, it does not make shipping it now compliant.

**The strategic consequence**: yt-dlp is the only permissive engine that covers all five required
services (Instagram, Facebook, TikTok, Pinterest, Reddit — extractor modules verified present in the
yt-dlp source tree), and the only thing standing between the project and permissive compliance is
the *wrapper*, which is ~200 lines of Kotlin. Licence-clean paths reduce to three: the MIT wrapper,
a self-built thin wrapper over yt-dlp + Chaquopy, or server-side yt-dlp.

**Versions must be pinned, never `latest` or `+`.** The existing `app/build.gradle` complies (section
1.1) and every new coordinate must too. The Compose BOM is the one sanctioned indirection: it is
itself pinned to `2024.10.00`, and it exists precisely so the individual Compose artifacts are not
floating.

---

## 3. Candidate dependencies for the media/download work

Coordinates, versions and licences are as recorded in
`DEPENDENCY_OPTIONS.md` §§3-13. **None of these is in the build
yet.**

### 3.1 Recommended — Media3 / ExoPlayer (Apache-2.0)

| Field | Value |
|---|---|
| Name | AndroidX Media3 / ExoPlayer |
| Version | **1.11.1** (released 2026-09-11 on Google Maven) for the modules below |
| Repository | **Google Maven only** (`google()`), *not* Maven Central |
| Licence | **Apache-2.0** |
| Purpose | Direct-media playback/preview; progressive/HLS/DASH consumption; **MP4 muxing/transformation so FFmpeg is never needed** |
| Native code | **None** in the standard artifacts |
| Supported ABI | n/a (no `.so`) — no ABI splits, no 16 KB alignment risk from Media3 itself |
| APK size impact | ~1–3 MB (estimate in the research, marked `[U]` — not measured here) |
| Android compatibility | `minSdk` 23 as of 1.9.0+ (fine at 26). **Requires `compileSdk` 36 at 1.11.x** — see section 4.1 |
| Maintenance | Google-maintained, very active |
| Modules | `media3-exoplayer`, `media3-exoplayer-hls`, `media3-exoplayer-dash`, `media3-datasource-okhttp`, `media3-ui` (and/or `media3-ui-compose`), `media3-session`, `media3-transformer`, `media3-muxer`, `media3-effect`, `media3-database` — **all modules must be the same version** |
| Reason selected | Permissive, no native code, and `transformer`+`muxer` remove the single biggest licence and APK-size liability (FFmpeg) because they do the operation a downloader needs most: combining a video-only and an audio-only stream into one MP4 |
| Alternative considered | FFmpeg for muxing — rejected: LGPL-3.0 relinking obligations, or GPL-3.0 with `--enable-gpl`, plus tens of MB per ABI |
| Obligations | Ship the Apache-2.0 text and any `NOTICE`; mark modified files. Patent grant (§3) applies. No source-disclosure duty |

**What Media3 does *not* do**: it cannot resolve an Instagram permalink to a media URL. It is a
playback/demux/mux/transform stack, a **complement** to an extractor, never an alternative.

**Do not add `media3-decoder-ffmpeg`**: it is not published on Google Maven at all, must be built
with the NDK, provides audio decoding only, and does not replace `media3-transformer`.

### 3.2 Recommended (conditional) — MIT yt-dlp wrapper

| Field | Value |
|---|---|
| Name | `dev.ffmpegkit-maintained:yt-dlp-android` |
| Version | **2.0.2** (the only version on Maven Central; published 2026-07-04) |
| Repository | Maven Central (JitPack coordinates are also advertised; Central is the recommended path) |
| Licence | **MIT** (`Copyright (c) 2026 LucQuebec`) |
| Purpose | Local extraction for the five hard services via yt-dlp |
| Native code | Yes — `libpython3.13.so` (CPython 3.13 through Chaquopy) plus yt-dlp as bytecode. **Bundles no FFmpeg** |
| Supported ABI | **`arm64-v8a` and `x86_64` only — `armeabi-v7a` is unsupported by design** |
| APK size impact | **~60–80 MB** AAR; mitigate with an App Bundle + ABI splits |
| Android compatibility | API 24+; in-process CPython (no `exec`, no `extractNativeLibs` hack, no scoped-storage friction) |
| Maintenance | **New and single-version** (one release, 2026), single vendor, explicit paid Pro tier for TLS-fingerprint impersonation — treated as a business-model risk, not a licence risk |
| Reason selected | The only *permissive* yt-dlp wrapper that covers all five required services; MIT wrapper + Unlicense yt-dlp + PSF-2.0 CPython + MIT Chaquopy means no copyleft anywhere in the chain |
| Alternatives considered | `youtubedl-android` (GPL-3.0, forbidden); a self-built wrapper over yt-dlp + Chaquopy (Fallback B); server-side yt-dlp (Fallback A) |
| Conditions before adoption | (a) verify `libpython3.13.so` 16 KB alignment (`llvm-readelf -l`, every `PT_LOAD` `p_align` = `0x4000`); (b) accept vendor/immaturity risk and the paid-tier TLS gap; (c) design and **test** the `armeabi-v7a` fallback it cannot serve. A `-compat` artifact (`yt-dlp-android-compat:2.0.2`) supplies `YoutubeDL`/`YoutubeDLRequest` typealiases, which is what makes swapping wrappers cheap |

### 3.3 The engine and interpreter (bundle contents, not direct dependencies)

| Name | Version | Licence | Note |
|---|---|---|---|
| yt-dlp | as bundled in the wrapper | **Unlicense (public domain)** | The only permissive engine covering all five services. **Avoid yt-dlp's `default` pip extra — it pulls `mutagen` (GPL-2.0)** |
| CPython | 3.13 (via Chaquopy) | PSF-2.0 | Permissive |
| Chaquopy | ≥ 12.0.1 (**17.0.0** current) | MIT | Free and open source with all restrictions removed since 12.0.1; **pin ≥ 12.0.1**; 16 KB supported as of 17.0.0 |
| `certifi` CA bundle | in the wrapper | reported MPL-2.0 (`[U]`, unverified) | File-level copyleft, no viral effect, but warrants a notice entry |

### 3.4 Conditional — FFmpeg, only if genuine transcoding is ever required

| Option | Version | Licence | Native code / ABI | 16 KB | Verdict |
|---|---|---|---|---|---|
| `dev.ffmpegkit-maintained:ffmpeg-kit-full` | 8.1.7 (also 7.1.6, 6.0.3) | **LGPL-3.0** | Yes; **`arm64-v8a` only** | Enforced in CI | **Permitted but conditional**: requires deliberate LGPL-3.0 compliance (notice, library source offer, preserved ability to relink). Prefer Media3 muxing and avoid the question entirely |
| `Javernaut/ffmpeg-android-maker` | FFmpeg 8.1.2 default | MIT script; **LGPL-2.1** output unless `FFMPEG_GPL_ENABLED=true` | Yes, all four ABIs; you bundle the `.so` | **Yes** (`-Wl,-z,max-page-size=16384`) | The best *licence-controlled* build-time route if transcoding is truly needed. No Maven coordinate — build-time only |
| `io.github.jamaismagic.ffmpeg:ffmpeg-kit-lts-16kb` | 6.1.7 | LGPL-3.0 | Yes | Purpose-built for 16 KB | Alternative; ABIs/minSdk **unverified** in the research (`[U]`) |

---

## 4. Hard build facts that constrain the choice

### 4.1 Media3 ≥ 1.5.0 requires `compileSdk` 36

Media3's own build logic hard-sets `aarMetadata.minCompileSdk` from the `compileSdk` it was built
with, and AGP's `checkDebugAarMetadata` fails any consumer compiling against a lower level:

| Media3 version | Library build `compileSdk` | You must compile with |
|---|---|---|
| 1.4.1 | 34 | **34 — the last version usable as-is** |
| 1.5.0 – 1.10.1 | 35 | 35 |
| **1.11.0 / 1.11.1** | **36** | **36** |

**Recommended fix: raise `compileSdk` to 36, leave `targetSdk` at 34 and `minSdk` at 26.**
`compileSdk` is a build-time-only knob — it changes no runtime behaviour and no Play targeting.
**This fork's `app/build.gradle:12` still says `compileSdk 34`.** The 1.5.0–1.10.1 rows are
*inferred* in the research (reading the packed `aar-metadata.properties` inside each AAR was out of
scope for that pass) and are marked `[U]` there; the 1.4.1/1.11.x endpoints and the build-logic line
are primary-sourced.

### 4.2 WorkManager must move from 2.9.0 to 2.11.x

`app/build.gradle:122` pins `androidx.work:work-runtime-ktx:2.9.0`. The Android 15 `dataSync`
foreground-service timeout path (`Service.onTimeout`, then a `RemoteServiceException`/ANR if the
service does not stop itself) is fixed in **WorkManager 2.10.0**, which added
`STOP_REASON_FOREGROUND_SERVICE_TIMEOUT` and fixed "foreground workers of type 'short service' and
'data sync' timing out and causing an ANR when WorkManager didn't call `stopSelf()`". The research
recommends **2.11.x** (latest stable 2.11.2, 2026-08-12) for that fix plus 2.11 observability.

Two related corrections from the same research, recorded so they are not repeated:

- The widely repeated claim that the three-argument `ForegroundInfo(notificationId, notification,
  foregroundServiceType)` arrived in **2.9.0** is **wrong** — it is documented as **2.3.0**. The
  2.9.0 attribution could not be confirmed anywhere.
- On Android 15+ a `BOOT_COMPLETED` receiver may no longer start a `dataSync` foreground service.
  "Resume downloads on boot" must re-enqueue WorkManager work, not start a service.

---

## 5. Forbidden list (one-line reasons)

Forbidden for this project on **licence** grounds — not quality. Quoted and compressed from
`DEPENDENCY_OPTIONS.md` §12.

| Forbidden | Licence | One-line reason |
|---|---|---|
| `io.github.junkfood02.youtubedl-android:library` (all versions, incl. 0.18.1) | GPL-3.0 | Links GPL-3.0 into the APK → the whole app must become GPL-3.0 with Corresponding Source and Installation Information duties. |
| `io.github.junkfood02.youtubedl-android:ffmpeg` (all versions) | GPL-3.0 | Ships FFmpeg built from the Termux package (`TERMUX_PKG_LICENSE="GPL-3.0"`, `--enable-gpl --enable-version3`, libx264/libx265/libxvid) → same whole-app copyleft. |
| `io.github.junkfood02.youtubedl-android:aria2c` | GPL-2.0-or-later (`[U]`) | Copyleft with no benefit worth the risk. |
| `yausername/youtubedl-android` source | GPL-3.0 | Copying it into an MIT app is a GPL-3.0 violation. |
| `JunkFood02/youtubedl-android` (fork) | GPL-3.0 | Same licence, and stale (last push 2024-12-02, issues disabled); it only hosts the Maven namespace. |
| `TeamNewPipe/NewPipeExtractor` | GPL-3.0-or-later | GPL-3.0 **and** supports **0 of 5** required services (only YouTube, SoundCloud, media.ccc.de, PeerTube, Bandcamp). |
| NewPipe app code | GPL-3.0 | Copying GPL application code — exactly what the specification forbids. |
| Seal (`JunkFood02/Seal`) app code | GPL-3.0 | Read it, learn from it, do not copy it. |
| YTDLnis (`deniscerri/ytdlnis`) app code | GPL-3.0 | Same. |
| Any FFmpeg built with `--enable-gpl` | GPL-2.0+/GPL-3.0 | Whole-app copyleft; includes the Termux build and every ffmpeg-kit `-gpl` artifact. |
| `ffmpeg-kit-{min,https,full}-gpl` (any fork) | GPL-3.0 | Explicitly GPL-3.0: copyleft applies to your app if you link it. |
| FFmpeg built with `--enable-nonfree` | **Unredistributable** | FFmpeg's own `LICENSE.md`: the resulting binary may not be redistributed. |
| `com.arthenica:ffmpeg-kit-*` | n/a | Not a licence problem — **unresolvable**: archived/retired and the coordinates 404 on Maven Central. |
| `arthenica/ffmpeg-kit-next` | LGPL-3.0 / GPL-3.0 | Publishes **nothing** to Maven Central (Nix-only local builds), so no coordinate exists to consume. |
| `tanersener/mobile-ffmpeg` | LGPL-3.0 / GPL-3.0 | Dead since 2020 and no 16 KB support. |
| `android-development-tools/youtubedl-android-maintained` | GPL-3.0 | **Untrustworthy**: 0★, a byte-identical upstream copy with a factually false README and unverifiable prebuilt binaries. |
| `odrevet/yt-dlp-kivy` | **No licence file** | Default copyright applies — legally unsafe to reuse. |
| `aliasghar317/youtubedl-android` | **No licence file** | Same. |
| `yausername/youtubedl-lazy` | GPL-3.0 | Build tooling; only relevant if reused. |
| `gallery-dl` | GPL-2.0 | Copyleft — reject in-app (server-side is fine). |
| `imputnet/cobalt` | AGPL-3.0 | Not an in-app library, but AGPL §13 binds anyone who self-hosts a modified instance. |
| yt-dlp's `default` pip extra | pulls `mutagen` (GPL-2.0) | Not the library — the extra. Install yt-dlp **without** `default` so GPL-2.0 is not dragged into the APK. |
| Maven artifacts that *claim* Apache-2.0 while describing themselves as "based on youtubedl-android" | almost certainly GPL-3.0 | **Verify licences from the AAR/POM/LICENSE, never from a README badge or a POM claim alone.** |

**Explicitly not forbidden** (permissive, safe to use): yt-dlp (Unlicense), CPython (PSF-2.0),
Chaquopy (MIT, free since 12.0.1), AndroidX Media3 (Apache-2.0),
`dev.ffmpegkit-maintained:yt-dlp-android` (MIT), and the standard Apache-2.0 Android libraries the
app already uses. `dev.ffmpegkit-maintained:ffmpeg-kit-full` (LGPL-3.0) is **permitted but
conditional**, not forbidden.

---

## 6. Recommended architecture (the dependency decision, in one shape)

Summarised from `DEPENDENCY_OPTIONS.md` §10:

```
app (MIT)
├── MediaExtractor                       ← app-owned interface, MIT. The replaceable seam.
│   ├── DirectMediaExtractor             ← OkHttp + Media3 (Apache-2.0); no native code
│   ├── YtDlpExtractor                   ← dev.ffmpegkit-maintained:yt-dlp-android (MIT), gated by ABI
│   └── RemoteExtractor                  ← own backend running yt-dlp (optional; ~zero in-app exposure)
├── MuxerStage                           ← androidx.media3:media3-muxer / media3-transformer (Apache-2.0)
└── ExternalHandoffFallback              ← share sheet / clipboard / open-in-browser / cookies
```

- **Tier 1** — always available, permissive, no native code: direct-media URLs (`.mp4`, `.webm`,
  `.m3u8`, `.mpd`) plus OpenGraph/oEmbed/JSON-LD/`<video>` parsing, plus permissive site endpoints
  (Reddit public JSON, Pinterest oEmbed).
- **Tier 2** — local extraction for the five hard services, behind a capability check
  (`Build.SUPPORTED_ABIS` must contain `arm64-v8a` or `x86_64`, and init must succeed inside a
  `try/catch`).
- **Tier 3** — graceful fallback, which is **load-bearing** here rather than hypothetical: the
  chosen MIT wrapper drops `armeabi-v7a` by design. The server-side resolver is the only fallback
  that actually delivers the five hard services on a 32-bit device.
- A `FeatureFlags` kill switch must be able to disable Tier 2 remotely, so a broken extractor
  degrades instead of bricking and no store release is needed for every TikTok change.

**Interface contract now on disk**: the enhanced module work (a separate branch,
`feature/enhanced-modules`) already defines `MediaExtractor`/`ExtractorRegistry`, `MediaSource`/
`MediaFormat`/`MediaInfo`/`MediaExtractionResult`, the download models and `DownloadEngine`,
`FilenameSanitizer`, `UrlTextExtractor`, `RuntimeCapabilities` and the `MediaResolver` contract.
See [CHANGELOG.md](CHANGELOG.md). **Those files exist on that branch only and have not been
compiled, built or executed** — see section 7.

---

## 7. What is verified, and what is not

Read this section before quoting anything above as fact.

**Verified for this document**

1. Every version, coordinate and repository name in section 1 was read from `app/build.gradle`,
   `build.gradle` and `settings.gradle` in this worktree on 2026-09-17; no version was guessed and
   no version is dynamic.
2. The licence column in section 1 cross-checks against [LICENSE_REVIEW.md](LICENSE_REVIEW.md) §5,
   which itself records where each licence came from.
3. Section 2-6 are summaries of `DEPENDENCY_OPTIONS.md`, whose
   own confidence markers `[P]`/`[S]`/`[U]` are preserved where this file quotes a specific number.

**Not verified — do not report any of this as working**

1. **No resolved-dependency licence scan has been run.** The Gradle `dependencies` report plus a
   licence plugin has never executed here, so "the shipped graph is Apache-2.0/MIT/EPL-1.0 only" is
   an expectation from the coordinates, not a measured result. It requires a configured Android
   module (see `BUILD_AND_RELEASE.md`).
2. **Nothing was added to `app/build.gradle` by this review.** Media3, the MIT wrapper, Chaquopy and
   every FFmpeg option are *candidates*; no dependency was introduced, and the review deliberately
   does not edit `app/build.gradle`, `build.gradle` or `settings.gradle`.
3. **`compileSdk` is still 34** in this worktree, so Media3 1.11.x **cannot** be added until it is
   raised to 36 (section 4.1). That bump has not been made.
4. **WorkManager is still 2.9.0** (section 4.2); the move to 2.11.x has not been made.
5. **No APK size figures were measured.** The size columns are research estimates; the only
   per-ABI APK figures in the research are other applications' (Seal, YTDLnis) or the wrapper
   authors' own sample apps.
6. **16 KB page-size alignment was not checked** for any candidate artifact. For the MIT wrapper
   this is an explicit open action (extract `libpython3.13.so` and inspect `PT_LOAD` alignment).
7. **The enhanced module contracts have not been compiled.** They live on
   `feature/enhanced-modules` (worktree `<repo-parent>\repo`) and are untracked there; no
   Gradle task has ever compiled or executed them.
8. **The dependency research's own unconfirmed items carry over unchanged** — `certifi`'s licence,
   the per-version `minCompileSdk` enforcement inside published Media3 AARs, the wrapper's bundled
   FFmpeg version, and the exact ABI/minSdk of `ffmpeg-kit-lts-16kb`. The full list is in
   `DEPENDENCY_OPTIONS.md` §14 ("What I could not confirm").

**A separate legal axis, outside software licensing.** Downloading media from Instagram, Facebook,
TikTok, Pinterest or Reddit may breach those platforms' Terms of Service or, in some
jurisdictions, anti-circumvention law. That question applies equally to *every* option in this
document — GPL, MIT or server-side — and is flagged for legal review, not resolved here. FFmpeg
patent exposure (H.264/H.265, x264/x265/kvazaar) is likewise a separate axis from copyright and
argues further for Media3's platform-codec path.

---

## 8. The decision actually taken, and its consequences

The project owner's instruction was to **ignore the licensing analysis and build a working
downloader**. This section records what was adopted and what it costs, so the decision is documented
rather than implicit.

### 8.1 Adopted

| Coordinates | Version | Licence | Why this one |
|---|---|---|---|
| `io.github.junkfood02.youtubedl-android:library` | **0.17.3** | **GPL-3.0** | Maintained (published by the `yausername` build into JunkFood02's Maven namespace), covers all five target services, ships Python |
| `io.github.junkfood02.youtubedl-android:ffmpeg` | **0.17.3** | **GPL-3.0** | needed to mux separate video and audio streams, which most Instagram/TikTok formats require |

**0.17.3 rather than 0.18.x on purpose.** Section 4 of the research documents an open, unassigned
crash in 0.18.x on 16 KB-page devices (five libraries inside `libffmpeg.zip.so` are still 4 KB
aligned, giving an uncatchable `linker64` abort), and 0.17.3 is what the Seal app ships. The
JitPack coordinate (`com.github.yausername.youtubedl-android`) is dead for every version ≥ 0.15.0;
Maven Central is the only usable source.

### 8.2 The consequence, stated plainly

**The distributed APK is now a GPL-3.0 combined work.** Linking `youtubedl-android` and its bundled
FFmpeg makes the whole application subject to GPL-3.0, "regardless of how they are packaged". If
this build is ever given to anyone else, the obligations are: release the complete corresponding
source under GPL-3.0, carry the licence and notices, and provide the installation information
GPL-3 requires for user-installable software. **Private personal use carries no such obligation.**
The `MediaExtractor` abstraction keeps the engine replaceable, but it does not launder the licence.

Removing this dependency is a build-file change plus deleting one package: everything else in the
app is licence-clean, and the direct-file downloader, the resolver and the whole URL-cleaning and
detection layer never touched it.

### 8.3 Measured cost

The bundled payload is one Python interpreter plus one FFmpeg per ABI, so the app now configures
ABI splits (`arm64-v8a`, plus a universal APK) instead of shipping one fat artifact:

| Artifact | Size | Note |
|---|---|---|
| Release APK, before this dependency | 5.21 MB | R8-minified, no native payload |
| Debug APK, `arm64-v8a` only | ~54.8 MB | split |
| Debug APK, universal | ~138.4 MB | carries every ABI's payload |
| Release APK, `arm64-v8a` only | not yet measured | R8 shrinks the Kotlin, not the Python/FFmpeg payload, so expect the same order of magnitude |

`packaging { jniLibs { useLegacyPackaging = true } }` is required by the library: without it the
loader maps the `.so` files straight out of the compressed APK and the bundled payload fails.

`proguard-rules.pro` gained keeps for `com.yausername.youtubedl_android.**`,
`com.yausername.youtubedl_common.**`, `com.fasterxml.jackson.**` and `org.apache.commons.io.**`.
The library reflects over Jackson's mapper types in a static initialiser, so without those keeps a
**release** build fails at the first `YoutubeDL.getInstance()` call — a runtime crash, not a build
error, which is worth knowing before shipping a minified build.

### 8.4 Device verification of the adopted engine

On the Android 16 x86_64 emulator, through the production extractor: yt-dlp initialised, the bundled
Python and FFmpeg native libraries loaded and executed, and a real URL extracted into 11 formats with
correct `requiresMuxing`/`isAudioOnly` flags. A YouTube URL was refused with `LOGIN_REQUIRED`
(`[youtube] … Please sign in`) — this is the datacentre IP being blocked, correctly classified as a
user-facing reason rather than a crash. The mux path (download a video-only stream, then merge with
FFmpeg) was **not** completed: on the emulator that download hung inside the Python subprocess and
had to be killed. Muxing is therefore implemented and reasoned about but **not verified end to end**.
