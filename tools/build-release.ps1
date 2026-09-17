<#
.SYNOPSIS
    Builds, tests and archives a signed Linksi Enhanced release APK.

.DESCRIPTION
    Runs the unit tests and lint first, then assembles the release APK with the private signing key,
    copies it to a release directory with the naming convention the specification asks for, and
    writes a SHA256 sidecar plus a small machine-readable build record.

    The signing password is read from the credentials file written when the keystore was generated
    and is passed to Gradle through environment variables. It is never printed and never echoed.

.PARAMETER KeystorePath
    The .jks file. Defaults to E:\Deepseek\keys\linksi-enhanced-release.jks.

.PARAMETER CredentialsFile
    File produced alongside the keystore, containing the alias and passwords.

.PARAMETER OutDir
    Where the APK and checksum are archived. Defaults to E:\Deepseek\release-artifacts.

.PARAMETER SkipChecks
    Skip tests and lint (use only for a quick rebuild; a release should not be produced this way).

.EXAMPLE
    powershell -File .\tools\build-release.ps1
#>
[CmdletBinding()]
param(
    [string]$RepoRoot,
    [string]$KeystorePath = 'E:\Deepseek\keys\linksi-enhanced-release.jks',
    [string]$CredentialsFile = 'E:\Deepseek\keys\KEYSTORE_CREDENTIALS.txt',
    [string]$OutDir = 'E:\Deepseek\release-artifacts',
    [switch]$SkipChecks
)

$ErrorActionPreference = 'Stop'

if (-not $RepoRoot) { $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path }
$RepoRoot = (Resolve-Path $RepoRoot).Path

# ── signing material ──────────────────────────────────────────────────────────
if (-not (Test-Path $KeystorePath)) { throw "keystore not found: $KeystorePath" }
if (-not (Test-Path $CredentialsFile)) { throw "credentials file not found: $CredentialsFile" }

$credentials = @{}
foreach ($line in Get-Content $CredentialsFile) {
    if ($line -match '^\s*([A-Za-z]+)\s*:\s*(.+?)\s*$') { $credentials[$matches[1].ToLower()] = $matches[2] }
}
$storePassword = $credentials['storepass']
$keyAlias = $credentials['alias']
$keyPassword = if ($credentials.ContainsKey('keypass')) { $credentials['keypass'] } else { $storePassword }
if (-not $storePassword -or -not $keyAlias) { throw "could not read storepass/alias from $CredentialsFile" }

# ── version identity ──────────────────────────────────────────────────────────
$buildGradle = Get-Content (Join-Path $RepoRoot 'app\build.gradle') -Raw
$versionName = if ($buildGradle -match 'versionName\s+"([^"]+)"') { $matches[1] } else { 'unknown' }
$versionCode = if ($buildGradle -match 'versionCode\s+(\d+)') { $matches[1] } else { 'unknown' }
$commit = (git -C $RepoRoot rev-parse HEAD).Trim()
$branch = (git -C $RepoRoot rev-parse --abbrev-ref HEAD).Trim()
$dirty = if ((git -C $RepoRoot status --porcelain)) { 'yes' } else { 'no' }

Write-Host "=== Linksi Enhanced release build ==="
Write-Host "  branch      : $branch"
Write-Host "  commit      : $commit"
Write-Host "  versionName : $versionName"
Write-Host "  versionCode : $versionCode"
Write-Host "  uncommitted : $dirty"
Write-Host ""

# ── environment ───────────────────────────────────────────────────────────────
$env:JAVA_HOME = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'E:\Deepseek\jdk-17' }
if (-not $env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = 'E:\Deepseek\.gradle-home-main' }
if (-not $env:GRADLE_OPTS) { $env:GRADLE_OPTS = "-Djava.io.tmpdir=$((Split-Path $RepoRoot -Parent))\.tmp" }
if (-not $env:DEBUG_KEYSTORE_PATH) { $env:DEBUG_KEYSTORE_PATH = Join-Path (Split-Path $KeystorePath -Parent) 'debug.keystore' }
$env:KEYSTORE_PATH = $KeystorePath
$env:KEYSTORE_PASSWORD = $storePassword
$env:KEY_ALIAS = $keyAlias
$env:KEY_PASSWORD = $keyPassword

$ErrorActionPreference = 'Continue'
$tasks = @()
if (-not $SkipChecks) { $tasks += ':app:testDebugUnitTest', ':app:lintDebug' }
$tasks += ':app:assembleRelease'

$log = Join-Path $RepoRoot 'build-release.log'
& (Join-Path $RepoRoot 'gradlew.bat') -p $RepoRoot @tasks --console=plain --no-watch-fs 2>&1 |
    Tee-Object -FilePath $log | Select-Object -Last 8
$code = $LASTEXITCODE

if ($code -ne 0) {
    Write-Host "BUILD FAILED - see $log"
    exit $code
}

# ── archive ───────────────────────────────────────────────────────────────────
$apk = Get-ChildItem (Join-Path $RepoRoot 'app\build\outputs\apk\release') -Filter *.apk -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notlike '*unsigned*' } | Select-Object -First 1
if (-not $apk) { throw "no signed release APK found under app\build\outputs\apk\release" }

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$safeVersion = $versionName -replace '[^A-Za-z0-9._-]', '_'
$target = Join-Path $OutDir "LinksiEnhanced_${safeVersion}_universal.apk"
Copy-Item $apk.FullName $target -Force

$hash = (Get-FileHash $target -Algorithm SHA256).Hash
$size = (Get-Item $target).Length
$hash | Set-Content "$target.sha256" -Encoding ASCII

$record = [ordered]@{
    builtAtUtc   = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    branch       = $branch
    commit       = $commit
    uncommitted  = $dirty
    versionName  = $versionName
    versionCode  = $versionCode
    apk          = (Split-Path $target -Leaf)
    sizeBytes    = $size
    sha256       = $hash
    signingAlias = $keyAlias
}
$record | ConvertTo-Json | Set-Content (Join-Path $OutDir 'latest-build.json') -Encoding UTF8

Write-Host ""
Write-Host "=== ARCHIVED ==="
Write-Host "  apk    : $target"
Write-Host ("  size   : {0} bytes ({1:N2} MB)" -f $size, ($size / 1MB))
Write-Host "  sha256 : $hash"
Write-Host "  record : $(Join-Path $OutDir 'latest-build.json')"

# Do not leave signing material in the environment of the caller's shell.
Remove-Item Env:KEYSTORE_PASSWORD, Env:KEY_PASSWORD -ErrorAction SilentlyContinue
