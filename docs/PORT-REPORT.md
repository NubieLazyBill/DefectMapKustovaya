# Отчёт о портировании DefectMap с Windows на Linux (ALT)

Дата: 2026-11-20 · Версия: 1.1.0 · Ветка: `master`

Задача: заменить Windows-only сборку на кроссплатформенную, убрать хардкод путей,
проверить javafxsvg / шрифты / PrinterJob / FileChooser, собрать `.deb` и AppImage
и подтвердить критерий «`./gradlew shadowJar && java -jar DefectMap.jar` запускается
на ALT, открывается БД, работает печать/экспорт Excel».

---

## 1. Резюме

Все шесть пунктов задачи закрыты в коде. По ходу работы найдена и исправлена
**блокирующая ошибка, из-за которой fat JAR не запускался вообще** — ни на Windows,
ни на Linux (см. §2).

Проверено на Windows 10 (JDK 21 для запуска, Temurin 17 для `jpackage`):

| Проверка | Результат |
|---|---|
| `./gradlew test` | 30 тестов, 0 падений |
| `./gradlew shadowJar` | `DefectMap.jar`, 74 МБ |
| `java -jar DefectMap.jar --selftest` | **18 OK, 1 WARN, 0 FAIL**, код 0 |
| `java -jar DefectMap.jar --uicheck` | WebKit загрузил SVG: 12 248 DOM-узлов, 93 880 символов текста |
| `java -jar DefectMap.jar --appsmoke` (реальные данные) | окно «DefectMap — Кустовая», 186 единиц оборудования из БД, **196 маркеров** на схеме |
| `./gradlew buildPortable` | app-image собран, `DefectMap.exe --version` отвечает |
| Симуляция установки из пакета (`appdir` ≠ `dataDir`) | 7 файлов развёрнуто в `~/.local/share/defectmap`, БД открылась, 19 OK / 0 FAIL |

**Что не проверено здесь:** реальный запуск на Linux. На этой машине нет WSL,
Docker и доступа к ALT-хосту, поэтому Linux-рантайм (GTK/WebKit/`.so`, CUPS, шрифты
дистрибутива) физически не запускался. Вместо этого подготовлены:

* `docs/LINUX-ALT.md` — пакеты, сборка, диагностика, чек-лист приёмки;
* `.github/workflows/linux.yml` — сборка и проверки на Linux (ubuntu-22.04 + Xvfb),
  включая установку `.deb` и `--appsmoke` установленного приложения;
* `packaging/linux/run-smoke-test.sh`, `check-environment.sh` — проверки на ALT.

Дополнительно подтверждено на артефактах Maven, что Linux-сборка JavaFX содержит
`libglass.so`, `libprism_sw.so`, `libjavafx_font.so`, `libjfxwebkit.so`, `libglassgtk3.so`
— то есть JAR, собранный на Linux, получит нужные нативные библиотеки,
и `--selftest` это проверяет автоматически.

---

## 2. Критическая находка: JAR не запускался ни на одной ОС

До изменений:

```
$ java -jar build/libs/DefectMap.jar
Error: JavaFX runtime components are missing, and are required to run this application
```

Причины (две сразу):

1. `Main-Class` указывал на `org.example.defectmap.DefectMapApp` — класс
   **без метода `main`** (проверено `javap`: только конструктор и `start`);
   `main` лежал в `DefectMapAppKt` (top-level функция).
2. Даже с правильным `main` JDK-лаунчер отказывается стартовать приложение,
   чей главный класс наследует `Application`, если JavaFX не является
   именованным модулем (в fat JAR он в classpath).

Исправление: добавлен `org.example.defectmap.Launcher` — обычный объект
с `@JvmStatic main`, не наследник `Application`, который вызывает
`Application.launch(DefectMapApp::class.java, *args)`. Он указан и в манифесте
`shadowJar`, и в `application.mainClass`, и в `launch4j.xml`, и в `jpackage --main-class`.

После изменений: `java -jar build/libs/DefectMap.jar` открывает окно
(проверено `--appsmoke`, скриншот `build/tmp/app_smoke.png`).

---

## 3. Пункт 1: `buildPortable` → кроссплатформенный

`build.gradle.kts` переписан:

