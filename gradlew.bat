@rem
@rem Gradle startup script (Windows)
@rem
@rem NOTE: This script adds a fallback on top of the standard Gradle Wrapper:
@rem       if gradle\wrapper\gradle-wrapper.jar is missing (this repo does not
@rem       ship binaries) and a system-wide `gradle` exists on PATH, it
@rem       generates the wrapper automatically, then continues.
@rem       See README section 4.1.
@rem       Keep this file pure ASCII: cmd.exe parses .bat files in the ANSI
@rem       codepage (GBK on zh-CN systems), and UTF-8 CJK text corrupts parsing.
@rem

@if "%DEBUG%"=="" @echo off
@rem ##########################################################################
@rem Locate java
@rem ##########################################################################
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto findAppHome

echo ERROR: JAVA_HOME is not set and java.exe was not found on PATH.
echo        Install JDK 17 (required by AGP 8.x) and retry.
goto fail

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe
if exist "%JAVA_EXE%" goto findAppHome

echo ERROR: JAVA_HOME is set but bin\java.exe was not found inside it.
goto fail

:findAppHome
set APP_HOME=%~dp0
set CLASSPATH=%APP_HOME%gradle\wrapper\gradle-wrapper.jar

@rem ##########################################################################
@rem Fallback: regenerate the wrapper jar if it is missing
@rem ##########################################################################
if exist "%CLASSPATH%" goto runGradle

echo NOTE: gradle\wrapper\gradle-wrapper.jar not found.
where gradle >NUL 2>&1
if %ERRORLEVEL% neq 0 goto noGradle

echo       System gradle detected, generating wrapper ...
pushd "%APP_HOME%"
call gradle wrapper --gradle-version 8.9 --distribution-type bin
popd
if %ERRORLEVEL% neq 0 goto fail
if not exist "%CLASSPATH%" goto fail
echo       Wrapper generated, continuing.
goto runGradle

:noGradle
echo.
echo       Pick one of the following to generate the wrapper:
echo         A. Install Gradle 8.9, then run: gradle wrapper --gradle-version 8.9 --distribution-type bin
echo         B. Open this project in Android Studio, which generates it automatically (recommended)
echo.
goto fail

:runGradle
"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% ^
  "-Dorg.gradle.appname=gradlew" ^
  -classpath "%CLASSPATH%" ^
  org.gradle.wrapper.GradleWrapperMain %*
if %ERRORLEVEL% neq 0 goto fail
goto end

:fail
exit /b 1

:end
exit /b 0
