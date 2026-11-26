# 用 NDK clang 直接编译 LibRaw 为 Android 共享库（不依赖 CMake）
#
# 背景：LibRaw 0.22.3 的 tar 包里没有 CMakeLists.txt（只有 autotools/MSVC），
# 而 Android SDK 自带的 CMake 在配置阶段会崩溃（0xC0000409），因此这里直接用
# NDK 里的 clang++ 编译全部源文件，规避构建系统问题。
#
# 用法：
#   powershell -File tools/build_libraw.ps1 -LibRawRoot C:\dev\libraw\LibRaw-0.22.3
#
# 说明：
# - 必须排除 *_ph.cpp（占位实现，会与真实实现产生重复符号）
# - 不能排除 src/write/*.cpp（LibRaw 内部引用 write_ppm_tiff，缺了会在 dlopen 时
#   报 "cannot locate symbol ... write_ppm_tiff"）
# - 静态链接 libc++，避免额外打包 8.8 MB 的 libc++_shared.so

param(
    [Parameter(Mandatory = $true)][string]$LibRawRoot,
    [string]$NdkRoot = "C:\dev\android-sdk\ndk\29.0.14206865",
    [string]$OutDir = "",
    [string]$ProjectRoot = ""
)

$ErrorActionPreference = "Stop"

if ($ProjectRoot -eq "") {
    $ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
}
if ($OutDir -eq "") {
    $OutDir = Join-Path $ProjectRoot "app\src\main\jniLibs"
}

$clang = Join-Path $NdkRoot "toolchains\llvm\prebuilt\windows-x86_64\bin\clang++.exe"
if (-not (Test-Path $clang)) { throw "clang++ 不存在: $clang" }

$jniSource = Join-Path $ProjectRoot "app\src\main\cpp\libraw_jni.cpp"
if (-not (Test-Path $jniSource)) { throw "JNI 源码不存在: $jniSource" }

$rootUnix = $LibRawRoot.Replace('\', '/')
$sources = Get-ChildItem (Join-Path $LibRawRoot "src") -Recurse -Filter "*.cpp" |
    Where-Object { $_.Name -notmatch '_ph\.cpp$' } |
    ForEach-Object { $_.FullName.Replace('\', '/') }

Write-Host "LibRaw 源文件数: $($sources.Count)"

$rsp = Join-Path ([System.IO.Path]::GetTempPath()) "libraw_sources.rsp"
Set-Content -Path $rsp -Value ($sources -join "`n") -Encoding ASCII

$targets = @(
    @{ Abi = "arm64-v8a"; Target = "aarch64-linux-android29" },
    @{ Abi = "x86_64";    Target = "x86_64-linux-android29" }   # 模拟器验证用；release 包通过 abiFilters 只留 arm64
)

foreach ($t in $targets) {
    $dir = Join-Path $OutDir $t.Abi
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $out = Join-Path $dir "libraw.so"
    $args = @(
        "--target=$($t.Target)",
        "-std=c++17", "-O2", "-fPIC", "-fexceptions", "-frtti", "-shared",
        "-static-libstdc++",
        "-DLIBRAW_NODLL=1", "-DNO_OPENMP=1",
        "-I`"$rootUnix`"", "-I`"$rootUnix/src`"",
        "@$rsp",
        $jniSource.Replace('\', '/'),
        "-o", "`"$out`"",
        "-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384",
        "-llog"
    )
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    & $clang @args
    if ($LASTEXITCODE -ne 0) { throw "编译失败: $($t.Abi)" }
    $sw.Stop()
    $size = [math]::Round((Get-Item $out).Length / 1MB, 2)
    Write-Host "$($t.Abi): $out  ${size}MB  $([math]::Round($sw.Elapsed.TotalSeconds,0))s"
}

$readelf = Join-Path $NdkRoot "toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe"
Write-Host "`n16 KB 页对齐检查（LOAD 段应为 0x4000）:"
& $readelf -l (Join-Path $OutDir "arm64-v8a\libraw.so") |
    Select-String "^\s+LOAD" |
    ForEach-Object { $_.Line.Trim() }