| Было | Стало |
|---|---|
| `jlink --module-path "C:\Program Files\Java\javafx-sdk-21.0.11\lib"` | `jlink` не нужен: runtime берётся из `java.home`, JavaFX едет в fat JAR; при необходимости `-PruntimeImage=build/jre` |
| `D:\Dev\JDK17\bin\jpackage.exe` | `resolveJdkTool("jpackage")`: `${java.home}/bin/jpackage` → `JAVA_HOME` → `org.gradle.java.home` → toolchain 17; внятная ошибка, если не найден |
| один путь для Windows | ветвление по `os.name`: `windows` / `linux` / `macos` (`.exe`, `bin/`, `.app/Contents/MacOS`) |
| нет пакетов | `buildPortable` (app-image), `buildDeb`, `buildRpm`, `buildAppImage`, `packageCurrentOs` |
| jar собирался с `--input build/libs` | `stageAppInput` (`Sync`) готовит `build/app-input`: JAR + БД + `substations.json` + `schemas/` + `images/` + README |

Java-опции пакета: `-Dfile.encoding=UTF-8` (обязательно для JDK 17 при `LANG=C`),
`-Dprism.order=sw` (переопределяется `-Pprism.order=hw`).

---

## 4. Пункт 2: хардкод Windows-путей и `user.dir`

Поиск вёлся по `C:\\`, `C:/`, `D:\\`, `Program Files`, `javafx-sdk`, `user.home`,
`System.getProperty`, `Paths.get`, `File.separator`.

Найдено и устранено:

| Файл | Было | Стало |
|---|---|---|
| `build.gradle.kts` | `C:\Program Files\Java\javafx-sdk-21.0.11\lib`, `D:\Dev\JDK17\bin\jpackage.exe` | поиск относительно `java.home` |
| `AppPaths.kt` | `codeSource.location.toURI().path` — на Linux ломается на путях с пробелами (`%20`) и в принципе не является путём | `File(uri)`, плюс XDG-каталоги |
| `Database.kt` | собственная копия `getAppDirectory()` с `user.dir` | `AppPaths.dataDir` |
| `AppSettings.kt` | третья копия `getAppDirectory()` | `AppPaths.dataDir` |
| `Substation.kt` | `File(AppPaths.appDir, "substations.json")` | `AppPaths.dataDir` |
| `DefectMapController.kt` | `File(AppPaths.appDir, "backups${File.separator}…")`, удаление БД по `appDir` | `AppPaths.backupsDir`, `AppPaths.dataDir` |
| `EquipmentCardController.kt` | `File("images/equipment.jpg")` — зависело от рабочей директории | `AppPaths.imagesDir` |

Оставшиеся `System.getProperty("user.dir")` — только осознанный fallback
«запуск из IDE, классы вместо JAR» (`AppPaths.detectAppDir`) и диагностика в `SelfTest`.

### Модель двух каталогов

```
appDir  — откуда запущен код (JAR, /opt/defectmap/lib/app, AppImage)
dataDir — куда пишем:
          appDir, если туда можно писать (Windows, распакованный AppImage)
          иначе $XDG_DATA_HOME/defectmap → ~/.local/share/defectmap
                %LOCALAPPDATA%\DefectMap (Windows), ~/Library/Application Support (macOS)
```

При старте в непортативном режиме `DataSeeder` копирует в `dataDir` БД,
`substations.json`, `schemas/`, `images/`, **не перезаписывая** файлы пользователя.
Переопределение: `-Ddefectmap.home=…` или `DEFECTMAP_HOME=…`
(плюс `-Ddefectmap.appdir=…` для диагностики).

Проверено: с `defectmap.appdir=build/app-input` и `defectmap.home=<пусто>`:
`📦 Данные развёрнуты … (файлов: 7)`, БД открылась, 19 OK / 0 FAIL.

---

## 5. Пункт 3: javafxsvg

Проверка показала: зависимость `de.codecentric:javafxsvg:1.2.0` **не используется**.
Поиск по `SvgImageLoader`, `SvgImageLoaderFactory`, `javafxsvg` не дал ни одного
обращения; SVG рисуется через `WebView` (`javafx.web`), который и так есть в fat JAR.

Действия:

* зависимость удалена (вместе с транзитивным Batik);
* **замена на `Image`/Batik не потребовалась** — нативных `.so` у javafxsvg нет,
  проблема «на Linux не находит .so» к нему не относится;
* загрузка схем вынесена в `Schemas.kt` и усилена:
  * порядок поиска: `dataDir/schemas` → `appDir/schemas` → ресурсы JAR → `src/main/resources`;
  * явный UTF-8 при чтении файла (раньше `readText()` брал кодировку ОС — при `LANG=C`
    кириллица в схеме превращалась в мусор);
  * проверка XML-корректности без загрузки внешнего DTD (на изолированной машине
    w3.org недоступен).

