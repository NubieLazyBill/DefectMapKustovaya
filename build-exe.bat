@echo off
chcp 65001 >nul
echo ========================================
echo СБОРКА EXE ДЛЯ DEFECTMAP (LAUNCH4J)
echo ========================================
echo.

echo 1. Очистка...
call gradlew clean
if errorlevel 1 (
    echo ❌ Ошибка очистки
    pause
    exit /b 1
)
echo ✅ Очистка завершена
echo.

echo 2. Сборка fat JAR...
call gradlew shadowJar
if errorlevel 1 (
    echo ❌ Ошибка сборки JAR
    pause
    exit /b 1
)
echo ✅ JAR собран
echo.

echo 3. Создание портативной сборки (jpackage)...
echo.
call gradlew buildPortable
if errorlevel 1 (
    echo ❌ Ошибка сборки EXE
    pause
    exit /b 1
)
echo.

echo ========================================
echo ✅ ГОТОВО!
echo EXE находится в папке: build\dist\DefectMap\
echo ========================================
pause