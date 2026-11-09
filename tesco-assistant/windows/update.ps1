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

Step "Stopping the helper"
StopHelper

Step "Downloading the latest files"
foreach ($f in "package.json", "server.mjs", "tesco-auth.mjs", "login.mjs", "weights.mjs", "probe-weights.mjs", "credentials.mjs", "save-password.mjs") {
  Invoke-WebRequest "$Repo/$f" -OutFile (Join-Path $Dir $f) -UseBasicParsing
  Write-Host "  $f"
}
Remove-Item (Join-Path $Dir "credential.ps1") -ErrorAction SilentlyContinue   # replaced by save-password.mjs
Push-Location $Dir
npm install --no-fund --no-audit | Out-Host
if ($LASTEXITCODE -ne 0) { Pop-Location; throw "npm install failed" }
Pop-Location

Step "Updating the 'Tesco sign-in' shortcut"
# Runs the Node sign-in directly in an ordinary command prompt: no PowerShell, and the helper
# keeps running (it picks up the new sign-in by itself).
Remove-Item (Join-Path $Dir "signin.ps1") -ErrorAction SilentlyContinue
$shell = New-Object -ComObject WScript.Shell
$lnk = $shell.CreateShortcut((Join-Path ([Environment]::GetFolderPath("Desktop")) "Tesco sign-in.lnk"))
$lnk.TargetPath = "cmd.exe"
$lnk.Arguments = "/k node login.mjs"
$lnk.WorkingDirectory = $Dir
$lnk.Save()

Step "Adding the 'Tesco password' shortcut (save or change the saved Tesco password)"
$shell = New-Object -ComObject WScript.Shell
$lnk = $shell.CreateShortcut((Join-Path ([Environment]::GetFolderPath("Desktop")) "Tesco password.lnk"))
$lnk.TargetPath = "cmd.exe"
$lnk.Arguments = "/k node save-password.mjs"
$lnk.WorkingDirectory = $Dir
$lnk.Save()

if ($SavePassword) {
  Step "Save your Tesco password"
  Push-Location $Dir
  node save-password.mjs
  Pop-Location
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

Step "Starting the helper (hidden, with a 5-minute watchdog that restarts it if it ever stops)"
Register-HelperTask
Remove-Item (Join-Path $Dir "run.ps1") -ErrorAction SilentlyContinue   # old PowerShell wrapper, no longer used
StopHelper   # make sure the new definition is the one that runs
Start-ScheduledTask -TaskName $TaskName
Start-Sleep -Seconds 15
try {
  $h = Invoke-RestMethod "http://127.0.0.1:8787/health" -TimeoutSec 10
  if ($h.signedIn) { Write-Host "`nAll set. The helper is running and signed in to Tesco." -ForegroundColor Green }
  else { Write-Host "`nThe helper is running but not signed in. Double-click 'Tesco sign-in' on the desktop." -ForegroundColor Yellow }
} catch {
  Write-Host "`nThe helper did not answer yet. Check $Dir\server.log for errors." -ForegroundColor Yellow
}