---

## 6. Пункт 4: шрифты (Arial / Times New Roman / Calibri)

В ALT этих шрифтов нет; JavaFX молча подставляет другой шрифт с другими метриками.
Сделано:

* `platform/UiFonts.kt`: цепочки fallback
  `Arial → Liberation Sans → Arimo → Helvetica → DejaVu Sans → Noto Sans → FreeSans`,
  `Times New Roman → Liberation Serif → Tinos → Times → DejaVu Serif`,
  `Consolas → Cascadia Mono → Liberation Mono → DejaVu Sans Mono → Courier New`;
* `Font.font("Arial", …)` в `EquipmentCardController` заменён на `UiFonts.fxFont(...)`;
  AWT-шрифт заглушки — на `UiFonts.awtFont(...)`;
* в HTML-обвязке WebView `font-family: Arial, sans-serif` заменён на CSS-стек
  (`"Arial", "Liberation Sans", …, sans-serif`);
* в SVG (Visio) `font-family:Arial` / `Times New Roman` дополняются аналогами
  (`Schemas.normalizeFonts`) — оригинальный шрифт остаётся первым, поэтому в Windows
  вид не меняется, а на Linux подхватывается метрически совместимый Liberation;
* поддержка встроенных шрифтов: TTF в `src/main/resources/org/example/defectmap/fonts/`
  подгружаются через `Font.loadFont` при старте;
* на Linux общая тема получает `-fx-font-family: "Liberation Sans"`.

`--selftest` печатает, какой шрифт выбран, и проверяет наличие кириллицы
`А…Я, Ё, №, —, «»`; при отсутствии Liberation выдаёт WARN с подсказкой по пакету.

---

## 7. Пункт 5: PrinterJob и FileChooser

**PrinterJob в приложении отсутствовал** — печати не было вообще (в коде только
`println`, `FileChooser` и Excel). Поэтому не «проверить», а реализовать:

* `report/ReportPrinter.kt` — печать отчёта: `PrinterJob` + `PageLayout(A4, portrait)`,
  таблица верстается JavaFX-узлами и печатается постранично (`printPage`), с шапкой,
  нумерацией страниц и подвалом; пагинация `paginate()` покрыта юнит-тестами;
* кнопка «🖨️ Печать» в окне отчёта;
* если принтеров нет — сообщение с подсказкой про CUPS (`lpstat -p -d`), а не падение;
* при нескольких принтерах — диалог выбора (без GTK print dialog, чтобы не зависеть
  от GTK-принт-бэкенда).

Проверено на Windows: `--selftest` видит 5 принтеров (включая «Microsoft Print to PDF»),
`--uicheck` подтверждает, что `javafx.print.Printer.getAllPrinters()` возвращает 5.
Реальная отправка страницы в спулер не выполнялась (чтобы не печатать мусор);
на ALT проверяется вручную по чек-листу.

**FileChooser** (экспорт Excel):

* задаётся `initialDirectory` (Documents или последняя папка сохранения);
* владелец окна берётся null-безопасно (`webView.scene?.window`);
* расширение `.xlsx` дописывается вручную — GTK-диалог в Linux его не добавляет;
* сам экспорт вынесен в `report/ExcelReportWriter.kt` (не зависит от JavaFX),
  поэтому проверяется в `--selftest` и юнит-тестами на любой ОС.

Дополнительно в Excel: `Locale.ROOT` для процентов (иначе на `ru_RU` в файл попадало
«12,5»), атомарная запись через временный файл, `autoSizeColumn` в try/catch
(в headless-окружении без fontconfig он падал), настройки печати листа
(A4, альбомная, по ширине страницы, повтор шапки).

---

## 8. Пункт 6: сборка `.deb`, `.rpm`, AppImage и проверка запуска

Добавлено:

* `./gradlew buildDeb` — `jpackage --type deb`, `--install-dir /opt/defectmap`,
  `--linux-shortcut`, `--linux-menu-group Utility`, `--linux-app-category Utility`;
* `./gradlew buildRpm` — то же для `.rpm` (родной формат пакетов ALT);
* `./gradlew buildAppImage` — `packaging/linux/build-appimage.sh`: собирает AppDir
  (`AppRun` → `bin/DefectMap`, `.desktop`, иконки), находит или скачивает `appimagetool`,
  работает без FUSE (`APPIMAGE_EXTRACT_AND_RUN=1`);
