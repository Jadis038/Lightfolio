# 从 logo.png 生成自适应图标的三层（background / foreground / monochrome）
#
# 背景：原图既无透明通道、又是满幅插画，因此：
#   - background 层用从插画边缘采样的深色（满幅铺底，不裁切主体）
#   - foreground 层把插画裁进 66dp 安全区内的圆角方块，四角透明（合规前景）
#   - monochrome 层用亮度阈值提主体剪影（主题图标用），形状不理想时退回圆角方块
Add-Type -AssemblyName System.Drawing

$src = "$env:USERPROFILE\Desktop\logo.png"
$root = 'C:\dev\yingce-m1\app\src\main\res'
$densities = @(
    @{ name = 'mdpi';    size = 108 },
    @{ name = 'hdpi';    size = 162 },
    @{ name = 'xhdpi';   size = 216 },
    @{ name = 'xxhdpi';  size = 324 },
    @{ name = 'xxxhdpi'; size = 432 }
)

$logo = [System.Drawing.Image]::FromFile($src)
$bmpSrc = New-Object System.Drawing.Bitmap($logo)

# 边缘采样求底色
$edge = New-Object System.Collections.Generic.List[System.Drawing.Color]
foreach ($p in @(@(4,4), @($bmpSrc.Width-5,4), @(4,$bmpSrc.Height-5), @($bmpSrc.Width-5,$bmpSrc.Height-5),
                 @([int]($bmpSrc.Width/2),4), @([int]($bmpSrc.Width/2),$bmpSrc.Height-5))) {
    $edge.Add($bmpSrc.GetPixel($p[0], $p[1]))
}
$br = [int](($edge | ForEach-Object { $_.R } | Measure-Object -Average).Average)
$bg = [int](($edge | ForEach-Object { $_.G } | Measure-Object -Average).Average)
$bb = [int](($edge | ForEach-Object { $_.B } | Measure-Object -Average).Average)
$bgColor = [System.Drawing.Color]::FromArgb(255, $br, $bg, $bb)
Write-Output ("背景采样色: R={0} G={1} B={2}" -f $br, $bg, $bb)

function New-SquirclePath([int]$size, [float]$inset) {
    $insetPx = [float]($size * $inset)
    $rect = New-Object System.Drawing.RectangleF($insetPx, $insetPx, ($size - 2 * $insetPx), ($size - 2 * $insetPx))
    $r = $rect.Width * 0.30
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = $r * 2
    $path.AddArc($rect.X, $rect.Y, $d, $d, 180, 90)
    $path.AddArc($rect.Right - $d, $rect.Y, $d, $d, 270, 90)
    $path.AddArc($rect.Right - $d, $rect.Bottom - $d, $d, $d, 0, 90)
    $path.AddArc($rect.X, $rect.Bottom - $d, $d, $d, 90, 90)
    $path.CloseFigure()
    return $path
}

foreach ($den in $densities) {
    $size = $den.size
    $dir = Join-Path $root ("mipmap-" + $den.name)
    New-Item -ItemType Directory -Force -Path $dir | Out-Null

    # ---------- background ----------
    $bgBmp = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bgBmp)
    $g.Clear($bgColor)
    # 满幅插画做底（略微放大避免边缘留白，再压暗一点让前景更突出）
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $srcRect = New-Object System.Drawing.RectangleF(0, 0, $bmpSrc.Width, $bmpSrc.Height)
    $dstRect = New-Object System.Drawing.RectangleF(0, 0, $size, $size)
    $g.DrawImage($bmpSrc, $dstRect, $srcRect, [System.Drawing.GraphicsUnit]::Pixel)
    $g.Dispose()
    for ($y = 0; $y -lt $bgBmp.Height; $y++) {
        for ($x = 0; $x -lt $bgBmp.Width; $x++) {
            $c = $bgBmp.GetPixel($x, $y)
            $bgBmp.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(255,
                [int]($c.R * 0.55), [int]($c.G * 0.55), [int]($c.B * 0.55)))
        }
    }
    $bgBmp.Save((Join-Path $dir 'ic_launcher_background.png'), [System.Drawing.Imaging.ImageFormat]::Png)
    $bgBmp.Dispose()

    # ---------- foreground：66dp 安全区内的圆角方块 ----------
    $fgBmp = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($fgBmp)
    $g.Clear([System.Drawing.Color]::Transparent)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    # 安全区：72dp 直径圆内的内容不会被裁；插画占 66/108
    $path = New-SquirclePath $size (18.0 / 108.0)
    $g.SetClip($path)
    $g.DrawImage($bmpSrc, (New-Object System.Drawing.RectangleF(0, 0, $size, $size)),
        $srcRect, [System.Drawing.GraphicsUnit]::Pixel)
    $g.ResetClip()
    $g.Dispose()
    $fgBmp.Save((Join-Path $dir 'ic_launcher_foreground.png'), [System.Drawing.Imaging.ImageFormat]::Png)
    $fgBmp.Dispose()

    # ---------- monochrome：亮度阈值剪影 ----------
    $mono = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($mono)
    $g.Clear([System.Drawing.Color]::Transparent)
    $small = New-Object System.Drawing.Bitmap($size, $size)
    $gs = [System.Drawing.Graphics]::FromImage($small)
    $gs.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $gs.DrawImage($bmpSrc, (New-Object System.Drawing.RectangleF(0, 0, $size, $size)),
        $srcRect, [System.Drawing.GraphicsUnit]::Pixel)
    $gs.Dispose()
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.SetClip((New-SquirclePath $size (18.0 / 108.0)))
    for ($y = 0; $y -lt $size; $y++) {
        for ($x = 0; $x -lt $size; $x++) {
            $c = $small.GetPixel($x, $y)
            $lum = (0.299 * $c.R + 0.587 * $c.G + 0.114 * $c.B)
            if ($lum -gt 140) {
                $alpha = [int]([Math]::Min(255, ($lum - 140) * 3))
                $mono.SetPixel($x, $y, [System.Drawing.Color]::FromArgb($alpha, 255, 255, 255))
            }
        }
    }
    $g.ResetClip()
    $g.Dispose()
    $small.Dispose()
    $mono.Save((Join-Path $dir 'ic_launcher_monochrome.png'), [System.Drawing.Imaging.ImageFormat]::Png)
    $mono.Dispose()

    Write-Output ("generated mipmap-{0} ({1}px)" -f $den.name, $size)
}

$bmpSrc.Dispose()
$logo.Dispose()
Write-Output "done"
