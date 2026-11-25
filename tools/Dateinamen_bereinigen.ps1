# Entfernt angehängte Prüfsummen aus ROM-Dateinamen, z. B.
#   Spiel (USA).MD.B362B705.339A8C9A96FC...  ->  Spiel (USA).MD
# Aufruf: Dateinamen_bereinigen.ps1 <Ordner> [-Test]
#   -Test zeigt nur an, was umbenannt würde.
param(
    [Parameter(Mandatory = $true, Position = 0)][string]$Ordner,
    [switch]$Test
)

if (-not (Test-Path -LiteralPath $Ordner -PathType Container)) {
    Write-Host "Ordner nicht gefunden: $Ordner" -ForegroundColor Red
    exit 1
}

$muster = '^(?<name>.+\.[A-Za-z0-9]{1,5})(\.[0-9A-Fa-f]{8,64})+$'
$umbenannt = 0; $uebersprungen = 0

Get-ChildItem -LiteralPath $Ordner -Recurse -File | ForEach-Object {
    if ($_.Name -match $muster) {
        $neu = $Matches['name']
        $ziel = Join-Path $_.DirectoryName $neu
        if (Test-Path -LiteralPath $ziel) {
            Write-Host "UEBERSPRUNGEN (Ziel existiert): $($_.Name)" -ForegroundColor Yellow
            $uebersprungen++
        } elseif ($Test) {
            Write-Host "WUERDE UMBENENNEN: $($_.Name)  ->  $neu"
            $umbenannt++
        } else {
            Rename-Item -LiteralPath $_.FullName -NewName $neu
            Write-Host "umbenannt: $neu"
            $umbenannt++
        }
    }
}
Write-Host ""
Write-Host "Fertig. Umbenannt: $umbenannt, übersprungen: $uebersprungen"
