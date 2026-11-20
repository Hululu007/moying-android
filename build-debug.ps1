[CmdletBinding()]
param(
    [string]$JdkPath,
    [string]$SdkPath,
    [string[]]$Task = @(':app:assembleDebug'),
    [string]$DeviceSerial = $env:ANDROID_SERIAL,
    [switch]$SkipInstall
)

# 发布构建成功后通过 ADB 覆盖安装到指定手机，-SkipInstall 可只打包。
# 只在当前进程内设置环境，临时映射中文路径，结束后恢复环境/本地配置并释放盘符。
$ErrorActionPreference = 'Stop'
$projectDirectory = $PSScriptRoot
$oldJavaHome = $env:JAVA_HOME
$oldAndroidHome = $env:ANDROID_HOME
$oldAndroidSdkRoot = $env:ANDROID_SDK_ROOT
$localPropertiesPath = Join-Path $projectDirectory 'local.properties'
$hadLocalProperties = Test-Path -LiteralPath $localPropertiesPath
$localPropertiesBytes = if ($hadLocalProperties) { [IO.File]::ReadAllBytes($localPropertiesPath) } else { $null }
$projectDrive = $null
$sdkDrive = $null
$exitCode = 1

try {
    $jdkCandidates = if ($JdkPath) { @($JdkPath) } else {
        @($env:JAVA_HOME, (Join-Path $projectDirectory '..\..\work\toolchains\jdk17'))
    }
    $selectedJdk = $null
    foreach ($candidate in $jdkCandidates) {
        if (-not $candidate) { continue }
        $javaExecutable = Join-Path $candidate 'bin\java.exe'
        if (-not (Test-Path -LiteralPath $javaExecutable)) { continue }
        $javaVersion = (& $javaExecutable -version 2>&1 | Out-String)
        if ($javaVersion -match 'version "17\.') { $selectedJdk = (Resolve-Path -LiteralPath $candidate).Path; break }
    }
    if (-not $selectedJdk) { throw '未找到 JDK 17。请运行 .\build-debug.ps1 -JdkPath "C:\path\to\jdk-17"。' }

    if (-not $SdkPath) {
        $sdkCandidates = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android\Sdk'), 'C:\AndroidGlassSdk')
        $SdkPath = $sdkCandidates | Where-Object { $_ -and (Test-Path -LiteralPath (Join-Path $_ 'platforms\android-35\android.jar')) } | Select-Object -First 1
    }
    if (-not $SdkPath -or -not (Test-Path -LiteralPath (Join-Path $SdkPath 'platforms\android-35\android.jar'))) {
        throw '未找到 Android SDK Platform 35。请在 Android Studio 安装，并用 -SdkPath 指定 SDK 目录。'
    }
    $selectedSdk = (Resolve-Path -LiteralPath $SdkPath).Path
    $freeLetters = @('ZYXWVUTSRQPONMLKJIHGFED'.ToCharArray() | Where-Object { -not (Test-Path -LiteralPath ("{0}:\" -f $_)) })
    if ($freeLetters.Count -lt 2) { throw '需要两个空闲盘符，供工程和 SDK 临时映射。' }
    $projectDrive = '{0}:' -f $freeLetters[0]
    $sdkDrive = '{0}:' -f $freeLetters[1]
    $substExecutable = Join-Path $env:WINDIR 'System32\subst.exe'
    & $substExecutable $projectDrive $projectDirectory
    if ($LASTEXITCODE -ne 0) { throw '创建工程临时盘符失败。' }
    & $substExecutable $sdkDrive $selectedSdk
    if ($LASTEXITCODE -ne 0) { throw '创建 SDK 临时盘符失败。' }

    $env:JAVA_HOME = $selectedJdk
    $env:ANDROID_HOME = "$sdkDrive\"
    $env:ANDROID_SDK_ROOT = "$sdkDrive\"
    [IO.File]::WriteAllText($localPropertiesPath, "sdk.dir=$($sdkDrive.Replace('\','/'))/`n", [Text.Encoding]::ASCII)
    Write-Host "JDK 17：$selectedJdk"
    Write-Host "工程临时盘符：$projectDrive；SDK 临时盘符：$sdkDrive"
    & "$projectDrive\gradlew.bat" -p "$projectDrive\" @Task --no-daemon --console=plain
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) { throw "Gradle 构建失败，退出码：$exitCode" }
    if ($Task -contains ':app:assembleRelease' -or $Task -contains 'assembleRelease') {
        $apkRelativePath = 'app\build\outputs\apk\release\app-release.apk'
        Write-Host "构建完成：$apkRelativePath"
        if (-not $SkipInstall) {
            $adb = Join-Path $selectedSdk 'platform-tools\adb.exe'
            if ([string]::IsNullOrWhiteSpace($DeviceSerial)) {
                Write-Host '发布 APK 已生成。可用 -DeviceSerial 指定安装目标。'
            } elseif (-not (Test-Path -LiteralPath $adb -PathType Leaf)) {
                Write-Warning '未找到 ADB，发布 APK 已生成。'
            } else {
                $phoneState = & $adb devices
                if ($LASTEXITCODE -ne 0) { throw 'ADB 设备列表读取失败，发布 APK 已生成。' }
                if ($phoneState -match "^$([regex]::Escape($DeviceSerial))\s+device\s*$") {
                    & $adb -s $DeviceSerial install -r "$projectDrive\$apkRelativePath"
                    if ($LASTEXITCODE -ne 0) { throw 'ADB 覆盖安装失败，发布 APK 已生成。' }
                } else {
                    Write-Warning '目标手机未连接或 USB 调试未授权，发布 APK 已生成。'
                }
            }
        }
    } else {
        Write-Host 'Gradle 构建完成。'
    }
}
finally {
    $env:JAVA_HOME = $oldJavaHome
    $env:ANDROID_HOME = $oldAndroidHome
    $env:ANDROID_SDK_ROOT = $oldAndroidSdkRoot
    if ($hadLocalProperties) { [IO.File]::WriteAllBytes($localPropertiesPath, $localPropertiesBytes) }
    elseif (Test-Path -LiteralPath $localPropertiesPath) { Remove-Item -LiteralPath $localPropertiesPath -Force }
    if ($sdkDrive) { & (Join-Path $env:WINDIR 'System32\subst.exe') $sdkDrive /d | Out-Null }
    if ($projectDrive) { & (Join-Path $env:WINDIR 'System32\subst.exe') $projectDrive /d | Out-Null }
}
