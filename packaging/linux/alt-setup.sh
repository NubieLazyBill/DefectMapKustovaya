#!/usr/bin/env bash
# DefectMap на ALT Linux: установка зависимостей, сборка и проверка.
#
# Запускать ВНУТРИ ALT (в графической сессии — тогда окно приложения будет видно):
#   bash packaging/linux/alt-setup.sh
# Если проект лежит на общей папке VirtualBox (только чтение) — скрипт сам
# скопирует его в ~/DefectMap:
#   bash /media/sf_DefectMap/packaging/linux/alt-setup.sh
#
# Флаги:
#   --install   после сборки поставить .rpm/.deb и проверить установленное приложение
#   --skip-net  не проверять интернет
#
# Скрипт не меняет настройки системы; ставит только пакеты, нужные для JavaFX
# (GTK/NSS/шрифты) и сборки (JDK 17 с jpackage).

set -uo pipefail

FAILS=0
WARNS=0
DO_INSTALL=0
SKIP_NET=0
for arg in "$@"; do
  case "$arg" in
    --install) DO_INSTALL=1 ;;
    --skip-net) SKIP_NET=1 ;;
    -h|--help) sed -n '2,18p' "$0"; exit 0 ;;
  esac
done

step() { echo; echo "════════════════════════════════════════════════════════════"; echo "▶ $*"; echo "════════════════════════════════════════════════════════════"; }
ok()   { echo "  [OK]   $*"; }
warn() { echo "  [WARN] $*"; WARNS=$((WARNS + 1)); }
fail() { echo "  [FAIL] $*"; FAILS=$((FAILS + 1)); }
info() { echo "  $*"; }

# ---------- права ----------
SUDO=""
if [[ "$(id -u)" -ne 0 ]]; then
  if command -v sudo >/dev/null 2>&1; then
    SUDO="sudo"
    info "Для установки пакетов потребуется пароль администратора (sudo)"
    if ! $SUDO -v 2>/dev/null; then
      echo
      echo "❌ sudo не даёт прав. В ALT бывает, что группа wheel не включена в sudoers." >&2
      echo "   Вариант 1: переключиться на root и запустить скрипт оттуда:" >&2
      echo "       su -" >&2
      echo "       bash $(realpath "$0" 2>/dev/null || echo "$0")" >&2
      echo "   Вариант 2 (навсегда разрешить sudo группе wheel, от root):" >&2
      echo "       echo '%wheel ALL=(ALL) ALL' > /etc/sudoers.d/wheel" >&2
      echo "   Подробнее: docs/LINUX-ALT.md, раздел «Проверено на реальном ALT»." >&2
      exit 1
    fi
  else
    echo "❌ Нужны права root (или sudo), чтобы поставить пакеты." >&2
    exit 1
  fi
fi

# ---------- gradle wrapper: запуск даже без +x ----------
gradle_task() {
  if [[ -x ./gradlew ]]; then
    ./gradlew "$@"
  else
    bash ./gradlew "$@"
  fi
}

# ---------- диагностика нативных библиотек (используется ниже) ----------
diagnose_natives() {
  info "Диагностика нативных библиотек JavaFX:"
  local so found=0
  while IFS= read -r so; do
    [[ -z "$so" ]] && continue
    found=1
    info "  $so"
    ldd "$so" 2>/dev/null | grep -i 'not found' | while read -r line; do
      info "    отсутствует: $line"
    done
  done < <(find "$HOME" "$PROJECT_DIR" -path '*openjfx*' \( -name 'libjfxwebkit.so' -o -name 'libglass.so' \) 2>/dev/null | head -n 4)

  if [[ "$found" -eq 0 ]]; then
    info "  распакованных библиотек JavaFX не найдено — toolkit вообще не стартовал"
  fi
  info "  Частые причины: нет libgtk+3 / libnss / libX11; проверьте раздел «Пакеты ALT» выше."
}

# ======================== 1. ПРЕФЛАЙТ ========================

step "1/8. Система"
if [[ -r /etc/os-release ]]; then
  # shellcheck disable=SC1091
  . /etc/os-release
  info "ОС: ${PRETTY_NAME:-$NAME $VERSION}"
else
  warn "Нет /etc/os-release — не похоже на ALT/Debian"
fi
info "Ядро: $(uname -srm)"
if [[ "$(uname -m)" != "x86_64" ]]; then
  warn "Ожидается x86_64, а здесь $(uname -m) — нативные библиотеки JavaFX могут не подойти"
fi

# UTF-8 обязателен: JDK 17 при LANG=C ломает кириллицу в именах файлов
if locale -a 2>/dev/null | grep -qiE '^ru_RU\.utf-?8$'; then
  export LANG=ru_RU.UTF-8
