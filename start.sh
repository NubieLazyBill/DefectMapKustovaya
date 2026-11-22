#!/usr/bin/env bash
# Запуск DefectMap из fat JAR на Linux/macOS.
#   ./start.sh                 — открыть приложение
#   ./start.sh --selftest      — проверить окружение
#
# Переменные:
#   DEFECTMAP_JAVA_OPTS  — дополнительные -D (например "-Dprism.order=hw")
#   DEFECTMAP_HOME       — каталог данных

set -euo pipefail

cd "$(dirname "$0")"

JAR="build/libs/DefectMap.jar"
if [[ ! -f "$JAR" ]]; then
  JAR="DefectMap.jar"
fi
if [[ ! -f "$JAR" ]]; then
  echo "❌ Не найден DefectMap.jar. Соберите: ./gradlew shadowJar" >&2
  exit 1
fi

if ! command -v java >/dev/null 2>&1; then
  echo "❌ java не найдена. Установите JDK 17+ (ALT: apt-get install java-17-openjdk)." >&2
  exit 1
fi

# UTF-8 обязателен для кириллицы в JDK 17 при LANG=C
export LANG="${LANG:-C.UTF-8}"
if [[ "${LANG}" == "C" || "${LANG}" == "POSIX" ]]; then
  export LANG=C.UTF-8
fi

JAVA_OPTS=(-Dfile.encoding=UTF-8 -Dprism.order=sw)
# shellcheck disable=SC2206
if [[ -n "${DEFECTMAP_JAVA_OPTS:-}" ]]; then
  JAVA_OPTS+=(${DEFECTMAP_JAVA_OPTS})
fi

exec java "${JAVA_OPTS[@]}" -jar "$JAR" "$@"
