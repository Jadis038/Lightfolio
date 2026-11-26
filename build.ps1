# YingCe Gallery M1 - build + verify
# Usage: pwsh -File build.ps1 [-SkipTests]
# NOTE: keep this file ASCII-only. Windows PowerShell 5.1 reads BOM-less
# .ps1 as ANSI, so non-ASCII text here breaks parsing.
param(
    [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'

# The project must live on a pure-ASCII path: AGP 8.13.2 rejects non-ASCII
# project paths, and it breaks unit test class loading on Windows.
$here = 'C:\dev\yingce-m1'
if (-not (Test-Path $here)) {
    $here = Split-Path -Parent $MyInvocation.MyCommand.Definition
    if ($here -match '[^\u0000-\u007F]') {
        throw "Project path contains non-ASCII characters: $here. Build from C:\dev\yingce-m1 or create a junction."
    }
}

$env:JAVA_HOME        = 'C:\dev\jdk21\jdk-21.0.2'
$env:ANDROID_HOME     = 'C:\dev\android-sdk'
$env:ANDROID_SDK_ROOT = 'C:\dev\android-sdk'
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\build-tools\36.0.0;$env:PATH"

$gradle = 'C:\dev\gradle\gradle-8.14.3\bin\gradle.bat'
$bt     = "$env:ANDROID_HOME\build-tools\36.0.0"
$apk    = Join-Path $here 'app\build\outputs\apk\release\app-release.apk'

Write-Host '==> assembleRelease' -ForegroundColor Cyan
& $gradle -p $here assembleRelease --console=plain
if ($LASTEXITCODE -ne 0) { throw "assembleRelease failed ($LASTEXITCODE)" }

if (-not $SkipTests) {
    Write-Host '==> unit tests (naming rules)' -ForegroundColor Cyan
    & $gradle -p $here testReleaseUnitTest --console=plain
    if ($LASTEXITCODE -ne 0) { throw "unit tests failed ($LASTEXITCODE)" }
}

Write-Host '==> verify APK' -ForegroundColor Cyan
& "$bt\aapt2.exe" dump badging $apk | Select-String 'package:|sdkVersion|targetSdkVersion|native-code' | ForEach-Object { $_.Line }

Write-Host '--- permissions (expect NO INTERNET) ---'
& "$bt\aapt2.exe" dump permissions $apk

Write-Host '--- signature ---'
& "$bt\apksigner.bat" verify --print-certs $apk | Select-Object -First 3

Write-Host '--- 16KB page alignment ---'
& "$bt\zipalign.exe" -c -P 16 4 $apk
if ($LASTEXITCODE -ne 0) { throw 'zipalign 16K check failed' }

Write-Host '--- native libs ---'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($apk)
$zip.Entries | Where-Object { $_.FullName -like 'lib/*' } | ForEach-Object { "  " + $_.FullName }
$zip.Dispose()

$size = [math]::Round((Get-Item $apk).Length / 1MB, 2)
Write-Host ("APK: " + $apk + " (" + $size + " MB)") -ForegroundColor Green
Get-FileHash $apk -Algorithm SHA256 | ForEach-Object { Write-Host ("SHA256: " + $_.Hash) }
