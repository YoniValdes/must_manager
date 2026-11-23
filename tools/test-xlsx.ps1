# Genera un .xlsx de prueba con XlsxWriter y lo lee con SheetJS (de analitica) para comprobar que es válido.
$ErrorActionPreference = "Stop"
$JDK = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { "D:\Android\jdk17" }
$root = Split-Path $PSScriptRoot
$out = Join-Path $root "android\build\test-xlsx"
New-Item -ItemType Directory -Force $out | Out-Null
& "$JDK\bin\javac.exe" -encoding UTF-8 -d $out "$root\android\src\com\jhonny\mustmanager\XlsxWriter.java" "$root\tools\XlsxTest.java"
& "$JDK\bin\java.exe" -cp $out XlsxTest "$out\prueba.xlsx"
$sheetjs = "D:/Git_Projects/analitica/app/vendor/xlsx.full.min.js"
node -e "const X=require('$sheetjs');const wb=X.read(require('fs').readFileSync(process.argv[1]),{cellDates:true});for(const n of wb.SheetNames){console.log('Hoja: '+n);X.utils.sheet_to_json(wb.Sheets[n],{header:1,raw:false,dateNF:'yyyy-mm-dd hh:mm:ss'}).forEach(r=>console.log('   '+JSON.stringify(r)))}" "$out\prueba.xlsx"
