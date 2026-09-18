# Android 16: detecting a link copy made in another app — problem statement

**Status:** unresolved at the platform level. Four channels investigated, all closed by measurement on a
real device. This document exists so the problem can be researched independently without repeating the
investigation.

**Written:** 2026-09-18 · **App:** Linksi Enhanced `3.1.1-enhanced.3` (targetSdk 36)
**Device:** OPPO `CPH2825` (marketing name not verified — the device only reports the model number),
ColorOS 16, **Android 16 / SDK 36**, arm64, not rooted
**Relevant code:** `app/src/main/java/com/linksi/app/enhanced/service/LinksiAccessibilityService.kt`

---

## 1. The problem in one paragraph

An accessibility service is enabled, **bound**, and receiving events. The app's own diagnostic confirms
the service is connected, the overlay permission is granted, notifications are granted, and all three
feature switches are on. When the user **long-presses a link in a browser or chat app and taps "Copy"**,
the app cannot obtain the copied URL, so it cannot show its detection bubble. When the user instead
**pastes** that link into the app, everything works. The asymmetry — copy fails, paste works — is the
whole of the problem.

## 2. Reproduction

1. Enable the app's accessibility service (confirmed bound — see §3).
2. Grant "display over other apps" and notifications.
3. Turn on all three switches: Smart link detection, Floating bubble, Accessibility assistance.
4. In **Brave** (or WhatsApp, or YouTube), long-press a link and tap **Copy**.
5. **Expected:** a bubble appears offering actions for the copied link.
   **Actual:** nothing happens.
6. Now paste the same link into any text field inside the app. **The bubble appears.**

## 3. What is *not* wrong (all verified on the device)

These were each checked directly, so a researcher does not need to re-check them:

| Check | Command | Result |
|---|---|---|
| Service enabled | `settings get secure enabled_accessibility_services` | the service is listed |
| Service **bound** | `dumpsys accessibility` → `Bound services:` | the service appears with all four event types |
| Not crashed | `dumpsys accessibility` → `Crashed services:{}` | empty |
| Overlay permission | `appops get <pkg> SYSTEM_ALERT_WINDOW` | `allow` |
| Notifications | `dumpsys package <pkg>` | `POST_NOTIFICATIONS: granted=true` |
| App process alive | `ps -A` | running |
| The bubble mechanism works | `logcat -s BubbleService:V` | `bubble shown as a TYPE_APPLICATION_OVERLAY window` — proven via other paths |

**The service is genuinely delivering events.** In the same logging window it recorded events from
`com.android.launcher` and `com.android.systemui` normally. So the failure is specific to what the
browser emits, not to the service.

## 4. The four channels, each measured

An accessibility service can learn about a copy from four places. All four were tested.

### 4.1 The event payload — no URL present

Events were logged at the entry point of `onAccessibilityEvent`, **before** any filtering, so nothing
could be discarded unseen. A copy in Brave produced:

```
LinksiDetect: arrived: VIEW_CLICKED pkg=com.brave.browser
```

and the full event stream around it:

```
WINDOW_CONTENT_CHANGED  pkg=com.brave.browser  verdict=NO_TEXT      (repeated)
WINDOW_CONTENT_CHANGED  pkg=com.brave.browser  verdict=NO_COPY_SIGNAL label="Web View"
VIEW_CLICKED            pkg=com.brave.browser  verdict=NO_TEXT
```

**Neither the selection event nor the click carries any text.** `event.text`, `event.contentDescription`
and `source.text` are all empty. There is no URL anywhere in the event stream, so no heuristic over those
events can succeed. On some runs **no selection event was delivered at all** — only the click.

### 4.2 The clipboard — refused to a background reader

The clipboard is where the copy lands, and it is the same source the working *paste* path reads. A
one-time probe at service connect asked the platform directly:

```
LinksiDetect: clipboard probe at connect: readable=false foundUrl=false
```

`ClipboardManager.hasPrimaryClip()`/`getPrimaryClip()` returned nothing usable. **Android does not permit
a background app to read the clipboard, and an accessibility service is not exempt.** This is a
platform-level restriction introduced in Android 10 and still present in 16.

### 4.3 A selection event carrying the text — not reliably delivered

Some platforms/apps do deliver `TYPE_VIEW_TEXT_SELECTION_CHANGED` with the selected text in `event.text`,
and when that happens detection works (verified: `SELECTION_REMEMBERED → ACCEPTED → bubble shown`). For
the Brave/WhatsApp gesture, the selection event either carries no text or is not delivered at all.

### 4.4 The clicked node's tree — searched, no link found

