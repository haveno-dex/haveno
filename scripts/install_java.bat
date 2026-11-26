:: This script will download and install the appropriate JDK for use with Haveno development.
:: It will also configure it as the default system JDK.
:: If you need to change to another default JDK for another purpose later, you just need to
:: change the JAVA_HOME environment variable. For example, use the following command:
::     setx /M JAVA_HOME "<JDK_PATH>"

@echo off

:: Ensure we have administrative privileges in order to install files and set environment variables
>nul 2>&1 "%SYSTEMROOT%\system32\cacls.exe" "%SYSTEMROOT%\system32\config\system"
if '%errorlevel%' == '0' (
    ::If no error is encountered, we have administrative privileges
    goto GotAdminPrivileges
)
echo Requesting administrative privileges...
echo Set UAC = CreateObject^("Shell.Application"^) > "%temp%\getadminprivileges.vbs"
set params = %*:"=""
echo UAC.ShellExecute "%~s0", "%params%", "", "runas", 1 >> "%temp%\getadminprivileges.vbs"
"%temp%\getadminprivileges.vbs"
exit /B
:GotAdminPrivileges
if exist "%temp%\getadminprivileges.vbs" ( del "%temp%\getadminprivileges.vbs" )
title Install Java

powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File "%~dp0install_java_windows.ps1" -ConfigureMachine
if errorlevel 1 exit /b %errorlevel%

echo Done!
pause
