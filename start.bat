@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo Запуск DefectMap из: %CD%

set "JAR=build\libs\DefectMap.jar"
if not exist "%JAR%" (
    echo Не найден %JAR%
    echo Соберите: gradlew shadowJar
    pause
    exit /b 1
)

java -Dfile.encoding=UTF-8 -Dprism.order=sw -jar "%JAR%" %*
if errorlevel 1 pause