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
function RunAdmin($exe, $arguments) {
  # Runs one program elevated (Windows asks for permission once per call). No PowerShell wrapper.
  $p = Start-Process $exe -Verb RunAs -Wait -PassThru -WindowStyle Hidden -ArgumentList $arguments
  if ($p.ExitCode -ne 0) { throw "Admin step failed (exit $($p.ExitCode)): $exe $arguments" }
}
}

if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
  throw "winget is missing. Install 'App Installer' from the Microsoft Store, then run this again."
}

# Register the helper's background task from a task file with Windows' own schtasks.exe.
# It starts node.exe directly (no PowerShell), with no window (conhost --headless), when you
# sign in to Windows, and every 5 minutes as a watchdog (ignored while it is already running).
function Register-HelperTask {
  $node = (Get-Command node -ErrorAction Stop).Source
  $log = Join-Path $Dir "server.log"
  $me = "$env:USERDOMAIN\$env:USERNAME"
  $start = (Get-Date).AddMinutes(1).ToString("s")
  $e = { param($s) [Security.SecurityElement]::Escape($s) }
  $taskArgs = "--headless `"$node`" server.mjs --http 8787 --log `"$log`""
  $xml = @"
<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <RegistrationInfo><Description>tesco-assistant: lets Claude use your Tesco account</Description></RegistrationInfo>
  <Triggers>
    <LogonTrigger><Enabled>true</Enabled><UserId>$(& $e $me)</UserId></LogonTrigger>
    <TimeTrigger><Enabled>true</Enabled><StartBoundary>$start</StartBoundary>
      <Repetition><Interval>PT5M</Interval><StopAtDurationEnd>false</StopAtDurationEnd></Repetition>
    </TimeTrigger>
  </Triggers>
  <Principals>
    <Principal id="Author"><UserId>$(& $e $me)</UserId><LogonType>InteractiveToken</LogonType><RunLevel>LeastPrivilege</RunLevel></Principal>
  </Principals>
  <Settings>
    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
    <StartWhenAvailable>true</StartWhenAvailable>
    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
    <RestartOnFailure><Interval>PT1M</Interval><Count>999</Count></RestartOnFailure>
    <IdleSettings><StopOnIdleEnd>false</StopOnIdleEnd><RestartOnIdle>false</RestartOnIdle></IdleSettings>
  </Settings>
  <Actions Context="Author">
    <Exec>
      <Command>conhost.exe</Command>
      <Arguments>$(& $e $taskArgs)</Arguments>
      <WorkingDirectory>$(& $e $Dir)</WorkingDirectory>
    </Exec>
  </Actions>
</Task>
"@
  $xmlFile = Join-Path $Dir "task.xml"
  $xml | Set-Content -Encoding Unicode $xmlFile
  & schtasks.exe /Create /TN $TaskName /XML $xmlFile /F 2>$null | Out-Null
  if ($LASTEXITCODE -eq 0) { Write-Host "Background task updated."; return }
  Write-Host "Windows needs administrator permission to update the background task. Click Yes on the prompt." -ForegroundColor Yellow
  $p = Start-Process schtasks.exe -Verb RunAs -Wait -PassThru -WindowStyle Hidden `
    -ArgumentList "/Create /TN `"$TaskName`" /XML `"$xmlFile`" /F"
  if ($p.ExitCode -eq 0) { Write-Host "Background task updated (as administrator)." }
  else { Write-Host "Couldn't update the background task (exit $($p.ExitCode))." -ForegroundColor Yellow }
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
foreach ($f in "package.json", "server.mjs", "tesco-auth.mjs", "login.mjs", "weights.mjs", "probe-weights.mjs", "credentials.mjs", "save-password.mjs") {
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
node login.mjs
if ($LASTEXITCODE -ne 0) { throw "Tesco sign-in did not complete. Run this setup again to retry." }
Pop-Location

Step "4/7 Installing the Cloudflare Tunnel service (Windows will ask for permission)"
RunAdmin "cmd.exe" "/c cloudflared service uninstall >nul 2>&1 & cloudflared service install $TunnelToken"

Step "5/7 Starting the helper (now and whenever you sign in to Windows)"
Register-HelperTask
Start-ScheduledTask -TaskName $TaskName

Step "6/7 Power settings"
if (-not $KeepAwakeOnPower) {
  Write-Host "Left unchanged. The laptop only needs to be on and signed in to Windows while you shop with Claude."
  Write-Host "(Re-run with -KeepAwakeOnPower to stop it sleeping when plugged in.)"
} else {
  # AC power only: never sleep, and closing the lid does nothing. Battery behaviour is unchanged.
  RunAdmin "cmd.exe" "/c powercfg /change standby-timeout-ac 0 && powercfg /change hibernate-timeout-ac 0 && powercfg /setacvalueindex SCHEME_CURRENT SUB_BUTTONS LIDACTION 0 && powercfg /setactive SCHEME_CURRENT"
  Write-Host "Done: when plugged in, the laptop no longer sleeps, even with the lid closed."
}

Step "7/7 Adding the 'Tesco sign-in' desktop shortcut"
# Runs the Node sign-in directly in an ordinary command prompt: no PowerShell, and the helper
# keeps running (it picks up the new sign-in by itself).
Remove-Item (Join-Path $Dir "signin.ps1") -ErrorAction SilentlyContinue
$shell = New-Object -ComObject WScript.Shell
$lnk = $shell.CreateShortcut((Join-Path ([Environment]::GetFolderPath("Desktop")) "Tesco sign-in.lnk"))
$lnk.TargetPath = "cmd.exe"
$lnk.Arguments = "/k node login.mjs"
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
