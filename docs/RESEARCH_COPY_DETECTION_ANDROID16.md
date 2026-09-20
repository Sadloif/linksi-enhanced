# Android 16: detecting a link copy made in another app — problem statement

**Status:** partially answered. Two of the four channels are **closed by the platform** (event payload,
clipboard). A third — Chromium's link metadata — is **proven reachable but not yet usable**, and §10
records exactly why. This document exists so the remaining question can be researched without repeating
the investigation.

**Written:** 2026-09-18, **updated** after an external review returned the findings in §10
**App:** Linksi Enhanced `3.1.1-enhanced.3` (targetSdk 36)
**Device:** OPPO the OPPO test device (marketing name not verified — the device only reports the model number),
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

---

## 10. UPDATE — the external review's suggestions, tested on the device

An external review identified two things this investigation had missed. **Both were correct, and both were
then measured on the OPPO.** One produced a real mechanism; the other exposed an open problem.

### 10.1 Confirmed: Chromium exposes link targets in node *extras*

The review's key claim was that Chromium stores a link's destination under
`AccessibilityNodeInfo.targetUrl` in the node's extras bundle, where `node.text` inspection cannot see it.
**Confirmed.** A window dump taken while a Brave context menu was open:

```
WINDOWS[selection pkg=com.brave.browser] #2 type=1 active=true focused=true
  WebView  extras=[…, AccessibilityNodeInfo.chromeRole, …]
  View  desc="Brave logo" clickable=true
      extras=[…, AccessibilityNodeInfo.targetUrl, …]  targetUrl=PRESENT(len=25)
  Image desc="Brave logo"
      extras=[…, AccessibilityNodeInfo.targetUrl, …]  targetUrl=PRESENT(len=88)
```

Two genuine hrefs (25 and 88 characters) that no amount of text inspection would ever have found. **The
earlier conclusion that "the clicked node's tree contains no link" was therefore wrong** — it was a search
through the wrong field.

### 10.2 Confirmed: `getWindows()` is required, not `rootInActiveWindow`

The review predicted the context menu would be a **separate `AccessibilityWindowInfo`**. The dump supports
this, and it caught the same mistake made twice in this investigation:

- the dump that **found** `targetUrl` used `AccessibilityService.getWindows()`;
- the search that followed used `rootInActiveWindow` — and reported `no targetUrl extras found` while the
  dump in the very same run was printing them.

Also confirmed as a real config gap: `flagRetrieveInteractiveWindows` is documented as requiring
`typeWindowsChanged`, which this service **never subscribed to**, even though it set the flag. Fixed.

Node budget also had to rise from 400 to 3000: the WebView sits deep inside Chromium's view hierarchy, so
400 nodes was exhausted before reaching page content.

### 10.3 NOT solved: which link was pressed

This is the open problem, and it is why the mechanism is not yet a working feature. When the scan did find
links, it found **fifteen** of them on one page:

```
scan: windows=4 visited=262 nodes, targetUrl nodes=15, touch=false
```

`touch=false` means the long-press event carried **no bounds**, so there was no touch point to match
against. With fifteen candidates and three ranking strategies (touch containment, live selection, "only
one candidate"), none applied, so the code correctly **declined to guess** rather than offer a wrong link.

Two further complications measured:

- the `targetUrl` extras are populated **inconsistently** between runs — sometimes 15 nodes, sometimes the
  whole tree is 39 nodes with none (`no targetUrl extras found`);
- the long-press must be on the correct element to produce them at all.

### 10.4 The review's answers that change the design

| Question | Answer | Consequence |
|---|---|---|
| Background clipboard read for an accessibility service? | **No.** CTS requires `READ_CLIPBOARD_IN_BACKGROUND`, available only to privileged system apps | The clipboard fallback is permanently dead; the code comment now says so |
| Can `getTextSelectionStart/End` recover text absent from the node? | **No.** They are offsets into that node's existing text | A dead end, not a bug |
| Have shipping apps solved this? | Only via IME, temporary focus, Shizuku/ADB, root, or explicit handoff. Retrace reportedly uses "display over other apps" to **briefly take focus** and then read the clipboard | **A genuine alternative this app has not tried** — see §10.5 |
| Does stock Android differ from ColorOS? | The clipboard rule is platform-wide; OEMs differ mainly in the accessibility observation layer | Testing on a Pixel would test the observation layer, not the clipboard rule |

### 10.5 The one mechanism still untried, and its cost

The review notes that Retrace appears to combine an accessibility *signal* with a **brief, focusable
overlay window** in order to satisfy the ordinary "currently has focus" clipboard condition.

This app already has `SYSTEM_ALERT_WINDOW`. Theoretically it could, on a copy-shaped signal, show a
tiny focusable overlay for a few milliseconds, read the clipboard legitimately, and dismiss it.

Two honest objections, which is why it has not been implemented:

1. **It steals input focus.** Taking focus mid-interaction can dismiss the very context menu whose Copy
   the user was reaching for, and could disturb typing. That makes it a race against the user.
2. **It is a focus-stealing trick to obtain data the platform deliberately restricts.** Even where it
   technically satisfies the letter of the rule, it is against the spirit of the Android 10 change, and it
   would be hard to describe honestly in a privacy disclosure.

It is recorded here so the decision is visible rather than silently avoided. It is the last known option.

### 10.6 Recommended position

Given the above, the defensible product decision is:

- **Share to Linksi** — primary, fully supported, already implemented.
- **`ACTION_PROCESS_TEXT`** — additional route for selectable text, verified working on this device.
- **Paste inside Linksi** — verified working.
- **Accessibility detection** — best-effort convenience, documented as such in the app itself, rather than
  presented as a guarantee.

A correct assessment of the feature is that Linksi notices *many* copy actions, not all, and that Android
does not inform an app of every one. That should be said in the UI so the feature never looks broken.