Because the click does carry a **node**, the context menu the user tapped should be in that node's tree.
The service walked the clicked node and its ancestors (up to 6 hops) and then each ancestor's subtree,
looking for a node whose label marks it as a copy affordance ("copy", "link", "clipboard") and — within
that subtree — an actionable URL. **No link was found.**

Two possibilities remain for a researcher to distinguish:

- the context menu is rendered in a **different window** (so it is not in `event.source`'s tree, and
  `event.source` may even be null for it);
- the menu items expose labels but the **link itself is not among them** (browsers often label the item
  "Copy link address" without putting the URL in the node).

`FLAG_RETRIEVE_INTERACTIVE_WINDOWS` is already set on this service
(`android:accessibilityFlags="flagDefault|flagRetrieveInteractiveWindows"`), and
`canRetrieveWindowContent="true"`.

## 5. Code positions relevant to a fix

| Concern | Location |
|---|---|
| Event entry point, all verdicts | `LinksiAccessibilityService.onAccessibilityEvent` |
| Where the event's text is read | `LinksiAccessibilityService.readEvent` |
| Clipboard read (currently gated on window focus for the panel path) | `enhanced/detect/ClipboardUrlReader.kt` |
| The detector's rules | `enhanced/detect/LikelyCopyDetector.kt` |
| Service configuration | `res/xml/accessibility_service_config.xml` |
| Debug-only diagnostics used for all measurements above | `CopyObservationLog`, `LabelSnippet` |

## 6. What is already known to work

| Path | Status |
|---|---|
| Paste a link into the app | **works** |
| Share a link to the app (share sheet) | **works** |
| Copy affordances that put the URL *in the event* (e.g. `ACTION_PROCESS_TEXT`) | **works** |
| Copy inside a browser / chat app | **not detectable** by any of the four channels above |

## 7. Specific questions for research

1. **Is there any supported way for an accessibility service to read the clipboard on Android 13–16 while
   it is not the foreground app?** If yes, under exactly which conditions (flags on the service, the
   `accessibilityFlags` value, a specific `AccessibilityServiceInfo` capability, an OEM exemption)?
2. **Does a browser's context menu appear as a separate `AccessibilityWindowInfo`?** If so, does
   `AccessibilityService.getWindows()` expose it, and do its nodes contain the link text or a
   `Spanned`/`URLSpan` that yields it? Note `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` is already enabled here.
3. **Can `AccessibilityNodeInfo.getTextSelectionStart()/getTextSelectionEnd()` be used on a WebView
   node** to recover the selection when `event.text` is empty? (Attempted here; no text was present to
   index into.)
4. **Is `ACTION_PROCESS_TEXT` / `ACTION_SEND` the only fully-supported route**, and is that the
   recommended design for this class of feature?
5. **Has any shipping app solved "detect a link copy from any app" on Android 12+?** If so, by what
   mechanism — and does it require root, an OEM partnership, or a privileged permission?
6. **Does the answer differ on stock Android** vs ColorOS/MIUI/One UI? This was measured on ColorOS 16
   only.

## 8. External references for prior art and platform policy

These are starting points, not verified claims — the measurements in §3–§4 are the primary evidence.

- Android 10 privacy/behaviour changes, which introduced the clipboard restriction:
  <https://developer.android.com/about/versions/10/privacy/changes>
- Android 10 release notes (AOSP):
  <https://source.android.com/docs/whatsnew/android-10-release>
- `AccessibilityService` API reference, including `getWindows()` and the interactive-windows flag:
  <https://developer.android.com/reference/android/accessibilityservice/AccessibilityService>
- `AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS`:
  <https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo#FLAG_RETRIEVE_INTERACTIVE_WINDOWS>
- A community report of exactly this asymmetry — a background accessibility service's clipboard read
  being denied, with no shell fallback: <https://github.com/kaeawc/auto-mobile/issues/2596>
- Community guides to the Android 10+ clipboard restriction (useful for policy wording, not for API
  detail): <https://github.com/harshdhamaniya/android-security-handbook>

## 9. Honest summary

The app's accessibility service works, is bound, and detects copy actions in the cases where the platform
gives it something to work with. For a copy made in a browser or chat app on this device it is given
nothing: no URL in the event, no clipboard access, no reliable selection event, and no link in the
clicked node's tree.

**This looks like a platform limit rather than an implementation defect**, but that conclusion rests on one
device and one OS version. The most valuable outcome of further research would be either (a) a supported
mechanism that was missed, or (b) authoritative confirmation that none exists, in which case the feature
should be documented as best-effort and the share/paste paths promoted as the reliable route.
