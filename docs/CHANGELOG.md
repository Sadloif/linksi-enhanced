# Changelog

All notable changes to **Linksi Enhanced** — this private fork of
[Linksi](https://github.com/AsukaAzure/Linksi) by AsukaAzure — are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project uses
the versioning conventions described in [BUILD_AND_RELEASE.md](../BUILD_AND_RELEASE.md) §5 (never
decrease `versionCode`; the baseline is `versionCode` 20 / `versionName` 3.1.1).

**Nothing in the `Unreleased` section has been released.** The build environment is now complete: the
app assembles, `:app:testDebugUnitTest` runs **729 tests with 0 failures**, `:app:lintDebug` passes
with **0 errors**, and a signed release APK is produced from `enhanced/integration`. See
[TEST_REPORT.md](../TEST_REPORT.md) §9 for the evidence and §9.5 for what is still untested.

---

## [Unreleased]

Work after the `3.1.1-enhanced.1` release. **Built as `3.1.1-enhanced.3` / versionCode 23**, signed and
archived under `artifacts\releases\` — not yet distributed.

### Added

- **The downloader is reachable from the UI.** A "Download / quick actions" row in the link options
  sheet opens the quick action panel for that link, so downloading no longer requires the floating
  bubble or the accessibility service. The panel's DOWNLOAD action runs
  `ExtractorRegistry.analyse` → `DownloadEngine.enqueue`, and shows progress, cancel, retry and the
  `error_download_*` messages in place.
- **A downloads screen** (Settings → Downloads): In progress / Finished sections from
  `DownloadEngine.observeAll()`, with cancel, retry and dismiss. Every number comes from
  `DownloadFormatting`, so a download whose total size is unknown shows an indeterminate bar rather
  than a fabricated percentage.
- **`DownloadEngineInstrumentedTest`** — a real download on a device through the production engine,
  asserting the state reaches `Completed`, the bytes are readable and match the reported size, and
  the published MediaStore row has `is_pending=0`.
- **Site-specific extraction via yt-dlp**: `YtDlpExtractor`, `YtDlpInfoMapper`, `YtDlpRuntime`,
  `YtDlpDownloader` and `YtDlpProgressParser`, plus ABI splits so a per-device APK is not several
  times the size of a universal one.
- **A download watchdog for site-engine downloads** (`YtDlpDownloadWatchdog.kt`): a 60 s limit on
  transferring **no bytes** (scaled up to 8× for large files) and a 30 min hard limit, checked every
  5 s. yt-dlp can hang on a silent socket indefinitely while its own options stay reactive, so
  without this a user's download sat at a frozen percentage forever. Progress is measured in bytes,
  not output lines, because yt-dlp prints destinations and post-processing steps without transferring
  anything. 20 unit tests.
- **`YtDlpInterruptedDownloadTest`** — a device test for what happens to an *interrupted* download:
  that cancelling really stops the engine's child process, that the partial file survives and the
  same request reuses its scratch directory, and that the watchdog ends a stalled download with a
  retryable failure.
- **`POST_NOTIFICATIONS` is now requested at runtime** (`DownloadNotificationPermission.kt`), from two
  in-context triggers — enabling *Download notifications*, and the first quick-panel download while
  that setting is on — and never at launch (specification §33). Upstream declared the permission but
  never asked, so on Android 13+ every download notification was silently dropped. 8 unit tests.
- `-e ytdlpDownloadTimeoutSeconds`, `-e ytdlpFormatId` and `-e ytdlpUrl` instrumentation arguments for
  `YtDlpMediaSmokeTest`, so a device run's deadline can be set from measured throughput instead of
  being guessed.
- **Deterministic loopback device tests** for the two previously missing download proofs: a server
  that transfers bytes and then stalls until the watchdog stops it, and a server that drops the
  first connection so the retry must send `Range` at exactly the preserved partial-file length.
- **`Android16CompatibilitySmokeTest`** — four API 36 device checks for the installed target SDK,
  Share Receiver content, the Quick Panel's explicit-URL path, and onboarding controls staying above
  the navigation bar.
- **A configurable real-target extractor probe** in `YtDlpMediaSmokeTest`. It accepts one public URL,
  verifies that it belongs to a primary target and reports success/failure without writing the URL or
  extracted title to logcat.
- **The site engine can refresh itself** (`YtDlpUpdater.kt`). `youtubedl-android` pins yt-dlp
  **2024.09.27**, and by the time it mattered that copy could not read a single one of the nine real
  Facebook links from the owner's export while a current release read five — including the
  specification's own Reel example. The refresh fetches the published release from a fixed
  `/releases/latest/download/` URL, **verifies its SHA-256 against the published `SHA2-256SUMS`**,
  stages it beside the old engine, swaps by atomic rename, then probes the installed result and rolls
  back if it does not run or does not take effect. It runs at most once a week and only when a
  download already needs the engine. 15 unit tests.
- **A "Site engine" section in Enhanced features**, showing the installed engine version and offering
  a manual check — so an extractor that has gone stale is something the user can see and act on.
- `aLinkSharedIntoTheAppIsStoredCleaned`: a regression test using the exact URL shared into the app on
  a device, which is the case the rest of the cleaner suite cannot distinguish — every existing test
  would pass with the cleaner implemented but never called on the save path.
- **`DirectFileDownloaderInstrumentedTest`** — a loopback server serving PNG, PDF, ZIP and MP3 with
  honest content types, asserting the classification, the derived extension, the byte count and the
  readability of the published location, plus a `text/html` body that must be refused as a file.
- **`SlowTransferInstrumentedTest`** — a loopback server that trickles 512 KiB in 4 KiB chunks over
  about eight seconds, on both the emulator and the POCO, proving the download watchdog does not kill a
  slow-but-healthy transfer. The test also asserts the transfer really was slow, so it cannot pass by
  being instant.
- **`PublishFallbackInstrumentedTest`** — constructs the state that makes MediaStore refuse a publish (a
  visible entry already claiming the name), so the filesystem fallback runs on demand rather than
  depending on MediaStore's mood. It asserts that the reported location **resolves to the bytes that
  were written**, which is the property that matters and the one that has been wrong four times.
- **`ClipboardPanelInstrumentedTest`** — covers the route the floating bubble's tap leads to, which had
  no test at all: the clipboard is not read without window focus, non-link text never becomes a URL, and
  the panel opened with no intent extra displays the clipboard link **cleaned**.
- **`ServerResolverInstrumentedTest`** — a test HTTPS server that captures what the optional resolver
  sends, so the specification's privacy rules are checked rather than asserted: the body is exactly
  `{"url": …}`, the API key is a bearer header and never in the URL, an `http://` endpoint is skipped
  before anything leaves the device, and a malformed body, an HTTP 500 and a refused connection are
  all values rather than exceptions.
- **The extractor logs what yt-dlp actually said.** Its stderr was classified into a user-facing
  `MediaError` and then discarded, so the app could report only that a link was `Unsupported` while
  yt-dlp knew exactly why. That single log line is what turned a year-old mystery about the owner's
  own links into a one-line diagnosis. URL query strings are redacted first.

### Changed

- **`targetSdk` is now 36.** Share Receiver and Quick Panel use edge-to-edge, the Quick Panel no
  longer opts back into decor fitting, and onboarding applies safe-drawing insets to its page and
  bottom controls. API 36 passed four core-flow and four focused compatibility tests.
- Site-engine cancellation now awaits yt-dlp in a cancellable context. Caller cancellation destroys
  the child promptly and rethrows; a watchdog-initiated stop remains a classified transient
  `NETWORK` result.

- **The project moved into a single `E:\Deepseek\Linksi` umbrella folder**: `repo\` (the checkout),
  `toolchain\`, `local\`, `keys\`, `artifacts\`, `evidence\`, `research\`, `scripts\` and
  `worktrees\`. Every hardcoded path in the documentation and helper scripts was rewritten, the
  merged worktrees were removed, and the build was re-verified afterwards.
- **`tools/build-release.ps1`** now derives every path from the umbrella folder, runs the checks and
  `assembleRelease` as **separate** Gradle invocations (in one invocation lint's debug analysis
  reaches for release-variant KSP output that does not exist yet and crashes), and streams output to
  a log instead of buffering it.
- **A site-engine download's scratch directory is now named after the download, not the attempt**: a
  SHA-256 digest of the URL, the format selector and the requested name. It is kept after a
  *transient* failure and deleted after a success or a permanent one, so a retry resumes from
  yt-dlp's `.part`/`.ytdl` state instead of re-downloading. Unfinished scratch directories older than
  an hour are reclaimed at the start of the next download, so keeping them cannot fill the cache.
- **yt-dlp is now invoked with `--fragment-retries 10` and `--force-ipv4`**: one refused DASH
  fragment no longer abandons a 160-fragment stream, and IPv4 is the maintainer-endorsed workaround
  for stalled CDN connections.
- **yt-dlp's own output (stdout and stderr) now goes to logcat**, with every URL stripped of its
  query string. Without the child's output the earlier "stalled merge" investigation had nothing to
  read; with the raw URL in it, a CDN signature and expiry would have landed in a world-readable log.

### Fixed

- **`BubbleService` failed silently on every path.** No foreground notification, a revoked overlay
  permission, and a refused window all ended in `stopSelf()` with no log, and `addBubbleView` swallowed
  its own exception — so a bubble that never appeared was indistinguishable from a copy that was never
  detected. All four paths now state the reason. Adding the overlay view is itself logged on success,
  which is what made the bubble's window state verifiable at all.
- **A web page could be saved as a downloaded file, silently.** The direct downloader classifies a URL
  from its response content type, and a server that serves an error document as `text/plain` or
  `application/octet-stream` passed every check — measured on a device, where a Facebook Reel the site
  engine could not read was written into Downloads as a 4 KB XML error page named
  `1710485373378939.vndwapxh`, with no error shown. The body's leading bytes are now inspected for a
  document signature (`<?xml`, `<!doctype`, `<html`, …) before anything is written, and the failure is
  reported as `EXTRACTOR_FAILED` with a reason.
- **A URL that turned out not to be a file never reached the site engine.** The panel offers one format
  for such a link, so the download took the direct path, failed on the page guard, and reported a
  failure — while the app contained an extractor that could fetch the video: tapping *Download* on a
  Facebook Reel produced an error where the same URL yields a playable video through the app's own
  engine. `DownloadWorker` now recognises that specific outcome and hands the request over before
  giving up. Verified on the emulator: the Reel publishes as a 6,777,555-byte MP4.
- **A dropped connection restarted the download instead of resuming it.** yt-dlp re-created the
  destination on every in-run retry, so a 6.46 MiB transfer that reached 97.6% still failed after three
  attempts — on a link that drops a connection every few megabytes, no download could ever finish.
  `--continue` is now passed, and measured on the device: `Resuming download at byte 2096128` →
  `100% of 6.46MiB`, producing the same 6,777,555-byte file the app publishes.
- **Three connection retries was too few.** yt-dlp's default was kept while the app's own transfers
  failed 88 KB short of the end of a 6.46 MiB file. Measured on the same link: `--retries 3` finished
  **incomplete** after surviving 4 drops; `--retries 10` finished **complete** after surviving 8, in
  the same nine seconds. Raised to 10 — nearly free, because each retry now resumes rather than
  restarts.
- **A fully downloaded file could be reported as a failed download.** Publishing flips `IS_PENDING` to
  0, and that update was rejected with `SQLiteConstraintException: UNIQUE constraint failed:
  files._data` because an earlier attempt at the same name had left a MediaStore row behind — in this
  flow always, since the direct download opens a sink, is refused by the page guard, and aborts before
  the site engine publishes. The user was told their 6.78 MB MP4 had failed while it sat in Downloads.
  Publishing now clears any other row claiming that exact path and retries once, and reports failure
  only when the entry is genuinely absent or still hidden.
- **The same class of false failure when MediaStore renames the file.** When the requested name is
  already taken, MediaStore saves the download as `clip (1).mp4`, and publishing the original name then
  fails on the same unique constraint — so the app reported failure while the completed file was
  visible in Downloads under the name the platform chose. Publishing now looks for a visible entry
  holding this file, under either name, before concluding anything; finding one, it drops the stuck row
  and returns the working entry.
- **That fix then deleted the user's own file, and only the release build showed it.** Clearing a
  "conflicting" row ran *before* the check for an already-published copy, and the row at that path is
  usually the user's completed download — so the cleanup deleted the good row, the retry failed against
  the emptied path, and the fallback found nothing because the row it needed had just been removed.
  The check now runs first, and clearing residue only happens when nothing published holds the name.
- **"Not enough storage is available" for a file that was sitting in Downloads.** Publishing can be
  refused with `UNIQUE constraint failed: files._data` while the completed bytes are already in place —
  observed on the signed release with a 6,777,555-byte MP4 present and its MediaStore row correct, and
  not reproducible on the same rows minutes later. `commit` now checks the **filesystem** as a last
  resort: a file in public Downloads matching the requested name whose size equals the bytes just
  written is reported as the download's location. Three distinct MediaStore failures have now produced
  the same false "it failed"; this is the first check that reads the bytes rather than the bookkeeping.
- **A loopback HTTP server is now usable from instrumented tests, in the debug variant only.** The
  app's network security policy permits cleartext nowhere — correctly — but that includes `127.0.0.1`,
  and several tests drive the real downloader against a local server because it is the only way to
  control a response body and its `Content-Type`. With cleartext blocked those requests failed with
  `UnknownServiceException: CLEARTEXT communication to 127.0.0.1 not permitted`, delivered as an
  ordinary `Failed(...)` result — so a loopback test could pass while proving nothing.
  `src/debug/res/xml/network_security_config.xml` now exempts loopback for the debug variant; the
  release build's policy is unchanged.
- **A success reported for a file that had been deleted.** The publish fallback trusted a MediaStore
  row over the filesystem, and a row outlives the file it describes: with the download deleted from
  Downloads and the row left behind, the app reported "Download complete" and handed back a location
  resolving to nothing. `commit` now asks the **filesystem first** (name plus exact written size) and
  the collection's rows only afterwards, because the filesystem is the part the user can see.
- `java.io.tmpdir is set to a directory that doesn't exist` when the release script ran after the
  relocation — the temp directory is now derived and created.
- The release script reported a **successful** build as failed, because its Gradle helper leaked
  `Tee-Object` output into its own return value and the exit code then compared as an array.
- **A site-engine download could run forever.** `YtDlpDownloader.download()` had no stall detection
  and no deadline, so a hung socket left the progress bar frozen with a Cancel button that might not
  work. See the watchdog above.
- **Cancelling a site-engine download could take the app process down.** The library's
  `CanceledException` was re-thrown as a `CancellationException` out of `download()`, and on Android
  an uncaught coroutine cancellation in the worker's scope kills the process. A stop the app itself
  caused (watchdog, or the caller's cancel) is now an ordinary failure value; only a cancellation
  this class did not cause is re-thrown.
- **Caller cancellation did not actually stop promptly.** The child was awaited inside
  `NonCancellable`, so the earlier green test took about 230 seconds and only returned after the
  stream completed. The POCO now cancels in 9.631 seconds with no surviving interpreter.
- **The `YtDlpMediaSmokeTest` merge test could never pass.** Its 120-second deadline was smaller than
  the ~300 s the sample transfer actually needs at the measured 71,596 B/s, so it reported the merge
  path as unverified three times. The deadline is now 900 s, documented as a stuck-process bound, and
  every run logs its elapsed time and throughput. The merge path is now verified end to end on real
  hardware (21,210,202 bytes merged and published to MediaStore).
- **A site-engine download that failed could lose its reason and read as a cancellation.**
  `runDownload` ran its work in a `coroutineScope`, so when the yt-dlp job threw — a 404 from the
  source, a refused connection — the scope was cancelled before the exception could be collected, and
  the caller received a `CancellationException` instead of the `MediaError`. A plain 404 therefore
  looked like the user having pressed Cancel. It now runs in a `supervisorScope`, so the job's own
  exception reaches the collector and becomes the failure value it always should have been.
- **Two paths in the progress callback could kill yt-dlp's stdout reader.** The callback runs on the
  library's reader thread; a throw from logging, parsing or the stall bookkeeping stops that thread
  and leaves yt-dlp blocked on a full pipe — a self-inflicted hang, and the failure mode the earlier
  investigation had guessed at. The callback body is now guarded, the caller's own `onProgress`
  cannot fail a download, and the channel send cannot throw on a closed channel.

- **The site-engine Settings row was inert until something else had used the engine.** `YtDlpRuntime`
  deliberately does not start the engine at app start, but the two methods the settings screen calls
  (`engineVersion`, `refreshEngine`) went straight to `YtDlpUpdater`, which runs the engine through the
  wrapper - and the wrapper refuses to run anything before `YoutubeDL.init`. So on a fresh process the
  row read "Version not reported yet" and tapping **Check** answered `instance not initialized`. Every
  other caller was fine, because every other caller drives a download and a download initialises first;
  asking *about* the engine without using it was the one path that skipped it. Both methods now start
  the engine first, with `engineVersion()` degrading to "not reported yet" and `refreshEngine()`
  surfacing the real reason, since only the second is a user-initiated action. Verified on the POCO:
  the row now reads `Version 2026.08.19`, and **Check** reports `Already up to date (2026.08.19)` after
  staging and checksum-verifying the published release. `TEST_REPORT.md` §40.

- **A test that drives the owner's own real links through the app** — `RealSiteLinksInstrumentedTest`.
  The five-site acceptance criterion had been unprovable from this machine because Instagram, TikTok
  and Pinterest answer a scripted client with a challenge page and guessed ids 404. Given real links it
  now reports per-site coverage, separating a site's refusal from an app failure. Result on the POCO:
  **13 of 16 links extracted — TikTok 6/6, YouTube 10/10**, with 33–178 formats per link, up to 3840p,
  full titles, uploaders and durations (including a 65-minute video). URLs come from
  `-e realLinkUrls "url,url,…"`, so a later session can probe a fresh list without editing source.
  `TEST_REPORT.md` §41.

### Verified on real hardware

Readings from the physical POCO X3 Pro (Android 13 / MIUI 14), plus one defect that only a device could
find. Full detail in `TEST_REPORT.md` §39–§41.

- **The floating bubble is visibly on screen.** The overlay window is present (`ty=APPLICATION_OVERLAY`,
  `appop=SYSTEM_ALERT_WINDOW`, 156×156 px at (900,722)), has a surface, is ready for display and is not
  obscured, and a screenshot of the composited framebuffer shows the purple-haloed Linksi mark floating
  over the launcher. `BubbleService` logs `bubble shown as a TYPE_APPLICATION_OVERLAY window`. This was
  the only element of the bubble module without device evidence, so the module is now complete.
- **The accessibility service binds.** `Bound services:{Service[label=Linksi link detection (optional),
  … eventTypes=[TYPE_VIEW_CLICKED, TYPE_WINDOW_STATE_CHANGED, TYPE_WINDOW_CONTENT_CHANGED,
  TYPE_VIEW_TEXT_SELECTION_CHANGED]]}` with `Binding services:{}` and `Crashed services:{}`. A MIUI
  quirk found along the way: after an `am force-stop`, enabling the service over `adb` leaves it parked
  in `Binding services` indefinitely with no error, and only launching the app first recovers it
  (`TEST_REPORT.md` §39.4). This is a developer-automation trap, not an end-user one.
- **The site engine reads real TikTok and YouTube links.** Driven through the app's own extractor on the
  POCO: **TikTok 6/6 and YouTube 10/10** of the owner's links, 33–178 formats each and up to 3840p,
  with the three failures being TikTok `Connection reset by peer` throttling on a second pass over
  links that had already extracted (`TEST_REPORT.md` §41). Together with the earlier Facebook result,
  three of the specification's named sites now have real-content proof.
- **All five named sites now have real-link proof.** The owner supplied Instagram, Reddit and Pinterest
  links as well, and one batch on the POCO produced **Instagram 4/5, Reddit 4/5, Pinterest 4/5**
  (`TEST_REPORT.md` §42). The failures are one `LOGIN_REQUIRED` post, TikTok `NETWORK` throttling, and
  two short links that redirect to a site's home page — properties of the links, not the app. Instagram,
  Pinterest and Reddit had been recorded as an unfixable gap because they cannot be self-served from
  this machine; with real input they took one batch to prove.
- **Pinterest share links kept their tracking parameters.** Pinterest's share sheet produces
  `…/pin/<id>/sent/?invite_code=…&sender=…&sfo=1`, and the URL cleaner only removes *known* tracking
  parameters — an explicit list, the `utm_` family, and a Facebook-only set. Pinterest's names were in
  none of them, so a pinned link kept `invite_code` (a per-share secret) forever. Measured on the POCO
  first: all three parameters **and** the `/sent/` segment are unnecessary — the full share URL, the URL
  with the query removed, and the canonical `/pin/<id>/` form all extract to the same title, uploader,
  duration and formats. The fix adds a Pinterest host-scoped parameter set alongside the existing
  Facebook one (`invite_code`, `sender`, `sfo`), and `/sent/` is deliberately left in place because
  rewriting a path is a larger change than dropping a query parameter. Seven unit tests, including that
  the rule does **not** apply on other hosts that use the same generic names. `TEST_REPORT.md` §43.
- **A false-alarm assertion was removed from `RealSiteLinksInstrumentedTest`.** It failed the whole
  suite with "the detector did not recognise a real video link" when the *engine* refused a short link
  that redirects to a site's home page. The detector had classified it correctly, and the app's own
  `YtDlpExtractor` documents that the engine's `"Unsupported URL"` is the site's answer — the same value
  returned for a URL no backend supports. The outcome is now recorded per link, so a dead link no longer
  reads as a defect while an app that extracts nothing still fails.
- **A completed download could be reported as "Not enough storage is available".** A refactor of
  `MediaStoreSink` removed the filesystem-first publish fallback in favour of trusting only the handle's
  own MediaStore row. The concern behind that was real — the older fallback matched any Downloads row of
  the same name and size, so it could claim another operation's file — but removing it also removed the
  recovery, and the existing device test caught it immediately: a download whose bytes were complete and
  on disk was reported as `NO_STORAGE`. The fallback is restored and **bounded**: the file must match the
  requested name (or MediaStore's `name (n).ext` collision form), be exactly the committed size, and have
  been written during this handle's lifetime. A device run showed three same-named, same-sized files
  coexisting in Downloads, so no single one of those tests suffices — the conjunction identifies our
  file, which is the safety property the refactor wanted *and* the recovery it dropped. Both behaviours
  are now proven at once on the POCO. Also learned: `MediaStore.MediaColumns.DATA` is **not** queryable
  on this ROM (`Invalid column data`), so the path must be derived rather than looked up.
  `TEST_REPORT.md` §44.

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
database contains only cleaned URLs. Full evidence in [TEST_REPORT.md](../TEST_REPORT.md) §10.

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
  (Details in [TEST_REPORT.md](../TEST_REPORT.md) §4.)

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
  fingerprint. [TEST_REPORT.md](../TEST_REPORT.md) §3 records this as a blocker, and the same is still
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
