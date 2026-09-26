@rem
@rem Copyright 2015 the original author or authors.
@rem
@rem Licensed under the Apache License, Version 2.0 (the "License");
@rem you may not use this file except in compliance with the License.
@rem You may obtain a copy of the License at
@rem
@rem      https://www.apache.org/licenses/LICENSE-2.0
@rem
@rem Unless required by applicable law or agreed to in writing, software
@rem distributed under the License is distributed on an "AS IS" BASIS,
@rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@rem See the License for the specific language governing permissions and
@rem limitations under the License.
@rem
@rem SPDX-License-Identifier: Apache-2.0
@rem

@if "%DEBUG%"=="" @echo off
@rem ##########################################################################
@rem
@rem  Gradle startup script for Windows
@rem
@rem ##########################################################################

@rem Set local scope for the variables with windows NT shell
if "%OS%"=="Windows_NT" setlocal

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
@rem This is normally unused
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

@rem Resolve any "." and ".." in APP_HOME to make it shorter.
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi

@rem The Windows JDK/Gradle test worker corrupts non-ASCII project paths when
@rem its native code page is GBK. Transparently re-enter this same wrapper
@rem through a verified ASCII junction. The second invocation sees an ASCII
@rem APP_HOME and continues below, so this cannot recurse.
if "%FITNESS_GRADLE_ASCII_ACTIVE%"=="1" goto fitnessAsciiWorkspaceReady
set "FITNESS_ASCII_WORKSPACE=__RESOLUTION_FAILED__"
set "FITNESS_RESOLVED_JAVA_HOME=__RESOLUTION_FAILED__"
for /f "tokens=1,* delims==" %%a in ('pwsh.exe -NoProfile -File "%APP_HOME%\scripts\Resolve-GradleAsciiWorkspace.ps1" 2^>^&1') do (
    if "%%a"=="FITNESS_ASCII_WORKSPACE" set "FITNESS_ASCII_WORKSPACE=%%b"
    if "%%a"=="FITNESS_JAVA_HOME" set "FITNESS_RESOLVED_JAVA_HOME=%%b"
)
if "%FITNESS_ASCII_WORKSPACE%"=="__RESOLUTION_FAILED__" (
    echo ERROR: Could not verify an ASCII workspace for Gradle. Ensure pwsh.exe, git.exe, and scripts\Resolve-GradleAsciiWorkspace.ps1 are available. 1>&2
    goto fail
)
if "%FITNESS_RESOLVED_JAVA_HOME%"=="__RESOLUTION_FAILED__" (
    echo ERROR: Could not locate the policy-pinned JDK. Set JAVA_HOME or jdk.dir in local.properties. 1>&2
    goto fail
)
set "JAVA_HOME=%FITNESS_RESOLVED_JAVA_HOME%"
if "%FITNESS_ASCII_WORKSPACE%"=="DIRECT" goto fitnessAsciiWorkspaceReady
set "FITNESS_GRADLE_ASCII_ACTIVE=1"
pushd "%FITNESS_ASCII_WORKSPACE%"
if %ERRORLEVEL% neq 0 goto fail
call "%FITNESS_ASCII_WORKSPACE%\gradlew.bat" %*
set "FITNESS_REDIRECT_EXIT=%ERRORLEVEL%"
popd
if "%OS%"=="Windows_NT" endlocal & exit /b %FITNESS_REDIRECT_EXIT%
exit /b %FITNESS_REDIRECT_EXIT%

:fitnessAsciiWorkspaceReady

@rem Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.
set DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"

@rem Find java.exe
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH. 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

goto fail

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe

if exist "%JAVA_EXE%" goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME% 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

goto fail

:execute
@rem Setup the command line

set CLASSPATH=


@rem Execute Gradle
"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -classpath "%CLASSPATH%" -jar "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" %*

:end
@rem End local scope for the variables with windows NT shell
if %ERRORLEVEL% equ 0 goto mainEnd

:fail
rem Set variable GRADLE_EXIT_CONSOLE if you need the _script_ return code instead of
rem the _cmd.exe /c_ return code!
set EXIT_CODE=%ERRORLEVEL%
if %EXIT_CODE% equ 0 set EXIT_CODE=1
if not ""=="%GRADLE_EXIT_CONSOLE%" exit %EXIT_CODE%
exit /b %EXIT_CODE%

:mainEnd
if "%OS%"=="Windows_NT" endlocal

:omega
