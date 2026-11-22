# DefectMap на Linux (ALT Linux, Debian/Ubuntu, Astra)

Документ описывает перенос приложения с Windows на Linux: что изменено в коде,
как собрать `.deb`, `.rpm` и AppImage, как проверить запуск на ALT и что делать,
если что-то не работает.

Целевая платформа: **ALT Linux p10, x86_64**, JDK 17, JavaFX 21.

---

## 1. Быстрый старт (проверка критерия приёмки)

```bash
# на машине сборки: JDK 17+ и интернет для зависимостей Gradle
./gradlew shadowJar
java -jar build/libs/DefectMap.jar --selftest     # код возврата 0 = окружение готово
java -jar build/libs/DefectMap.jar               # обычный запуск GUI

# проверки без монитора:
xvfb-run -a java -jar build/libs/DefectMap.jar --uicheck --show --snapshot /tmp/ui.png
xvfb-run -a java -jar build/libs/DefectMap.jar --appsmoke --snapshot /tmp/app.png
```

Критерий «`./gradlew shadowJar && java -jar DefectMap.jar` запускается на ALT,
открывается БД, работает печать/экспорт Excel» проверяется тремя командами:

| Что проверяем | Команда | Признак успеха |
|---|---|---|
| Окно, схема, шрифты, WebKit | `--uicheck` | `[OK] WebView загрузил схему` |
| Реальный старт приложения | `--appsmoke` | `[OK] приложение стартовало … маркеров N` |
| БД, миграции, Excel, печать, нативы | `--selftest` | `РЕЗУЛЬТАТ: OK`, код возврата 0 |

Скрипт, делающий всё сразу:

```bash
bash packaging/linux/run-smoke-test.sh build/libs/DefectMap.jar
```

---

## 2. Что было не так и что изменено

### 2.1. Главная причина, по которой JAR не запускался вообще

`Main-Class` в манифесте указывал на `org.example.defectmap.DefectMapApp` —
класс, унаследованный от `javafx.application.Application` **и без метода `main`**.
`java -jar` падал с сообщением:

```
Error: JavaFX runtime components are missing, and are required to run this application
```

Это известное поведение JDK-лаунчера: если Main-Class наследует `Application`,
JavaFX обязан быть именованным модулем, а в fat JAR он лежит в classpath.

**Исправлено:** добавлен класс-обёртка `org.example.defectmap.Launcher`
(не наследник `Application`), он указан в `Main-Class` и в `application.mainClass`.

### 2.2. `buildPortable` был прибит к Windows

Было: `jlink --module-path "C:\Program Files\Java\javafx-sdk-21.0.11\lib"` и
`D:\Dev\JDK17\bin\jpackage.exe`.

Стало (`build.gradle.kts`):

* `jpackage` и `jlink` ищутся как `${java.home}/bin/jpackage` → `JAVA_HOME` → toolchain 17;
* ветвление по `os.name`: `windows` / `linux` / `macos` (суффикс `.exe`, пути лаунчера);
* задачи: `buildPortable` (app-image), `buildDeb`, `buildRpm`, `buildAppImage`, `packageCurrentOs`;
* `jlink` больше не обязателен: по умолчанию jpackage берёт runtime из `java.home`
  (включает `java.desktop`, `java.sql`, ...), а JavaFX едет в fat JAR.
  При необходимости можно передать заранее собранный образ: `-PruntimeImage=build/jre`.

### 2.3. Хардкод Windows-путей и `user.dir`

Найдено и устранено:

| Место | Было | Стало |
|---|---|---|
| `AppPaths` | `codeSource.location.toURI().path` (на Linux ломается на путях с пробелами и в `%20`) | `File(uri)`, каталог по правилам платформы |
| `Database`, `AppSettings`, `Substations` | три копии `getAppDirectory()` с `user.dir` | единый `AppPaths.dataDir` |
| `DefectMapController` | `File(AppPaths.appDir, "backups…")` | `AppPaths.backupsDir`, `AppPaths.dataDir` |
| `EquipmentCardController` | `File("images/equipment.jpg")` (зависело от CWD) | `AppPaths.imagesDir` |
| `buildPortable` | `C:\Program Files\Java\javafx-sdk…`, `D:\Dev\JDK17\…` | поиск относительно `java.home` |