else
  export LANG=C.UTF-8
fi
export LC_ALL="$LANG"
info "Локаль: $LANG"

FREE_MB=$(df -Pm "$HOME" 2>/dev/null | awk 'NR==2 {print $4}')
if [[ -n "${FREE_MB:-}" ]]; then
  info "Свободно в \$HOME: ${FREE_MB} МБ"
  if [[ "$FREE_MB" -lt 2500 ]]; then
    warn "Мало места: Gradle + app-image + пакеты требуют ~1,5 ГБ (лучше 3 ГБ)"
  fi
fi

if [[ "$SKIP_NET" -eq 0 ]]; then
  if command -v curl >/dev/null 2>&1; then
    if curl -fsI --max-time 12 https://repo1.maven.org/maven2/ >/dev/null 2>&1; then
      ok "Интернет до Maven Central есть"
    else
      fail "Нет доступа к repo1.maven.org — Gradle не скачает зависимости. Включите сеть в ВМ."
    fi
  elif command -v wget >/dev/null 2>&1; then
    if wget -q --spider --timeout=12 https://repo1.maven.org/maven2/ 2>/dev/null; then
      ok "Интернет до Maven Central есть"
    else
      fail "Нет доступа к repo1.maven.org — Gradle не скачает зависимости."
    fi
  else
    warn "Нет curl/wget — проверка сети пропущена (поставлю curl вместе с пакетами)"
  fi
fi

# ======================== 2. ПРОЕКТ НА LINUX-ФС ========================

step "2/8. Каталог проекта"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

# Скрипт могли скопировать отдельным файлом — тогда ищем проект рядом
if [[ ! -f "$PROJECT_DIR/build.gradle.kts" ]]; then
  for candidate in "$PWD" "$HOME/DefectMap" "$HOME/Desktop/DefectMap" "$HOME/Рабочий стол/DefectMap"; do
    if [[ -f "$candidate/build.gradle.kts" ]]; then
      PROJECT_DIR="$(cd "$candidate" && pwd)"
      break
    fi
  done
fi
if [[ ! -f "$PROJECT_DIR/build.gradle.kts" ]]; then
  echo "❌ Не нашёл проект (нет build.gradle.kts)." >&2
  echo "   Перейдите в папку DefectMap и запустите скрипт оттуда:" >&2
  echo "   cd ~/Рабочий\\ стол/DefectMap && bash <путь>/alt-setup.sh" >&2
  exit 1
fi
info "Найден проект: $PROJECT_DIR"

