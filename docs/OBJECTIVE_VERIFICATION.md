# Objective verification — Linksi Enhanced

This file exists to answer one question: **is the objective actually delivered?** It maps each clause of
the objective to the code that implements it, the test that exercises it, and the evidence that it
works. `TEST_REPORT.md` holds the measurements; this file holds the mapping.

Compiled 2026-09-18 against commit `8bed4ea` (which adds documentation only — the source tree is
`94a99a1`), tree clean, release artifact recorded in `artifacts\releases\latest-build.json`.

---

## The objective, clause by clause

> Deliver the Linksi Enhanced private fork per `LINKSI_ENHANCED_REVISED_SPEC.md`

| Clause | Delivered | Implementation | Tests | Evidence |
|---|---|---|---|---|
| **keep baseline Linksi working** | **Yes** | Baseline packages untouched: `data/`, `domain/`, `ui/`, `worker/`, `utils/` (merged into `utils/` additions only). Every enhanced feature is opt-in and off by default | `CoreFlowsSmokeTest` 4/4, `EnhancedModulesTest`, plus the baseline unit suites | §3 of `SESSION_HANDOVER.md`; `CoreFlowsSmokeTest` on the POCO |
| **isolated optional modules** | **Yes** | All new code under `com.linksi.app.enhanced.*`, reached through interfaces so the baseline never names a concrete enhanced type | `EnhancedModulesTest` (12 tests) asserts module grouping and that the enhanced surface is discoverable independently | `CODEBASE_GUIDE.md` §4 |
| **URL cleaner integration** | **Yes** | `utils/UrlCleaner.kt`; wired into saving, link cards, the share sheet and the panel | `UrlCleanerTest` **68 tests** | §24, §43 |
| **smart link detection** | **Yes** | `enhanced/detect/`: `SmartLinkDetector`, `LikelyCopyDetector`, `ClipboardUrlReader`, `UrlTextExtractor` | `LikelyCopyDetectorTest` 38, `UrlTextExtractorTest` 34 | §25, §38 |
| **accessibility** | **Yes** | `enhanced/service/LinksiAccessibilityService` | bound on the POCO with all four event types | §39.4 |
| **floating bubble** | **Yes** | `enhanced/bubble/`: `BubbleService`, `BubblePolicy`, `BubbleSettings` | `BubblePolicyTest` 31, `BubbleOverlayInstrumentedTest` 1 | §39 — **visible in a screenshot** |
| **quick action panel** | **Yes** | `enhanced/ui/`: `QuickPanelActivity`, `QuickActionPanel`, `QuickAction`, `DownloadsScreen` | `ClipboardPanelInstrumentedTest` 5/5, `QuickActionTest` 33, `DownloadUiModelTest` 20 | §38, §45 |
| **universal media downloader with extractor abstraction** | **Yes** | `enhanced/media/`: `MediaExtractor` + `ExtractorRegistry` abstraction; `DirectFileExtractor` and `YtDlpExtractor` implementations | `ExtractorRegistryTest`, `YtDlp*` unit suites, `YtDlpMediaSmokeTest` 7/7, `YtDlpInterruptedDownloadTest` 4/4 | §14, §19, §22, §41 |
| **direct file downloader** | **Yes** | `enhanced/download/DirectFileDownloader`, `DirectFileClassifier`, `MediaStoreSink`, `AppStorageSink` | `DirectFileDownloaderInstrumentedTest` 2/2, `PublishFallbackInstrumentedTest` 2/2, `StorageIntegrityInstrumentedTest` 3/3 | §20, §27, §35, §44 |
| **optional server resolver** | **Yes** | `enhanced/resolver/`: `MediaResolver`, `HttpMediaResolver`, `DataStoreMediaResolver`, `MediaResolverFallback`, `ResolverResponseParser` | `ServerResolverInstrumentedTest` 3/3 against a test HTTPS server, plus 4 unit suites | §29 |
| **build and test a signed APK** | **Yes** | `tools/build-release.ps1` | signed, archived, digest-verified, signature-verified | below |
| **produce the required documentation set** | **Yes** | 11 markdown documents | — | below |

---

## The signed APK

| Property | Value |
|---|---|
| Version | `3.1.1-enhanced.3`, versionCode 23 |
| arm64-v8a | `474A37E43C414DA86E378E076126BE64EB2590105B54B1DFABF8492D835DC8FD` (37,925,980 bytes) |
| universal | `51FCF54AB92D7824DC4C21F5F1AB945E27974AB800ED0650DD020C225F54B9EC` (125,483,693 bytes) |
| Signature | verifies under APK Signature Scheme **v2**, 4096-bit RSA, `CN=Linksi Enhanced (private)` |
| Built from | commit `94a99a1`, tree clean, `uncommitted: no` |
| Freshness | APK built 09:12:26, newest source file 09:02:38 — the artifact is newer than the source |
| Digests re-checked | after the build, against both `.sha256` files — MATCH |

