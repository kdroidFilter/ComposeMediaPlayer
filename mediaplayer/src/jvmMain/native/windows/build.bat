@echo off
setlocal

echo === Starting compilation for x64 and ARM64 ===

rem Clean previous build directories to ensure a fresh build
if exist build-x64 rmdir /s /q build-x64
if exist build-arm64 rmdir /s /q build-arm64

rem Clear local DLL cache so the JVM loader picks up the new build
set "NATIVE_CACHE=%LOCALAPPDATA%\composemediaplayer\native"
if exist "%NATIVE_CACHE%" (
    echo Clearing native DLL cache: %NATIVE_CACHE%
    rmdir /s /q "%NATIVE_CACHE%"
)

echo.
echo === x64 Configuration ===
cmake -B build-x64 -A x64 .
if %ERRORLEVEL% neq 0 (
    echo Error during x64 configuration
    exit /b %ERRORLEVEL%
)

echo.
echo === x64 Compilation ===
cmake --build build-x64 --config Release
if %ERRORLEVEL% neq 0 (
    echo Error during x64 compilation
    exit /b %ERRORLEVEL%
)

echo.
echo === ARM64 Configuration ===
cmake -B build-arm64 -A ARM64 .
if %ERRORLEVEL% neq 0 (
    echo Error during ARM64 configuration
    exit /b %ERRORLEVEL%
)

echo.
echo === ARM64 Compilation ===
cmake --build build-arm64 --config Release
if %ERRORLEVEL% neq 0 (
    echo Error during ARM64 compilation
    exit /b %ERRORLEVEL%
)

echo.
echo === Compilation completed successfully for both architectures ===
echo.

rem Clean up build directories
if exist build-x64 rmdir /s /q build-x64
if exist build-arm64 rmdir /s /q build-arm64

echo x64 DLL: ..\..\resources\composemediaplayer\native\win32-x86-64\NativeVideoPlayer.dll
echo ARM64 DLL: ..\..\resources\composemediaplayer\native\win32-arm64\NativeVideoPlayer.dll

endlocal