case "$PROJECT_DIR" in
  /media/*|/run/media/*|/mnt/*) NEED_COPY=1 ;;
  *)
    if [[ -w "$PROJECT_DIR" ]] && touch "$PROJECT_DIR/.dm-write-test" 2>/dev/null; then
      rm -f "$PROJECT_DIR/.dm-write-test"
      NEED_COPY=0
    else
      NEED_COPY=1
    fi
    ;;
esac

if [[ "$NEED_COPY" -eq 1 ]]; then
  DEST="$HOME/DefectMap"
  info "Копирую проект в $DEST (права, скорость, chmod)"
  mkdir -p "$DEST"
  tar -C "$PROJECT_DIR" \
      --exclude=build --exclude=.gradle --exclude=.kotlin --exclude=.idea \
      -cf - . 2>/dev/null | tar -C "$DEST" -xf -
  PROJECT_DIR="$DEST"
  ok "Проект скопирован"
else
  ok "Проект на записываемой ФС — работаю на месте"
fi

cd "$PROJECT_DIR" || { echo "❌ Не удалось войти в $PROJECT_DIR" >&2; exit 1; }

# Windows-сборка (JAR с .dll, старый app-image) только мешает
if [[ -d build ]]; then
  info "Удаляю Windows-сборку build/ (внутри JAR с нативными .dll)"
  rm -rf build
fi
rm -rf .gradle .kotlin

# ======================== 3. ПАКЕТЫ ========================

step "3/8. Пакеты ALT"
$SUDO apt-get update -qq || warn "apt-get update завершился с ошибкой (зеркала недоступны?)"

install_first() {
  local what="$1"; shift
  local pkg
  for pkg in "$@"; do
    if $SUDO apt-get install -y "$pkg" >/dev/null 2>&1; then
      ok "$what → $pkg"
      return 0
    fi
  done
  return 1
}

# --- JDK 17 (jpackage входит в java-17-openjdk-devel) ---
JDK_OK=0
if command -v javac >/dev/null 2>&1 && command -v jpackage >/dev/null 2>&1; then
  JDK_OK=1
  ok "JDK 17 + jpackage уже есть: $(javac -version 2>&1)"
elif install_first "JDK 17 с jpackage" java-17-openjdk-devel java-17-openjdk; then
  JDK_OK=1
fi

install_first "curl" curl || true

# --- утилиты, без которых jpackage не соберёт пакеты (проверено на ALT 10.4) ---
# objcopy: нужен jlink внутри jpackage (иначе "Cannot run program objcopy")
install_first "binutils (objcopy)" binutils || warn "binutils нет — jpackage может упасть на jlink"
# dpkg + fakeroot: без них jpackage не умеет тип deb ("Invalid or unsupported type")
install_first "dpkg" dpkg || warn "dpkg нет — .deb не соберётся"
install_first "fakeroot" fakeroot || warn "fakeroot нет — .deb не соберётся"
# xdg-utils: зависимость .deb-пакета (иначе dpkg оставит пакет ненастроенным)
install_first "xdg-utils" xdg-utils || warn "xdg-utils нет — установка .deb будет с ошибкой зависимостей"
# rpm-build: без rpmbuild jpackage не умеет тип rpm
install_first "rpm-build" rpm-build || warn "rpm-build нет — .rpm не соберётся"

if [[ "$JDK_OK" -ne 1 ]]; then
  warn "JDK 17 из репозитория ALT не встал — скачиваю Temurin 17 (Adoptium)"
  TEMURIN_URL="https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
  if curl -fL --retry 2 --connect-timeout 20 -o /tmp/temurin17.tar.gz "$TEMURIN_URL"; then
    rm -rf "$HOME/jdk17"
    mkdir -p "$HOME/jdk17"
    if tar -xzf /tmp/temurin17.tar.gz -C "$HOME/jdk17" --strip-components=1; then
      export JAVA_HOME="$HOME/jdk17"
      export PATH="$JAVA_HOME/bin:$PATH"
      command -v jpackage >/dev/null 2>&1 && JDK_OK=1
    fi
  fi
  [[ "$JDK_OK" -eq 1 ]] && ok "Temurin 17 установлен в \$HOME/jdk17" \
                        || fail "Нет JDK 17 с jpackage — сборка и пакеты невозможны"
fi
if [[ "$JDK_OK" -eq 1 ]]; then
  info "javac: $(command -v javac)"
  info "jpackage: $(command -v jpackage)"
fi

# --- GUI-стек JavaFX/WebView ---
install_first "GTK 3"            libgtk+3 gtk+3 libgtk3        || warn "GTK 3 не найден — окно/WebView не запустятся"
install_first "NSS (WebKit)"     libnss nss libnss3            || warn "NSS не найден — WebView может упасть"
install_first "libX11"           libX11 libx11                 || warn "libX11 не найден"
install_first "libXext"          libXext libxext               || warn "libXext не найден"
install_first "libXrender"       libXrender libxrender         || warn "libXrender не найден"
install_first "libXtst"          libXtst libxtst               || warn "libXtst не найден"
install_first "libXi"            libXi libxi                   || warn "libXi не найден"
install_first "FreeType"         libfreetype libfreetype6       || warn "FreeType не найден"
install_first "fontconfig"       libfontconfig1 fontconfig fontconfig1 || warn "fontconfig не найден"
install_first "шрифты Liberation" fonts-ttf-liberation fonts-liberation || warn "Liberation не найден — Arial подменится менее похожим шрифтом"
install_first "шрифты DejaVu"     fonts-ttf-dejavu fonts-dejavu || warn "DejaVu не найден"

if [[ -z "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ]]; then
  install_first "Xvfb" xvfb xorg-xvfb || warn "Xvfb не найден — GUI-проверку пропущу"
fi

# ======================== 4. СБОРКА ========================

step "4/8. Сборка (тесты + fat JAR)"
chmod +x gradlew 2>/dev/null || warn "chmod +x gradlew не удался (ФС без прав?)"
gradle_task --stop >/dev/null 2>&1

if gradle_task clean test shadowJar --console=plain; then
  ok "Сборка и юнит-тесты прошли"
else
  fail "Сборка упала — см. вывод выше (чаще всего нет JDK 17 или сети)"
fi

JAR="build/libs/DefectMap.jar"
if [[ -f "$JAR" ]]; then
  ok "JAR: $JAR ($(du -h "$JAR" | cut -f1))"
else
  fail "JAR не создан — дальнейшие проверки бессмысленны"
fi

# ======================== 5. SELFTEST ========================

step "5/8. Проверка окружения (--selftest, без GUI)"
if [[ -f "$JAR" ]]; then
  if java -jar "$JAR" --selftest; then
    ok "--selftest пройден"
  else
    fail "--selftest вернул ошибку — см. [FAIL] в выводе выше"
  fi
fi

# ======================== 6. APPSMOKE ========================

step "6/8. Реальный запуск окна (--appsmoke)"
SHOT="$HOME/defectmap-app-smoke.png"
if [[ ! -f "$JAR" ]]; then
  warn "Пропускаю: нет JAR"
elif [[ -n "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ]]; then
  info "Графическая сессия есть — окно приложения появится на экране на ~5 секунд"
  if java -jar "$JAR" --appsmoke --snapshot "$SHOT"; then
    ok "--appsmoke пройден (скриншот: $SHOT)"
  else
    fail "--appsmoke упал"
    diagnose_natives
  fi
elif command -v xvfb-run >/dev/null 2>&1; then
  info "Дисплея нет — запускаю под xvfb-run"
  if xvfb-run -a java -jar "$JAR" --appsmoke --snapshot "$SHOT"; then
    ok "--appsmoke пройден (скриншот: $SHOT)"
  else
    fail "--appsmoke под Xvfb упал"
    diagnose_natives
  fi
else
  warn "Нет ни графической сессии, ни Xvfb — GUI-проверка пропущена"
fi

# ======================== 7. ПАКЕТЫ ========================

step "7/8. Пакеты (.deb, .rpm, app-image, AppImage)"
if [[ -f "$JAR" ]]; then
  if gradle_task buildPortable --console=plain; then
    ok "Портативная сборка: build/dist/DefectMap/"
  else
    fail "buildPortable упал"
  fi

  if gradle_task buildDeb --console=plain; then
    ok ".deb собран"
  else
    warn ".deb не собрался (для ALT не критично — ниже .rpm)"
  fi

  if gradle_task buildRpm --console=plain; then
    ok ".rpm собран"
  else
    warn ".rpm не собрался — для ALT это основной формат, смотрите вывод"
  fi

  if gradle_task buildAppImage --console=plain; then
    ok "AppImage собран"
  else
    warn "AppImage не собрался (нужны curl/appimagetool и сеть)"
  fi
fi

info "Файлы в build/dist:"
ls -la build/dist 2>/dev/null | grep -vE '^total|^d' || info "  (пусто)"

# ======================== 8. УСТАНОВКА (опционально) ========================

if [[ "$DO_INSTALL" -eq 1 ]]; then
  step "8/8. Установка пакета и проверка установленного"
  RPM_FILE=$(ls build/dist/*.rpm 2>/dev/null | head -n1)
  DEB_FILE=$(ls build/dist/*.deb 2>/dev/null | head -n1)

  if [[ -n "$RPM_FILE" ]]; then
    if $SUDO rpm -i --replacepkgs "$RPM_FILE"; then ok "Установлен $RPM_FILE"; else fail "rpm -i не удался"; fi
  elif [[ -n "$DEB_FILE" ]] && command -v dpkg >/dev/null 2>&1; then
    if $SUDO dpkg -i "$DEB_FILE"; then ok "Установлен $DEB_FILE"; else fail "dpkg -i не удался"; fi
  else
    warn "Пакет не найден или нет rpm/dpkg — установка пропущена"
  fi

  if [[ -x /opt/defectmap/bin/DefectMap ]]; then
    if /opt/defectmap/bin/DefectMap --selftest; then
      ok "Установленное приложение: selftest пройден"
    else
      fail "Установленное приложение: selftest упал"
    fi
    info "Данные пользователя: ~/.local/share/defectmap"
  fi
else
  info
  info "Установить собранный пакет (по желанию):"
  info "  sudo rpm -i --replacepkgs build/dist/*.rpm"
  info "  /opt/defectmap/bin/DefectMap --selftest"
  info "или повторить этот скрипт с флагом --install"
fi

# ======================== ОТЧЁТ ========================

step "ОТЧЁТ"
info "Проект:  $PROJECT_DIR"
info "JAR:     $PROJECT_DIR/build/libs/DefectMap.jar"
[[ -f "$SHOT" ]] && info "Снимок:  $SHOT"
echo
if [[ "$FAILS" -eq 0 ]]; then
  echo "  ✅ Ошибок: 0, предупреждений: $WARNS"
  echo "  DefectMap собран и проверен на этой системе."
else
  echo "  ❌ Ошибок: $FAILS, предупреждений: $WARNS"
  echo "  Пришлите весь вывод скрипта — по нему видно, чего не хватает."
fi
echo
if [[ "$FAILS" -eq 0 ]]; then exit 0; else exit 1; fi
