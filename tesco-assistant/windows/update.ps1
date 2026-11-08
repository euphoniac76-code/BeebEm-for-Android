<#
  tesco-assistant: update an existing Windows install (no admin rights needed).

    & ([scriptblock]::Create((irm https://raw.githubusercontent.com/euphoniac76-code/BeebEm-for-Android/<commit>/tesco-assistant/windows/update.ps1))) -Ref <commit>

  Downloads the latest helper files, points the "Tesco sign-in" shortcut at the
  new sign-in, learns once how tesco.com adds weighed items (probe-weights.mjs),
  and restarts the helper. Add -SignIn to sign in to Tesco again as well.
#>
param(
  [string]$Ref = "ccr-56360ad8-iesdrj",
  [switch]$SignIn,        # also sign in to Tesco again
  [switch]$SkipWeightProbe,
  [switch]$SavePassword   # save your Tesco email + password in Windows Credential Manager
)

$ErrorActionPreference = "Stop"
$Repo = "https://raw.githubusercontent.com/euphoniac76-code/BeebEm-for-Android/$Ref/tesco-assistant"
$Dir = Join-Path $env:LOCALAPPDATA "tesco-assistant"
$TaskName = "Tesco Assistant"
if (-not (Test-Path (Join-Path $Dir "server.mjs"))) { throw "tesco-assistant is not installed in $Dir. Run setup.ps1 first." }

function Step($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function StopHelper {
  Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
  Get-CimInstance Win32_Process -Filter "Name='node.exe'" |
    Where-Object { $_.CommandLine -like '*server.mjs*' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}

Step "Stopping the helper"
StopHelper

Step "Downloading the latest files"
foreach ($f in "package.json", "server.mjs", "tesco-auth.mjs", "login.mjs", "weights.mjs", "probe-weights.mjs", "credentials.mjs") {
  Invoke-WebRequest "$Repo/$f" -OutFile (Join-Path $Dir $f) -UseBasicParsing
  Write-Host "  $f"
}
Invoke-WebRequest "$Repo/windows/credential.ps1" -OutFile (Join-Path $Dir "credential.ps1") -UseBasicParsing
Write-Host "  credential.ps1"
Push-Location $Dir
npm install --no-fund --no-audit | Out-Host
if ($LASTEXITCODE -ne 0) { Pop-Location; throw "npm install failed" }
Pop-Location

Step "Updating the 'Tesco sign-in' shortcut"
$signinScript = Join-Path $Dir "signin.ps1"
@"
Write-Host 'Re-signing in to Tesco...' -ForegroundColor Cyan
Stop-ScheduledTask -TaskName '$TaskName' -ErrorAction SilentlyContinue
Get-CimInstance Win32_Process -Filter "Name='node.exe'" | Where-Object { `$_.CommandLine -like '*server.mjs*' } | ForEach-Object { Stop-Process -Id `$_.ProcessId -Force }
Set-Location '$Dir'
node login.mjs
Start-ScheduledTask -TaskName '$TaskName'
Write-Host 'Done. You can close this window.' -ForegroundColor Green
Read-Host 'Press Enter to close'
"@ | Set-Content -Encoding UTF8 $signinScript

Step "Adding the 'Tesco password' shortcut (save or change the saved Tesco password)"
$credScript = Join-Path $Dir "credential.ps1"
$shell = New-Object -ComObject WScript.Shell
$lnk = $shell.CreateShortcut((Join-Path ([Environment]::GetFolderPath("Desktop")) "Tesco password.lnk"))
$lnk.TargetPath = "powershell.exe"
$lnk.Arguments = "-NoProfile -ExecutionPolicy Bypass -NoExit -File `"$credScript`" -Save"
$lnk.WorkingDirectory = $Dir
$lnk.Save()

if ($SavePassword) {
  Step "Save your Tesco password"
  & $credScript -Save
}

if ($SignIn) {
  Step "Sign in to Tesco"
  Push-Location $Dir
  node login.mjs
  $ok = $LASTEXITCODE -eq 0
  Pop-Location
  if (-not $ok) { Write-Host "Sign-in did not complete. Double-click 'Tesco sign-in' on the desktop to try again." -ForegroundColor Yellow }
}

$fmt = Join-Path $env:USERPROFILE ".basketeer\weight-format.json"
$learned = $false
try { $learned = [bool]((Get-Content $fmt -Raw | ConvertFrom-Json).weightOptions.weightKey) } catch {}
if (-not $SkipWeightProbe -and -not $learned) {
  Step "Learning how Tesco adds weighed items (one-off, about a minute)"
  Write-Host "A Chrome window will open and add a pork fillet and some loose bananas to your basket," -ForegroundColor Yellow
  Write-Host "then take them out again. Please don't touch it while it works." -ForegroundColor Yellow
  Push-Location $Dir
  node probe-weights.mjs
  Pop-Location
}

Step "Starting the helper"
Start-ScheduledTask -TaskName $TaskName
Start-Sleep -Seconds 15
try {
  $h = Invoke-RestMethod "http://127.0.0.1:8787/health" -TimeoutSec 10
  if ($h.signedIn) { Write-Host "`nAll set. The helper is running and signed in to Tesco." -ForegroundColor Green }
  else { Write-Host "`nThe helper is running but not signed in. Double-click 'Tesco sign-in' on the desktop." -ForegroundColor Yellow }
} catch {
  Write-Host "`nThe helper did not answer yet. Check $Dir\server.log for errors." -ForegroundColor Yellow
}
