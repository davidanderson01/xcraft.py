param(
    [ValidateSet("github", "local")]
    [string]$Source = "github",
    [string]$Target = "elevatecraft/xcraftpy",
    [string]$Device = ""
)

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$PyScript = Join-Path $ScriptDir "xcraft_summon.py"

$ArgsList = @($PyScript, "--source", $Source, "--target", $Target)
if ($Device -ne "") {
    $ArgsList += @("--device", $Device)
}

& python @ArgsList
