@echo off
setlocal EnableExtensions EnableDelayedExpansion
rem ============================================================
rem  YiCLI global command installer (Windows)
rem
rem  Usage:
rem    yicli-install.cmd             install global "yicli" command
rem    yicli-install.cmd uninstall   remove the global "yicli" command
rem
rem  Installs a small shim into %USERPROFILE%\.yicli\bin and adds it
rem  to the user PATH, so `yicli` works from any directory, just like
rem  the `claude` command from Claude Code.
rem ============================================================

set "APP_HOME=%~dp0"
set "BIN_DIR=%USERPROFILE%\.yicli\bin"
set "SHIM=%BIN_DIR%\yicli.cmd"
set "SOURCE_SHIM=%APP_HOME%yicli.cmd"
set "JAR=%APP_HOME%target\yicli-1.0-SNAPSHOT.jar"

if /i "%~1"=="uninstall" goto :uninstall

if not exist "%SOURCE_SHIM%" (
    echo [yicli] launcher not found: %SOURCE_SHIM%
    exit /b 1
)
if not exist "%JAR%" (
    echo [yicli] jar not found: %JAR%
    echo [yicli] run "mvn clean package" in the project root first.
    exit /b 1
)

if not exist "%BIN_DIR%" mkdir "%BIN_DIR%"

> "%SHIM%" (
    echo @echo off
    echo setlocal EnableExtensions
    echo set "YICLI_JAR=%JAR%"
    echo call "%SOURCE_SHIM%" %%*
    echo exit /b %%errorlevel%%
)

call :ensure_path "%BIN_DIR%"

echo [yicli] global command installed: %SHIM%
echo [yicli] user PATH updated: %BIN_DIR%
echo [yicli] open a new terminal, then type: yicli
exit /b 0

:uninstall
if exist "%SHIM%" del "%SHIM%"
call :remove_path "%BIN_DIR%"
echo [yicli] global yicli command removed.
exit /b 0

:ensure_path
set "NEW_DIR=%~1"
set "CUR_PATH="
for /f "tokens=2*" %%a in ('reg query "HKCU\Environment" /v Path 2^>nul') do set "CUR_PATH=%%b"
if defined CUR_PATH (
    rem Exact per-entry match: pipe children lose delayed expansion and duplicate entries.
    for %%p in ("!CUR_PATH:;=" "!") do (
        if /i "%%~p"=="!NEW_DIR!" exit /b 0
    )
    set "NEW_PATH=!CUR_PATH!;!NEW_DIR!"
) else (
    set "NEW_PATH=!NEW_DIR!"
)
reg add "HKCU\Environment" /v Path /t REG_EXPAND_SZ /d "!NEW_PATH!" /f >nul
exit /b 0

:remove_path
set "REM_DIR=%~1"
set "CUR_PATH="
for /f "tokens=2*" %%a in ('reg query "HKCU\Environment" /v Path 2^>nul') do set "CUR_PATH=%%b"
if not defined CUR_PATH exit /b 0
set "NEW_PATH="
for %%p in ("!CUR_PATH:;=" "!") do (
    set "ITEM=%%~p"
    if /i not "!ITEM!"=="!REM_DIR!" if /i not "!ITEM!"=="!REM_DIR!\" (
        if defined NEW_PATH (
            set "NEW_PATH=!NEW_PATH!;!ITEM!"
        ) else (
            set "NEW_PATH=!ITEM!"
        )
    )
)
reg add "HKCU\Environment" /v Path /t REG_EXPAND_SZ /d "!NEW_PATH!" /f >nul
exit /b 0
