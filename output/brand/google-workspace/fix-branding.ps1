Add-Type -AssemblyName System.Drawing
$brandRoot = $PSScriptRoot
$backupRoot = Join-Path $brandRoot 'branding-backup-20260910'
New-Item -ItemType Directory -Path $backupRoot -Force | Out-Null
$primary = Join-Path $brandRoot 'continental-payment-technologies-logo-primary.png'
$reverse = Join-Path $brandRoot 'continental-payment-technologies-logo-reversed.png'
Copy-Item -LiteralPath 'C:/Users/fabal/.codex/generated_images/019feec6-2c8d-75f1-9943-5ee820bb9cdc/exec-f00a391a-daee-4273-a2d4-b01d7eb8a74c.png' -Destination $reverse
function Fit-Logo($source, $dest, $width, $height, $color) {
    $im = [System.Drawing.Image]::FromFile($source)
    $bmp = New-Object System.Drawing.Bitmap($width, $height)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.Clear([System.Drawing.ColorTranslator]::FromHtml($color))
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $scale = [Math]::Min($width / $im.Width, $height / $im.Height)
    $w = [int]($im.Width * $scale); $h = [int]($im.Height * $scale)
    $g.DrawImage($im, [int](($width-$w)/2), [int](($height-$h)/2), $w, $h)
    $bmp.Save($dest, [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $bmp.Dispose(); $im.Dispose()
}
Fit-Logo $primary (Join-Path $brandRoot 'continental-payment-technologies-workspace-logo-master-1280x528.png') 1280 528 '#FFFFFF'
Fit-Logo $reverse (Join-Path $brandRoot 'continental-payment-technologies-workspace-logo-master-reversed-1280x528.png') 1280 528 '#071D31'
Fit-Logo $primary (Join-Path $brandRoot 'continental-payment-technologies-email-logo-400x132.png') 400 132 '#FFFFFF'
Fit-Logo $reverse (Join-Path $brandRoot 'continental-payment-technologies-form-header-1600x400.png') 1600 400 '#071D31'
$targets = @(Get-ChildItem -LiteralPath $brandRoot -Recurse -File | Where-Object { $_.Extension -in '.html','.htm','.txt','.md' -and $_.FullName -notmatch 'backup' })
$installed = 'C:/Users/fabal/AppData/Roaming/Microsoft/Signatures'
$targets += Get-Item -LiteralPath "$installed/Continental Payment Systems.htm", "$installed/Continental Payment Systems.txt"
foreach ($file in $targets) {
    $relative = if ($file.FullName.StartsWith($brandRoot)) { $file.FullName.Substring($brandRoot.Length).TrimStart('\') } else { 'installed/' + $file.Name }
    $bak = Join-Path $backupRoot $relative
    New-Item -ItemType Directory -Path (Split-Path $bak) -Force | Out-Null
    if (!(Test-Path -LiteralPath $bak)) { Copy-Item -LiteralPath $file.FullName -Destination $bak }
    $content = [System.IO.File]::ReadAllText($file.FullName)
    $content = $content.Replace('CONTINENTAL PAYMENT SYSTEMS','CONTINENTAL PAYMENT TECHNOLOGIES').Replace('Continental Payment Systems','Continental Payment Technologies')
    # Resource filenames remain stable for Outlook's existing signature selection.
    $content = $content.Replace('Continental Payment Technologies_files','Continental Payment Systems_files')
    if ($file.Extension -eq '.html') {
        $content = $content.Replace('https://drive.usercontent.google.com/download?id=1193h_v0S5_eyqYCXTHVkBzFLIO3uM_R7&amp;export=view','continental-payment-technologies-email-logo-400x132.png')
        $content = $content.Replace('height="63"','height="83"').Replace('height:63px','height:83px')
    }
    [System.IO.File]::WriteAllText($file.FullName, $content, [System.Text.UTF8Encoding]::new($false))
}
$resourceImages = @(Get-ChildItem -LiteralPath $brandRoot -Recurse -File | Where-Object { $_.Name -eq 'image001.png' -and $_.FullName -notmatch 'backup' })
$resourceImages += Get-Item -LiteralPath "$installed/Continental Payment Systems_files/image001.png"
foreach ($file in $resourceImages) {
    $relative = if ($file.FullName.StartsWith($brandRoot)) { $file.FullName.Substring($brandRoot.Length).TrimStart('\') } else { 'installed/resources/' + $file.Name }
    $bak = Join-Path $backupRoot $relative
    New-Item -ItemType Directory -Path (Split-Path $bak) -Force | Out-Null
    if (!(Test-Path -LiteralPath $bak)) { Copy-Item -LiteralPath $file.FullName -Destination $bak }
    Copy-Item -LiteralPath (Join-Path $brandRoot 'continental-payment-technologies-email-logo-400x132.png') -Destination $file.FullName -Force
}
Write-Output "Corrected $($targets.Count) text files and $($resourceImages.Count) embedded image files. Backup: $backupRoot"
foreach ($file in Get-ChildItem -LiteralPath $brandRoot -Filter '*.html' -File) {
    $content = [System.IO.File]::ReadAllText($file.FullName).Replace('src="continental-payment-technologies-email-logo-400x132.png"','src="https://drive.usercontent.google.com/download?id=1193h_v0S5_eyqYCXTHVkBzFLIO3uM_R7&amp;export=view"')
    [System.IO.File]::WriteAllText($file.FullName,$content,[System.Text.UTF8Encoding]::new($false))
}
$oldAssets = Get-ChildItem -LiteralPath $brandRoot -Recurse -File -Filter '*.png' | Where-Object { $_.FullName -notmatch 'backup' -and $_.Name -match '^continental-payment-systems-(email-logo|workspace-logo|form-header)' }
foreach ($file in $oldAssets) {
    $relative = $file.FullName.Substring($brandRoot.Length).TrimStart('\')
    $bak = Join-Path $backupRoot $relative
    New-Item -ItemType Directory -Path (Split-Path $bak) -Force | Out-Null
    if (!(Test-Path -LiteralPath $bak)) { Copy-Item -LiteralPath $file.FullName -Destination $bak }
    $im = [System.Drawing.Image]::FromFile($file.FullName); $w=$im.Width; $h=$im.Height; $im.Dispose()
    if ($file.Name -match 'reversed|form-header') { Fit-Logo $reverse $file.FullName $w $h '#071D31' }
    else { Fit-Logo $primary $file.FullName $w $h '#FFFFFF' }
}
$package = Join-Path $brandRoot 'Continental-Payment-Technologies-Outlook-Signature'
if (!(Test-Path -LiteralPath $package)) { Copy-Item -LiteralPath (Join-Path $brandRoot 'outlook-signature') -Destination $package -Recurse }
Get-ChildItem -LiteralPath $package -File | Where-Object { $_.Name -like 'Continental Payment Systems.*' } | ForEach-Object { Rename-Item -LiteralPath $_.FullName -NewName $_.Name.Replace('Systems','Technologies') }
Compress-Archive -Path "$package/*" -DestinationPath (Join-Path $brandRoot 'Continental-Payment-Technologies-Outlook-Signature.zip') -Force
