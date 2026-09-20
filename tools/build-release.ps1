<#
.SYNOPSIS
    Builds, tests and archives a signed Linksi Enhanced release APK.

.DESCRIPTION
    Runs the unit tests and lint first, then assembles the release APK with the private signing key,
    copies it to a release directory with the naming convention the specification asks for, and
    writes a SHA256 sidecar plus a small machine-readable build record.

    The signing password is read from the credentials file written when the keystore was generated
    and is passed to Gradle through environment variables. It is never printed and never echoed.

.PARAMETER KeyDir
    Directory holding the signing material, **outside the repository**. Defaults to `<repo>\..\keys`,
    i.e. a `keys` directory beside the checkout. Pass it explicitly if yours lives elsewhere.

.PARAMETER KeystorePath
    The .jks file. Defaults to `<KeyDir>\linksi-enhanced-release.jks`.

.PARAMETER CredentialsFile
    File produced alongside the keystore, containing the alias and passwords.
    Defaults to `<KeyDir>\KEYSTORE_CREDENTIALS.txt`.

.PARAMETER OutDir
    Where the APK and checksum are archived. Defaults to `<repo>\..\artifacts\releases`.

.PARAMETER SkipChecks
    Skip tests and lint (use only for a quick rebuild; a release should not be produced this way).

.EXAMPLE
    powershell -File .\tools\build-release.ps1
#>
[CmdletBinding()]
param(
    [string]$RepoRoot,
    [string]$KeyDir,
    [string]$KeystorePath,
    [string]$CredentialsFile,
    [string]$OutDir,
    [switch]$SkipChecks
)

$ErrorActionPreference = 'Stop'

if (-not $RepoRoot) { $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path }
$RepoRoot = (Resolve-Path $RepoRoot).Path

# Signing material and release archives live beside the checkout, never inside it. These used to be
# absolute paths for one machine; deriving them means the script works from any clone, and publishing
# the repository no longer discloses where one particular machine kept its keystore.
if (-not $KeyDir) {
    $KeyDir = Join-Path (Split-Path $RepoRoot -Parent) 'keys'
    if (-not (Test-Path $KeyDir)) { $KeyDir = Join-Path $RepoRoot 'keys' }
}
if (-not $KeystorePath)    { $KeystorePath    = Join-Path $KeyDir 'linksi-enhanced-release.jks' }
if (-not $CredentialsFile) { $CredentialsFile = Join-Path $KeyDir 'KEYSTORE_CREDENTIALS.txt' }
if (-not $OutDir)          { $OutDir          = Join-Path (Split-Path $RepoRoot -Parent) 'artifacts\releases' }

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
# The project keeps its toolchain and caches in sibling folders of the checkout, under the Linksi
# umbrella (repo\, toolchain\, local\, keys\, artifacts\). Every path is derived so the layout can
# move without editing this script.
$umbrella = Split-Path $RepoRoot -Parent
if (-not $env:JAVA_HOME) {
    $toolchainJdk = Join-Path $umbrella 'toolchain\jdk-17'
    # Fall back to whatever JDK is on PATH rather than a hardcoded machine-specific directory.
    if (Test-Path (Join-Path $toolchainJdk 'bin\java.exe')) {
        $env:JAVA_HOME = $toolchainJdk
    } else {
        $javaCmd = Get-Command java -ErrorAction SilentlyContinue
        if ($javaCmd) { $env:JAVA_HOME = Split-Path (Split-Path $javaCmd.Source -Parent) -Parent }
    }
    if (-not $env:JAVA_HOME) { throw 'JAVA_HOME is not set and no JDK was found on PATH.' }
}
if (-not $env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = Join-Path $umbrella 'local\.gradle-home-main' }
if (-not $env:GRADLE_OPTS) {
    $tmpDir = Join-Path $umbrella 'local\.tmp'
    New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null
    $env:GRADLE_OPTS = "-Djava.io.tmpdir=$tmpDir"
}
if (-not $env:DEBUG_KEYSTORE_PATH) { $env:DEBUG_KEYSTORE_PATH = Join-Path (Split-Path $KeystorePath -Parent) 'debug.keystore' }
$env:KEYSTORE_PATH = $KeystorePath
$env:KEYSTORE_PASSWORD = $storePassword
$env:KEY_ALIAS = $keyAlias
$env:KEY_PASSWORD = $keyPassword

