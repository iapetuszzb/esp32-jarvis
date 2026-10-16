param([Parameter(Mandatory=$true)][ValidatePattern('^COM[0-9]+$')][string]$Port)
$ErrorActionPreference = 'Stop'
$image = Join-Path $PSScriptRoot 'jarvis-esp32s3-merged.bin'
if (!(Test-Path -LiteralPath $image)) { throw 'Extract the full firmware ZIP before flashing.' }
py -m esptool --chip esp32s3 --port $Port --baud 460800 write_flash --flash_mode keep --flash_freq keep --flash_size keep 0x0 $image
if ($LASTEXITCODE -ne 0) { throw 'Flash failed. Hold BOOT, tap RESET, release BOOT and retry.' }
Write-Host 'Flash complete. Press RESET, then connect to the Jarvis-Cam Wi-Fi.'
