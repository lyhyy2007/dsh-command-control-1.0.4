# Generates a local maven shim for net.neoforged:minecraft-dependencies:<mcversion>.
#
# Why: that pom is only published on maven.neoforged.net, which is unreachable in some
# networks, and no reachable mirror carries it. Its content is just "the list of Minecraft
# libraries for this version", which is exactly the `libraries` array of Mojang's own
# version manifest. So we rebuild the pom from authoritative data instead of guessing.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\gen-minecraft-dependencies.ps1
#   ... -McVersion 1.21.1 -Manifest "C:\path\to\minecraft_1.21.1_version_manifest.json"
param(
    [string]$McVersion = "1.21.1",
    [string]$Manifest = ""
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot

if (-not $Manifest) {
    $candidates = @(
        (Join-Path $env:USERPROFILE ".gradle\caches\neoformruntime\artifacts\minecraft_${McVersion}_version_manifest.json"),
        (Join-Path $projectRoot "build\minecraft_${McVersion}_version_manifest.json")
    )
    $Manifest = ($candidates | Where-Object { Test-Path $_ } | Select-Object -First 1)
}
if (-not $Manifest -or -not (Test-Path $Manifest)) {
    throw "Version manifest not found. Pass -Manifest <path> (file: minecraft_${McVersion}_version_manifest.json)."
}

$manifestJson = Get-Content $Manifest -Raw -Encoding UTF8 | ConvertFrom-Json
if (-not $manifestJson.libraries) { throw "No 'libraries' array in $Manifest" }

$deps = New-Object System.Collections.Generic.List[string]
$seen = @{}
foreach ($lib in $manifestJson.libraries) {
    $name = $lib.name
    if (-not $name) { continue }
    # only entries that actually have a main artifact (skip natives-only classifiers)
    if (-not $lib.downloads -or -not $lib.downloads.artifact) { continue }
    $parts = $name.Split(':')
    if ($parts.Count -lt 3) { continue }
    $classifier = if ($parts.Count -ge 4) { $parts[3] } else { $null }
    $key = "$($parts[0]):$($parts[1]):$($parts[2]):$classifier"
    if ($seen.ContainsKey($key)) { continue }
    $seen[$key] = $true
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.Append("      <dependency>`n")
    [void]$sb.Append("        <groupId>$($parts[0])</groupId>`n")
    [void]$sb.Append("        <artifactId>$($parts[1])</artifactId>`n")
    [void]$sb.Append("        <version>$($parts[2])</version>`n")
    if ($classifier) {
        # keep the classifier out: the NeoForm pipeline only needs the plain artifacts
        continue
    }
    [void]$sb.Append("      </dependency>")
    $deps.Add($sb.ToString())
}

$pom = @"
<?xml version="1.0" encoding="UTF-8"?>
<!--
  Locally generated shim for net.neoforged:minecraft-dependencies:$McVersion
  Source of truth: Mojang version manifest ($(Split-Path $Manifest -Leaf))
  Regenerate with: tools/gen-minecraft-dependencies.ps1
-->
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>net.neoforged</groupId>
  <artifactId>minecraft-dependencies</artifactId>
  <version>$McVersion</version>
  <packaging>pom</packaging>
  <name>Minecraft $McVersion libraries (local shim)</name>
  <dependencies>
$($deps -join "`n")
  </dependencies>
</project>
"@

$outDir = Join-Path $projectRoot "local-maven\net\neoforged\minecraft-dependencies\$McVersion"
New-Item -ItemType Directory -Force $outDir | Out-Null
$outFile = Join-Path $outDir "minecraft-dependencies-$McVersion.pom"
[System.IO.File]::WriteAllText($outFile, $pom, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "generated $outFile with $($deps.Count) dependencies"
