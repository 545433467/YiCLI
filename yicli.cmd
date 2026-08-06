@echo off
setlocal EnableExtensions
rem ============================================================
rem  YiCLI launcher for Windows cmd
rem
rem  Usage:
rem    yicli.cmd                      interactive CLI (run mvn clean package first)
rem    yicli.cmd doctor               environment check
rem    yicli.cmd wechat setup         bind WeChat iLink channel
rem    yicli.cmd wechat start         start WeChat channel
rem    yicli.cmd serve --http --port 8080    start Runtime API
rem
rem  Optional environment variables:
rem    YICLI_JAR        override jar path (default: target\yicli-1.0-SNAPSHOT.jar)
rem    YICLI_JAVA_OPTS  extra JVM arguments
rem ============================================================

rem Remember the current code page and restore it on exit.
for /f "tokens=2 delims=:" %%a in ('chcp') do set "OLD_CP=%%a"
set "OLD_CP=%OLD_CP: =%"

rem Switch to UTF-8 so CJK text and ANSI colors render correctly.
chcp 65001 >nul

set "APP_HOME=%~dp0"

if defined YICLI_JAR (
    set "JAR=%YICLI_JAR%"
) else (
    set "JAR=%APP_HOME%target\yicli-1.0-SNAPSHOT.jar"
)

if not exist "%JAR%" (
    echo [yicli] jar not found: %JAR%
    echo [yicli] run "mvn clean package" in the project root first.
    goto :fail
)

where java >nul 2>nul
if errorlevel 1 (
    echo [yicli] java not found. Install JDK 17+ and add it to PATH.
    goto :fail
)

set "YICLI_JAVA_OPTS=%YICLI_JAVA_OPTS% -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"

java %YICLI_JAVA_OPTS% -jar "%JAR%" %*
set "EXIT_CODE=%errorlevel%"
goto :end

:fail
set "EXIT_CODE=1"

:end
if defined OLD_CP chcp %OLD_CP% >nul
exit /b %EXIT_CODE%
