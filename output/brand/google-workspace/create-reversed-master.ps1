Add-Type -AssemblyName System.Drawing
$sourcePath = Join-Path $PSScriptRoot 'continental-payment-systems-workspace-logo-master-1280x528.png'
$destinationPath = Join-Path $PSScriptRoot 'continental-payment-systems-workspace-logo-master-reversed-1280x528.png'
$sourceLogo = [System.Drawing.Bitmap]::FromFile($sourcePath)
try {
    # Preserve the master alpha and geometry; reverse only the headline ink.
    for ($y = 180; $y -lt 280; $y++) {
        for ($x = 400; $x -lt 1150; $x++) {
            $pixel = $sourceLogo.GetPixel($x, $y)
            if ($pixel.A -gt 0) {
                $sourceLogo.SetPixel($x, $y, [System.Drawing.Color]::FromArgb($pixel.A, 255, 255, 255))
            }
        }
    }
    $sourceLogo.Save($destinationPath, [System.Drawing.Imaging.ImageFormat]::Png)
    Write-Output "$destinationPath ($($sourceLogo.Width) x $($sourceLogo.Height))"
} finally {
    $sourceLogo.Dispose()
}