* иконка `packaging/linux/defectmap.png` (256×256, генерируется `make-icon.py`),
  `.desktop`-файл, `README.txt` в `schemas/` и `images/` внутри поставки;
* `.github/workflows/linux.yml` — сборка и проверки на Linux;
* `packaging/linux/check-environment.sh` — отчёт о библиотеках/шрифтах/CUPS/дисплее;
* `packaging/linux/run-smoke-test.sh` — `--selftest` + `--uicheck` одной командой.

Проверено здесь: `buildPortable` на Windows собирает app-image, `DefectMap.exe --version`
отвечает, `--selftest --in-place` доходит до проверок. Полностью прогнать `.deb`/AppImage
на этой машине нельзя — `jpackage` не собирает Linux-пакеты на Windows, AppImage
требует Linux. Это делается на ALT или в CI (шаги в workflow готовы).

**Замечание по среде:** на этой Windows-машине неподписанный `DefectMap.exe`
из app-image не может писать в `%TEMP%` и в `~/.openjfx/cache`
(`AccessDeniedException`, «Отказано в доступе») — локальная политика безопасности.
Поэтому `--selftest` из exe корректно сообщает `[FAIL] tmpdir` и останавливается,
не трогая данные. Тот же код через `java -jar` проходит полностью.
На Linux такой проблемы нет: `--selftest` в CI выполняется и для установленного `.deb`.

---

## 9. Матрица проверок

| Проверка | Где выполнена | Результат |
|---|---|---|
| `./gradlew test` (30 тестов) | Windows | PASS |
| `--selftest` (jar) | Windows | 18 OK / 1 WARN / 0 FAIL |
| `--selftest` из app-image (exe) | Windows | блокируется политикой записи в TEMP (см. §8) |
| `--uicheck` + snapshot | Windows | OK, SVG отрисован, снимок сохранён |
| `--appsmoke` без данных | Windows | OK, окно 1920×1017, схема, `window.equipment` |
| `--appsmoke` с реальной БД | Windows | OK, 186 единиц оборудования, 196 маркеров |
| Симуляция `.deb`-режима (appdir ≠ dataDir) | Windows | OK, 7 файлов развёрнуто, БД из `dataDir` |
| `buildPortable` | Windows (jpackage 17) | app-image собран |
| Linux-нативы в Maven-артефактах | проверка архивов | `libglass.so`, `libprism_sw.so`, `libjavafx_font.so`, `libjfxwebkit.so`, `libglassgtk3.so` |
| `bash -n` всех `.sh` | Git Bash | OK |
| `buildDeb` / `buildRpm` / `buildAppImage` | **не выполнено** (нет Linux) | шаги в CI |
| Запуск на ALT | **не выполнено** (нет доступа к ALT) | чек-лист в `docs/LINUX-ALT.md` |

---

## 10. Что осталось сделать на ALT (по шагам)

```bash
# 1. Окружение
bash packaging/linux/check-environment.sh

# 2. Сборка и тесты
./gradlew test shadowJar

# 3. Проверки без GUI и с GUI
java -jar build/libs/DefectMap.jar --selftest
xvfb-run -a java -jar build/libs/DefectMap.jar --appsmoke --snapshot /tmp/app.png

# 4. Пакеты
./gradlew buildDeb buildRpm buildPortable buildAppImage

# 5. Установка и проверка установленного
sudo rpm -i build/dist/defectmap-*.rpm        # или sudo dpkg -i build/dist/defectmap_*.deb
/opt/defectmap/bin/DefectMap --selftest
xvfb-run -a /opt/defectmap/bin/DefectMap --appsmoke

# 6. Ручные проверки из чек-листа: БД, Excel, печать (CUPS)
```

Ожидаемые предупреждения (не ошибки): `WARNING: Unsupported JavaFX configuration:
classes were loaded from 'unnamed module'` — JavaFX из classpath fat JAR, работе не мешает.

---

## 11. Изменённые файлы

**Новое:**

```
README.md, .gitattributes, start.sh
docs/LINUX-ALT.md, docs/PORT-REPORT.md
.github/workflows/linux.yml
packaging/linux/{build-appimage.sh, check-environment.sh, run-smoke-test.sh,
                 defectmap.desktop, defectmap.png, make-icon.py}
packaging/data/{schemas,images}/README.txt
src/main/kotlin/org/example/defectmap/Launcher.kt
src/main/kotlin/org/example/defectmap/Schemas.kt
src/main/kotlin/org/example/defectmap/DataSeeder.kt
src/main/kotlin/org/example/defectmap/platform/{PlatformInfo,UiFonts,FxNativeCache}.kt
src/main/kotlin/org/example/defectmap/report/{ReportItem,ExcelReportWriter,ReportPrinter}.kt
src/main/kotlin/org/example/defectmap/diagnostics/{SelfTest,UiCheck,AppSmoke,Snapshots}.kt
src/test/kotlin/org/example/defectmap/{DataSeeder,DatabaseCrud,ExcelReportWriter,
    PlatformAndPaths,ReportPrinterPagination,Schemas,Substations}Test.kt
```

