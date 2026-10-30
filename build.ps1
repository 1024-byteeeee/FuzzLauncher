param([switch]$Package,[switch]$Installer,[switch]$Preview,[switch]$Integration,[string]$JdkPath,[string]$Version)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
if ($Installer) { $Package = $true }
if (!$Version) {
    $Version = (Select-String -LiteralPath (Join-Path $PSScriptRoot 'gradle.properties') -Pattern '^launcher_version\s*=\s*(.+)$').Matches.Groups[1].Value.Trim()
}
$nativeVersion = if ($Version -match '^(\d+)\.(\d+)\.(\d+)') {
    "$($Matches[1]).$($Matches[2]).$($Matches[3])"
} else {
    (Select-String -LiteralPath (Join-Path $PSScriptRoot 'gradle.properties') -Pattern '^launcher_version\s*=\s*(.+)$').Matches.Groups[1].Value.Trim()
}
if (-not ($nativeVersion -match '^(\d+)\.(\d+)\.(\d+)$')) {
    throw 'Unable to determine a MAJOR.MINOR.PATCH version for the Windows package.'
}
if ([int]$Matches[1] -gt 255 -or [int]$Matches[2] -gt 255 -or [int]$Matches[3] -gt 65535) {
    throw 'Windows installer version requires major/minor <= 255 and patch <= 65535.'
}
if ($Installer) {
    Get-Command iscc.exe -ErrorAction Stop | Out-Null
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
    & "$JdkPath\bin\jlink.exe" --module-path "$JdkPath\jmods;$sdk" --add-modules java.se,javafx.controls,javafx.swing,jdk.httpserver,jdk.crypto.ec,jdk.unsupported,jdk.management --strip-debug --no-header-files --no-man-pages --exclude-files 'glob:**/legal/**,glob:**/README*,glob:**/readme*' --output $runtime
    if ($LASTEXITCODE -ne 0) { throw 'Runtime creation failed.' }
    Copy-Item -Path build\javafx\bin\*.dll -Destination "$runtime\bin" -Force
    & "$JdkPath\bin\jpackage.exe" --type app-image --name FuzzLauncher --dest $destination --input $inputDir --main-jar "fuzz-launcher-$Version.jar" --main-class com.fuzzlauncher.Launcher --runtime-image $runtime --app-version $nativeVersion --vendor '1024_byteeeee' --icon 'src/main/resources/com/fuzzlauncher/launcher.ico' --java-options '--add-modules=javafx.controls,javafx.swing,jdk.httpserver,jdk.management'
    if ($LASTEXITCODE -ne 0) { throw 'Packaging failed.' }
    Write-Output "Portable application: $destination\FuzzLauncher\FuzzLauncher.exe"
    if ($Installer) {
        & iscc.exe "/DAppVersion=$nativeVersion" "/DSrcDir=$destination\FuzzLauncher" "/DOutputDir=$destination\installer" (Join-Path $PSScriptRoot 'FuzzLauncher.iss')
        if ($LASTEXITCODE -ne 0) { throw 'Windows installer creation failed.' }
        Write-Output "Windows installer: $destination\installer"
    }
}
