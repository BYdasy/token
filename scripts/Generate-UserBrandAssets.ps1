param(
    [string]$Source = (Join-Path $PSScriptRoot '../token-user/public/brand.png')
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$assetDirectory = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../token-user/public')).Path
$original = [System.Drawing.Bitmap]::new((Resolve-Path -LiteralPath $Source).Path)
try {
    if ($original.Width -ne $original.Height) { throw '品牌原图必须为正方形，避免安装图标变形。' }
    foreach ($asset in @(
        @{ Name = 'icon-192.png'; Size = 192 },
        @{ Name = 'icon-512.png'; Size = 512 },
        @{ Name = 'apple-touch-icon.png'; Size = 180 }
    )) {
        $bitmap = [System.Drawing.Bitmap]::new($asset.Size, $asset.Size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
        $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
        try {
            $graphics.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceCopy
            $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
            $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
            $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
            $graphics.DrawImage($original, [System.Drawing.Rectangle]::new(0, 0, $asset.Size, $asset.Size))
            $bitmap.Save((Join-Path $assetDirectory $asset.Name), [System.Drawing.Imaging.ImageFormat]::Png)
        } finally {
            $graphics.Dispose()
            $bitmap.Dispose()
        }
    }
    # 保留 favicon.svg 的旧引用兼容，内部使用同一原图的缩放结果，不另画品牌。
    $encodedIcon = [Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $assetDirectory 'icon-192.png')))
    $svg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 192 192"><image width="192" height="192" href="data:image/png;base64,' + $encodedIcon + '"/></svg>'
    [IO.File]::WriteAllText((Join-Path $assetDirectory 'favicon.svg'), $svg, [Text.UTF8Encoding]::new($false))
} finally {
    $original.Dispose()
}
