# Linksi Enhanced

A private, enhanced build of [Linksi](https://github.com/AsukaAzure/Linksi) — an offline-first
Android link manager — extended with optional modules for URL cleaning, link detection, and media
downloading.

> **Private build.** Not for redistribution until the licence question in
> [LICENSE_REVIEW.md](LICENSE_REVIEW.md) is closed: upstream *declares* MIT in its README but has
> never committed a `LICENSE` file, so the notice text a redistributed build must carry does not
> exist in the repository. Private use is unaffected.

---

## 1. What this private version adds

| Added | State |
|---|---|
| **Deterministic URL cleaner** — strips known tracking parameters, never lowercases a URL | Done, 82 unit tests |
| **Fixed whole-URL lowercasing** — `normalizeUrl` used to destroy path/query case on every save | Done, verified on device |
| **"Clean URLs when saving"** setting, default on | Done |
| **"Clean URL" / "Copy clean URL"** actions in the link options sheet | Done |
| **Enhanced module architecture** — capabilities, media source/format/extractor abstraction, download state machine, filename sanitiser, URL text extractor, server-resolver contract | Done, 278 unit tests |
| **Direct-file downloader** — images, PDFs, audio, video, archives, with progress, cancel and Range resume | Done (arm64/x86_64 device tier) |
| **Smart link detection** — optional accessibility-assisted detection of likely copy actions | Done |
| **Floating Linksi bubble** — optional overlay prompt, tap to act | Done |
| **Quick action panel** — context-sensitive actions plus a clean-URL preview | Done |
| **Optional private server resolver** — fallback when the device cannot resolve media | Done (client; server not built) |
| **Enhanced Features settings screen** — one place to enable/disable each optional module | Done |
| **CI workflow, release tooling, signed release APK** | Done |
| **Site-specific extractors** (Instagram, Facebook, TikTok, Pinterest, Reddit) | **Not implemented** — see §8 |

---

## 2. What original Linksi features are preserved

Everything. The baseline is treated as a working product and is not rewritten:

link saving, folders, nested folders, tags, notes, favourites, read/unread, pinning, search,
filtering, sorting, trash with 30-day retention, bulk move/delete, reminders, expiry, import/export
(JSON, CSV, Netscape bookmarks), the built-in browser, AI categorisation, app lock, folder lock,
biometrics, screenshot protection, themes, dark mode and grid/list layouts.

The database schema is unchanged (still version 12), `applicationId` is unchanged, and no
destructive migration was added. Where the baseline has defects, they are documented in
[CODE_REVIEW.md](CODE_REVIEW.md) rather than silently "fixed" — with two deliberate exceptions: the
whole-URL lowercasing (which the specification explicitly forbids) and a `MissingPermission` lint
failure in the new download notifications.

---

## 3. Optional feature overview

Every enhanced module is **off by default** except URL cleaning, and each can be switched off in
**Settings → Enhanced features**. Core Linksi never depends on any of them: with the accessibility
service off, the overlay permission denied, the downloader unused and the server unset, saving,
sharing, searching and organising work exactly as before.

---

## 4. Smart link detection

Detects *likely* copy actions and offers to act on them. It is event driven and deliberately weak:
Android does not allow background clipboard reading, so the app never polls the clipboard, never
watches it, and never reads it in the background at all.

The flow is the one Android permits:

```
you copy a link
      ↓
accessibility event suggests a copy happened
      ↓
a small bubble appears
      ↓
you tap it  →  Linksi gains focus
      ↓
only now is the clipboard read
      ↓
if it holds an HTTP(S) URL → quick action panel
otherwise                  → "No valid URL detected."
```

Detection is rate limited (at most one bubble every few seconds), and non-URL clipboard content is
discarded immediately and never stored or logged.

---

## 5. Accessibility permission

Accessibility is **optional** and the app says so in plain language before you enable it:

> Linksi can optionally use Android Accessibility services to detect likely link-related actions and
> show its quick action bubble. This feature is optional. Standard sharing and saving work without
> it. Linksi never reads password fields, never stores screen contents and never sends accessibility
> data anywhere.

Concretely, the service refuses password fields before reading their text, honours an ignore list
for chosen apps, does not request key-event filtering, and is never declared as an accessibility
tool. Nothing is uploaded anywhere.

---

## 6. Floating bubble

A small draggable overlay that appears when a link is detected. It is non-focusable, sized to the
bubble itself so it cannot swallow touches meant for other apps, snaps to the left or right edge,
and auto-dismisses. It needs the "display over other apps" permission, and if that permission is
missing the bubble simply does not appear — the app never crashes and never nags.

---

## 7. URL cleaner

Removes only **known** tracking parameters: `utm_*` (whole family), `fbclid`, `gclid`, `dclid`,
`msclkid`, `igshid`, `mc_cid`, `mc_eid`, `yclid`, `_ga`, `_gl`, plus `referral_source`,
`surface_type` and `in_reels_tab_context` **on Facebook hosts only**.

It deliberately does **not**:

- lowercase the URL (only the scheme is case folded),
- touch unknown parameters — `?id=42` and `?customImportantValue=ABC123` survive,
- corrupt percent-encoding, international domains or fragments,
- rewrite anything when it cannot parse the input (`cleanOrSelf` returns the original, so a cleaner
  failure can never lose a link).

Worked example, from the specification:

```
in : https://www.facebook.com/reel/1710485373378939/?referral_source=external_link&surface_type=tab&in_reels_tab_context=TRUE
out: https://www.facebook.com/reel/1710485373378939
```

---

## 8. Downloader

The downloader is deliberately split into tiers, because the honest constraint here is licensing:
**every Android yt-dlp wrapper is GPL-3.0, and so is the FFmpeg they bundle** — linking one would
relicense this entire app. yt-dlp itself is Unlicense. See
[DEPENDENCY_REVIEW.md](DEPENDENCY_REVIEW.md) for the primary-sourced analysis.

| Tier | State |
|---|---|
| **Direct files** — images, PDFs, audio, video, archives, detected from `Content-Type` | **Implemented**, with a single "Original quality" format, resumable transfer, progress, cancel and MediaStore output |
| **Site-specific extraction** (Instagram, Facebook, TikTok, Pinterest, Reddit) | **Not implemented.** Requires an extractor dependency decision (permissive MIT wrapper, or the optional server resolver, or accepting GPL-3.0 for private use only) |
| **Private server resolver** | **Implemented client**; the server itself is not part of this repository |

Downloads run through WorkManager with a `dataSync` foreground service, write into the public
Downloads collection on Android 10+ (no storage permission required) and into app-specific storage
below that, and post throttled progress/completion notifications. A download failure disables only
the download action: saving, opening, sharing and copying the link all keep working.

---

## 9. Supported websites

- **Direct file URLs** — any site, when the URL points at a file (`image/*`, `video/*`, `audio/*`,
  `application/pdf`, archives). This covers Pinterest image pins, Reddit images, direct MP4s, PDFs
  and similar.
- **Instagram, Facebook, TikTok, Pinterest video, Reddit video** — depend on the extractor tier
  described in §8 and are **not enabled in this build**.
- **Anything else** — not promised. The app never claims support it cannot deliver: a URL that
  cannot be resolved shows "This URL is not supported for downloading." and the link is still saved
  normally.

---

## 10. Server fallback

Optional, off by default, and unusable unless you configure an **HTTPS** address. When enabled and
local extraction fails, the link's URL — and nothing else — is sent to your server, which returns
media information. No clipboard contents, no screen contents, no saved links, no credentials. The
access token travels in the `Authorization` header rather than the URL. Linksi keeps working if the
server is offline.

---

## 11. Android compatibility

| | |
|---|---|
| Minimum | Android 8.0 (API 26) — unchanged from upstream |
| Compile SDK | 36 (build-time only) |
| Target SDK | 34 in this build; the move to 36 is a separate, tested change (predictive back and edge-to-edge become mandatory there) |
| Verified on | **Android 16 (API 36), x86_64 emulator** — app installs, launches, and the on-device flow tests pass |
| Not yet verified | any physical device, including the intended OPPO/ColorOS target; older Android versions; other manufacturers |

---

## 12. Known limitations

Everything here is documented rather than hidden. See [TEST_REPORT.md](TEST_REPORT.md) §9.5 for the
full list.

- **No physical-device testing has happened.** All device evidence is from one Android 16 emulator.
- **No site-specific extractors** (see §8), so Instagram/Facebook/TikTok downloads are unavailable.
- **The bubble's Android 14+/15 background-activity launch is unverified** on hardware; it is
  guarded and fails silently, with a notification fallback as the known remedy.
- **Reminders are broken in upstream Linksi** and were not fixed here: the scheduling stubs are
  empty and `POST_NOTIFICATIONS` is never requested. See CODE_REVIEW.md finding 4.
- **The quick action panel is reachable from the bubble** (via its own activity) but the reusable
  panel composable is not yet mounted on the main screens.
- **MediaStore download resume is not implemented** (app-specific storage resume is).
- **A malformed shared text can still be saved as a link** — the upstream share-receiver fallback is
  unchanged; `UrlTextExtractor` exists to fix it and is used by the new modules only.
- **Untranslated strings**: the app ships English plus partial Spanish/Russian/Chinese translations,
  and new strings fall back to English.

---

## 13. Privacy behaviour

- **URL cleaning happens entirely on the device.** No network call is involved.
- **The clipboard is read only after you tap something**, never in the background, and only the URL
  is kept — everything else is discarded immediately.
- **Accessibility data never leaves the device.** Password fields are refused before their contents
  are read; screen text is never logged.
- **The downloader contacts only the URL you asked it to download** (plus, on Android, the system
  MediaStore).
- **The server resolver sends only the URL**, only when you enable it, and only over HTTPS.
- **Unchanged upstream behaviour worth knowing**: link metadata is fetched from the sites you save
  (and, for social links, through a hard-coded third-party scraper), and every saved domain is
  disclosed to Google's favicon service. Saved AI API keys and the app PIN are stored unencrypted in
  app-private storage with `allowBackup="true"`. These are upstream behaviours documented in
  CODE_REVIEW.md, not additions.

---

## Documentation

| File | Contents |
|---|---|
| [CODE_REVIEW.md](CODE_REVIEW.md) | Baseline architecture, verified feature inventory, 25 ranked findings |
| [LICENSE_REVIEW.md](LICENSE_REVIEW.md) | Licence facts and the redistribution blocker |
| [DEPENDENCY_REVIEW.md](DEPENDENCY_REVIEW.md) | Per-dependency licence/capability review, including the GPL findings |
| [BUILD_AND_RELEASE.md](BUILD_AND_RELEASE.md) | Reproducible build, signing and release instructions |
| [UPSTREAM_UPDATE_GUIDE.md](UPSTREAM_UPDATE_GUIDE.md) | How to review and integrate future upstream changes |
| [TEST_REPORT.md](TEST_REPORT.md) | What was tested, how, and what was not |
| [CHANGELOG.md](CHANGELOG.md) | Change history |

## Credit

Linksi Enhanced is a private extension of **Linksi** by
[AsukaAzure](https://github.com/AsukaAzure/Linksi), used under the MIT licence that project
declares. The original application, its architecture and all baseline functionality are that
author's work.
