@echo off
setlocal
set "APP_HOME=%~dp0"
set "GRADLE_VERSION=8.13"
if "%GRADLE_USER_HOME%"=="" set "GRADLE_USER_HOME=%USERPROFILE%\.gradle"
set "CACHE_DIR=%GRADLE_USER_HOME%\manual-wrapper"
set "GRADLE_HOME_DIR=%CACHE_DIR%\gradle-%GRADLE_VERSION%"
set "ZIP_PATH=%CACHE_DIR%\gradle-%GRADLE_VERSION%-bin.zip"
set "URL=https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip"

if not exist "%GRADLE_HOME_DIR%\bin\gradle.bat" (
  if not exist "%CACHE_DIR%" mkdir "%CACHE_DIR%"
  echo Gradle %GRADLE_VERSION% is not cached; downloading...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -UseBasicParsing -Uri '%URL%' -OutFile '%ZIP_PATH%'; Expand-Archive -Force '%ZIP_PATH%' '%CACHE_DIR%'"
  if errorlevel 1 exit /b 1
)

call "%GRADLE_HOME_DIR%\bin\gradle.bat" -p "%APP_HOME%" %*
endlocal
