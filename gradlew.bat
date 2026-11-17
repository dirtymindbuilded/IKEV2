@echo off
setlocal
set GRADLE_VERSION=8.9
if "%GRADLE_USER_HOME%"=="" set GRADLE_USER_HOME=%USERPROFILE%\.gradle
set BASE_DIR=%GRADLE_USER_HOME%\ikev2-manager
set DIST_DIR=%BASE_DIR%\gradle-%GRADLE_VERSION%
set GRADLE_EXE=%DIST_DIR%\bin\gradle.bat
if not exist "%GRADLE_EXE%" (
  if not exist "%BASE_DIR%" mkdir "%BASE_DIR%"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%TEMP%\gradle-%GRADLE_VERSION%-bin.zip'; Expand-Archive -Force '%TEMP%\gradle-%GRADLE_VERSION%-bin.zip' '%BASE_DIR%'; Remove-Item '%TEMP%\gradle-%GRADLE_VERSION%-bin.zip' -Force"
  if errorlevel 1 exit /b 1
)
call "%GRADLE_EXE%" %*
endlocal