Поиск по проекту: `C:\\`, `C:/`, `D:\\`, `Program Files`, `javafx-sdk`, `user.dir`
(в `src/` и `build.gradle.kts`) больше не даёт совпадений, кроме осознанных
упоминаний в документации.

**Две папки вместо одной.** На Windows приложение портативное — БД рядом с JAR.
В `.deb`/`.rpm`/AppImage папка `/opt/defectmap` принадлежит root, писать туда нельзя,
поэтому:

* `appDir` — откуда запущен код (поставка, только чтение);
* `dataDir` — куда пишем: если рядом с JAR писать можно (Windows, распакованный
  AppImage, `~/Applications/DefectMap`) — берётся `appDir`; иначе
  `$XDG_DATA_HOME/defectmap` или `~/.local/share/defectmap`;
* при первом запуске `DataSeeder` копирует в `dataDir` БД, `substations.json`,
  `schemas/`, `images/` — не перезаписывая пользовательские файлы.

Переопределение каталога данных: `-Ddefectmap.home=/path` или `DEFECTMAP_HOME=/path`.

### 2.4. javafxsvg

Зависимость `de.codecentric:javafxsvg:1.2.0` **нигде не использовалась** —
ни `SvgImageLoader`, ни `SvgImageLoaderFactory` в коде не вызывались.
SVG рисуется через `WebView` (`javafx.web`), а не через Image/ImageIO.

Поэтому зависимость удалена вместе с транзитивным Batik. Это снимает вопрос
«на Linux не находит нативные .so»: у javafxsvg/Batik нативных библиотек нет,
но был риск конфликта ImageIO-плагинов и лишние 5–8 МБ в JAR.
Замена на `Image`/Batik не потребовалась.

Дополнительно улучшена загрузка схем (`Schemas.kt`):

* порядок поиска: `dataDir/schemas` → `appDir/schemas` → ресурсы JAR → `src/main/resources`;
* файлы читаются **явно в UTF-8** (`readText()` без кодировки ломал кириллицу при `LANG=C`);
* в `font-family` SVG подставляются свободные аналоги (см. 2.5);
* есть проверка XML-корректности без загрузки внешнего DTD (на изолированной машине
  без сети w3.org недоступен).

### 2.5. Шрифты

Arial, Times New Roman, Calibri — шрифты Microsoft, в ALT их нет. JavaFX молча
подставляет системный шрифт с другими метриками.

Сделано (`platform/UiFonts.kt`):

* fallback-цепочки: `Arial → Liberation Sans → Arimo → DejaVu Sans → Noto Sans`;
  `Times New Roman → Liberation Serif → Tinos → DejaVu Serif`;
  `Consolas → Liberation Mono → DejaVu Sans Mono`;
* все JavaFX-шрифты в коде (`Font.font("Arial", …)` в карточке оборудования,
  картинка-заглушка через AWT) идут через `UiFonts.fxFont/awtFont`;
* в HTML и SVG подставляются CSS-стеки, а не одиночный `Arial`
  (`font-family: "Arial", "Liberation Sans", …, sans-serif`);
* поддержаны встроенные шрифты: положите TTF в
  `src/main/resources/org/example/defectmap/fonts/` (`LiberationSans-Regular.ttf`,
  `Carlito-Regular.ttf`, …) — они загрузятся через `Font.loadFont`;
* `--selftest` проверяет наличие кириллицы в выбранном шрифте и печатает,
  какой шрифт подставился.

На ALT рекомендуется поставить `fonts-ttf-liberation` (метрически совместим с Arial
и Times New Roman, поэтому вёрстка схем не поедет) и `fonts-ttf-dejavu`.

### 2.6. PrinterJob и FileChooser

* **PrinterJob в приложении отсутствовал** — печати не было вообще.
  Добавлена печать отчёта: кнопка «🖨️ Печать» в окне отчёта
  (`report/ReportPrinter.kt`), вёрстка выполняется JavaFX-узлами и печатается
  постранично через `PrinterJob.printPage` — работает и с CUPS на Linux,
  и с winspool на Windows, без GTK-диалога печати.
  Если принтеров нет — понятное сообщение (на сервере без CUPS это норма).
* **FileChooser** доработан: задаётся `initialDirectory` (Documents или последняя
  папка), владелец окна берётся `null`-безопасно, а расширение `.xlsx`
  дописывается вручную — GTK-диалог на Linux этого не делает.
