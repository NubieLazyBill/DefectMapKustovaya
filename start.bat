@echo off
cd /d "%~dp0"
echo Запуск из: %CD%

set JAVA_HOME=%CD%\runtime
set PATH=%JAVA_HOME%\bin;%PATH%

"%JAVA_HOME%\bin\java" -Dprism.order=sw -Dprism.forceGPU=true --module-path "%JAVA_HOME%\lib" --add-modules javafx.controls,javafx.fxml,javafx.web,javafx.media,javafx.swing -jar app\DefectMap.jar
pause