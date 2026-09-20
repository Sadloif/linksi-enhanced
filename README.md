# Linksi Enhanced

<div align="center">

[![Android API 26+](https://img.shields.io/badge/Android-API%2026%2B-green?logo=android)](https://www.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-100%25-blue?logo=kotlin)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack-Compose-4285F4?logo=android)](https://developer.android.com/jetpack/compose)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

**A personal, enhanced build of [Linksi](https://github.com/AsukaAzure/Linksi)** — the offline-first
Android link manager — with link detection, URL cleaning and built-in media downloading added on top.

[What's new](#whats-new-in-this-fork) • [Install](#install) • [Modules](#the-five-enhanced-modules) • [Building](#building) • [Credits](#credits)

</div>

---

## What this is

This repository is a **fork of [AsukaAzure/Linksi](https://github.com/AsukaAzure/Linksi)**, not the
original project. Linksi itself — the link manager, folders, search, import/export, browser, app lock
and the rest of the app — was written by **AsukaAzure** and is used here under its MIT licence. Every
credit for the foundation belongs to them.

Everything this fork adds lives in a single isolated package,
[`com.linksi.app.enhanced`](app/src/main/java/com/linksi/app/enhanced), so the upstream app keeps
working exactly as it did. Remove that package and you have upstream Linksi again.

The fork was made to answer one question: *a link manager that can detect, clean and actually
download the links you throw at it is far more useful than one that only stores them.*

---

## What's new in this fork

| | Capability | Where it lives |
|---|---|---|
| ✅ | **URL cleaner** — strips tracking parameters (`utm_*`, `fbclid`, `gclid`, Pinterest's, and more) | `utils/UrlCleaner.kt` |
| ✅ | **Smart link detection** — recognises URLs in text, with a floating bubble to act on them | `enhanced/detect/`, `enhanced/bubble/` |
| ✅ | **Quick action panel** — 11 actions (clean, save, folder, tags, notes, download, open, share, copy…) | `enhanced/ui/` |
| ✅ | **Universal media downloader** — pluggable extractor abstraction (site engine + direct file) | `enhanced/media/` |
| ✅ | **Direct file downloader** — plain HTTP files, resumable, via WorkManager | `enhanced/download/` |
| ✅ | **Optional server resolver** — off-by-default HTTP fallback for stubborn links | `enhanced/resolver/` |
| | Everything else | upstream, unchanged |

### URL cleaner

Removes tracking junk without breaking the link. Handles general parameters and site-specific ones
(Facebook's `fbclid`-family, Pinterest's, plus any `utm_*` prefix), with options for empty parameters
and fragments. It reports exactly which parameters it removed, so the result is explainable rather
than magic.

### Smart link detection

An optional accessibility service notices links you copy or select in other apps and offers to act on
them. It is **best-effort by design**, and it is worth being straight about why: Android 10+ denies
background clipboard reads
(`ClipboardService: Denying clipboard access … not in focus` — this is the OS, not a bug here), and
Chromium exposes a link's target URL only intermittently. The service reads a link from the
accessibility node when the app publishes it, and falls back to selection events otherwise.

So: **share, paste and text-selection are the reliable routes; sniffing a copy tap is a bonus when it
works.** If you want guaranteed behaviour, share the link to Linksi.

### Media downloading

The downloader is an **extractor abstraction**, not a hardcoded set of sites — each extractor
declares what it supports, and a registry picks candidates by capability and priority:

- **Site engine** — `youtubedl-android` (yt-dlp), which handles the overwhelming majority of video
  sites, with a refreshable engine.
- **Direct file** — anything that is simply a file over HTTP, resumable, saved through MediaStore or
  app storage.

Downloads run under **WorkManager**, so they survive the app being backgrounded, and a watchdog kills
genuinely stuck transfers without killing slow-but-healthy ones.

### Optional server resolver

A fallback that asks a configurable HTTP endpoint to resolve a media URL when local extraction fails.
**Off by default**, and it does nothing until you supply an endpoint and key — nothing is sent
anywhere unless you turn it on.

---

## Install

Grab an APK from [Releases](../../releases):

| Build | Size | Use it if |
|---|---|---|
| `arm64-v8a` | ~36 MB | Any modern phone (recommended) |
| `universal` | ~120 MB | You're not sure, or it's not arm64 |

Each release carries a `.sha256` file. The APKs are signed with a private key, so this fork installs
and upgrades **in place** — but Android will reject it over an APK signed by a different key, which
would require an uninstall and loss of your saved links.

---

## The five enhanced modules

Everything is optional and independently switchable in **Settings → Enhanced**. The defaults are
conservative: nothing that needs a permission or a network hop is on until you turn it on.

```text
com/linksi/app/enhanced/
├── detect/      smart link detection + clipboard/selection readers
├── bubble/      the floating bubble and its policy
├── ui/          quick action panel, downloads screen
├── download/    WorkManager engine, MediaStore & app-storage sinks
├── media/       extractor abstraction + yt-dlp and direct-file extractors
├── resolver/    optional server-side fallback
└── service/     the accessibility service
```

---

## Building

Requires JDK 17, Android SDK with API 36 build tools, and Gradle 8.13 (wrapper included).

```bash
./gradlew assembleDebug            # app/build/outputs/apk/debug/
./gradlew test                     # unit tests
./gradlew assembleRelease          # needs signing env vars, see below
```

Release signing reads environment variables (`KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
`KEY_PASSWORD`); debug builds can be pointed at a keystore with `DEBUG_KEYSTORE_PATH`. The full build,
signing and verification procedure — including the traps that cost real time here — is in
[`BUILD_AND_RELEASE.md`](BUILD_AND_RELEASE.md).

### Tech stack

| Layer | Technology |
|---|---|
| UI | Jetpack Compose, Material Design 3 |
| Architecture | MVVM + StateFlow |
| Database | Room (schema 12) |
| DI | Hilt |
| Images | Coil |
| Async | Coroutines + Flow, WorkManager |
| Scraping | Jsoup |
| Media | youtubedl-android (yt-dlp) + FFmpeg |
| SDK | min 26, target/compile 36 |
| Language | 100% Kotlin |

---

## Documentation

| Document | What it covers |
|---|---|
| [`BUILD_AND_RELEASE.md`](BUILD_AND_RELEASE.md) | Building, signing, publishing, environment traps |
| [`TEST_REPORT.md`](TEST_REPORT.md) | What has actually been verified, and how |
| [`RECOVERY_AND_UPSTREAM.md`](RECOVERY_AND_UPSTREAM.md) | Rebuilding from a clean clone; merging upstream updates |
| [`CODEBASE_GUIDE.md`](CODEBASE_GUIDE.md) | Reading the code |
| [`SESSION_HANDOVER.md`](SESSION_HANDOVER.md) | Current state and open work |
| [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) | Bundled third-party components |

---

## Credits

**[AsukaAzure/Linksi](https://github.com/AsukaAzure/Linksi)** is the original project and the source
of essentially everything that makes this app good. This fork is a personal extension of their work;
please star and support the upstream repository rather than this one.

This is a **private, personal build** — not a supported product, and not accepting contributions or
feature requests.

---

## License

MIT — see [LICENSE](LICENSE). The bundled site engine and FFmpeg are GPL-3.0 and keep their own
licences; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