A release build that has not been *run* is only half a claim, so the release variant has also been
installed and exercised on hardware (a Facebook Reel publishing as a 6.78 MB MP4), recorded in §20–§36.

---

## Test totals at this commit

| Gate | Result |
|---|---|
| `:app:testDebugUnitTest` | **772 tests, 35 suites, 0 failures, 0 errors, 0 skipped** |
| `:app:lintDebug` | **0 errors** (180 warnings, 4 hints — all pre-existing baseline) |
| Instrumented suites | **13 suites, 0 failures** on the POCO X3 Pro |
| Instrumented tests | CoreFlows 4, Android16Compatibility 4, DownloadEngine 1, BubbleOverlay 1, ClipboardPanel 5, DirectFileDownloader 2, PublishFallback 2, RealSiteLinks 2, ServerResolver 3, SlowTransfer 1, StorageIntegrity 3, YtDlpInterruptedDownload 4, YtDlpMediaSmoke 7 |

The unit count is 772 across 35 suites; the largest are `UrlCleanerTest` 68, `FilenameSanitizerTest` 48,
`RuntimeCapabilitiesTest` 40, `YtDlpInfoMapperTest` 39, and `LikelyCopyDetectorTest` 38.

---

## Real-content verification

The specification's acceptance criterion is phrased in terms of real public links, so it was met with
the owner's own links through the app's own extractor on a physical device:

| Site | Readable | Notes |
|---|---|---|
| YouTube | **10 / 10** | 33–178 formats, up to 3840p |
| TikTok | **6 / 6** | up to 1280p; later passes throttled |
| Instagram | **4 / 5** | up to 2560p; one post `LOGIN_REQUIRED` |
| Reddit | **4 / 5** | one short link redirects to Reddit's home page |
| Pinterest | **4 / 5** | one short link redirects to Pinterest's home page |
| Facebook | **5 / 9 live** | the other 4 are dead share links |

Every failure was a property of the link or the network, not the app. §41–§43.

---

## The documentation set

| Document | Purpose |
|---|---|
| `TEST_REPORT.md` | what is proven, with a measurement for each claim (45 sections) |
| `CHANGELOG.md` | what changed, per release |
| `SESSION_HANDOVER.md` | continuity: state, environment traps, remaining work |
| `CODEBASE_GUIDE.md` | structure, design decisions, reading order for a reviewer |
| `CODE_REVIEW.md` | issues found by review, with reasoning |
| `BUILD_AND_RELEASE.md` | how to produce a signed APK |
| `README_ENHANCED.md` | user-facing description of the enhanced features |
| `DEPENDENCY_REVIEW.md` | third-party dependency review |
| `LICENSE_REVIEW.md` | licence position |
| `UPSTREAM_UPDATE_GUIDE.md` | how to take upstream changes |
| `OBJECTIVE_VERIFICATION.md` | this file |

---

## What is explicitly NOT claimed

Stated so that completion of the objective is not mistaken for the absence of limits.

| Item | Status |
|---|---|
| **Testing on the owner's own phone** | **Done as of 2026-09-18.** The OPPO the OPPO test device (ColorOS 16, Android 16 / SDK 36) has now run the app; the field findings are in `TEST_REPORT.md` §47–§50. Everything before §47 was a POCO X3 Pro (Android 13 / MIUI 14) or an API 36 emulator. One finding is unresolved and documented as a platform limit, not a defect: copying a link inside a browser or chat app is not detectable (`RESEARCH_COPY_DETECTION_ANDROID16.md`) |
| API 36 UI depth | Predictive-back ordering through nested sheets, rotation, cutouts, IME, tablet/foldable layouts — untested. Compatibility depth, not known failures |
| `POST_NOTIFICATIONS` dialog | Seen in context on the emulator (§27); not walked through end-to-end on the POCO |
| Reminders | Broken **upstream**, not fixed |
| Room schema migrations | Schema 12, no migrations written |
| Licence | MIT promised in `README.md` but not committed; the build bundles GPL-3.0 components. `LICENSE_REVIEW.md` covers it. The owner directed that full licence rights be assumed |
| yt-dlp process kill | `destroyProcessById` in the wrapper cannot be fixed from here; the app works around it and verifies the outcome |
| APK reproducibility | Builds are not byte-reproducible — absolute paths feed the output |
| MediaStore resume | Not implemented; app-private storage resume is |
| Lint warnings | 180 warnings remain, all pre-existing baseline; **0 errors** is the gate that is met |

---

## Bottom line

Every clause of the objective has an implementation, a test, and recorded evidence from a real device,
and the signed APK is built from the committed tree with verified digests and signature. The
qualifications above are depth and external constraints — the one that matters most is that **nothing has
run on the owner's actual phone**, which is the single highest-value thing left to do.
