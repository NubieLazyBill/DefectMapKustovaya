#!/usr/bin/env bash
# Проверка окружения перед запуском DefectMap на Linux (ALT, Debian/Ubuntu, Astra и др.).
# Ничего не меняет в системе — только сообщает, чего не хватает.
#
#   bash packaging/linux/check-environment.sh

set -uo pipefail

ok()   { echo "  [OK]   $*"; }
warn() { echo "  [WARN] $*"; }
bad()  { echo "  [FAIL] $*"; }

FAILS=0
fails() { bad "$*"; FAILS=$((FAILS + 1)); }

echo "=================================================================="
echo " DefectMap — проверка окружения Linux"
echo "=================================================================="
echo "  Ядро:      $(uname -srm)"
if [[ -r /etc/os-release ]]; then
  # shellcheck disable=SC1091
  . /etc/os-release
  echo "  Дистрибутив: ${PRETTY_NAME:-$NAME $VERSION}"
fi
echo "  Локаль:    LANG=${LANG:-<не задана>}  LC_ALL=${LC_ALL:-<не задана>}"
echo

echo "[Java]"
if command -v java >/dev/null 2>&1; then
  JAVA_VERSION="$(java -version 2>&1 | head -n1)"
  ok "java: $JAVA_VERSION"
  JAVA_MAJOR="$(printf '%s' "$JAVA_VERSION" | grep -oE '"[0-9]+' | tr -d '"' | head -n1)"
  if [[ "${JAVA_MAJOR:-0}" == "1" ]]; then
    JAVA_MAJOR="$(printf '%s' "$JAVA_VERSION" | grep -oE '"[0-9]+\.[0-9]+' | tr -d '"' | cut -d. -f2)"
  fi
  if [[ -n "${JAVA_MAJOR:-}" && "${JAVA_MAJOR}" -lt 17 ]]; then
    warn "Java ${JAVA_MAJOR} — для запуска fat JAR нужен JDK 17+ (в пакете свой runtime уже внутри)"
  fi
else
  fails "java не найдена в PATH (для пакета .deb/.rpm свой runtime уже внутри)"
fi

echo
echo "[Библиотеки JavaFX / GTK (нужны для окна и WebView)]"
check_lib() {
  local pattern="$1" human="$2"
  if command -v ldconfig >/dev/null 2>&1 && ldconfig -p 2>/dev/null | grep -q "$pattern"; then
    ok "$human"
  else
    warn "$human — не найдено ($pattern). Установите пакет (см. docs/LINUX-ALT.md)"
  fi
}
check_lib "libgtk-3\.so" "GTK 3"
check_lib "libnss3\.so"  "NSS (WebKit)"
check_lib "libXtst\.so"  "XTest"
check_lib "libXrender\.so" "XRender"
check_lib "libfreetype\.so" "FreeType"
check_lib "libfontconfig\.so" "Fontconfig"

echo
echo "[Шрифты]"
if command -v fc-list >/dev/null 2>&1; then
  TOTAL=$(fc-list 2>/dev/null | wc -l)
  ok "fontconfig доступен, шрифтов: $TOTAL"
  if fc-list 2>/dev/null | grep -qi 'Liberation Sans'; then
    ok "Liberation Sans (метрический аналог Arial) установлен"
  else
    warn "Liberation Sans не найден — Arial будет заменён менее похожим шрифтом (пакет fonts-liberation / fonts-ttf-liberation)"
  fi
  if fc-match -f '%{family}\n' 'Arial' 2>/dev/null | grep -qi 'Arial'; then
    ok "Arial доступен"
  else
    warn "Arial не установлен — это нормально; используется fallback"
  fi
else
  warn "fc-list отсутствует (fontconfig)"
fi

echo
echo "[Печать]"
if command -v lpstat >/dev/null 2>&1; then
  if lpstat -p -d >/dev/null 2>&1; then
    PRINTERS=$(lpstat -p 2>/dev/null | wc -l)
    if [[ "$PRINTERS" -gt 0 ]]; then
      ok "CUPS настроен, принтеров: $PRINTERS"
    else
      warn "CUPS работает, но принтеров нет — печать будет недоступна (экспорт в Excel работает)"
    fi
  else
    warn "CUPS не отвечает — установите и запустите cups"
  fi
else
  warn "lpstat отсутствует — печать, скорее всего, недоступна (пакет cups-client)"
fi

echo
echo "[Дисплей]"
if [[ -n "${DISPLAY:-}" || -n "${WAYLAND_DISPLAY:-}" ]]; then
  ok "графическая сессия: DISPLAY=${DISPLAY:-<нет>} WAYLAND_DISPLAY=${WAYLAND_DISPLAY:-<нет>}"
else
  warn "нет DISPLAY/WAYLAND_DISPLAY — GUI не запустится. Для проверки без экрана: xvfb-run -a ..."
  if command -v xvfb-run >/dev/null 2>&1; then
    ok "xvfb-run установлен — проверки --uicheck выполнимы"
  else
    warn "xvfb-run отсутствует (пакет xvfb / xorg-xvfb)"
  fi
fi

echo
echo "=================================================================="
if [[ "$FAILS" -eq 0 ]]; then
  echo " Итог: критичных проблем не найдено"
else
  echo " Итог: критичных проблем: $FAILS (см. FAIL выше)"
fi
echo " Далее: java -jar DefectMap.jar --selftest"
echo "=================================================================="
exit 0
