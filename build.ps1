param([switch]$Package,[switch]$Preview,[switch]$Integration,[string]$JdkPath)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
if (!$JdkPath) { $JdkPath = $env:JAVA_HOME }
if (!$JdkPath) {
    $java = Get-Command java.exe -ErrorAction Stop
    $JdkPath = Split-Path (Split-Path $java.Source -Parent) -Parent
}
if (!$JdkPath -or !(Test-Path -LiteralPath "$JdkPath\bin\javac.exe")) { throw 'Set JAVA_HOME or pass -JdkPath with a full JDK 21.' }
if ($Package -and !(Test-Path -LiteralPath "$JdkPath\bin\jpackage.exe")) { throw 'Packaging requires a full JDK 21 with jpackage.' }
$env:JAVA_HOME = $JdkPath
$gradle = Join-Path $PSScriptRoot 'gradlew.bat'
$gradleArgs = @('build')
if ($Integration) { $gradleArgs += '-Dfuzz.integration=true' }
if ($Package) { $gradleArgs += @('preparePackage', 'prepareJavafx') }
& $gradle @gradleArgs
if ($LASTEXITCODE -ne 0) { throw 'Build or verification failed.' }
if ($Preview) {
    & $gradle 'run' '-Dfuzz.snapshot=true'
    if ($LASTEXITCODE -ne 0) { throw 'Preview failed.' }
}
if ($Package) {
    $buildId = Get-Date -Format 'yyyyMMdd-HHmmss'
    $runtime = Join-Path $PSScriptRoot "build\runtime-$buildId"
    $inputDir = Join-Path $PSScriptRoot 'build\package-input'
    $sdk = Join-Path $PSScriptRoot 'build\javafx\lib'
    $destination = Join-Path $PSScriptRoot "dist\$buildId"
    $version = (Select-String -LiteralPath (Join-Path $PSScriptRoot 'gradle.properties') -Pattern '^launcher_version\s*=\s*(.+)$').Matches.Groups[1].Value.Trim()
    if (!$version) { throw 'Missing launcher_version in gradle.properties.' }
    & "$JdkPath\bin\jlink.exe" --module-path "$JdkPath\jmods;$sdk" --add-modules java.se,javafx.controls,javafx.swing,jdk.httpserver,jdk.crypto.ec,jdk.unsupported --strip-debug --no-header-files --no-man-pages --output $runtime
    if ($LASTEXITCODE -ne 0) { throw 'Runtime creation failed.' }
    Copy-Item -Path build\javafx\bin\*.dll -Destination "$runtime\bin" -Force
    & "$JdkPath\bin\jpackage.exe" --type app-image --name FuzzLauncher --dest $destination --input $inputDir --main-jar "fuzz-launcher-$version.jar" --main-class com.fuzzlauncher.Launcher --runtime-image $runtime --app-version $version --vendor Fuzz --icon 'src/main/resources/com/fuzzlauncher/launcher.ico' --java-options '--add-modules=javafx.controls,javafx.swing,jdk.httpserver'
    if ($LASTEXITCODE -ne 0) { throw 'Packaging failed.' }
    Copy-Item -LiteralPath README.md,THIRD_PARTY_NOTICES.md -Destination "$destination\FuzzLauncher"
    Copy-Item -LiteralPath docs -Destination "$destination\FuzzLauncher" -Recurse
    Write-Output "Portable application: $destination\FuzzLauncher\FuzzLauncher.exe"
}
