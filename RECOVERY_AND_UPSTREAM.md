# Recovering and updating this project

Two questions answered by measurement, not assumption: what happens if the working folder is lost, and
how to take updates from the original project.

Tested 2026-09-18 by cloning the published repository into a scratch folder and building it there.

---

## 1. If the local folder is deleted, is git enough?

**For the code: yes. For building: almost — four things live outside git and must be kept.**

### What a fresh clone gives you

| Check | Result |
|---|---|
| Commits | **187** |
| Tags | **24** (including `baseline-linksi-original`, `v1.0`–`v3.1.1`) |
| Branches | **22** (all `feature/*`, `chore/*`, `master`, `enhanced/integration`, and the `backup/pre-replace-20260918/*` restore points) |
| `:app:compileDebugKotlin` from the clone | **BUILD SUCCESSFUL in 2m 25s** |
| Signed release build from the clone | **BUILD SUCCESSFUL** |

So the entire source history, every branch and every tag is recoverable with one command — see §3.

### What a fresh clone is MISSING

These are gitignored or never in the repository, and a clone does not have them:

| Missing | Why it matters | How to restore |
|---|---|---|
| **`app/debug.keystore` → actually `<repo-parent>\keys\debug.keystore`** | **This is the trap.** It signed the debug APK installed on the phone. Lose it and a rebuilt app **cannot update in place** — Android refuses a differently-signed package over the installed one, so the app must be uninstalled first, losing its data | **Keep a copy of `<repo-parent>\keys\` somewhere else entirely** |
| **`keys/linksi-enhanced-release.jks`** | Signs the published release. Lose it and you can never update the published APK in place again | Same backup |
| `keys/KEYSTORE_CREDENTIALS.txt` | Store password, key alias, key password | Same backup |
| `keys/github-token.txt` | Publishing | Regenerate from GitHub if lost |
| `local.properties` | Holds `sdk.dir` pointing at the Android SDK. Gitignored because it is machine-specific | Recreate one line: `sdk.dir=E\:\\Deepseek\\Linksi\\toolchain\\android-sdk` |
| `<repo-parent>\toolchain\` (JDK 17, Android SDK) | Not in git at all — it is a toolchain, not source | Reinstall, or copy |
| `<repo-parent>\local\.gradle-home-main\` | Gradle caches. Not required, but a rebuild re-downloads dependencies without it | Optional |

**The one item that would actually hurt is the keystore folder.** Everything else is rebuildable or
re-installable; a signing key is not. Back up `<repo-parent>\keys\` now.

### One environment setting a fresh clone needs

`git` cannot reach GitHub on this machine until the OpenSSL TLS backend is selected — it is set in the
existing repo's local config, which a clone does **not** inherit:

```powershell
git config http.sslBackend openssl
```

Without it, every `git clone`, `fetch` and `push` fails with
`schannel: AcquireCredentialsHandle failed: SEC_E_NO_CREDENTIALS`.

### The environment variables the build needs

```powershell
$env:JAVA_HOME='<repo-parent>\toolchain\jdk-17'
$env:GRADLE_USER_HOME='<repo-parent>\local\.gradle-home-main'
$env:GRADLE_OPTS='-Djava.io.tmpdir=<repo-parent>\local\.tmp -Dkotlin.compiler.execution.strategy=in-process'
$env:DEBUG_KEYSTORE_PATH='<repo-parent>\keys\debug.keystore'
$env:ANDROID_HOME='<repo-parent>\toolchain\android-sdk'
```

---

## 2. Rebuilds are NOT byte-reproducible, and the published digests are the record

Measured, from the same source commit, three builds produced three different digests:

| Build | arm64 SHA-256 |
|---|---|
| Published release `v3.1.1-enhanced.3` | `2FFBF5EC3A484F5C749CA4F7BE08F6C8BDAA7B5F81C7F37F2C1ED26A30527B33` |
| Rebuilt in the checkout | `75D56178067914771BF167583017A9F14AEFD48CD1A7DC1B0C9DE32B61F6C6D7` |
| Rebuilt in a fresh clone | `207F8AC96A5BF9199BB53FB34CCB94E08A61A4E9A35A5C57BF4495CFB9AFD5F1` |

Absolute build paths feed into the APK, so the same source never yields the same bytes twice. **This is a
known limitation, not a fault to chase.** Its consequence:

> Do not compare a local rebuild's digest against a published one and conclude something is wrong. Compare
> a local rebuild only against the `.sha256` file sitting beside it, which is what proves the artifact was
> not corrupted in transit.

The published release assets are the authoritative bytes for anything installed from GitHub.

---

## 3. Restoring from scratch

```powershell
# 1. Get git working
git config --global http.sslBackend openssl

