@echo off
setlocal enabledelayedexpansion
title V380 Camera Phone APK Builder

:: ============================================================
::  V380 Camera Phone APK Builder — Windows
::  Builds without Android Studio using command-line tools only.
::
::  Prerequisites:
::    - JDK 17  (https://adoptium.net/)
::    - Android SDK command-line tools installed at SDK_ROOT
::      containing: build-tools\35.0.0  platforms\android-35  platform-tools
::
::  Usage:  build.bat
:: ============================================================

:: ── Edit these two lines to match your environment ──────────
set SDK_ROOT=C:\Android\cmdline-tools
:: ────────────────────────────────────────────────────────────

set BASE_DIR=%~dp0
set BUILD_TOOLS=%SDK_ROOT%\build-tools\35.0.0
set ANDROID_JAR=%SDK_ROOT%\platforms\android-35\android.jar
set ADB=%SDK_ROOT%\platform-tools\adb.exe
set AAPT2=%BUILD_TOOLS%\aapt2.exe
set D8=%BUILD_TOOLS%\d8.bat
set ZIPALIGN=%BUILD_TOOLS%\zipalign.exe
set APKSIGNER=%BUILD_TOOLS%\apksigner.bat

set APP_SRC=%BASE_DIR%app\src\main
set OUT_DIR=%BASE_DIR%build
set APK_NAME=V380CamPhone
set KEYSTORE=%BASE_DIR%debug.keystore
set PKG=com\example\v380cam

echo.
echo ============================================================
echo   V380 Camera Phone APK Builder
echo ============================================================
echo.

:: [1/7] CHECK JAVA
echo [1/7] Checking Java...
where java  >nul 2>&1 || ( echo ERROR: java not found. Install JDK 17. & pause & exit /b 1 )
where javac >nul 2>&1 || ( echo ERROR: javac not found. Install JDK 17. & pause & exit /b 1 )
where keytool >nul 2>&1 || ( echo ERROR: keytool not found. Install JDK 17. & pause & exit /b 1 )
java -version 2>&1 | findstr /i "version"
echo        OK

:: [2/7] VERIFY SDK TOOLS
echo.
echo [2/7] Verifying SDK tools...
if not exist "%AAPT2%"       ( echo ERROR: aapt2 not found at %AAPT2%       & pause & exit /b 1 )
if not exist "%ANDROID_JAR%" ( echo ERROR: android.jar not found at %ANDROID_JAR% & pause & exit /b 1 )
if not exist "%D8%"          ( echo ERROR: d8 not found at %D8%              & pause & exit /b 1 )
if not exist "%ZIPALIGN%"    ( echo ERROR: zipalign not found at %ZIPALIGN%  & pause & exit /b 1 )
if not exist "%APKSIGNER%"   ( echo ERROR: apksigner not found at %APKSIGNER% & pause & exit /b 1 )
echo        OK

:: [3/7] CLEAN BUILD DIRS
echo.
echo [3/7] Cleaning build directory...
if exist "%OUT_DIR%" rmdir /s /q "%OUT_DIR%"
mkdir "%OUT_DIR%\obj\%PKG%"
mkdir "%OUT_DIR%\dex"
mkdir "%OUT_DIR%\res"
mkdir "%OUT_DIR%\apk"
echo        OK

:: [4/7] COMPILE RESOURCES
echo.
echo [4/7] Compiling resources with aapt2...
"%AAPT2%" compile --dir "%APP_SRC%\res" -o "%OUT_DIR%\res"
if %errorlevel% neq 0 ( echo ERROR: aapt2 compile failed. & pause & exit /b 1 )

set FLAT_LIST=%OUT_DIR%\flat_files.txt
if exist "%FLAT_LIST%" del "%FLAT_LIST%"
for %%f in ("%OUT_DIR%\res\*.flat") do echo %%f >> "%FLAT_LIST%"

"%AAPT2%" link ^
    -o "%OUT_DIR%\apk\resources.ap_" ^
    -I "%ANDROID_JAR%" ^
    --manifest "%APP_SRC%\AndroidManifest.xml" ^
    --java "%OUT_DIR%\obj" ^
    --output-text-symbols "%OUT_DIR%\R.txt" ^
    @"%FLAT_LIST%"
if %errorlevel% neq 0 ( echo ERROR: aapt2 link failed. & pause & exit /b 1 )
echo        OK

:: [5/7] COMPILE JAVA
echo.
echo [5/7] Compiling Java sources...
dir /s /b "%APP_SRC%\java\*.java" >  "%OUT_DIR%\sources.txt"
dir /s /b "%OUT_DIR%\obj\*.java"  >> "%OUT_DIR%\sources.txt"

javac ^
    -source 8 -target 8 ^
    -classpath "%ANDROID_JAR%" ^
    -d "%OUT_DIR%\obj" ^
    @"%OUT_DIR%\sources.txt"
if %errorlevel% neq 0 ( echo ERROR: Java compilation failed. & pause & exit /b 1 )
echo        OK

:: [6/7] DEX
echo.
echo [6/7] Converting to DEX with d8...
set D8_CMD=call "%D8%" --output "%OUT_DIR%\dex" --lib "%ANDROID_JAR%"
for /r "%OUT_DIR%\obj" %%f in (*.class) do set D8_CMD=!D8_CMD! "%%f"
%D8_CMD%
if %errorlevel% neq 0 ( echo ERROR: d8 failed. & pause & exit /b 1 )
echo        OK

:: [7/7] PACKAGE + SIGN
echo.
echo [7/7] Packaging and signing APK...

copy /y "%OUT_DIR%\dex\classes.dex" "%OUT_DIR%\apk\classes.dex" >nul

powershell -NoProfile -Command ^
    "Add-Type -Assembly 'System.IO.Compression.FileSystem'; " ^
    "$ap  = '%OUT_DIR%\apk\resources.ap_'; " ^
    "$dex = '%OUT_DIR%\apk\classes.dex'; " ^
    "$out = '%OUT_DIR%\apk\%APK_NAME%-unsigned.apk'; " ^
    "Copy-Item $ap $out -Force; " ^
    "$zip    = [System.IO.Compression.ZipFile]::Open($out, 'Update'); " ^
    "$entry  = $zip.CreateEntry('classes.dex', 'Optimal'); " ^
    "$stream = $entry.Open(); " ^
    "$bytes  = [System.IO.File]::ReadAllBytes($dex); " ^
    "$stream.Write($bytes, 0, $bytes.Length); " ^
    "$stream.Close(); $zip.Dispose(); Write-Host 'Packaged OK'"
if %errorlevel% neq 0 ( echo ERROR: Packaging failed. & pause & exit /b 1 )

"%ZIPALIGN%" -f 4 "%OUT_DIR%\apk\%APK_NAME%-unsigned.apk" "%OUT_DIR%\apk\%APK_NAME%-aligned.apk"
if %errorlevel% neq 0 (
    copy /y "%OUT_DIR%\apk\%APK_NAME%-unsigned.apk" "%OUT_DIR%\apk\%APK_NAME%-aligned.apk" >nul
)

if not exist "%KEYSTORE%" (
    echo        Generating debug keystore...
    keytool -genkeypair ^
        -keystore "%KEYSTORE%" -alias androiddebugkey ^
        -keypass android -storepass android ^
        -dname "CN=Android Debug,O=Android,C=US" ^
        -keyalg RSA -keysize 2048 -validity 10000 -storetype PKCS12
    if %errorlevel% neq 0 ( echo ERROR: keytool failed. & pause & exit /b 1 )
)

call "%APKSIGNER%" sign ^
    --ks "%KEYSTORE%" --ks-pass pass:android ^
    --key-pass pass:android --ks-key-alias androiddebugkey ^
    --out "%OUT_DIR%\apk\%APK_NAME%-signed.apk" ^
    "%OUT_DIR%\apk\%APK_NAME%-aligned.apk"
if %errorlevel% neq 0 ( echo ERROR: apksigner failed. & pause & exit /b 1 )

call "%APKSIGNER%" verify --verbose "%OUT_DIR%\apk\%APK_NAME%-signed.apk" 2>&1 | findstr /i "verified"
echo        Signing OK

echo.
echo ============================================================
echo   BUILD SUCCESSFUL
echo ============================================================
echo.
echo   Signed APK: %OUT_DIR%\apk\%APK_NAME%-signed.apk
echo.

:: ── Optional: ADB install ─────────────────────────────────────
if not exist "%ADB%" (
    echo No ADB found at %ADB% — skipping install.
    echo To install manually: adb install %OUT_DIR%\apk\%APK_NAME%-signed.apk
    pause & exit /b 0
)

"%ADB%" devices | findstr /r "device$" >nul 2>&1
if %errorlevel% neq 0 (
    echo No ADB device connected — skipping install.
    echo To install manually: adb install %OUT_DIR%\apk\%APK_NAME%-signed.apk
    pause & exit /b 0
)

echo Installing APK on device...
"%ADB%" install -r "%OUT_DIR%\apk\%APK_NAME%-signed.apk"
echo Launching app...
"%ADB%" shell am start -n "com.example.v380cam/com.example.v380cam.MainActivity"

echo.
echo ============================================================
echo   APK installed and launched!
echo ============================================================
echo.
pause
