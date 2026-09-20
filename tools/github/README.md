# Publishing to GitHub

## Why there is Java tooling here

`git` and `curl` both fail to reach GitHub on this machine:

```
schannel: AcquireCredentialsHandle failed: SEC_E_NO_CREDENTIALS (0x8009030e)
```

That is a failure of Windows' TLS stack to obtain credentials, not an authentication problem — the token is
fine. The JVM has its own TLS implementation and works:

```powershell
& "$env:JAVA_HOME\bin\java.exe" tools\github\GhGet.java https://api.github.com/rate_limit <token>
# STATUS=200
```

So any GitHub API call is made through these three classes rather than through PowerShell or curl.

## The one-line fix for git itself

Git for Windows can use OpenSSL instead of schannel, which makes `git push` work normally:

```powershell
git config http.sslBackend openssl
```

This is set in the local repo config. If `git ls-remote` or `git push` ever fails with the schannel error
above, that setting is missing.

## Tooling

| Class | Purpose |
|---|---|
| `GhGet.java` | Any GET. Prints `STATUS=<code>` then the body. |
| `GhApi.java` | Any method with an optional JSON body file: `GhApi <METHOD> <url> <token> [bodyFile]`. Pass the literal `null` as `bodyFile` for a bodyless call such as `DELETE .../assets/<id>`. |
| `GhUpload.java` | Uploads one release asset (binary body): `GhUpload <uploadUrl> <token> <file> <name> [contentType]`. Pass the **bare** `upload_url` and let this class add `?name=`; it appends only when the URL has no `name=` already. |

> **Trap, hit once for real:** passing `<uploadUrl>?name=<name>` to `GhUpload` makes it construct
> `?name=<name>?name=<name>`, and GitHub does not reject that — it stores the whole string as the
> literal filename, so the assets upload "successfully" under names like
> `app.apk.name.app.apk`. The release looks populated but every download link is wrong. Verify with
> the assets endpoint after any upload, and check the `name` field, not just the status code.

## The full publish sequence

```powershell
$token = (Get-Content 'E:\Deepseek\Linksi\keys\github-token.txt' -Raw).Trim()
$env:JAVA_HOME='E:\Deepseek\Linksi\toolchain\jdk-17'
$repo = 'Sadloif/linksi-enhanced'

# 1. Push the code (openssl backend required, see above)
git push origin HEAD:enhanced/integration

# 2. Build and verify the signed release
powershell -File tools\build-release.ps1 -SkipChecks
# then re-hash and check the signature, as BUILD_AND_RELEASE.md describes

# 3. Create the release
& "$env:JAVA_HOME\bin\java.exe" tools\github\GhApi.java POST `
    "https://api.github.com/repos/$repo/releases" $token <release.json>

# 4. Upload the assets to the release's upload_url
$up = "https://uploads.github.com/repos/$repo/releases/<releaseId>/assets"
& "$env:JAVA_HOME\bin\java.exe" tools\github\GhUpload.java $up $token <apk> <name> `
    'application/vnd.android.package-archive'
```

## Verifying an upload

The releases API reports a `digest` field per asset, computed by GitHub. Compare it against the local
`Get-FileHash` value — that proves the bytes arrived intact without downloading them back:

```powershell
& "$env:JAVA_HOME\bin\java.exe" tools\github\GhGet.java `
    "https://api.github.com/repos/$repo/releases/<releaseId>" $token
# look for "digest":"sha256:<hex>" per asset
```

A matching digest proves the *bytes*, not the *name*. Check the bundle of names as well, because a
mangled name is the failure that actually happened here:

```powershell
$out = (& "$env:JAVA_HOME\bin\java.exe" tools\github\GhGet.java `
    "https://api.github.com/repos/$repo/releases/<releaseId>/assets" $token) -join ''
[regex]::Matches($out, '"name":"([^"]+)"') | ForEach-Object { $_.Groups[1].Value }
```

Every name should be exactly what you intended, with no `.name.` inside it.

## Notes

- The token lives at `E:\Deepseek\Linksi\keys\github-token.txt`, **outside the repository**, and that
  directory is never committed.
- `git remote -v` embeds the token in `origin`'s URL, which is what makes a plain `git push` work without a
  credential helper (the sandbox blocks the named pipes git's helpers need). Be aware it is therefore
  readable in `.git/config`.
- Upstream (`AsukaAzure/Linksi`) has its push URL deliberately disabled. Never push there.
