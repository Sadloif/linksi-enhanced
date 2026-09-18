# OPPO Reno field issues — plan

Reported 2026-09-18 against `LinksiEnhanced_3.1.1-enhanced.3_arm64-v8a.apk` (the signed release) on an
OPPO Reno, ColorOS 16, with the user's own words:

1. *"with all the access enabled to linksi, copy, accessibility, its not picking up links copied, nor the
   bubble appears"*
2. *"when I open linksi and add link, I can't see option to see the enhancement features, add download,
   url cleaner option there"*
3. *"No previews, I am seeing only links, no thumbnails"*
4. *"if I share to linksi, I also dont get these enhanced features"*

---

## What the code actually does (established by reading it, not assumed)

| Finding | Where |
|---|---|
| Detection requires **two** switches on, not one: `SMART_LINK_DETECTION` **and** `ACCESSIBILITY_ASSISTANCE` | `LinksiAccessibilityService.isEnabled()` |
| The bubble needs a **third** gate plus overlay: `SMART_LINK_DETECTION`, `FLOATING_BUBBLE`, and `SYSTEM_ALERT_WINDOW` | `SmartLinkDetector.resolveRun()` |
| A **4-second** rate limit suppresses repeat bubbles | `MIN_INTERVAL_BETWEEN_BUBBLES_MS` |
| The accessibility service **contains no `Log` call at all**, by deliberate privacy design | its class comment: *"nothing is ever logged: this class contains no `Log` call"* |
| The detector computes a precise reason (`SMART_DETECTION_OFF`, `BUBBLE_OFF`, `OVERLAY_PERMISSION_MISSING`, `RATE_LIMITED`, `ERROR`) and exposes it as `state` | `SmartDetectionState.Reason` |
| Manifest is **correct** for Android 14+/ColorOS: `FOREGROUND_SERVICE_SPECIAL_USE`, the mandatory `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`, `SYSTEM_ALERT_WINDOW`, `POST_NOTIFICATIONS`, `BIND_ACCESSIBILITY_SERVICE` | `AndroidManifest.xml` |
| Enhanced features are reachable **only** from Settings → Enhanced features | `SettingsScreen.kt:191` |
| Link cards render **only** `link.faviconUrl`; the stored `previewImageUrl` (og:image) is fetched and saved but **never displayed** in the list | `LinkCards.kt:241` |
| `ShareReceiverActivity` already fetches metadata and images, but offers no route into the enhanced actions | `ShareReceiverActivity.kt` |

So issue 2 is a discoverability gap, issue 3 is a rendering gap, and issue 1 is an **environmental
failure that the code is structurally unable to explain** — which is itself the defect worth fixing
first, because everything else about issue 1 is guesswork until something can be observed.

---

## Work plan

### A. Make the OPPO failure observable, then fix what it shows

The blocker is not a missing feature; it is that nothing can be seen when detection fails. Two changes,
both privacy-preserving:

1. **A diagnostics status row in Enhanced features** that reads the detector's own `state` and the
   platform's real permission state, and says *why* nothing is happening in plain words:
   service bound or not, overlay granted or not, each switch on or off, notifications granted or not.
   Today the user is told nothing and the only recourse is guessing.
2. **A user-visible "last detection" signal** — the detector already records `BubbleRequested` /
   `BubbleShown` / `Disabled(reason)`; surface it so a copy can be confirmed end to end without logcat.

Deliberately **not** doing: adding `Log` calls to the accessibility service. Its no-logging promise is a
documented privacy commitment, and the fix belongs in user-visible status, not in the log buffer.

### B. Force a real copy to verify detection on the device

Once the OPPO is authorized: set the clipboard from adb with a real link, watch the diagnostics row and
logcat, and confirm whether the service receives events at all. If ColorOS is killing the FGS, that will
show as the bubble service not running.

### C. Surface the enhanced features where links are added

An **Enhanced actions** card in `AddLinkSheet`: Download (runs the real extractor and opens the quick
panel), Clean URL (cleans the field in place, showing what was removed), and a route into Enhanced
settings. Plus a "Clean URLs when saving" toggle so the cleaner applies on save.

### D. Render real thumbnails

Use the already-stored `previewImageUrl` as the card image, with the favicon as the fallback and a
neutral placeholder when neither is available. This is a display change only — the data is already
being fetched and persisted.

### E. Enhanced features from the share sheet

`ShareReceiverActivity` already fetches metadata; add the same enhanced actions so a share can be
downloaded or cleaned without saving first.

---

## Verification standard

Same as every other round in this project: unit tests for pure logic, instrumented tests for anything
touching the platform, and a real device run for the claim. Nothing here is "done" on the strength of
compiling.

## Open question for the owner

The OPPO currently reports `unauthorized` over adb, and the user cannot see the authorization prompt.
Section A does not depend on that — the diagnostics row is worth having regardless — but section B does,
and B is what turns issue 1 from a guess into a finding.
