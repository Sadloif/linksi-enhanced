# UPSTREAM_UPDATE_GUIDE.md

How to review and integrate upstream Linksi changes into this enhanced fork
(specification section 55).

> **Read §11 first if your only question is "how do I update from upstream?".** It records the
> 2026-09-18 merge of upstream 3.2.0 — the exact commands, the two files that conflicted, and how they
> were resolved — plus a condensed checklist. Sections 1-10 are the full review methodology behind it.
>
> **Branch naming note.** This guide was first written when the integration branch was called `master`.
> It is now **`enhanced/integration`**, which is also the repository's default branch. Every command
> below has been rewritten accordingly, so use `enhanced/integration` literally. `upstream/main` is
> different: that is upstream's own branch name and is unchanged.

- **Document date**: 2026-09-20 (merge history in §11; methodology written 2026-09-17)
- **Upstream**: `https://github.com/AsukaAzure/Linksi` (public, MIT-declared — see
  [LICENSE_REVIEW.md](docs/LICENSE_REVIEW.md))
- **Remote name in this repository**: **`upstream`**, fetch URL
  `https://github.com/AsukaAzure/Linksi.git`, **push URL deliberately disabled** to the literal string
  `DISABLED-do-not-push-to-upstream`
- **Integration branch / default branch**: **`enhanced/integration`** (upstream's own default is
  `master`)
- **`origin`**: `https://github.com/Sadloif/linksi-enhanced` — this fork is **public** since
  2026-09-20. The credential is embedded in the remote URL so that a plain `git push` works without a
  credential helper (the sandbox blocks the named pipes git helpers use); `.git/config` is never
  committed. For anything outside `git` itself, use the JVM helpers in `tools/github/` — `git` and
  `curl` cannot reach GitHub from this machine on the Windows TLS stack.
- **Verified against**: `git remote -v` and the merge records in §11, 2026-09-20

---

## 1. The rule this document exists to enforce

**Never merge upstream unreviewed.** Upstream is the codebase this fork was born from and it is
where the bugs and the destructive migration came from (`CODE_REVIEW.md` §2.11, §4). An upstream
commit is a *proposal*, not an improvement. It must be read, classified and tested before it becomes
part of this fork.

**Never push to `upstream`.** The push URL is disabled precisely so that a mistaken
`git push upstream` fails instead of publishing an unattributed private change to the original
author's repository. Do not re-enable it. Do not `git remote set-url --push upstream …`. If a future
agent believes it needs to contribute back upstream, that is a **separate, deliberate, human-approved
act** — open a fork and a pull request from *there*, never from this repository's remote.

Three more rules with concrete failure modes behind them:

- **Never cherry-pick or merge without reading the diff.** `git log --oneline upstream/main` is not
  a review.
- **A database-version bump upstream can collide with private migrations.** The baseline schema is
  Room version **12** (`LinksDatabase.kt:9-13`), and the migration chain `MIGRATION_1_2` …
  `MIGRATION_11_12` is hand-written. If upstream ships its own `version = 13`, and this fork also
  migrates to 13, the two are different schemas wearing the same number: users upgrading either
  way get a silently wrong database. See section 7.
- **Never import upstream CI, signing, or release configuration blindly.** The upstream workflow
  (`.github/workflows/android.yml`) decodes a keystore from `secrets.RELEASE_KEYSTORE_BASE64` and
  builds a release; this fork's signing and release rules are in
  [BUILD_AND_RELEASE.md](BUILD_AND_RELEASE.md), and the two must not drift.

---

## 2. Before starting

```powershell
# 0. Be in the private fork, on a clean tree
Set-Location <repo-parent>\repo        # or wherever this fork is checked out
git status                              # must be clean
git branch --show-current               # must be enhanced/integration (or a release branch), not a feature branch

# 1. Confirm the remotes are as expected
git remote -v
# upstream  https://github.com/AsukaAzure/Linksi.git (fetch)
# upstream  DISABLED-do-not-push-to-upstream (push)
```

