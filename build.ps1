param([switch]$Package,[switch]$Installer,[switch]$Preview,[switch]$Integration,[string]$JdkPath,[string]$Version)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
if ($Installer) { $Package = $true }
if (!$Version) {
    $Version = (Select-String -LiteralPath (Join-Path $PSScriptRoot 'gradle.properties') -Pattern '^launcher_version\s*=\s*(.+)$').Matches.Groups[1].Value.Trim()
}
if ($Version -notmatch '^(\d+)\.(\d+)\.(\d+)(?:-[0-9A-Za-z][0-9A-Za-z.-]*)?(?:\+[0-9A-Za-z][0-9A-Za-z.-]*)?$') {
    throw 'Version must use MAJOR.MINOR.PATCH, optionally followed by a prerelease/build suffix.'
}
$nativeVersion = "$($Matches[1]).$($Matches[2]).$($Matches[3])"
if ([int]$Matches[1] -gt 255 -or [int]$Matches[2] -gt 255 -or [int]$Matches[3] -gt 65535) {
    throw 'Windows installer version requires major/minor <= 255 and patch <= 65535.'
}
if ($Installer) {
    Get-Command candle.exe,light.exe -ErrorAction Stop | Out-Null
}
if (!$JdkPath) { $JdkPath = $env:JAVA_HOME }
if (!$JdkPath) {
    $java = Get-Command java.exe -ErrorAction Stop
    $JdkPath = Split-Path (Split-Path $java.Source -Parent) -Parent
}
if (!$JdkPath -or !(Test-Path -LiteralPath "$JdkPath\bin\javac.exe")) { throw 'Set JAVA_HOME or pass -JdkPath with a full JDK 21.' }
if ($Package -and !(Test-Path -LiteralPath "$JdkPath\bin\jpackage.exe")) { throw 'Packaging requires a full JDK 21 with jpackage.' }
$env:JAVA_HOME = $JdkPath
$gradle = Join-Path $PSScriptRoot 'gradlew.bat'
$gradleArgs = @('build', "-Plauncher_version=$Version")
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
    & "$JdkPath\bin\jlink.exe" --module-path "$JdkPath\jmods;$sdk" --add-modules java.se,javafx.controls,javafx.swing,jdk.httpserver,jdk.crypto.ec,jdk.unsupported --strip-debug --no-header-files --no-man-pages --output $runtime
    if ($LASTEXITCODE -ne 0) { throw 'Runtime creation failed.' }
    Copy-Item -Path build\javafx\bin\*.dll -Destination "$runtime\bin" -Force
    & "$JdkPath\bin\jpackage.exe" --type app-image --name FuzzLauncher --dest $destination --input $inputDir --main-jar "fuzz-launcher-$Version.jar" --main-class com.fuzzlauncher.Launcher --runtime-image $runtime --app-version $nativeVersion --vendor Fuzz --icon 'src/main/resources/com/fuzzlauncher/launcher.ico' --java-options '--add-modules=javafx.controls,javafx.swing,jdk.httpserver'
    if ($LASTEXITCODE -ne 0) { throw 'Packaging failed.' }
    Copy-Item -LiteralPath README.md,THIRD_PARTY_NOTICES.md -Destination "$destination\FuzzLauncher"
    Copy-Item -LiteralPath docs -Destination "$destination\FuzzLauncher" -Recurse
    Write-Output "Portable application: $destination\FuzzLauncher\FuzzLauncher.exe"
    if ($Installer) {
        & "$JdkPath\bin\jpackage.exe" --type exe --name FuzzLauncher --app-image "$destination\FuzzLauncher" --dest "$destination\installer" --app-version $nativeVersion --vendor Fuzz --icon 'src/main/resources/com/fuzzlauncher/launcher.ico' --win-per-user-install --win-dir-chooser --win-shortcut --win-menu --win-menu-group FuzzLauncher --win-upgrade-uuid 'f9ebdb38-42a1-4bdb-8756-d66737e29ddc'
        if ($LASTEXITCODE -ne 0) { throw 'Windows installer creation failed.' }
        Write-Output "Windows installer: $destination\installer"
    }
}
