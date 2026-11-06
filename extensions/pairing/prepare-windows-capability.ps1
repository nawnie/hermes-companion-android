$ErrorActionPreference = 'Stop'
if (-not $env:APPDATA) { throw 'APPDATA is not set.' }
$root = (Resolve-Path -LiteralPath $env:APPDATA).Path
foreach ($segment in @('Hermes', 'plugin-files', 'data')) {
    $root = Join-Path $root $segment
    if (Test-Path -LiteralPath $root) {
        $node = Get-Item -LiteralPath $root -Force
        if (-not $node.PSIsContainer -or ($node.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "Refusing a non-directory or linked ancestor: $root"
        }
    } else {
        New-Item -ItemType Directory -Path $root | Out-Null
    }
}
$target = Join-Path $root 'hermes-companion-pairing'
if (Test-Path -LiteralPath $target) {
    throw 'The plugin data directory already exists. Inspect its owner, links and ACL before making changes.'
}
New-Item -ItemType Directory -Path $target | Out-Null
$acl = New-Object System.Security.AccessControl.DirectorySecurity
$acl.SetAccessRuleProtection($true, $false)
$inherit = [System.Security.AccessControl.InheritanceFlags]::ContainerInherit -bor [System.Security.AccessControl.InheritanceFlags]::ObjectInherit
$propagate = [System.Security.AccessControl.PropagationFlags]::None
$userSid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
foreach ($sid in @($userSid, [System.Security.Principal.SecurityIdentifier]'S-1-5-18', [System.Security.Principal.SecurityIdentifier]'S-1-5-32-544')) {
    $rule = New-Object System.Security.AccessControl.FileSystemAccessRule($sid, 'FullControl', $inherit, $propagate, 'Allow')
    [void]$acl.AddAccessRule($rule)
}
Set-Acl -LiteralPath $target -AclObject $acl
Get-Acl -LiteralPath $target | Format-List Owner, AreAccessRulesProtected, AccessToString
