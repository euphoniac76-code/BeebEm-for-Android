<#
  tesco-assistant: one-time Windows laptop setup.

  Run from an ordinary PowerShell window with the one-line command Claude gives you, which looks like:
    & ([scriptblock]::Create((irm https://raw.githubusercontent.com/euphoniac76-code/BeebEm-for-Android/ccr-56360ad8-iesdrj/tesco-assistant/windows/setup.ps1))) -TunnelToken <token>

  What it does:
    1. Installs Node.js, Google Chrome (if missing) and cloudflared with winget.
    2. Downloads tesco-assistant to %LOCALAPPDATA%\tesco-assistant and installs its packages.
    3. Opens Chrome so you can sign in to Tesco once.
    4. Installs the Cloudflare Tunnel as a Windows service (starts with Windows).
    5. Starts the helper now and every time you sign in to Windows (hidden, in the background).
    6. Optional (-KeepAwakeOnPower): stops the laptop sleeping while plugged in, even with the lid closed.
    7. Adds a "Tesco sign-in" shortcut to the desktop for the monthly re-sign-in.
#>
param(
  [Parameter(Mandatory = $true)][string]$TunnelToken,
  [string]$Branch = "ccr-56360ad8-iesdrj",
  [switch]$KeepAwakeOnPower
)

$ErrorActionPreference = "Stop"
$Repo = "https://raw.githubusercontent.com/euphoniac76-code/BeebEm-for-Android/$Branch/tesco-assistant"
$Dir = Join-Path $env:LOCALAPPDATA "tesco-assistant"
$TaskName = "Tesco Assistant"

function Step($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function RefreshPath {
  $env:Path = [Environment]::GetEnvironmentVariable("Path", "Machine") + ";" + [Environment]::GetEnvironmentVariable("Path", "User")
}
function WingetInstall($id, $name) {
  Write-Host "Installing $name..."
  winget install --id $id -e --silent --accept-package-agreements --accept-source-agreements | Out-Host
  RefreshPath
}
function RunAdmin($command) {
  # Runs one command elevated (Windows asks for permission once per call).
  $p = Start-Process powershell -Verb RunAs -Wait -PassThru -WindowStyle Hidden `
    -ArgumentList "-NoProfile -ExecutionPolicy Bypass -Command $command"
  if ($p.ExitCode -ne 0) { throw "Admin step failed (exit $($p.ExitCode)): $command" }
}

if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
  throw "winget is missing. Install 'App Installer' from the Microsoft Store, then run this again."
}

Step "1/7 Installing Node.js, Chrome and cloudflared"
RefreshPath
if (-not (Get-Command node -ErrorAction SilentlyContinue)) { WingetInstall "OpenJS.NodeJS.LTS" "Node.js" }
$chrome = @("$env:ProgramFiles\Google\Chrome\Application\chrome.exe",
            "${env:ProgramFiles(x86)}\Google\Chrome\Application\chrome.exe",
            "$env:LOCALAPPDATA\Google\Chrome\Application\chrome.exe") | Where-Object { Test-Path $_ }
if (-not $chrome) { WingetInstall "Google.Chrome" "Google Chrome" }
if (-not (Get-Command cloudflared -ErrorAction SilentlyContinue)) { WingetInstall "Cloudflare.cloudflared" "cloudflared" }
foreach ($c in "node", "npm", "cloudflared") {
  if (-not (Get-Command $c -ErrorAction SilentlyContinue)) { throw "$c is not on PATH. Close PowerShell, open a new window and run this again." }
}

Step "2/7 Downloading tesco-assistant to $Dir"
New-Item -ItemType Directory -Force -Path $Dir | Out-Null
foreach ($f in "package.json", "server.mjs") {
  Invoke-WebRequest "$Repo/$f" -OutFile (Join-Path $Dir $f) -UseBasicParsing
}
Push-Location $Dir
npm install --no-fund --no-audit | Out-Host
if ($LASTEXITCODE -ne 0) { throw "npm install failed" }
Pop-Location

# Stop an older copy of the helper before signing in (it would hold the Chrome profile).
Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue

Step "3/7 Sign in to Tesco"
Write-Host "A Chrome window will open on the Tesco login page." -ForegroundColor Yellow
Write-Host "Sign in normally (including any code Tesco emails or texts you), wait for the Tesco page to load," -ForegroundColor Yellow
Write-Host "then come back to this window and press Enter." -ForegroundColor Yellow
Push-Location $Dir
npx basketeer login
if ($LASTEXITCODE -ne 0) { throw "Tesco sign-in did not complete. Run this setup again to retry." }
Pop-Location

Step "4/7 Installing the Cloudflare Tunnel service (Windows will ask for permission)"
RunAdmin "cloudflared service uninstall 2>`$null; cloudflared service install $TunnelToken"

Step "5/7 Starting the helper (now and whenever you sign in to Windows)"
$log = Join-Path $Dir "server.log"
$runner = Join-Path $Dir "run.ps1"
@"
`$env:TESCO_HTTP_PORT = '8787'
Set-Location '$Dir'
while (`$true) {
  node server.mjs *>> '$log'
  Start-Sleep -Seconds 10   # restart if it ever stops
}
"@ | Set-Content -Encoding UTF8 $runner
$action = New-ScheduledTaskAction -Execute "powershell.exe" `
  -Argument "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$runner`""
$trigger = New-ScheduledTaskTrigger -AtLogOn -User "$env:USERDOMAIN\$env:USERNAME"
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
  -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) -StartWhenAvailable
$principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive -RunLevel Limited
Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings `
  -Principal $principal -Force | Out-Null
Start-ScheduledTask -TaskName $TaskName

Step "6/7 Power settings"
if (-not $KeepAwakeOnPower) {
  Write-Host "Left unchanged. The laptop only needs to be on and signed in to Windows while you shop with Claude."
  Write-Host "(Re-run with -KeepAwakeOnPower to stop it sleeping when plugged in.)"
} else {
  # AC power only: never sleep, and closing the lid does nothing. Battery behaviour is unchanged.
  RunAdmin "powercfg /change standby-timeout-ac 0; powercfg /change hibernate-timeout-ac 0; powercfg /setacvalueindex SCHEME_CURRENT SUB_BUTTONS LIDACTION 0; powercfg /setactive SCHEME_CURRENT"
  Write-Host "Done: when plugged in, the laptop no longer sleeps, even with the lid closed."
}

Step "7/7 Adding the 'Tesco sign-in' desktop shortcut"
$signin = Join-Path $Dir "signin.ps1"
@"
Write-Host 'Re-signing in to Tesco...' -ForegroundColor Cyan
Stop-ScheduledTask -TaskName '$TaskName' -ErrorAction SilentlyContinue
Get-CimInstance Win32_Process -Filter "Name='node.exe'" | Where-Object { `$_.CommandLine -like '*server.mjs*' } | ForEach-Object { Stop-Process -Id `$_.ProcessId -Force }
Set-Location '$Dir'
npx basketeer login
Start-ScheduledTask -TaskName '$TaskName'
Write-Host 'Done. You can close this window.' -ForegroundColor Green
Read-Host 'Press Enter to close'
"@ | Set-Content -Encoding UTF8 $signin
$shell = New-Object -ComObject WScript.Shell
$lnk = $shell.CreateShortcut((Join-Path ([Environment]::GetFolderPath("Desktop")) "Tesco sign-in.lnk"))
$lnk.TargetPath = "powershell.exe"
$lnk.Arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$signin`""
$lnk.WorkingDirectory = $Dir
$lnk.Save()

Start-Sleep -Seconds 5
try {
  $h = Invoke-RestMethod "http://127.0.0.1:8787/health" -TimeoutSec 10
  if ($h.signedIn) { Write-Host "`nAll set. The helper is running and signed in to Tesco." -ForegroundColor Green }
  else { Write-Host "`nThe helper is running but is not signed in. Double-click 'Tesco sign-in' on the desktop." -ForegroundColor Yellow }
} catch {
  Write-Host "`nThe helper did not answer yet. Check $log for errors." -ForegroundColor Yellow
}
Write-Host "When you want Claude to shop: switch the laptop on and sign in to Windows. Everything else starts by itself."
