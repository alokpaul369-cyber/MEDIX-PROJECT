@echo off
rem Starts MediX. Needs Java 8 or newer ("java" on the PATH).
rem "ant package-fat" copies this file next to dist\MediX-all.jar.
rem The database (data\medix.db) is created next to the jar.
cd /d "%~dp0"
if exist MediX-all.jar (
    java -jar MediX-all.jar
) else (
    java -jar dist\MediX-all.jar
)
if errorlevel 1 (
    echo.
    echo MediX could not start. Check that Java 8 or newer is installed: java -version
    pause
)