# 2. Clone (the repo is private, so a token is needed)
git clone https://<token>@github.com/Sadloif/linksi-enhanced.git repo
cd repo

# 3. Restore what git does not carry — see the table in §1
#    local.properties, and the whole keys\ folder

# 4. Set the environment variables above, then build
powershell -File tools\build-release.ps1 -SkipChecks
```

---

## 4. Taking updates from the original project

The original project is the `upstream` remote: **`https://github.com/AsukaAzure/Linksi.git`**. Its push URL
is deliberately disabled so nothing can be sent there by accident.

### Where the two have diverged

As of 2026-09-18, from the common ancestor `0f4af65` (tag `baseline-linksi-original`):

| Direction | Count |
|---|---|
| Commits upstream has that this fork does not | **5** |
| Commits this fork has that upstream does not | **72** |

The five upstream commits:

```
8725910  Add: Added Metadata refresh progress
34fe852  Fix: Fixed button behaviour
ac9693d  Fix: Fixed abnormal behavior when deleting a link
24207cb  Fix: Fixed child folder links appearance
f8caa5e  Fix: Metadata fetching from instagram
```

### These five were merged on 2026-09-18

Merge commit `2270690`, **two conflicts**, both resolved deliberately. Recorded here because the next
merge will hit the same two.

**1. `app/build.gradle` — version metadata only.**

| | Upstream | This fork | Resolution |
|---|---|---|---|
| `targetSdk` | 34 | 36 | **36** — 34 would be a regression |
| `versionCode` | 21 | 23 | **24** — 23 is installed on a phone, and an equal versionCode cannot update over it |
| `versionName` | `3.2.0` | `3.1.1-enhanced.3` | **`3.2.0-enhanced.1`** — upstream's number with this fork's marker |

**2. `MetadataFetcher.kt` — upstream added three helpers this fork had already moved.**

`extractDomain`, `isValidUrl` and `normalizeUrl` live in `UrlNormalizer.kt` here. Taking upstream's copies
would be a **duplicate-definition error**, so this fork's side was kept and upstream's helpers are absent
from that file. Upstream's larger refactor of `MetadataFetcher` — which removed its hosted scraper API in
favour of per-domain resolvers in the new `LinkResolvers.kt` — merged cleanly around it.

**Take-away for next time:** expect exactly these two files to conflict. Everything else merged
automatically, including `HomeScreen.kt`, `HomeViewModel.kt` and `strings.xml`.

### How to bring them in

```powershell
cd <repo-parent>\repo
git fetch upstream
git log --oneline HEAD..upstream/master     # see what is new before touching anything
```

Then choose one:

**Option A — merge (safer, keeps both histories):**

```powershell
git checkout enhanced/integration
git merge upstream/master
# resolve conflicts, then build and test
```

**Option B — rebase this fork on top of upstream (linear history):**

```powershell
git checkout enhanced/integration
git rebase upstream/master
```

Option B rewrites this fork's 72 commits. Because the branch is already published, a rebase would require
a force-push and would break anyone who had cloned it — acceptable for a single-owner private repo, but it
is a rewrite, so prefer Option A unless linear history matters.

**Option C — take one commit only.** Often best, since not every upstream change is wanted:

```powershell
git cherry-pick f8caa5e     # e.g. just the Instagram metadata fix
```

### Before merging, know what will conflict

This fork touched files upstream also touches. The likely conflict points, from the changed-file lists:

- `app/build.gradle` — versionCode, dependencies
- `README.md` — the licence section was rewritten here
- anything under `app/src/main/java/com/linksi/app/ui/screens/` — the enhanced work added rows to
  `SettingsScreen`, `HomeScreen`, `AddLinkSheet`
- `MetadataFetcher` — upstream's `f8caa5e` fixes Instagram fetching there, and this fork's cleaner depends
  on metadata too

### After any merge

1. `:app:testDebugUnitTest` — expect **782 tests, 0 failures** before the merge, more after.
2. `lintDebug` — expect 0 errors.
3. Run the instrumented suites on a device; the merge touches UI.
4. Rebuild the release and re-verify digests *before* republishing.

### A standing recommendation

The fork carries 72 commits upstream does not have, and upstream is actively developed. Each merge gets
harder as that number grows. If upstream changes are wanted, take them **in small batches, regularly**
rather than in one large merge later.
