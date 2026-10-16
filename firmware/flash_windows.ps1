param(
    [Parameter(Mandatory=$true)][ValidatePattern('^COM[0-9]+$')][string]$Port,
    [string]$Python = 'py',
    [switch]$ApplicationOnly
)
$ErrorActionPreference = 'Stop'
$image = Join-Path $PSScriptRoot 'jarvis-esp32s3-merged.bin'
$offset = '0x0'
if ($ApplicationOnly) {
    $image = Join-Path $PSScriptRoot 'parts/firmware.bin'
    $offset = '0x10000'
}
if (!(Test-Path -LiteralPath $image)) { throw 'Extract the full firmware ZIP before flashing.' }
if (!(Get-Command $Python -ErrorAction SilentlyContinue)) {
    throw 'Python was not found. Install Python 3 or pass -Python with its full executable path.'
}
& $Python -c "import importlib.util,sys; sys.exit(0 if importlib.util.find_spec('esptool') else 2)"
if ($LASTEXITCODE -ne 0) {
    throw "esptool is missing from the selected Python. Run: & '$Python' -m pip install esptool==4.8.1 . No connection to the board was attempted."
}
& $Python -m esptool --chip esp32s3 --port $Port --baud 460800 write_flash --flash_mode keep --flash_freq keep --flash_size keep $offset $image
if ($LASTEXITCODE -ne 0) {
    throw 'Flashing did not finish. Read the esptool error above: check the COM port, close other serial programs, and only use BOOT/RESET if the error says it cannot connect.'
}
Write-Host 'Flash complete. Press RESET, then connect to the Jarvis-Cam Wi-Fi.'
