# LICENSE_REVIEW.md

Mandatory licence review for the Linksi Enhanced private project (specification section 5).

- **Review date**: 2026-10-09
- **Reviewed source**: `https://github.com/AsukaAzure/Linksi` cloned locally to `E:\\Deepseek\\Linksi\\repo`
- **Reviewed revision**: `0f4af65eb4fd87fcc77b79717071f975f7ccd122` (branch `master`, 115 commits)
- **Reviewer**: automated code-review process on behalf of the repository owner
- **Status**: **ACTION REQUIRED before any redistribution.** See section 3.

> This document records licence *facts and obligations*. It is not legal advice.

---

## 1. Exact licence name

| Question | Finding |
|---|---|
| Is there a `LICENSE`, `LICENSE.txt`, `COPYING` or `NOTICE` file? | **No.** |
| Was such a file ever committed and later deleted? | **No.** `git log --all --name-status --diff-filter=AD -- '*LICEN*' '*COPYING*' '*NOTICE*'` returns nothing. |
| What does the repository `README.md` state? | **MIT.** `README.md:9` carries the badge `License: MIT`; `README.md:211-213` states *"This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details."* |
| What does GitHub report? | `"license": null` from `https://api.github.com/repos/AsukaAzure/Linksi` — GitHub detects no licence file. |
| Per-file copyright headers? | **None** in any `.kt` source file. Only the Gradle wrapper scripts carry notices, and those are Gradle's own Apache-2.0 headers (`gradlew:4-16`, `gradlew.bat:2-14`). |

**Conclusion.** The project declares **MIT** in prose, but the canonical MIT licence text is
missing from the repository and the link `[LICENSE](LICENSE)` in the README is broken. This is the
single most important licensing fact about this codebase.

---

## 2. Copyright owner

| Question | Finding |
|---|---|
| Copyright owner | `AsukaAzure` (GitHub user id `147522771`), commits authored as `AsukaAzure <anushkumar128@gmail.com>` |
| Contribution history | 116 commits total: 102 by `AsukaAzure <anushkumar128@gmail.com>`, 14 by `Anush <147522771+AsukaAzure@users.noreply.github.com>` — the same person under a second identity |
| Third-party contributions | No other authors appear in `git shortlog -sne --all`, and there are no merge commits from outside contributors |
| First commit | `02b7475` "Simple Link saver with - folder - Share receiver - material 3 ui - fully local", 2026-04-26 |

A single copyright holder simplifies attribution, but it also means the missing licence text
cannot be reconstructed from a second contributor's fork.

---

## 3. Answers to the required questions

The specification asks for eleven specific determinations.

| # | Question | Answer |
|---|---|---|
| 1 | Exact licence name | **MIT, declared in `README.md` only.** No licence file exists. |
| 2 | Copyright owner | `AsukaAzure` (`anushkumar128@gmail.com`). |
| 3 | Is modification permitted? | Under the declared MIT terms: **yes**. As a matter of strict fact, without licence text in the repository the default is "all rights reserved", so this rests on the README declaration and should be confirmed. |
| 4 | Is private modification permitted? | **Yes.** Private modification and private use are not restricted by copyright in any case; nothing is distributed. |
| 5 | Is redistribution permitted? | Under the declared MIT terms: **yes, provided the copyright notice and the MIT licence text accompany the distribution** (MIT condition). Because the licence text is absent from the repo, **this notice is currently not satisfiable from the repository alone.** |
| 6 | Is commercial distribution permitted? | Under the declared MIT terms: **yes**, MIT is permissive and non-copyleft. |
| 7 | Attribution requirements | Retain the copyright notice and the permission notice (MIT). The app must not present Linksi as wholly original private work; the original project and author must be credited (specification section 5). |
| 8 | Source code disclosure requirements | **None.** MIT imposes no copyleft obligation; the enhanced sources need not be published. |
| 9 | Notice requirements | The MIT copyright + permission text must be included in distributions (APK/release bundles). This must therefore be reproduced in the app's about/licences surface and in the release assets. |
| 10 | Requirements for derivative works | The same MIT notice must be carried through to the derivative work. Derivative work is permitted. |
| 11 | Requirements for bundled dependencies | Each bundled dependency keeps its own licence; their notices must be preserved. See section 5. New dependencies (specification section 54) each require their own entry before adoption. |

---

## 4. Consequence for this project

The specification states (section 5):

> If no explicit license exists, stop before public redistribution and document that default
> copyright restrictions may apply.

The situation is a middle case: a licence is **declared** (MIT) but the **licence text is absent**.
The practical consequences are:

1. **Private development and private use: clear and unblocked.** Nothing in this review restricts
   building the enhanced app for personal use. This is what phase 1 does.
2. **Redistribution (including sharing an APK with friends, as the testing plan sections 101-103
   anticipate): blocked until the licence question is closed.** Distribution requires the MIT
   notice text, which the repository does not contain.
3. **Do not add a `LICENSE` file on the author's behalf without confirmation.** Writing our own MIT
   file with a guessed copyright line would misstate the author's grant. The correct move is to
   obtain the missing file from the author.

### Recommended resolution (in order of preference)