Checklist before any integration:

- [ ] working tree clean, or changes stashed and understood;
- [ ] `upstream` push URL is still `DISABLED-do-not-push-to-upstream` (if it is not, restore it);
- [ ] you know the last upstream commit already integrated (the review commit or `CHANGELOG.md`);
- [ ] you have a JDK 17+ and the Android SDK as described in
  [BUILD_AND_RELEASE.md](BUILD_AND_RELEASE.md) — a review that cannot run tests is a partial review
  and must say so.

---

## 3. Create the update branch

Branch naming is fixed: **`update/linksi-YYYY-MM`** (year and month of the review).

```powershell
$month = (Get-Date -Format 'yyyy-MM')
git switch -c "update/linksi-$month" enhanced/integration
```

One update branch per review. Do not reuse an old one, and do not do the review on `enhanced/integration`: the
review's value is that it can be thrown away.

---

## 4. Fetch and see what is new

```powershell
git fetch upstream --prune --tags

# What has upstream got that we do not?
git log --oneline enhanced/integration..upstream/main

# …and the inverse: what private work is not upstream?
git log --oneline upstream/main..enhanced/integration

# How far have the histories diverged?
git rev-list --left-right --count enhanced/integration...upstream/main
```

If `git log enhanced/integration..upstream/main` is empty, upstream has nothing new: record that and stop. Do not
create an empty merge commit.

Useful detail commands:

```powershell
git log --stat enhanced/integration..upstream/main                 # files touched per commit
git diff --stat enhanced/integration...upstream/main               # net difference from the fork point
git log --merges enhanced/integration..upstream/main               # merges upstream took from elsewhere
git log --format='%h %ad %an %s' --date=short enhanced/integration..upstream/main
```

---

## 5. The ten-point review report

Produce this as a written report (a file in the update branch, or a PR description) **before**
integrating anything. It is the deliverable of the review; the merge is a consequence of it.

1. **New features** — what upstream added, in user-visible terms. Name the commits and files. If a
   feature overlaps something private, say so explicitly.
2. **Bug fixes** — each fix, the defect it addresses (with a `file:line` reference into the baseline
   where possible), and whether this fork has the same defect. Compare against the ranked findings in
   `CODE_REVIEW.md` §4 — a fix that closes one of those findings is a priority; a fix that touches a
   file this fork has rewritten is a conflict risk.
3. **Security fixes** — anything touching the plaintext PIN, the AI API keys, `FLAG_SECURE`,
   `allowBackup`, the exported `ShareReceiverActivity`, cleartext traffic, network security config,
   or logging of URLs. Treat every security change as high priority **and** high conflict risk.
4. **Android compatibility changes** — `compileSdk`/`targetSdk`/`minSdk` bumps, `enableEdgeToEdge`,
   `enableOnBackInvokedCallback` (predictive back), foreground-service types, storage/MediaStore,
   notification permission, and anything touching 16 KB page-size alignment. Cross-check against
   `ANDROID16_REQUIREMENTS.md` and the open gaps in
   [BUILD_AND_RELEASE.md](BUILD_AND_RELEASE.md) §5.
5. **Database changes** — any `@Database(version = …)` bump, new `Migration`, changed entity,
   changed DAO SQL or changed index. **State the upstream version number and this fork's version
   number side by side.** This is the single most dangerous category (section 7). Note that
   `exportSchema = false` means there are no schema JSON files to diff against.
6. **Dependency changes** — every change to `app/build.gradle`, `build.gradle` or `settings.gradle`:
   added/removed/bumped coordinates, new repositories, new plugins. Each new dependency must be put
   through [DEPENDENCY_REVIEW.md](docs/DEPENDENCY_REVIEW.md) before it is accepted — including the licence
   question, not just the version.
7. **Files modified** — the complete list (`git diff --name-status enhanced/integration...upstream/main`), split into:
   files this fork has also modified, files this fork has added, and files only upstream changed.
