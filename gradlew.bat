@echo off
setlocal
set "APP_HOME=%~dp0"
set "GRADLE_VERSION=9.7.1"
set "EXPECTED_SHA=acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a"
if not defined GRADLE_USER_HOME set "GRADLE_USER_HOME=%USERPROFILE%\.gradle"
set "DIST_ROOT=%GRADLE_USER_HOME%\wrapper\dists\gradle-9.7.1-bin\%EXPECTED_SHA%"
set "GRADLE_HOME=%DIST_ROOT%\gradle-%GRADLE_VERSION%"

if not exist "%GRADLE_HOME%\bin\gradle.bat" (
  if not exist "%DIST_ROOT%" mkdir "%DIST_ROOT%"
  powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$archive = Join-Path $env:TEMP 'gradle-9.7.1-bin.zip';" ^
    "Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-9.7.1-bin.zip' -OutFile $archive;" ^
    "if ((Get-FileHash $archive -Algorithm SHA256).Hash.ToLower() -ne '%EXPECTED_SHA%') { throw 'Gradle distribution checksum mismatch.' };" ^
    "Expand-Archive -Path $archive -DestinationPath '%DIST_ROOT%' -Force;" ^
    "Remove-Item $archive"
  if errorlevel 1 exit /b 1
)

call "%GRADLE_HOME%\bin\gradle.bat" %*
exit /b %ERRORLEVEL%
