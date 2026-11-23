# Compila Must Manager (APK) sin Gradle, llamando directamente a las herramientas del SDK.
# Uso:  .\android\build.ps1            → genera dist\MustManager-<versión>.apk
#       .\android\build.ps1 -Install   → además la instala en la tablet conectada por USB (adb)
param([switch]$Install)
$ErrorActionPreference = "Stop"

$SDK = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "D:\Android\sdk" }
$JDK = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { "D:\Android\jdk17" }
$env:JAVA_HOME = $JDK
$env:PATH = "$JDK\bin;$env:PATH"
$BT = "$SDK\build-tools\35.0.0"
$AJ = "$SDK\platforms\android-34\android.jar"
$ADB = "$SDK\platform-tools\adb.exe"

$here = $PSScriptRoot                      # carpeta android\
$root = Split-Path $here                   # raíz del repo
$build = Join-Path $here "build"
$dist = Join-Path $root "dist"
$secrets = Join-Path $root "secretos"
$ks = Join-Path $secrets "mustmanager.keystore"
$pwFile = Join-Path $secrets "firma.txt"

function Run($exe, [string[]]$arguments) {
    & $exe @arguments
    if ($LASTEXITCODE -ne 0) { throw "Falló: $exe $($arguments -join ' ')" }
}

$manifest = Get-Content (Join-Path $here "AndroidManifest.xml") -Raw
$ver = [regex]::Match($manifest, 'versionName="([^"]+)"').Groups[1].Value
Write-Host "== Must Manager $ver =="

# Firma: se crea una sola vez y se guarda en secretos\ (fuera de Git).
# ¡Haz copia de seguridad! Sin ella no se podrá actualizar la app instalada sin desinstalarla.
if (-not (Test-Path $ks)) {
    New-Item -ItemType Directory -Force $secrets | Out-Null
    $chars = [char[]]'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789'
    $pw = -join (1..24 | ForEach-Object { $chars | Get-Random })
    Set-Content -Path $pwFile -Value $pw -NoNewline
    Run "$JDK\bin\keytool.exe" @("-genkeypair", "-keystore", $ks, "-alias", "mustmanager", "-keyalg", "RSA",
        "-keysize", "2048", "-validity", "10000", "-dname", "CN=Must Manager", "-storepass", $pw, "-keypass", $pw)
    Write-Host "Firma nueva creada en $ks"
}
$pw = (Get-Content $pwFile -Raw).Trim()

Remove-Item -Recurse -Force $build -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$build\obj", "$build\dex", "$build\gen", "$build\assets\www", $dist | Out-Null
Copy-Item -Recurse "$root\app\*" "$build\assets\www\"

Write-Host "1/6 Recursos (aapt2)"
Run "$BT\aapt2.exe" @("compile", "--dir", "$here\res", "-o", "$build\res.zip")
# Sin -A: en Windows aapt2 guarda los assets con "\" (assets/www\index.html) y Android no los encuentra.
# Los añadimos nosotros en el paso 4 con rutas "/".
Run "$BT\aapt2.exe" @("link", "-o", "$build\base.apk", "-I", $AJ, "--manifest", "$here\AndroidManifest.xml",
    "--java", "$build\gen", "$build\res.zip")

Write-Host "2/6 Java (javac)"
$sources = Get-ChildItem -Recurse "$here\src", "$build\gen" -Filter *.java | ForEach-Object { $_.FullName }
Run "$JDK\bin\javac.exe" (@("-nowarn", "-encoding", "UTF-8", "--release", "8", "-cp", $AJ, "-d", "$build\obj") + $sources)

Write-Host "3/6 DEX (d8)"
$classes = Get-ChildItem -Recurse "$build\obj" -Filter *.class | ForEach-Object { $_.FullName }
Run "$BT\d8.bat" (@("--release", "--min-api", "24", "--lib", $AJ, "--output", "$build\dex") + $classes)

Write-Host "4/6 Empaquetar classes.dex y la interfaz (assets/www)"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open("$build\base.apk", "Update")
[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$build\dex\classes.dex", "classes.dex") | Out-Null
$assetsRoot = "$build\assets"
Get-ChildItem -Recurse -File $assetsRoot | ForEach-Object {
    $rel = "assets/" + $_.FullName.Substring($assetsRoot.Length + 1).Replace('\', '/')
    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $_.FullName, $rel) | Out-Null
}
$zip.Dispose()

Write-Host "5/6 Alinear (zipalign)"
Run "$BT\zipalign.exe" @("-f", "-p", "4", "$build\base.apk", "$build\aligned.apk")

Write-Host "6/6 Firmar (apksigner)"
$apk = Join-Path $dist "MustManager-$ver.apk"
Run "$BT\apksigner.bat" @("sign", "--ks", $ks, "--ks-pass", "pass:$pw", "--key-pass", "pass:$pw", "--out", $apk, "$build\aligned.apk")
Write-Host ("Listo: {0} ({1:N0} KB)" -f $apk, ((Get-Item $apk).Length / 1KB))

if ($Install) {
    Write-Host "Instalando en la tablet (adb)…"
    Run $ADB @("install", "-r", $apk)
}
