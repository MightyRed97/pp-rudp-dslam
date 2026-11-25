<#
Rebuilds the offline kit on a Windows x64 computer with internet access:
  1. downloads the JDK, Gradle, Node.js and the Android command-line tools, each checked against the
     publisher's checksum, and unpacks them into offline\;
  2. installs the Android SDK packages the app needs into offline\android-sdk (this accepts Google's
     Android SDK licence);
  3. builds, tests and lints the app once with an empty Gradle home, so that Gradle downloads every
     library, and copies those libraries into offline\m2repository (plus aapt2 for Linux and macOS);
  4. copies the app to offline\app\IrudpChat-debug.apk.
Folders that are already there are kept; delete one to fetch it again.

  powershell -ExecutionPolicy Bypass -File offline\tools\make-kit.ps1 [-Downloads <folder with the archives>]
#>
param([string]$Downloads = '')
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$kit = Split-Path -Parent $PSScriptRoot
$root = Split-Path -Parent $kit
$work = Join-Path $kit '_work'
if (-not $Downloads) { $Downloads = Join-Path $work 'downloads' }
New-Item -ItemType Directory -Force $work, $Downloads | Out-Null

function Get-Checked($url, $algorithm, $sum) {
  $file = Join-Path $Downloads ([IO.Path]::GetFileName(([Uri]$url).AbsolutePath))
  if (-not (Test-Path $file)) {
    Write-Host "downloading $url"
    Invoke-WebRequest -Uri $url -OutFile $file -UseBasicParsing
  }
  $got = (Get-FileHash -Algorithm $algorithm $file).Hash.ToLower()
  if ($got -ne $sum) { throw "checksum mismatch for $file ($got)" }
  return $file
}

function Expand-Into($zip, $target) {          # unpack and drop the archive's single top folder
  $tmp = Join-Path $work 'unpack'
  if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
  Expand-Archive -Path $zip -DestinationPath $tmp
  $top = @(Get-ChildItem $tmp)
  if ($top.Count -ne 1) { throw "unexpected layout in $zip" }
  New-Item -ItemType Directory -Force (Split-Path -Parent $target) | Out-Null
  Move-Item $top[0].FullName $target
  Remove-Item -Recurse -Force $tmp
}

# 1. tools
$tools = @(
  @{ Dir = 'jdk'; Algo = 'SHA256'; Sum = 'eafba0944f777abdc74d5aad16049c9b5a1275b6ce99e2939ba9f7f3e6f08c87'
     Url = 'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.21.1%2B2/OpenJDK17U-jdk_x64_windows_hotspot_17.0.21.1_2.zip' },
  @{ Dir = 'gradle'; Algo = 'SHA256'; Sum = 'd725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab'
     Url = 'https://services.gradle.org/distributions/gradle-8.9-bin.zip' },
  @{ Dir = 'node'; Algo = 'SHA256'; Sum = '2b8f2256382f97ad51e29ff71f702961af466c4616393f767455501e6aece9b8'
     Url = 'https://nodejs.org/dist/v22.12.0/node-v22.12.0-win-x64.zip' },
  @{ Dir = 'android-sdk\cmdline-tools\latest'; Algo = 'SHA1'; Sum = '57d04f2d75eb8e8fffc5000a987e5de4b5a63e9d'
     Url = 'https://dl.google.com/android/repository/commandlinetools-win-16111833_latest.zip' }
)
foreach ($t in $tools) {
  $target = Join-Path $kit $t.Dir
  if (Test-Path $target) { Write-Host "$($t.Dir): already there"; continue }
  Expand-Into (Get-Checked $t.Url $t.Algo $t.Sum) $target
  Write-Host "$($t.Dir): unpacked"
}

# 2. Android SDK packages
$env:JAVA_HOME = Join-Path $kit 'jdk'
$sdk = Join-Path $kit 'android-sdk'
$sdkmanager = Join-Path $sdk 'cmdline-tools\latest\bin\sdkmanager.bat'
$packages = Join-Path $work 'sdk-packages.txt'
Set-Content -Path $packages -Value @('platforms;android-35', 'build-tools;34.0.0', 'platform-tools') -Encoding ascii
("y`n" * 20) | & $sdkmanager "--sdk_root=$sdk" --licenses | Out-Null
& $sdkmanager "--sdk_root=$sdk" "--package_file=$packages" | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'sdkmanager failed' }
Write-Host 'Android SDK packages installed'

# 3. libraries: one build with an empty Gradle home, without the old repository
$repo = Join-Path $kit 'm2repository'
if (Test-Path $repo) {
  $old = "$repo-old-$(Get-Date -Format yyyyMMddHHmmss)"
  Move-Item $repo $old
  Write-Host "the previous repository was moved to $old"
}
# a short path: Gradle unpacks aapt2.exe deep inside its home, and Windows cannot start a program whose
# path is longer than 260 characters (the build then fails with "AAPT2 ... Daemon startup failed")
$gradleHome = Join-Path ([IO.Path]::GetTempPath()) 'irudp-kit-gradle-home'
if (Test-Path $gradleHome) { Remove-Item -Recurse -Force $gradleHome }
$env:GRADLE_USER_HOME = $gradleHome
$env:ANDROID_HOME = $sdk
& (Join-Path $kit 'gradle\bin\gradle.bat') -p (Join-Path $root 'android') --no-daemon testDebugUnitTest assembleDebug lintDebug
if ($LASTEXITCODE -ne 0) { throw 'the build failed' }
& (Join-Path $kit 'node\node.exe') (Join-Path $PSScriptRoot 'gradle-cache-to-maven.js') $gradleHome $repo
if ($LASTEXITCODE -ne 0) { throw 'copying the libraries failed' }
$aapt2 = Get-ChildItem (Join-Path $repo 'com\android\tools\build\aapt2') -Directory | Select-Object -First 1
foreach ($os in 'linux', 'osx') {                # the build only fetched the aapt2 of this system
  $name = "aapt2-$($aapt2.Name)-$os.jar"
  $url = "https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2/$($aapt2.Name)/$name"
  $content = (Invoke-WebRequest -Uri "$url.sha1" -UseBasicParsing).Content
  if ($content -is [byte[]]) { $content = [Text.Encoding]::ASCII.GetString($content) }   # served as bytes
  Copy-Item (Get-Checked $url 'SHA1' $content.Trim()) (Join-Path $aapt2.FullName $name)
}
Write-Host "libraries copied to $repo"

# 4. the app
New-Item -ItemType Directory -Force (Join-Path $kit 'app') | Out-Null
Copy-Item (Join-Path $root 'android\app\build\outputs\apk\debug\app-debug.apk') (Join-Path $kit 'app\IrudpChat-debug.apk') -Force
Remove-Item -Recurse -Force $gradleHome
Write-Host "done: the kit is in $kit"
