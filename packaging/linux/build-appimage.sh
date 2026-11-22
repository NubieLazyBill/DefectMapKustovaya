#!/usr/bin/env bash
# Сборка AppImage из портативной app-image, которую делает jpackage.
#
# Использование:
#   bash packaging/linux/build-appimage.sh <app-image-dir> <dist-dir> [version]
# Пример:
#   bash packaging/linux/build-appimage.sh build/dist/DefectMap build/dist 1.1.0
#
# Требуется: bash, coreutils, appimagetool (скачается автоматически при наличии curl).
# FUSE не нужен: используется APPIMAGE_EXTRACT_AND_RUN=1.

set -euo pipefail

APP_IMAGE_DIR="${1:-}"
DIST_DIR="${2:-}"
VERSION="${3:-1.0.0}"
APP_NAME="DefectMap"

die() { echo "❌ $*" >&2; exit 1; }
info() { echo "▶ $*"; }

[[ -n "$APP_IMAGE_DIR" && -n "$DIST_DIR" ]] || die "использование: $0 <app-image-dir> <dist-dir> [version]"
[[ -d "$APP_IMAGE_DIR" ]] || die "нет каталога app-image: $APP_IMAGE_DIR"
[[ -x "$APP_IMAGE_DIR/bin/$APP_NAME" ]] || die "нет исполняемого файла: $APP_IMAGE_DIR/bin/$APP_NAME"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

APPDIR="$WORK_DIR/$APP_NAME.AppDir"
info "Готовим AppDir: $APPDIR"
mkdir -p "$APPDIR"
cp -a "$APP_IMAGE_DIR/." "$APPDIR/"

# Стандартная точка входа AppImage
ln -sf "bin/$APP_NAME" "$APPDIR/AppRun"

# .desktop и иконки обязательны для appimagetool
cp "$SCRIPT_DIR/defectmap.desktop" "$APPDIR/$APP_NAME.desktop"
cp "$SCRIPT_DIR/defectmap.png" "$APPDIR/$APP_NAME.png"
cp "$SCRIPT_DIR/defectmap.png" "$APPDIR/.DirIcon"
mkdir -p "$APPDIR/usr/share/icons/hicolor/256x256/apps"
cp "$SCRIPT_DIR/defectmap.png" "$APPDIR/usr/share/icons/hicolor/256x256/apps/defectmap.png"

# ---- appimagetool ----
APPIMAGETOOL="${APPIMAGETOOL:-}"
if [[ -z "$APPIMAGETOOL" ]] && command -v appimagetool >/dev/null 2>&1; then
  APPIMAGETOOL="$(command -v appimagetool)"
fi

if [[ -z "$APPIMAGETOOL" ]]; then
  TOOLS_DIR="$PROJECT_DIR/build/tools"
  mkdir -p "$TOOLS_DIR"
  APPIMAGETOOL="$TOOLS_DIR/appimagetool-x86_64.AppImage"
  if [[ ! -x "$APPIMAGETOOL" ]]; then
    command -v curl >/dev/null 2>&1 || die "нужен curl (или установите appimagetool в PATH)"
    info "Скачиваю appimagetool..."
    URLS=(
      "https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage"
      "https://github.com/AppImageKit/AppImageKit/releases/download/continuous/appimagetool-x86_64.AppImage"
    )
    ok=0
    for url in "${URLS[@]}"; do
      if curl -fsSL --connect-timeout 20 --retry 2 -o "$APPIMAGETOOL" "$url"; then
        ok=1
        break
      fi
      echo "  (не удалось: $url)"
    done
    [[ "$ok" -eq 1 ]] || die "не удалось скачать appimagetool. Установите его вручную и задайте APPIMAGETOOL=/path"
    chmod +x "$APPIMAGETOOL"
  fi
fi

ARCH="${ARCH:-x86_64}"
mkdir -p "$DIST_DIR"
OUT="$DIST_DIR/${APP_NAME}-${VERSION}-${ARCH}.AppImage"
rm -f "$OUT"

export ARCH
# Работает без FUSE и без root
export APPIMAGE_EXTRACT_AND_RUN=1

info "Собираю AppImage: $OUT"
if ! "$APPIMAGETOOL" --no-appstream "$APPDIR" "$OUT" 2>/dev/null; then
  # старые версии appimagetool не знают --no-appstream
  "$APPIMAGETOOL" "$APPDIR" "$OUT"
fi

[[ -f "$OUT" ]] || die "AppImage не создан"
chmod +x "$OUT"

SIZE_MB=$(( $(stat -c %s "$OUT") / 1024 / 1024 ))
echo "✅ AppImage готов: $OUT (${SIZE_MB} МБ)"
echo "   Запуск: chmod +x \"$OUT\" && \"$OUT\""
