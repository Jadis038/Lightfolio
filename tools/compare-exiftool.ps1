# 影·册 M2 — 与 ExifTool 逐字段比对
# 用法： powershell -File compare-exiftool.ps1
# 前置： C:\dev\exiftool\et.exe 可用、C:\dev\dl\exif_dump.tsv 已由仪器化测试导出

$ErrorActionPreference = 'Stop'
$et   = 'C:\dev\exiftool\et.exe'
$tsv  = 'C:\dev\dl\exif_dump.tsv'
$dir  = 'C:\Users\16774\Desktop\逆向'
$out  = 'C:\Users\16774\Desktop\影册-build\M2-EXIF对照表.md'

$samples = [ordered]@{
    'Canon_EOS_R5.CR3'        = 'Canon EOS R5.CR3'
    'Nikon_D750.NEF'          = 'Nikon D750.NEF'
    'Sony_A7R_II.ARW'         = 'Sony A7R II.ARW'
    'Fujifilm_X-T10.RAF'      = 'Fujifilm X-T10.RAF'
    'Hasselblad_H3D-39II.3FR' = 'Hasselblad H3D-39II.3FR'
}

if (-not (Test-Path $et)) { throw "ExifTool 未就绪: $et" }
if (-not (Test-Path $tsv)) { throw "缺少解析结果: $tsv" }

# ---- 读我们 App 的结果 ----
$rows = Import-Csv -Delimiter "`t" -Path $tsv
$ours = @{}
foreach ($r in $rows) { $ours[$r.file] = $r }

# ---- 跑 ExifTool ----
$tags = 'FileName','Make','Model','LensModel','FNumber','ExposureTime','ISO',
        'FocalLength','FocalLengthIn35mmFormat','WhiteBalance','ExposureProgram',
        'ExposureCompensation','MeteringMode','ImageWidth','ImageHeight','DateTimeOriginal'
$args = @('-json','-n','-G0') + ($tags | ForEach-Object { "-$_" })
$etJson = @{}
foreach ($safe in $samples.Keys) {
    $real = Join-Path $dir $samples[$safe]
    $raw = & $et @args $real 2>$null | Out-String
    $obj = ($raw | ConvertFrom-Json)[0]
    $etJson[$safe] = $obj
}

function Fmt([object]$v) {
    if ($null -eq $v) { return $null }
    if ($v -is [double] -or $v -is [int] -or $v -is [long]) { return "$v" }
    return "$v".Trim()
}

$md = New-Object System.Text.StringBuilder
[void]$md.AppendLine("# 影·册 M2 — EXIF 与 ExifTool 对照表")
[void]$md.AppendLine("")
[void]$md.AppendLine("生成时间：$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')　　样例目录：``%USERPROFILE%\Desktop\逆向``")
[void]$md.AppendLine("")
[void]$md.AppendLine("ExifTool 版本：$(& $et -ver)　App：影·册 0.2.0-m2（`com.yingce.gallery`）")
[void]$md.AppendLine("")
[void]$md.AppendLine("> 判定口径：App 显示「该文件未记录」= App 未取到该字段；若 ExifTool 也未取到则记「双方均无」。")
[void]$md.AppendLine("")

# ---- 明细：每个文件逐字段 ----
$md.AppendLine("## 一、逐文件逐字段明细").Trim() | Out-Null
[void]$md.AppendLine("")

foreach ($safe in $samples.Keys) {
    $o = $ours[$safe]; $e = $etJson[$safe]
    [void]$md.AppendLine("### $($samples[$safe])")
    [void]$md.AppendLine("")
    [void]$md.AppendLine("| 字段 | App 显示 | ExifTool | 判定 |")
    [void]$md.AppendLine("|---|---|---|---|")

    $pairs = @(
        @{ n='光圈';       a=$o.aperture;  e=$e.FNumber },
        @{ n='快门速度';   a=$o.shutter;   e=$e.ExposureTime },
        @{ n='ISO';        a=$o.iso;       e=$e.ISO },
        @{ n='等效焦距';   a=$o.focal35;   e=$e.FocalLengthIn35mmFormat },
        @{ n='实际焦距';   a=$o.focal;     e=$e.FocalLength },
        @{ n='白平衡';     a=$o.wb;        e=$e.WhiteBalance },
        @{ n='拍摄模式';   a=$o.program;   e=$e.ExposureProgram },
        @{ n='曝光补偿';   a=$o.bias;      e=$e.ExposureCompensation },
        @{ n='测光模式';   a=$o.metering;  e=$e.MeteringMode },
        @{ n='宽';         a=$o.width;     e=$e.ImageWidth },
        @{ n='高';         a=$o.height;    e=$e.ImageHeight },
        @{ n='机型';       a="$($o.make) $($o.model)".Trim(); e=("$($e.Make) $($e.Model)").Trim() },
        @{ n='镜头型号';   a=$o.lens;      e=$e.LensModel },
        @{ n='拍摄时间';   a=$o.takenAt;   e=$e.DateTimeOriginal }
    )

    foreach ($p in $pairs) {
        $av = if ([string]::IsNullOrWhiteSpace("$($p.a)")) { '该文件未记录' } else { "$($p.a)" }
        $ev = Fmt $p.e
        if ([string]::IsNullOrWhiteSpace($ev)) { $ev = '（无）' }

        $verdict = '需人工判读'
        if ($av -eq '该文件未记录' -and $ev -eq '（无）') { $verdict = '双方均无 ✅' }
        elseif ($av -eq '该文件未记录') { $verdict = 'App 缺失 ⚠️' }
        elseif ($ev -eq '（无）') { $verdict = '仅 App 有 ❓' }
        else { $verdict = '见下' }
        [void]$md.AppendLine("| $($p.n) | $av | $ev | $verdict |")
    }
    [void]$md.AppendLine("")
}

$md.ToString() | Set-Content -Path $out -Encoding UTF8
Write-Host "written: $out"
