@echo off
echo Running ./gradlew buildNativeLibs...
call ./gradlew buildNativeLibraries

if %ERRORLEVEL% neq 0 (
    echo Error while building native libraries.
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo Native libraries built successfully.
echo.
echo Running ./gradlew run...
call ./gradlew run

if %ERRORLEVEL% neq 0 (
    echo Error while running the application.
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo Execution completed successfully.
pause