1. **Ask the maintainer** (`anushkumar128@gmail.com`, or open an issue at
   `https://github.com/AsukaAzure/Linksi/issues`) to commit the MIT `LICENSE` file the README
   already promises. One-line request, and it fixes the upstream repository too.
2. Check whether the licence was published elsewhere by the same author, for example on a Play
   Store listing, F-Droid, or a release page. Record the evidence if found.
3. If the author confirms MIT, add `LICENSE` containing the standard MIT text with
   `Copyright (c) 2026 AsukaAzure`, and record the confirmation (link/quote/date) in this file.
4. If the author does not respond and redistribution is still desired, redistribute only the
   MIT-implied minimum with an explicit attribution note, or keep the enhanced build private.

Until step 3 is complete, treat the project as **"MIT declared, licence text unverified"** and keep
distribution private.

---

## 5. Existing dependency licence situation

The licence of the baseline dependency set matters because the APK bundles their code. The
dependency coordinates are pinned in `app/build.gradle:68-133`. The table below records the
licence each coordinate *publishes*, which still has to be confirmed against the resolved graph
before a release.

| Dependency | Version | Declared licence | Obligation |
|---|---|---|---|
| `androidx.*` (core-ktx, lifecycle, activity-compose, navigation, room, datastore, biometric, work, hilt-navigation-compose, graphics-shapes) | mixed, pinned | Apache-2.0 | Notice + licence text |
| `androidx.compose:*` (BOM 2024.10.00, material3, animation, material-icons-extended) | 2024.10.00 | Apache-2.0 | Notice + licence text |
| `com.google.android.material:material` | 1.12.0 | Apache-2.0 | Notice + licence text |
| `com.google.dagger:hilt-android` + `hilt-android-compiler` | 2.52 | Apache-2.0 | Notice + licence text |
| `com.squareup.okhttp3:okhttp` | 4.12.0 | Apache-2.0 | Notice + licence text |
| `org.jsoup:jsoup` | 1.17.2 | MIT | Notice + licence text |
| `com.android.volley:volley` | 1.2.1 | Apache-2.0 | Notice + licence text |
| `io.coil-kt:coil-compose` | 2.6.0 | Apache-2.0 | Notice + licence text |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.7.3 | Apache-2.0 | Notice + licence text |
| `junit:junit` (test only, not shipped) | 4.13.2 | EPL-1.0 | Test-only, not distributed in the APK |
| `androidx.test.ext:junit`, `espresso-core` (androidTest only, not shipped) | pinned | Apache-2.0 | Test-only, not distributed in the APK |
| Gradle wrapper (`gradle-wrapper.jar`, `gradlew`, `gradlew.bat`) | 8.13 | Apache-2.0 | Keep the existing headers (already present) |

**Pending work.** A resolved-dependency licence scan (Gradle `dependencies` report plus a licence
plugin) has not been run because the Android SDK is not installed on this machine, so the Android
Gradle module cannot be configured. It is a prerequisite for `DEPENDENCY_REVIEW.md` and must be
run before the first shared release. The command is recorded in `CODE_REVIEW.md` section 8.

### Dependencies this project must NOT add without a licence review

The specification's downloader work (sections 15-19) will tempt the project towards
strongly-copyleft components. Recorded here as hard constraints:

- **yt-dlp**: Unlicense (public domain) — permissive, usable.
- **FFmpeg**: LGPL-2.1+ by default, **GPL-2.0+ if built with `--enable-gpl`**. A GPL FFmpeg build
  bundled into the APK would impose GPL obligations on the whole application. Use an LGPL build,
  or keep FFmpeg out of the APK, or use the optional server resolver (specification section 24).
- **Seal / YTDLnis and similar downloader apps**: mostly **GPL-3.0**. Reading them for patterns is
  fine; copying code into this app is not, unless the project accepts GPL-3.0 for the whole APK.
  The specification explicitly warns about this in section 17.

---

## 6. Required notices to preserve

1. Do not remove or alter the `gradlew` / `gradlew.bat` Apache-2.0 headers or
   `gradle/wrapper/gradle-wrapper.jar`.
2. Do not remove or alter any copyright notice found in a dependency.
3. Keep the original project credited in `README_ENHANCED.md` and in the app's own about surface,
   for example: *"Linksi Enhanced is a private extension of Linksi by AsukaAzure
   (https://github.com/AsukaAzure/Linksi), used under the MIT licence declared by that project."*
4. Never state or imply that the original Linksi code is wholly original private work
   (specification section 5).

---

## 7. Evidence trail

Commands used to establish the facts above, reproducible from the clone:

```bash
git -C E:\\Deepseek\\Linksi\\repo ls-files | grep -iE 'licen|copying|notice'      # no output
git -C E:\\Deepseek\\Linksi\\repo log --all --name-status --diff-filter=AD \
    -- '*LICEN*' '*COPYING*' '*NOTICE*'                                   # no output
git -C E:\\Deepseek\\Linksi\\repo shortlog -sne --all
git -C E:\\Deepseek\\Linksi\\repo log --reverse --format='%h %an <%ae> %ad %s' | head -3
```

```powershell
(Invoke-WebRequest https://api.github.com/repos/AsukaAzure/Linksi).Content | ConvertFrom-Json |
    Select-Object full_name, license
# license : (null)
```

`README.md:9` and `README.md:211-213` contain the MIT declaration quoted in section 1.
