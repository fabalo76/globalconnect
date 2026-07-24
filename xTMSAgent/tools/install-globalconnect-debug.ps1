param(
    [string]$DeviceSerial
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$adbArguments = @()
if (-not [string]::IsNullOrWhiteSpace($DeviceSerial)) {
    $adbArguments = @("-s", $DeviceSerial)
}

Push-Location $root
try {
    & .\gradlew.bat :app:assembleGlobalconnectDebug
    if ($LASTEXITCODE -ne 0) {
        throw "The globalconnectDebug build failed."
    }

    $apk = Get-ChildItem "app\build\outputs\apk\globalconnect\debug\*.apk" |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($null -eq $apk) {
        throw "The globalconnectDebug APK was not found."
    }

    & adb @adbArguments install -r -t $apk.FullName
    if ($LASTEXITCODE -ne 0) {
        throw "ADB could not update xTMSAgent."
    }

    & adb @adbArguments shell am start `
        -n "one.globalconnect.xtmsagent.globalconnect/one.globalconnect.xtmsagent.MainActivity"
    if ($LASTEXITCODE -ne 0) {
        throw "xTMSAgent was installed, but Android could not open it."
    }
} finally {
    Pop-Location
}