* `--selftest` опрашивает `javax.print.PrintServiceLookup` и печатает список
  принтеров; `--uicheck` — список принтеров глазами JavaFX.

### 2.7. Прочее, что ломалось бы на Linux

* `-Dfile.encoding=UTF-8` добавлен в `--java-options` пакетов и в `--selftest`;
  без него JDK 17 при `LANG=C` читает/пишет файлы не в UTF-8.
* `AppPaths.openFolder` теперь умеет `xdg-open` / `gio open` — `java.awt.Desktop`
  на Linux без GNOME-библиотек часто «не поддерживается».
* `ExcelReportWriter`: проценты форматируются с `Locale.ROOT` (иначе на `ru_RU`
  в xlsx попадало «12,5»), `autoSizeColumn` обёрнут в try/catch (в headless
  окружении без fontconfig он падал бы), запись файла атомарная.
* Мусорное сообщение POI `ERROR StatusLogger Log4j2 could not find a logging
  implementation` больше не пугает пользователя (`log4j2.StatusLogger.level=OFF`).
* Убраны `javafx.media` и `javafx.swing` из модулей — они не использовались,
  это минус ~2 МБ и минус одна причина падения нативных загрузчиков.

---

## 3. Подготовка ALT Linux

```bash
# Java для сборки (если собираете на этой же машине)
apt-get update
apt-get install -y java-17-openjdk-devel

# Проверить окружение (ничего не меняет, только отчёт)
bash packaging/linux/check-environment.sh
```

Библиотеки, которые нужны JavaFX/WebView в рантайме (имена пакетов в ALT
могут отличаться — проверяйте `apt-cache search <часть имени>`):

```bash
apt-get install -y libgtk+3 libnss libXtst libXrender libX11 libxi \
                   libfreetype libfontconfig1 libgdk-pixbuf
apt-get install -y fonts-ttf-liberation fonts-ttf-dejavu
apt-get install -y cups cups-client        # печать
apt-get install -y xvfb                    # проверки без дисплея / CI
```

Пакеты `defectmap*.deb` / `defectmap*.rpm` несут собственный runtime Java,
поэтому для установленного приложения `java` в системе не нужна.

---

## 4. Сборка

```bash
./gradlew clean test        # 30 юнит-тестов
./gradlew shadowJar         # build/libs/DefectMap.jar (fat JAR, ~70 МБ)

./gradlew buildPortable     # build/dist/DefectMap/  (app-image, запуск bin/DefectMap)
./gradlew buildDeb          # build/dist/defectmap_*.deb    (только Linux)
./gradlew buildRpm          # build/dist/defectmap-*.rpm    (только Linux, родной для ALT)
./gradlew buildAppImage     # build/dist/DefectMap-1.1.0-x86_64.AppImage (нужен curl)
./gradlew packageCurrentOs  # app-image + нативный пакет текущей ОС
```

Полезные ключи:

| Ключ | Значение |
|---|---|
| `-Pprism.order=sw` | программный рендеринг (по умолчанию, надёжно в VM/RDP/ALT) |
| `-Pprism.order=hw` | аппаратный рендеринг, если есть нормальный GPU-драйвер |
| `-PruntimeImage=build/jre` | использовать заранее собранный jlink-образ |
| `-Dorg.gradle.java.home=/usr/lib/jvm/java-17` | явно указать JDK с `jpackage` |
| `APPIMAGETOOL=/path/appimagetool` | свой appimagetool вместо загрузки |

**Важно:** fat JAR содержит нативные библиотеки JavaFX **только для той ОС,
на которой он собран**. JAR, собранный на Windows, на Linux не запустится —
`--selftest` это покажет явным FAIL. Собирайте на целевой платформе (или в CI).

---

## 5. Установка на ALT

```bash
# Родной для ALT формат:
sudo rpm -i defectmap-1.1.0-1.x86_64.rpm
# либо, если в системе есть dpkg:
sudo dpkg -i defectmap_1.1.0_amd64.deb

# AppImage (без установки):
chmod +x DefectMap-1.1.0-x86_64.AppImage
./DefectMap-1.1.0-x86_64.AppImage
# без FUSE:
APPIMAGE_EXTRACT_AND_RUN=1 ./DefectMap-1.1.0-x86_64.AppImage
```