8. **Conflicts with private modifications** — for every file in both sets, describe the actual
   competing edits, not just the filename. The expected hot spots are the URL logic
   (`UrlCleaner.kt`, `UrlNormalizer.kt`, `MetadataFetcher.kt`), the save path
   (`HomeViewModel.kt`, `LinkRepository.kt`), settings (`DataStoreExtensions.kt`,
   `SettingsViewModel.kt`, `SettingsScreen.kt`), and `app/build.gradle`.
9. **Recommended commits** — the specific upstream commits worth integrating, in order, each with a
   one-line justification. Prefer many small cherry-picks over one large merge when the commits are
   independent.
10. **Changes to skip** — what must **not** be integrated, and why. Typical reasons: it would redo
    work this fork already did differently, it imports the destructive `MIGRATION_11_12` pattern, it
    changes `applicationId`, it re-enables a removed permission, or it asserts behaviour in the
    README that the code does not implement.

A minimal report skeleton (a template to copy, not part of this document's own outline):

````markdown
# Upstream update review — linski-YYYY-MM

- Upstream commits reviewed: <first>..<last> (<n> commits)
- Local branch: update/linksi-YYYY-MM   Base: <local commit>
- Reviewer / date:
- Overall recommendation: integrate all / integrate selected / skip

Sections, in this exact order:

1. New features
2. Bug fixes
3. Security fixes
4. Android compatibility changes
5. Database changes
   upstream @Database version = ?   local @Database version = 12   collision: yes/no
6. Dependency changes
7. Files modified
8. Conflicts with private modifications
9. Recommended commits        (ordered list, with hashes)
10. Changes to skip           (with reasons)

Verification performed: <commands run and results — or "not run, because …">
````

---

## 6. Cherry-pick or merge?

| Situation | Action |
|---|---|
| A small number of independent fixes, and the histories have diverged a lot | **Cherry-pick** each recommended commit (`git cherry-pick -x <sha>`); `-x` records the upstream hash in the message so provenance survives |
| Upstream restructured a whole subsystem this fork has not touched | **Merge** `upstream/main` into the update branch and review the merge result |
| Upstream changed files this fork rewrote (URL logic, save path, settings) | Cherry-pick file by file, or hand-port the change, and say so in the report |
| Anything in the "changes to skip" list | Do not take it; if it arrived inside a merge, revert it in a **separate** commit with a message saying why |
| Upstream only reformatted/renamed | Skip, unless it also fixes a ranked finding |

Cherry-picking:

```powershell
git cherry-pick -x <sha>            # repeat per recommended commit
git show --stat HEAD                # confirm what actually landed
```

Merging:

```powershell
git merge --no-ff --no-commit upstream/main   # stop before committing
git status                                    # inspect the conflict set
git diff --cached                             # inspect the resolved result
# resolve, then:
git commit
```

Prefer one commit per upstream change where the changes are independent: it keeps `git blame` and
future reviews honest. Never squash a review of several unrelated fixes into a single "sync
upstream" commit.

---

## 7. Conflict resolution, with the known traps

Resolve conflicts by understanding both sides, then re-running the tests. Specific rules:

1. **`app/build.gradle`.** This fork's version is the one that matters: `versionCode` must never
   decrease (baseline 20), and the signing configuration is environment-driven
   ([BUILD_AND_RELEASE.md](BUILD_AND_RELEASE.md) §3). Take upstream's *dependencies* only after
   running each through [DEPENDENCY_REVIEW.md](docs/DEPENDENCY_REVIEW.md); never take upstream's
   `compileSdk`/`targetSdk`/`versionCode`/`applicationId` mechanically.
2. **`AndroidManifest.xml`.** Keep this fork's permission set and the exported/intent-filter shape of
   `ShareReceiverActivity` (`CODE_REVIEW.md` §8.5). Verify any upstream manifest change against
   `ANDROID16_REQUIREMENTS.md` — for example a new foreground
   service needs its `foregroundServiceType` and type-specific permission, and a re-added
   `USE_EXACT_ALARM` is a Play-policy problem.
3. **Database version collision — the trap.** If upstream bumps `@Database(version = 13)` with its own
   `MIGRATION_12_13` while this fork also plans a 13:
   - do **not** merge the two migration chains under the same version number;
   - renumber this fork's migration to 14 and add `MIGRATION_13_14`, so the chain is
     `12 → 13 (upstream) → 14 (private)`, or keep this fork at 13 and take upstream's migration only
     if it is byte-equivalent in effect;
   - write the decision into the report and into [CHANGELOG.md](docs/CHANGELOG.md);
   - test both upgrade paths (a fresh install *and* an upgrade from 12) — `exportSchema = false`
     (`LinksDatabase.kt:12`) means Room's automatic migration test helper needs schema export enabled
     first.
4. **The URL logic.** This fork's `normalizeUrl` no longer lowercases whole URLs and the new
   `UrlCleaner` is wired behind the `auto_clean_urls` setting. Upstream code that reintroduces
   lowercasing, unconditional `http://`→`https://`, trailing-slash stripping or schemeless prefixing
   must be **rejected**, however it is phrased.
5. **README and documentation.** Upstream's README advertises features that do not exist
   (`CODE_REVIEW.md` §4, "Contradictions between the README and the code"). Never propagate those
   claims into this fork's docs; when in doubt, describe only what the code does.
6. **CI workflows.** `.github/workflows/android.yml` is upstream's and is left untouched here;
   this fork's build is `.github/workflows/android-build.yml`. Resolve CI conflicts in favour of
   keeping both files as they are.
7. After resolving, re-run the verification in section 8. A conflict resolved "by inspection" is not
   resolved.

---

## 8. Verify — run the tests, then build

Run these on the update branch, after the cherry-picks/merge and before merging to `enhanced/integration`:

```powershell
$env:JAVA_HOME        = 'C:\Program Files\JetBrains\PyCharm Community Edition 2024.2.4\jbr'
$env:ANDROID_HOME     = '<repo-parent>\toolchain\android-sdk'
$env:GRADLE_USER_HOME = '<repo-parent>\local\.gradle-home'

# the whole module's unit tests
.\gradlew.bat clean test

# the fast URL-only harness (no Android SDK needed), which parses JUnit XML rather than trusting Gradle
powershell -File .\tools\run-urlcleaner-tests.ps1

# static analysis
.\gradlew.bat lint

# the actual APK, debug first (side-by-side install, no keystore needed)
.\gradlew.bat assembleDebug
```

Then, only if the release is intended:

```powershell
# signed (see BUILD_AND_RELEASE.md §3 for the environment variables)
.\gradlew.bat assembleRelease
Get-FileHash .\app\build\outputs\apk\release\app-release.apk -Algorithm SHA256

# install and smoke-test on a device/emulator
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

Record **what was actually run and what the result was**, including failures. If the SDK, a device
or a keystore is unavailable, say so plainly — an upstream update reviewed without a build is a
paper review, and [TEST_REPORT.md](TEST_REPORT.md) is the model for recording that honestly.

Minimum gate before merging an update branch to `enhanced/integration`:

- [ ] `gradlew test` passes (or the failure is pre-existing and identified as such);
- [ ] the URL harness reports `tests=82 failures=0 errors=0`;
- [ ] `assembleDebug` produces an APK;
- [ ] any database-affecting change has a stated migration plan and both upgrade paths tested;
- [ ] no new dependency entered the build without a [DEPENDENCY_REVIEW.md](docs/DEPENDENCY_REVIEW.md) entry;
- [ ] the ten-point report exists and its "changes to skip" list was honoured.

---

## 9. Merge to `enhanced/integration`

```powershell
# final review of the branch as a whole
git log --oneline enhanced/integration..HEAD
git diff --stat enhanced/integration...HEAD

git switch enhanced/integration
git merge --no-ff "update/linksi-$month"
git log --oneline -1                     # confirm the merge commit
```

Then:

1. update [CHANGELOG.md](docs/CHANGELOG.md) — what upstream changes were taken, what was skipped, and why;
2. update [DEPENDENCY_REVIEW.md](docs/DEPENDENCY_REVIEW.md) if any dependency changed;
3. keep the report (it is the evidence trail for the next review);
4. delete the update branch only after the report is committed somewhere durable;
5. bump `versionCode` if a release follows ([BUILD_AND_RELEASE.md](BUILD_AND_RELEASE.md) §5).

---

## 10. Quick reference

```powershell
# the whole workflow, condensed
$month = (Get-Date -Format 'yyyy-MM')
git switch -c "update/linksi-$month" enhanced/integration
git fetch upstream --prune --tags
git log --oneline enhanced/integration..upstream/main              # what is new
git log --oneline upstream/main..enhanced/integration              # what is private
git diff --stat enhanced/integration...upstream/main               # the net difference
# ... write the ten-point report BEFORE integrating ...
git cherry-pick -x <sha>                           # or: git merge --no-ff --no-commit upstream/main
.\gradlew.bat clean test
powershell -File .\tools\run-urlcleaner-tests.ps1
.\gradlew.bat lint
.\gradlew.bat assembleDebug
git switch enhanced/integration
git merge --no-ff "update/linksi-$month"
```

**Do not, under any circumstances**: push to `upstream`; merge upstream without the report; take a
database version bump without resolving the collision; or let upstream's README claims into this
fork's documentation.

---

## 11. The 2026-09-18 merge of upstream 3.2.0 — what actually happened

This is the only upstream integration performed so far, and it is the best available evidence of what
to expect. Everything here was measured, not predicted.

### 11.1 The operation

```powershell
git fetch upstream --prune --tags          # upstream/master was at 8725910
git log --oneline enhanced/integration..upstream/master
#   f8caa5e  Fix: Metadata fetching from instagram
#   8725910  Add: Added metadata refresh progress
#   34fe852  Fix: Fixed button behaviour
#   ac9693d  Fix: Fixed abnormal behavior when deleting a link
#   24207cb  Fix: Fixed child folder links appearance

git switch -c update/linksi-2026-09 enhanced/integration
git merge --no-ff upstream/master          # a merge, deliberately - see 11.4
```

**A merge, not a rebase.** This fork carries 87 commits upstream does not have (it was 72 at merge
time; the rest came from the release, documentation and CI work that followed). A rebase would rewrite
them and force a force-push of a published branch. Merge, then resolve.

### 11.2 What it touched, and what actually conflicted

The merge commit is **`2270690`** (parents `b4be6c7` + `8725910`). It changed **9 files**:

| File | Outcome |
|---|---|
| `app/build.gradle` | **CONFLICT** — resolved by hand (§11.3) |
| `app/src/main/java/com/linksi/app/utils/MetadataFetcher.kt` | **CONFLICT** — resolved by hand (§11.3) |
| `app/src/main/java/com/linksi/app/data/db/Daos.kt` | clean merge |
| `app/src/main/java/com/linksi/app/ui/screens/HomeScreen.kt` | clean merge |
| `app/src/main/java/com/linksi/app/ui/screens/HomeViewModel.kt` | clean merge |
| `app/src/main/java/com/linksi/app/utils/LinkResolvers.kt` | clean merge (upstream added it, 446 lines) |
| `app/src/main/res/values{,-es,-ru,-zh}/strings.xml` | clean merge |

**Only two files conflicted**, and both are predictable. The lesson: a 5-commit upstream release may
touch many files, but the conflicts concentrate in exactly the two places this fork has deliberately
diverged — version metadata, and duplicated helper functions.

### 11.3 The two resolutions, and the reasoning to reuse

**1. `app/build.gradle` — version metadata only.**

| | targetSdk | versionCode | versionName |
|---|---|---|---|
| Upstream 3.2.0 | 34 | 21 | `3.2.0` |
| This fork before the merge | 36 | 23 | `3.1.1-enhanced.3` |
| **Resolution** | **36** (fork wins) | **24** | **`3.2.0-enhanced.1`** |

- `targetSdk` stays **36**: that is this fork's baseline, and taking 34 would be a regression.
- `versionName` takes upstream's `3.2.0` but keeps the `-enhanced` marker.
- `versionCode` **must always increase past anything already installed** — 23 was on a physical phone,
  and an equal or lower `versionCode` cannot install over it. That is why it became 24.

**Reuse this logic next time:** fork wins on `targetSdk` and the `-enhanced` marker, upstream wins on
the version number, and `versionCode` is bumped past the highest installed one.

**2. `MetadataFetcher.kt` — upstream added three helpers this fork had already moved.**

Upstream's commit added `extractDomain`, `isValidUrl` and `normalizeUrl`. This fork had already moved
them to `UrlNormalizer.kt:22/52/64`. Taking upstream's copies would have been a duplicate-definition
compile error, so **the fork's side was kept and upstream's helpers are absent**. Upstream's other
change in that file — removing its hosted scraper API in favour of per-domain resolvers — merged
cleanly, and the moved helpers still resolve.

**Reuse this logic next time:** when upstream re-adds something this fork relocated, keep the fork's
location and drop upstream's duplicate. Grep for the symbol before resolving, rather than accepting
either side wholesale.

### 11.4 Verification that was actually run

```powershell
.\gradlew.bat :app:compileDebugKotlin      # BUILD SUCCESSFUL
.\gradlew.bat :app:testDebugUnitTest       # 782 tests, 0 failures
git grep -n '<<<<<<<' -- app/src          # no conflict markers anywhere
```

A release was then built, signed and published as `v3.2.0-enhanced.1`, and installed in place on the
OPPO over `v3.1.1-enhanced.3` **with the user database intact** — which is the real proof that the
merge did not disturb the schema.

**Database check:** `LinksDatabase.kt` is still `version = 12` after the merge. Upstream 3.2.0 did not
bump it, so the collision described in §1/§7 did not arise this time. **Check this every time** — it
is the one conflict that fails silently rather than loudly.

### 11.5 The condensed recipe for next time

```powershell
Set-Location <repo-parent>\repo
git status                                  # must be clean
git remote get-url --push upstream          # must print DISABLED-do-not-push-to-upstream
git fetch upstream --prune --tags

$month = (Get-Date -Format 'yyyy-MM')
git switch -c "update/linksi-$month" enhanced/integration

git log  --oneline enhanced/integration..upstream/master    # what is new
git diff --stat    enhanced/integration...upstream/master   # the net difference
#   -> if empty, record that and stop; do not create an empty merge commit
#   -> write the ten-point report (§5) BEFORE integrating

git merge --no-ff upstream/master           # expect conflicts in app/build.gradle and one utils file
#   resolve per §11.3: fork wins on targetSdk/marker, versionCode bumps past installed,
#   upstream's re-added duplicates are dropped in favour of this fork's location

.\gradlew.bat :app:testDebugUnitTest        # expect 782+ tests, 0 failures
.\gradlew.bat lint
git grep -n '<<<<<<<' -- app/src            # must return nothing
#   check app/src/main/java/com/linksi/app/data/db/LinksDatabase.kt version number

git switch enhanced/integration
git merge --no-ff "update/linksi-$month"
git push origin HEAD:enhanced/integration
```

Then, because this fork publishes releases: bump `versionCode` past the highest installed value,
rebuild the signed release, and publish it as a **new** release tag keeping the previous one for
rollback. See [BUILD_AND_RELEASE.md](BUILD_AND_RELEASE.md) and
[RECOVERY_AND_UPSTREAM.md](RECOVERY_AND_UPSTREAM.md).

### 11.6 What to append here after the next merge

Keep this section current; it is the cheapest possible handover. After integrating upstream again,
add: the upstream commit range taken, the merge commit hash, the files that conflicted, how each was
resolved and why, the exact verification commands and their results, and whether
`LinksDatabase.kt`'s version changed.
