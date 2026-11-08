<#
  tesco-assistant: keep your Tesco email and password in Windows Credential Manager.

    powershell -ExecutionPolicy Bypass -File credential.ps1 -Save     # asks for them and saves them
    powershell -ExecutionPolicy Bypass -File credential.ps1 -Delete   # removes them

  The entry is "tesco-assistant:tesco.com" (Control Panel > Credential Manager >
  Windows Credentials). Windows encrypts it for your user account only.
  The helper uses -Read when Tesco asks for a fresh sign-in; nothing is ever logged.
#>
param([switch]$Save, [switch]$Read, [switch]$Delete)

$ErrorActionPreference = "Stop"
$Target = "tesco-assistant:tesco.com"

if (-not ("TescoCred" -as [type])) {
  Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
using System.Text;
public static class TescoCred {
  [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
  struct CREDENTIAL {
    public int Flags; public int Type; public string TargetName; public string Comment;
    public System.Runtime.InteropServices.ComTypes.FILETIME LastWritten;
    public int CredentialBlobSize; public IntPtr CredentialBlob; public int Persist;
    public int AttributeCount; public IntPtr Attributes; public string TargetAlias; public string UserName;
  }
  [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)] static extern bool CredWrite(ref CREDENTIAL c, int flags);
  [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)] static extern bool CredRead(string target, int type, int flags, out IntPtr c);
  [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)] static extern bool CredDelete(string target, int type, int flags);
  [DllImport("advapi32.dll")] static extern void CredFree(IntPtr p);
  const int GENERIC = 1, PERSIST_LOCAL_MACHINE = 2;
  public static void Write(string target, string user, string secret) {
    byte[] b = Encoding.Unicode.GetBytes(secret);
    var c = new CREDENTIAL { Type = GENERIC, TargetName = target, UserName = user, Persist = PERSIST_LOCAL_MACHINE, CredentialBlobSize = b.Length };
    c.CredentialBlob = Marshal.AllocCoTaskMem(b.Length);
    try {
      Marshal.Copy(b, 0, c.CredentialBlob, b.Length);
      if (!CredWrite(ref c, 0)) throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
    } finally { Marshal.FreeCoTaskMem(c.CredentialBlob); }
  }
  public static string[] Read(string target) {
    IntPtr p;
    if (!CredRead(target, GENERIC, 0, out p)) return null;
    try {
      var c = (CREDENTIAL)Marshal.PtrToStructure(p, typeof(CREDENTIAL));
      return new string[] { c.UserName, Marshal.PtrToStringUni(c.CredentialBlob, c.CredentialBlobSize / 2) };
    } finally { CredFree(p); }
  }
  public static bool Delete(string target) { return CredDelete(target, GENERIC, 0); }
}
"@
}

# A successful save or delete should let the helper try automatic sign-in again straight away.
$state = Join-Path $env:USERPROFILE ".basketeer\renewal-state.json"

if ($Save) {
  Write-Host "Your Tesco email and password will be saved in Windows Credential Manager" -ForegroundColor Cyan
  Write-Host "(encrypted for your Windows account) so the helper can sign in to Tesco by itself." -ForegroundColor Cyan
  $user = Read-Host "Tesco email address"
  $sec = Read-Host "Tesco password (not shown)" -AsSecureString
  $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec)
  try { $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
  if (-not $user -or -not $plain) { throw "Email and password are both needed." }
  [TescoCred]::Write($Target, $user.Trim(), $plain)
  $plain = $null
  Remove-Item $state -ErrorAction SilentlyContinue
  Write-Host "Saved as '$Target'. You can view or remove it in Control Panel > Credential Manager." -ForegroundColor Green
}
elseif ($Read) {
  $r = [TescoCred]::Read($Target)
  if (-not $r) { exit 2 }
  $json = @{ u = $r[0]; p = $r[1] } | ConvertTo-Json -Compress
  [Console]::Out.Write([Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($json)))
}
elseif ($Delete) {
  if ([TescoCred]::Delete($Target)) { Write-Host "Removed '$Target'." } else { Write-Host "Nothing saved under '$Target'." }
  Remove-Item $state -ErrorAction SilentlyContinue
}
else {
  Write-Host "Use -Save, -Read or -Delete."
}
