# DefectMap — карта дефектов оборудования подстанций

Десктоп-приложение: схема подстанции (SVG) в `WebView`, маркеры оборудования и дефектов,
карточки оборудования, отчёт по дефектам с фильтрами, экспорт в Excel и печать.

Стек: **Kotlin 1.9.23 · JDK 17 · JavaFX 21 · Gradle 8.11 · SQLite (sqlite-jdbc) · Gson · Apache POI**

Поддерживаемые платформы: **Windows 10/11**, **Linux (ALT Linux p10, Debian/Ubuntu, Astra)**,
macOS. Портативная сборка — `jpackage` (`app-image`), пакеты — `.deb` / `.rpm`, AppImage.

## Быстрый старт

```bash
./gradlew shadowJar
java -jar build/libs/DefectMap.jar              # графический интерфейс
java -jar build/libs/DefectMap.jar --selftest   # проверка окружения, GUI не нужен
java -jar build/libs/DefectMap.jar --help       # все команды
```

На Linux без дисплея (сервер, CI):

```bash
xvfb-run -a java -jar build/libs/DefectMap.jar --uicheck --show --snapshot /tmp/ui.png
xvfb-run -a java -jar build/libs/DefectMap.jar --appsmoke --snapshot /tmp/app.png
```

## Сборка пакетов

```bash
./gradlew buildPortable     # портативная папка build/dist/DefectMap/
./gradlew buildDeb          # .deb  (Linux)
./gradlew buildRpm          # .rpm  (Linux, родной формат ALT)
./gradlew buildAppImage     # AppImage (Linux, нужен curl)
./gradlew packageCurrentOs  # всё, что применимо к текущей ОС
```

## Где лежат данные

| Режим | Каталог |
|---|---|
| Портативный (Windows, распакованный AppImage) | рядом с JAR |
| Установка из пакета | `~/.local/share/defectmap` (Linux XDG), `%LOCALAPPDATA%\DefectMap` (Windows) |
| Переопределение | `-Ddefectmap.home=/path` или `DEFECTMAP_HOME=/path` |

В каталоге данных: `equipment_<ПС>.db`, `substations.json`, `app_settings.json`,
`backups/`, `schemas/`, `images/`.

## Как получить запускаемый файл

**Windows** (ничего устанавливать не нужно):

```powershell
.\gradlew shadowJar        # build/libs/DefectMap.jar → java -jar build\libs\DefectMap.jar
.\gradlew buildPortable    # build/dist/DefectMap/DefectMap.exe (свой runtime)
```

**Linux — без установки (самое простое):**

| Вариант | Что делать |
|---|---|
| AppImage (один файл) | `chmod +x DefectMap-1.1.0-x86_64.AppImage && ./DefectMap-1.1.0-x86_64.AppImage` |
| без FUSE | `APPIMAGE_EXTRACT_AND_RUN=1 ./DefectMap-1.1.0-x86_64.AppImage` |
| папка app-image | `build/dist/DefectMap/bin/DefectMap` — просто запустить |

AppImage/app-image ничего не ставят в систему: нужны только базовые
GTK3/NSS, которые на любом десктопном дистрибутиве уже есть.

**Linux — с установкой (ярлык в меню, `/opt/defectmap`):**

```bash
sudo apt-get install -y ./build/dist/defectmap_*.deb     # Debian/Ubuntu
sudo dpkg -i --force-depends build/dist/defectmap_*.deb  # ALT (dpkg не видит rpm-пакеты)
```

**Совсем без Linux:** CI собирает `.deb`, AppImage и JAR — их можно скачать
из артефактов прогона GitHub Actions (`.github/workflows/linux.yml`).

**Если маркеры «съехали» после обновления** (старые координаты в БД):

```bash
./gradlew fixMarkersDryRun   # посмотреть, что изменится
./gradlew fixMarkers         # применить, бэкап в backups/marker-fix-*
```

## Документация

* [docs/LINUX-ALT.md](docs/LINUX-ALT.md) — перенос на ALT Linux: пакеты, сборка `.deb`/`.rpm`/AppImage,
  шрифты, печать, диагностика, чек-лист приёмки, стенды (WSL2, виртуалка, CI).
* `packaging/linux/check-environment.sh` — проверить систему перед запуском.
* `packaging/linux/run-smoke-test.sh` — сквозная проверка собранного приложения.
* `packaging/linux/wsl-setup.sh` — поднять стенд в WSL2 (Ubuntu) и прогнать все проверки.

## Проверки

```bash
./gradlew test              # юнит-тесты (пути, БД, Excel, SVG, пагинация печати)
./gradlew runSelfTest       # java -jar … --selftest
./gradlew runUiCheck        # java -jar … --uicheck (Linux: под xvfb-run)
./gradlew runAppSmoke       # реальный старт окна + скриншот
```

`--selftest` проверяет: платформу и кодировки, **нативные библиотеки JavaFX в JAR**
(частая ошибка — JAR собран под другую ОС), шрифты и кириллицу, открытие и миграции БД,
экспорт Excel, наличие схем, службы печати CUPS. Возвращает 0 при успехе.
