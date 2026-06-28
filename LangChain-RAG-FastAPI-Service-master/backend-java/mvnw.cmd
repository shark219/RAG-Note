@REM Maven Wrapper script for Windows
@REM ----------------------------------------------------------------------------
@REM Licensed to the Apache Software Foundation (ASF)
@REM
@REM Required ENV vars:
@REM JAVA_HOME - location of a JDK home dir
@REM
@REM Optional ENV vars:
@REM MAVEN_OPTS - parameters passed to the Java VM when running Maven

@echo off
setlocal

set MAVEN_CMD=%~dp0.mvn\wrapper\maven-wrapper.jar
set WRAPPER_URL=https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.2/maven-wrapper-3.3.2.jar

if not "%JAVA_HOME%"=="" (
    set JAVA_CMD="%JAVA_HOME%\bin\java.exe"
) else (
    set JAVA_CMD=java.exe
)

if exist "%MAVEN_CMD%" (
    goto run
)

echo Downloading Maven Wrapper...
mkdir "%~dp0.mvn\wrapper" 2>nul
powershell -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri '%WRAPPER_URL%' -OutFile '%MAVEN_CMD%'" 2>nul
if %ERRORLEVEL% neq 0 (
    echo Failed to download Maven Wrapper using PowerShell, trying certutil...
    certutil -urlcache -split -f "%WRAPPER_URL%" "%MAVEN_CMD%"
)
if not exist "%MAVEN_CMD%" (
    echo ERROR: Failed to download Maven Wrapper JAR.
    echo Please download manually from: %WRAPPER_URL%
    echo And place it at: %MAVEN_CMD%
    exit /b 1
)

:run
%JAVA_CMD% -Dmaven.multiModuleProjectDirectory=%~dp0 %MAVEN_OPTS% -classpath "%MAVEN_CMD%" org.apache.maven.wrapper.MavenWrapperMain %*
