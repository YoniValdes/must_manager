# Prueba el protocolo del BMS en el PC (sin tablet) con tramas reales capturadas.
$ErrorActionPreference = "Stop"
$JDK = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { "D:\Android\jdk17" }
$root = Split-Path $PSScriptRoot
$out = Join-Path $root "android\build\test"
New-Item -ItemType Directory -Force $out | Out-Null
& "$JDK\bin\javac.exe" -encoding UTF-8 -d $out "$root\android\src\com\jhonny\mustmanager\BmsProtocol.java" "$root\tools\ProtocolTest.java"
if ($LASTEXITCODE -ne 0) { exit 1 }
& "$JDK\bin\java.exe" -cp $out ProtocolTest
exit $LASTEXITCODE
