#!/usr/bin/env bash
# Подготовка Ubuntu внутри WSL2 к сборке и проверке DefectMap.
#
# Запускать ВНУТРИ WSL (Ubuntu), из любого места:
#   bash /mnt/c/Users/<вы>/IdeaProjects/DefectMap/packaging/linux/wsl-setup.sh
#
# Что делает:
#   1. ставит JDK 17, Xvfb, GTK/NSS-библиотеки и шрифты;
#   2. если проект лежит на /mnt/c (медленная ФС, права Windows) — копирует его
#      в ~/dev/DefectMap и работает там;
#   3. собирает fat JAR, прогоняет --selftest и --appsmoke (скриншот в /tmp);
#   4. собирает .deb и портативную app-image.
#
# Ничего в Windows не меняет, кроме копии скриншота в C:\Users\Public.

set -euo pipefail

# JDK 17 на Linux с LANG=C ломает кириллицу без явной локали
export LANG="${LANG:-C.UTF-8}"
export LC_ALL="${LC_ALL:-C.UTF-8}"

step() { echo; echo "═══ $* ═══"; }

step "1/5. Пакеты (нужен sudo)"
sudo apt-get update
sudo apt-get install -y --no-install-recommends \
  openjdk-17-jdk \
  xvfb \
  libgtk-3-0 libnss3 libxrender1 libxtst6 libxi6 \
  fonts-liberation fonts-dejavu-core \
  curl tar gzip ca-certificates

java -version
echo "jpackage: $(command -v jpackage || echo 'НЕ НАЙДЕН')"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

step "2/5. Проект"
if [[ "$PROJECT_DIR" == /mnt/* ]]; then
  echo "Проект на Windows-ФС ($PROJECT_DIR) — копирую на Linux-ФС"
  DEST="$HOME/dev/DefectMap"
  mkdir -p "$(dirname "$DEST")"
  tar -C "$PROJECT_DIR" \
      --exclude=build --exclude=.gradle --exclude=.idea --exclude=.kotlin \
      -czf /tmp/defectmap-src.tgz .
  mkdir -p "$DEST"
  tar -xzf /tmp/defectmap-src.tgz -C "$DEST"
  PROJECT_DIR="$DEST"
fi
cd "$PROJECT_DIR"
chmod +x gradlew
echo "Рабочий каталог: $PROJECT_DIR"

step "3/5. Тесты и fat JAR"
./gradlew test shadowJar --console=plain

step "4/5. Проверки на Linux"
java -jar build/libs/DefectMap.jar --selftest

SHOT="/tmp/defectmap-app-smoke.png"
echo
echo "--- --appsmoke (окно под Xvfb, на Windows 10 GUI WSL не показывается) ---"
if xvfb-run -a java -jar build/libs/DefectMap.jar --appsmoke --snapshot "$SHOT"; then
  if [[ -d /mnt/c/Users/Public ]]; then
    cp "$SHOT" /mnt/c/Users/Public/defectmap-app-smoke.png || true
    echo "Скриншот: C:\\Users\\Public\\defectmap-app-smoke.png"
  fi
else
  echo "⚠️ --appsmoke не прошёл: проверьте вывод выше (обычно не хватает GTK/NSS)"
fi

step "5/5. Пакеты"
./gradlew buildPortable buildDeb --console=plain

echo
echo "✅ Готово"
ls -la build/dist 2>/dev/null || true
echo
echo "Установить .deb и проверить установленное приложение:"
echo "  sudo apt-get install -y ./build/dist/defectmap_*.deb"
echo "  /opt/defectmap/bin/DefectMap --selftest"
echo "  xvfb-run -a /opt/defectmap/bin/DefectMap --appsmoke"