$ErrorActionPreference = 'Continue'
$log = Join-Path $RepoRoot 'build-release.log'

function Invoke-Gradle([string[]]$Tasks) {
    Write-Host ("=== gradlew {0} ===" -f ($Tasks -join ' '))
    # Everything goes to the log file, and to the host (not to the output stream, which would
    # otherwise be captured as this function's return value alongside the exit code).
    & (Join-Path $RepoRoot 'gradlew.bat') -p $RepoRoot @Tasks --console=plain --no-watch-fs 2>&1 |
        Tee-Object -FilePath $log -Append |
        ForEach-Object { Write-Host $_ }
    return $LASTEXITCODE
}

$code = 0
if (-not $SkipChecks) {
    # Tests and lint run in their own invocation, separate from assembleRelease. In a single
    # invocation lint's debug analysis can reach for release-variant KSP output that has not been
    # generated yet and dies with "Unexpected failure during lint analysis ... Hilt_MainActivity.java
    # (The system cannot find the path specified)".
    $code = Invoke-Gradle @(':app:testDebugUnitTest', ':app:lintDebug')
}

if ($code -eq 0) {
    $code = Invoke-Gradle @(':app:assembleRelease')
}

if ($code -ne 0) {
    Write-Host "BUILD FAILED - see $log"
    exit $code
}

# ── archive ───────────────────────────────────────────────────────────────────
# The downloader's ABI splits mean assembleRelease emits one APK per ABI plus a universal one, so
# archive every signed artifact rather than guessing which single file is "the" APK.
$apks = @(Get-ChildItem (Join-Path $RepoRoot 'app\build\outputs\apk\release') -Filter *.apk -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notlike '*unsigned*' })
if (-not $apks) { throw "no signed release APK found under app\build\outputs\apk\release" }

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$safeVersion = $versionName -replace '[^A-Za-z0-9._-]', '_'

function Get-AbiLabel([string]$fileName) {
    # app-arm64-v8a-release.apk -> arm64-v8a ; app-universal-release.apk -> universal
    if ($fileName -match 'universal') { return 'universal' }
    if ($fileName -match '-(arm64-v8a|armeabi-v7a|x86_64|x86)-') { return $matches[1] }
    return 'universal'
}

$record = [System.Collections.Generic.List[object]]::new()
$primaryTarget = $null

foreach ($apk in $apks | Sort-Object Name) {
    $label = Get-AbiLabel $apk.Name
    $target = Join-Path $OutDir "LinksiEnhanced_${safeVersion}_${label}.apk"
    Copy-Item $apk.FullName $target -Force

    $hash = (Get-FileHash $target -Algorithm SHA256).Hash
    $size = (Get-Item $target).Length
    $hash | Set-Content "$target.sha256" -Encoding ASCII

    Write-Host ""
    Write-Host ("=== ARCHIVED ({0}) ===" -f $label)
    Write-Host ("  apk    : {0}" -f (Split-Path $target -Leaf))
    Write-Host ("  size   : {0} bytes ({1:N2} MB)" -f $size, ($size / 1MB))
    Write-Host ("  sha256 : {0}" -f $hash)

    # The universal APK is the one to attach to a release by default.
    if ($label -eq 'universal') { $primaryTarget = $target }

    $record.Add([ordered]@{
        abi         = $label
        apk         = (Split-Path $target -Leaf)
        sizeBytes   = $size
        sha256      = $hash
    })
}

if (-not $primaryTarget) { $primaryTarget = (Join-Path $OutDir "LinksiEnhanced_${safeVersion}_arm64-v8a.apk") }

$buildRecord = [ordered]@{
    builtAtUtc   = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    branch       = $branch
    commit       = $commit
    uncommitted  = $dirty
    versionName  = $versionName
    versionCode  = $versionCode
    signingAlias = $keyAlias
    artifacts    = $record
}
$buildRecord | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $OutDir 'latest-build.json') -Encoding UTF8

Write-Host ""
Write-Host ("  record : {0}" -f (Join-Path $OutDir 'latest-build.json'))
Write-Host ("  release asset candidate: {0}" -f (Split-Path $primaryTarget -Leaf))

# Do not leave signing material in the environment of the caller's shell.
Remove-Item Env:KEYSTORE_PASSWORD, Env:KEY_PASSWORD -ErrorAction SilentlyContinue
