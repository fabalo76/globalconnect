param(
    [string]$DeviceSerial
)

$ErrorActionPreference = "Stop"
$packageName = "one.globalconnect.xtmsagent.globalconnect"
$receiverName =
    "$packageName/one.globalconnect.xtmsagent.debug.DebugDeviceOwnerControlReceiver"
$adbArguments = @()
if (-not [string]::IsNullOrWhiteSpace($DeviceSerial)) {
    $adbArguments = @("-s", $DeviceSerial)
}

& adb @adbArguments shell am broadcast `
    -a "one.globalconnect.xtmsagent.DEBUG_CLEAR_DEVICE_OWNER" `
    -n $receiverName
if ($LASTEXITCODE -ne 0) {
    throw "Android could not ask the debug app to relinquish device ownership."
}

Start-Sleep -Seconds 1
& adb @adbArguments shell am force-stop $packageName
if ($LASTEXITCODE -ne 0) {
    throw "Android could not stop xTMSAgent after it relinquished device ownership."
}

& adb @adbArguments uninstall $packageName
if ($LASTEXITCODE -ne 0) {
    throw "Android could not uninstall xTMSAgent."
}
