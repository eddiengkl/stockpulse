@echo off
REM StockPulse - compile and start.
REM The JDK must be on PATH, or JAVA_HOME must point at a JDK 21 installation.
REM com.sun.net.httpserver is outside the public Java API, so it is compiled
REM without --release; the compiler does not warn about it in this build.

setlocal
if not "%JAVA_HOME%"=="" set "JAVAC=%JAVA_HOME%\bin\javac.exe"
if not "%JAVA_HOME%"=="" set "JAVA=%JAVA_HOME%\bin\java.exe"
if "%JAVAC%"=="" set "JAVAC=javac"
if "%JAVA%"=="" set "JAVA=java"

echo Compiling...
if exist out rmdir /s /q out
mkdir out
dir /s /b src\*.java > out\sources.txt
"%JAVAC%" -encoding UTF-8 -d out @out\sources.txt
if errorlevel 1 (
    echo.
    echo Compilation failed. Nothing was started.
    exit /b 1
)
del out\sources.txt

echo Starting StockPulse...
"%JAVA%" -cp out app.StockPulseApp %*
endlocal
