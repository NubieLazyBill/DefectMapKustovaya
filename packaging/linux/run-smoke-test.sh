#!/usr/bin/env bash
# Сквозная проверка готовой сборки на Linux.
#
#   bash packaging/linux/run-smoke-test.sh build/libs/DefectMap.jar
#   bash packaging/linux/run-smoke-test.sh build/dist/DefectMap/bin/DefectMap
#   bash packaging/linux/run-smoke-test.sh /opt/defectmap/bin/DefectMap
#
# Шаг 1: --selftest  — без GUI: БД, Excel, шрифты, схемы, нативные библиотеки, CUPS
# Шаг 2: --uicheck   — JavaFX + WebView (под xvfb-run, если нет дисплея)
# Шаг 3: --appsmoke  — реальный старт окна: FXML, контроллер, БД, маркеры

set -uo pipefail

TARGET="${1:-build/libs/DefectMap.jar}"
FAILS=0
PASSES=0
SHOT="${TMPDIR:-/tmp}/defectmap-app-smoke.png"

step() { echo; echo "──────────────────────────────────────────────────────────────"; echo "▶ $*"; echo "──────────────────────────────────────────────────────────────"; }
pass() { echo "✅ $*"; PASSES=$((PASSES + 1)); }
fail() { echo "❌ $*"; FAILS=$((FAILS + 1)); }

if [[ ! -e "$TARGET" ]]; then
  echo "❌ не найден: $TARGET"
  exit 2
fi

case "$TARGET" in
  *.jar)
    RUN=(java -jar "$TARGET")
    ;;
  *)
    [[ -x "$TARGET" ]] || { echo "❌ файл не исполняемый: $TARGET"; exit 2; }
    RUN=("$TARGET")
    ;;
esac

HAVE_DISPLAY=0
if [[ -n "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ]]; then
  HAVE_DISPLAY=1
elif command -v xvfb-run >/dev/null 2>&1; then
  HAVE_DISPLAY=2
fi

echo "Проверяем: $TARGET"
"${RUN[@]}" --version || true

step "1/3. --selftest (без графики)"
if "${RUN[@]}" --selftest; then
  pass "--selftest"
else
  fail "--selftest (код $?)"
fi

step "2/3. --uicheck (JavaFX + WebView)"
case "$HAVE_DISPLAY" in
  1)
    if "${RUN[@]}" --uicheck --show; then pass "--uicheck (дисплей)"; else fail "--uicheck (дисплей)"; fi
    ;;
  2)
    if xvfb-run -a "${RUN[@]}" --uicheck --show; then
      pass "--uicheck (xvfb-run)"
    else
      fail "--uicheck (xvfb-run)"
    fi
    ;;
  *)
    fail "--uicheck пропущен: нет дисплея и нет xvfb-run. Установите пакет xvfb и повторите."
    ;;
esac

step "3/3. --appsmoke (реальный старт окна)"
case "$HAVE_DISPLAY" in
  1)
    if "${RUN[@]}" --appsmoke --snapshot "$SHOT"; then pass "--appsmoke (дисплей)"; else fail "--appsmoke (дисплей)"; fi
    ;;
  2)
    if xvfb-run -a "${RUN[@]}" --appsmoke --snapshot "$SHOT"; then
      pass "--appsmoke (xvfb-run)"
    else
      fail "--appsmoke (xvfb-run)"
    fi
    ;;
  *)
    fail "--appsmoke пропущен: нужен дисплей или xvfb-run."
    ;;
esac

if [[ -f "$SHOT" ]]; then
  echo "   скриншот: $SHOT"
fi

echo
echo "══════════════════════════════════════════════════════════════"
echo " Итог: $PASSES успешно, $FAILS с ошибками"
echo "══════════════════════════════════════════════════════════════"
[[ "$FAILS" -eq 0 ]] || exit 1