**Изменено:** `build.gradle.kts`, `AppPaths.kt`, `AppSettings.kt`, `Database.kt`,
`DefectMapApp.kt`, `DefectMapController.kt`, `EquipmentCardController.kt`,
`Substation.kt`, `build-exe.bat`, `launch4j.xml`, `.gitignore`.

**Удалено:** зависимость `de.codecentric:javafxsvg` (код приложения не менялся —
её просто не использовали), `javafx.media`/`javafx.swing` из модулей.

---

## 12. Проверено на реальном ALT Linux 10.4

Стенд: VirtualBox, «Альт Рабочая станция 10.4» x86_64, JDK 17 (`javac 17.0.21`).

| Проверка | Команда | Результат |
|---|---|---|
| Сборка | `./gradlew clean test shadowJar` | BUILD SUCCESSFUL, 34 теста |
| Окружение | `java -jar … --selftest` | 18 OK / 1 WARN / 0 FAIL |
| Окно и схема | `java -jar … --appsmoke` | окно открылось, сцена 1200×800 |
| БД | `--appsmoke` | загружено 186 единиц оборудования |
| Маркеры | `--appsmoke` | 196 маркеров, `область схемы: 676×653 center=50.0,50.0` |
| Координаты | `--fix-markers --dry-run` | `top > 100` нет → миграция применена |
| Функциональная проверка | `FunctionalCheck` | **РЕЗУЛЬТАТ: OK** (БД, Excel, печать) |
| Печать | `ReportPrinter` → Cups-PDF | PDF 133 КБ создан |
| Пакеты | `buildPortable`, `buildDeb`, `buildAppImage` | app-image, `.deb` 107 МБ, AppImage 120 МБ |
| Установка | `dpkg -i --force-depends` | `/opt/defectmap/defectmap/bin/DefectMap`, `--selftest` OK |

### Найденные на ALT особенности

1. **JDK**: в системе был только JRE (`java-17-openjdk`). Для сборки нужен
   `java-17-openjdk-devel` (или Temurin 17 в домашний каталог).
2. **`jpackage` не в PATH** — он лежит внутри JDK, Gradle находит его через
   `java.home`; `which jpackage` ничего не покажет, это нормально.
3. **`sudo` отключён для `wheel`** (правило в `/etc/sudoers` закомментировано).
   Либо `su -`, либо один раз добавить `%wheel ALL=(ALL) ALL` в
   `/etc/sudoers.d/wheel`.
4. **`jpackage` требует инструменты**: `binutils` (objcopy для jlink),
   `dpkg` + `fakeroot` (тип `deb`), `xdg-utils` (зависимость пакета),
   `rpm-build` (тип `rpm`). Без них ошибки «Cannot run program objcopy» и
   «Invalid or unsupported type: [deb]».
5. **`.deb` ставится с `--force-depends`**: `dpkg` не видит пакеты ALT
   (они rpm-ные), поэтому зависимость `xdg-utils` считается отсутствующей.
6. **Маркеры**: старая версия сохраняла `top` в процентах от окна, а не от
   схемы — в данных встречались значения до 190%. Лечится `--fix-markers`.
7. **Колесо мыши в VirtualBox** присылает фантомные события с `deltaY = 0`,
   из-за чего приближение не работало. В коде такие события игнорируются,
   плюс есть `Ctrl + / − / 0` и пункты меню.
8. **Cups-PDF сохраняет PDF на рабочий стол**, а не в `~/PDF` — проверка
   `FunctionalCheck` ищет файл в нескольких каталогах.

### CI: первый запуск на GitHub

Первый прогон после пуша упал на шаге «Юнит-тесты» за секунду: в репозитории у
`gradlew` не было бита исполнения (хранился как `100644`), а в ВМ запускалось
`bash gradlew`. Исправлено: `git update-index --chmod=+x` для `gradlew` и всех
`*.sh`, а в workflow добавлены `fakeroot`/`binutils` и таймауты.