После установки:

* бинарник: `/opt/defectmap/bin/DefectMap`
* ярлык в меню: «DefectMap» (категория Utility)
* данные пользователя: `~/.local/share/defectmap/`
  (`equipment_*.db`, `substations.json`, `app_settings.json`, `backups/`,
  `schemas/`, `images/`)

Проверка установленного приложения:

```bash
/opt/defectmap/bin/DefectMap --selftest
xvfb-run -a /opt/defectmap/bin/DefectMap --uicheck --show --snapshot /tmp/ui.png
```

---

## 6. Печать

* Печать отчёта: окно «Дефекты» → фильтры → «🖨️ Печать».
* Используется `javafx.print.PrinterJob`, на Linux — через CUPS.
* Диагностика:
  ```bash
  lpstat -p -d        # есть ли принтеры
  systemctl status cups   # запущена ли служба CUPS
  ```
* Если принтеров нет, приложение сообщает об этом и предлагает проверить CUPS;
  **экспорт в Excel при этом работает независимо**.
* Печать из Excel (файл `.xlsx`) уже настроена: A4, альбомная, «вписать по ширине»,
  шапка повторяется на каждой странице.

---

## 7. Если не запускается

| Симптом | Причина | Решение |
|---|---|---|
| `Error: JavaFX runtime components are missing` | JAR собран старым кодом (Main-Class — наследник `Application`) | пересобрать: `./gradlew shadowJar` |
| `--selftest`: `[FAIL] natives` и «JAR собран под другую ОС» | fat JAR собран на Windows | собрать на Linux/CI |
| `UnsatisfiedLinkError: libglass.so` / `libjfxwebkit.so` | нет GTK3/NSS | поставить `libgtk+3`, `libnss` |
| `WARNING: Unsupported JavaFX configuration: classes were loaded from 'unnamed module'` | JavaFX в classpath, а не в module-path | это предупреждение, работе не мешает |
| Окно есть, схема не отрисована | WebKit не смог загрузить SVG / нет шрифтов | `--uicheck --show --snapshot`; проверить `fc-list` |
| «Текст съехал», другой шрифт | нет Liberation/DejaVu | `apt-get install fonts-ttf-liberation fonts-ttf-dejavu` |
| Кириллица в именах файлов превращается в `?` | `LANG=C` | запускать с `LANG=C.UTF-8` или `ru_RU.UTF-8`; в пакете уже есть `-Dfile.encoding=UTF-8` |
| БД создаётся, но данные не сохраняются | каталог данных только для чтения | проверить `AppPaths.describe()` в логе; задать `DEFECTMAP_HOME` |
| Нет принтеров | не установлен/не запущен CUPS | `cups`, `lpstat -p -d` |
| Пустой список схем | нет SVG для этой ПС | «Для разработчиков» → «Открыть папку schemas» |

Диагностический лог при старте печатает платформу и каталоги:

```
DefectMap 1.1.0 — OS=Linux (…), arch=amd64, java=17.0.x, file.encoding=UTF-8, sun.jnu.encoding=UTF-8
  appDir=/opt/defectmap/lib/app, dataDir=/home/user/.local/share/defectmap, режим=пользовательский каталог
```

---

## 8. Проверки в CI

`.github/workflows/linux.yml` на `ubuntu-22.04`:

1. `./gradlew test` — юнит-тесты;
2. `./gradlew shadowJar` → `java -jar … --selftest`;
3. `xvfb-run … --uicheck --show --snapshot` — реальный WebKit-рендер схемы;
4. `xvfb-run … --appsmoke` — реальный запуск окна: FXML, контроллер, БД, маркеры;
5. `buildPortable`, `buildDeb`, установка `.deb`, `--selftest` **и** `--appsmoke`
   установленного приложения;
6. `buildAppImage`;
7. артефакты: `.jar`, `.deb`, `.AppImage`, скриншоты `uicheck.png` и `app-smoke.png`.

Для сборки именно под ALT Linux используйте контейнер `alt:p10`:

```bash
docker run --rm -it -v "$PWD:/work" -w /work alt:p10 bash
# внутри контейнера:
apt-get update && apt-get install -y java-17-openjdk-devel curl
./gradlew buildDeb buildRpm buildPortable
```

