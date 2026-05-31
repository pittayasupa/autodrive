# ============================================================
# build_apk.ps1 — สร้าง APK แบบ portable (ไม่ต้องสิทธิ์ admin)
# ใช้ JDK + Android SDK + Gradle ที่แตก zip ไว้ใน tools\
# ============================================================
$t   = 'D:\AutoDrive\tools'
$sdk = "$t\android-sdk"

# --- 1) JAVA_HOME ---
$jdkDir = (Get-ChildItem "$t\jdk" -Directory | Where-Object { $_.Name -like 'jdk*' } | Select-Object -First 1).FullName
$env:JAVA_HOME = $jdkDir
$env:PATH = "$jdkDir\bin;$env:PATH"
Write-Output "JAVA_HOME = $jdkDir"

# --- 2) จัด layout cmdline-tools -> $sdk\cmdline-tools\latest ---
New-Item -ItemType Directory -Force "$sdk\cmdline-tools" | Out-Null
if (-not (Test-Path "$sdk\cmdline-tools\latest\bin")) {
    if (Test-Path "$t\cmdtools_tmp\cmdline-tools") {
        Move-Item "$t\cmdtools_tmp\cmdline-tools" "$sdk\cmdline-tools\latest" -Force
    }
}
$sm = "$sdk\cmdline-tools\latest\bin\sdkmanager.bat"

# --- 3) รับ license + ติดตั้ง package ---
Write-Output "== accepting licenses =="
$y = ("y`r`n") * 50
$y | & $sm --sdk_root="$sdk" --licenses 2>$null | Out-Null
Write-Output "== installing platform-tools / android-34 / build-tools 34.0.0 =="
& $sm --sdk_root="$sdk" "platform-tools" "platforms;android-34" "build-tools;34.0.0" 2>$null | Out-Null

# --- 4) โหลดโมเดล detection ---
$assets = 'D:\AutoDrive\android\app\src\main\assets'
New-Item -ItemType Directory -Force $assets | Out-Null
if (-not (Test-Path "$assets\efficientdet_lite2.tflite")) {
    Write-Output "== downloading model =="
    Invoke-WebRequest "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite2/float32/latest/efficientdet_lite2.tflite" -OutFile "$assets\efficientdet_lite2.tflite" -UseBasicParsing
}

# --- 5) build ---
$gradle = (Get-ChildItem "$t\gradle" -Directory | Where-Object { $_.Name -like 'gradle-*' } | Select-Object -First 1).FullName + "\bin\gradle.bat"
Write-Output "== gradle assembleDebug =="
Set-Location D:\AutoDrive\android
& $gradle assembleDebug --no-daemon --stacktrace 2>&1 | Tee-Object "D:\AutoDrive\build.log" | Select-Object -Last 4

# --- 6) เก็บ APK ---
$apk = "D:\AutoDrive\android\app\build\outputs\apk\debug\app-debug.apk"
if (Test-Path $apk) {
    Copy-Item $apk "D:\AutoDrive\AutoDrive.apk" -Force
    Write-Output ("APK READY -> D:\AutoDrive\AutoDrive.apk  ({0} MB)" -f [math]::Round((Get-Item $apk).Length/1MB, 1))
} else {
    Write-Output "BUILD FAILED — ดู D:\AutoDrive\build.log"
}
