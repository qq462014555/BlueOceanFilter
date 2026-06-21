@echo off
setlocal enabledelayedexpansion
set WRAPPER_JAR=%~dp0.mvn\wrapper\maven-wrapper.jar
set WRAPPER_LAUNCHER=-Dmaven.multiModuleProjectDirectory=%~dp0
java -cp "%WRAPPER_JAR%" %WRAPPER_LAUNCHER% org.apache.maven.wrapper.MavenWrapperMain %*