То же самое автоматизировано в CI: job **`alt-p10`** (`.github/workflows/linux.yml`)
поднимает официальный образ `alt:p10`, ставит в нём JDK 17 и собирает `.deb` + `.rpm`
**настоящим ALT**, прогоняет `--selftest` и, если в контейнере есть GTK/Xvfb —
`--appsmoke`. Артефакты складываются в `defectmap-alt-p10`.

Важно: этот job помечен `continue-on-error` и запускается **только вручную**
(`Actions → Linux build & smoke test → Run workflow`), потому что сборка внутри
контейнера тяжёлая и на push/PR не должна блокировать остальные проверки.
Основной job `build-linux` идёт на каждый push и должен быть зелёным.

### Стенд без установки ALT: WSL2 + Ubuntu

Если нужен быстрый Linux «под рукой» (проверить запуск, собрать `.deb`/AppImage),
на Windows 10 Pro достаточно WSL2:

```powershell
# PowerShell от администратора
wsl --install -d Ubuntu-22.04
# перезагрузка, затем:
wsl --update
```

В Ubuntu (первый запуск попросит придумать логин/пароль):

```bash
bash /mnt/c/Users/<вы>/IdeaProjects/DefectMap/packaging/linux/wsl-setup.sh
```

Скрипт поставит JDK 17, Xvfb, GTK/NSS и шрифты, скопирует проект на Linux-ФС
(на `/mnt/c` Gradle работает медленно и путается в правах), соберёт JAR,
прогонит `--selftest` и `--appsmoke`, соберёт `.deb` и app-image.

Особенности Windows 10: WSLg (графические окна Linux) официально доступен только в
Windows 11, поэтому на 10-ке окно приложения в WSL, как правило, не показывается.
Проверка идёт через `--appsmoke`, который сохраняет скриншот
(`/tmp/defectmap-app-smoke.png`, копия в `C:\Users\Public`).
Если хочется видеть окно — поставьте X-сервер (VcXsrv/X410) и `export DISPLAY=...`
либо используйте виртуальную машину.

### Настоящий ALT на десктопе (виртуалка)

Нужен, если хотите своими глазами увидеть ALT-рабочий стол, меню, печать и шрифты.

1. ISO: [alt-workstation-10.4-x86_64.iso](http://download.basealt.ru/pub/distributions/ALTLinux/images/p10/workstation/x86_64/)
   (7,2 ГБ, полный рабочий стол) или
   [alt-server-10.4-x86_64.iso](http://download.basealt.ru/pub/distributions/ALTLinux/images/p10/server/x86_64/)
   (5,5 ГБ, без графики — хватит для `--selftest`/`--appsmoke` под Xvfb).
   Сверьте SHA256 с файлом `SHA256SUM` из того же каталога.
2. Гипервизор: Hyper-V (встроен в Windows 10 Pro) либо VirtualBox
   (`winget install Oracle.VirtualBox`).
3. В установленном ALT:

   ```bash
   apt-get update
   apt-get install -y java-17-openjdk-devel fonts-ttf-liberation fonts-ttf-dejavu \
                      cups cups-client xvfb
   ```

4. Передать JAR из Windows: в VirtualBox включите проброс SSH
   (`VBoxManage modifyvm ALT-p10 --natpf1 "ssh,tcp,,2222,,22"`), в ALT —
   `apt-get install -y openssh-server`, затем из Windows:

   ```powershell
   scp -P 2222 build/libs/DefectMap.jar user@127.0.0.1:/home/user/
   ```

   Дальше — `--selftest`, `--appsmoke` и `./gradlew buildDeb buildRpm` прямо на ALT.

---

## 9. Чек-лист приёмки на ALT

- [ ] `./gradlew shadowJar` собирается без ошибок;
- [ ] `java -jar DefectMap.jar --selftest` → `РЕЗУЛЬТАТ: OK`, код 0;
- [ ] `xvfb-run -a java -jar DefectMap.jar --appsmoke` → окно открылось, схема и маркеры на месте;
- [ ] `java -jar DefectMap.jar` открывает окно с картой и маркерами;
- [ ] открывается БД: «📋 Список оборудования» показывает данные подстанции;
- [ ] экспорт Excel: «📊 Дефекты» → «Создать отчёт» → файл `.xlsx` читается LibreOffice;
- [ ] печать: «🖨️ Печать» → задание уходит в CUPS (`lpstat -o`);
- [ ] `buildDeb` собирается; `rpm -i`/`dpkg -i` ставит приложение, запуск из меню работает и
      создаёт данные в `~/.local/share/defectmap`;
- [ ] `buildAppImage` собирается и запускается;
- [ ] шрифты: подписи на схеме совпадают по расположению с Windows (Liberation вместо Arial).

---

## 10. Проверено на реальном ALT 10.4 (VirtualBox)

Ниже — факты, полученные на «Альт Рабочая станция 10.4» x86_64 в VirtualBox:
что реально понадобилось, какие сообщения об ошибках встречаются и как они лечатся.
Это дополнение к теории выше — всё проверено руками.

### 10.1. JDK: в системе только JRE

По умолчанию в ALT стоит `java-17-openjdk` — это **только runtime**
(`OpenJDK Runtime Environment`), без `javac` и `jpackage`:

```bash
javac -version      # bash: javac: команда не найдена
jpackage --version  # bash: jpackage: команда не найдена
```

Два пути:

```bash
# 1) из репозитория ALT (нужен root):
apt-get install -y java-17-openjdk-devel

# 2) без root — Temurin 17 в домашний каталог (проверено, работает):
curl -L -o ~/jdk17.tgz "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
mkdir -p ~/jdk17 && tar -xzf ~/jdk17.tgz -C ~/jdk17 --strip-components=1
export JAVA_HOME=$HOME/jdk17
export PATH=$JAVA_HOME/bin:$PATH
```

### 10.2. jpackage падает на `objcopy`

```
jlink failed with: Error: java.io.IOException: Cannot run program "objcopy": ...
```

`jpackage` при сборке runtime вызывает `jlink`, который обдирает отладочные символы
через `objcopy` из пакета **binutils**. Лечится двумя способами:

```bash
# правильный: поставить binutils (root)
apt-get install -y binutils

# обходной, без root: подсунуть готовый runtime
cp -a ~/jdk17 ~/jre17 && rm -rf ~/jre17/jmods ~/jre17/lib/src.zip ~/jre17/include ~/jre17/man
./gradlew buildPortable -PruntimeImage=$HOME/jre17
```

### 10.3. jpackage не знает тип `deb` / `rpm`

```
Error: Invalid or unsupported type: [deb]
```

Это не про права на файлы: у `jpackage` просто **нет инструментов** для этого типа.
Проверено на ALT:

| Тип | Что нужно поставить | Результат на ALT 10.4 |
|---|---|---|
| `app-image` | ничего | ✅ работает |
| `deb` | `dpkg`, `fakeroot`, `binutils` | ✅ работает после установки |
| `rpm` | `rpm-build` (rpmbuild, rpm 4.13 уже есть) | ❌ `Invalid or unsupported type: [rpm]` |

Про `.rpm`: `jpackage` из Temurin 17 на ALT 10.4 тип `rpm` не поддерживает, даже когда
`rpmbuild` установлен (проверено также с `LC_MESSAGES=C`). Практический вывод:
для ALT используйте **`.deb` + `dpkg`** (работает, см. 10.5) или **AppImage**.
Если rpm обязателен — его можно собрать отдельно `rpmbuild`-ом из готового
app-image (SPEC-файл пишется вручную), либо собирать на RHEL-подобной системе.

### 10.4. Установка `.deb` на ALT: две тонкости

1. Нужен `dpkg` (`apt-get install -y dpkg`).
2. `dpkg` сверяет зависимости по **своей** базе, а в ALT пакеты rpm-ные, поэтому
   он «не видит» установленный `xdg-utils`:

   ```
   dpkg: зависимости пакетов не позволяют настроить пакет defectmap:
    defectmap зависит от xdg-utils, однако: Пакет xdg-utils не установлен.
   ```

   Ставить так (проверено, пакет настраивается и работает):

   ```bash
   sudo dpkg -i --force-depends build/dist/defectmap_*.deb
   ```

3. Файлы ставятся **с подкаталогом имени**: `/opt/defectmap/defectmap/bin/DefectMap`
   (а не `/opt/defectmap/bin/DefectMap`). Ярлык в меню при этом правильный:
   `Exec=/opt/defectmap/defectmap/bin/DefectMap`.

### 10.5. sudo в ALT выключен для wheel

Симптом: пароль вводится (невидимо, как всегда), но:

```
[sudo] password for admin: admin отсутствует в файле sudoers.
```

Причина: пользователь в группе `wheel` есть, а правило для `wheel` в `/etc/sudoers`
**закомментировано** (строка `## Uncomment to allow members of group wheel ...`).
Временно работать через `su -` (пароль root задаётся при установке).
Разрешить `sudo` навсегда (от root):

```bash
echo '%wheel ALL=(ALL) ALL' > /etc/sudoers.d/wheel
```

### 10.6. Не используйте `LC_ALL=C` при сборке

Если путь к проекту содержит кириллицу (например `~/Рабочий стол/DefectMap`),
`LC_ALL=C` ломает Gradle:

```
Error: Could not find or load main class org.gradle.wrapper.GradleWrapperMain
```

Причина: без UTF-8 локали JVM не читает кириллический путь в `-classpath`.
Если нужны английские сообщения инструментов — используйте `LC_MESSAGES=C`
(кодировка имён файлов при этом остаётся UTF-8).

### 10.7. Что получилось в итоге (замеры на ALT 10.4)

| Артефакт | Размер | Проверка |
|---|---|---|
| `build/libs/DefectMap.jar` | 78 МБ | `--selftest`: 18 OK / 0 FAIL |
| `build/dist/DefectMap/` (app-image) | ~100 МБ | `bin/DefectMap --selftest`: 18 OK / 0 FAIL |
| `defectmap_1.1.0-1_amd64.deb` | 107 МБ | установлен через `dpkg -i --force-depends`, `--selftest`: 18 OK / 0 FAIL |
| `DefectMap-1.1.0-x86_64.AppImage` | 120 МБ | `APPIMAGE_EXTRACT_AND_RUN=1 … --selftest`: 18 OK / 0 FAIL |

Что подтвердилось на живой системе:

* нативки Linux в JAR: `libglassgtk3.so`, `libprism_sw.so`, `libjavafx_font_pango.so`, `libjfxwebkit.so`;
* шрифты: Arial подменяется на `Liberation Sans`, кириллица в SVG корректна;
* БД: 186 единиц оборудования, 9 дефектов, миграции проходят;
* Excel: `.xlsx` создаётся и читается обратно;
* печать: найден `Cups-PDF` (виртуальный принтер) — печать в PDF доступна;
* окно приложения открывается, схема рисуется в WebView;
* установленный из `.deb` вариант сам разворачивает данные в `~/.local/share/defectmap`.

### 10.8. Маркеры оборудования: причина смещения и миграция

**Исправлено.** Проблема не связана с Linux: слой маркеров занимал всё окно,
а схема вписывалась с сохранением пропорций, поэтому координаты не совпадали.
Плюс при сохранении нового маркера проценты считались от окна, а не от схемы —
в старых БД `top` доходил до **190%** (вертикальное искажение ≈1.94 при окне
1920×1017), при этом `left` был в норме.

Что изменено в коде:

* схема получает явную высоту и вписывается целиком;
* `#equipment-container` (слой маркеров) подгоняется под реально видимый
  прямоугольник схемы функцией `fitMarkerLayer()` — при загрузке, ресайзе
  и пересоздании слоя;
* все пересчёты «пиксель ↔ процент» (клик для добавления оборудования,
  перетаскивание маркера) используют `window.dmBoxRect()` — область схемы.
  `--appsmoke` печатает контрольную строку `область схемы: … center=50.0,50.0`.

Старые данные нужно один раз пересчитать (бэкап кладётся в `backups/marker-fix-*`):

```bash
# посмотреть, что изменится
java -Ddefectmap.home="$PWD" -jar build/libs/DefectMap.jar --fix-markers --dry-run

# применить (по умолчанию коэффициент 1.94; для другого размера окна — --factor=N)
java -Ddefectmap.home="$PWD" -jar build/libs/DefectMap.jar --fix-markers
```

После миграции маркеры встают на оборудование, а новые добавляются сразу точно.
Если коэффициент подобран неверно, маркеры сместятся по вертикали — тогда
восстановите БД из `backups/marker-fix-*` и повторите с другим `--factor`.