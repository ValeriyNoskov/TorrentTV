#!/bin/bash
# Установка КиноСервера на Mac (запускается НА самом сервере).
#
#   bash install_mac.sh              — автоопределение кеша TorrServe, порт 8092
#   bash install_mac.sh <кеш> <порт> — вручную
#
# Делает: копию сервиса в ~/kinoserver, поиск кеша TorrServe, автозапуск
# через launchd (переживает перезагрузку), проверку панели.
set -e

APP_DIR="$HOME/kinoserver"
SRC_DIR="$(cd "$(dirname "$0")" && pwd)"
PORT="${2:-8092}"

mkdir -p "$APP_DIR"
cp "$SRC_DIR/kino_server.py" "$APP_DIR/"
echo "✓ сервис: $APP_DIR/kino_server.py"

# ---------- Кеш TorrServe ----------
find_cache() {
  local c
  for c in \
    "$HOME/Library/Application Support/TorrServe" \
    "$HOME/Library/Caches/TorrServe" \
    "$HOME/TorrServe" \
    "$HOME/Downloads/TorrServe"; do
    [ -d "$c" ] && { echo "$c"; return; }
  done
  # Поиск по имени в домашней папке (неглубоко, быстро)
  c=$(find "$HOME" -maxdepth 4 -type d -iname "*torrserve*" 2>/dev/null | head -1)
  [ -n "$c" ] && echo "$c"
}

CACHE="$1"
[ -z "$CACHE" ] && CACHE=$(find_cache)

# Python: новый (python.org/brew) предпочтительнее системного 3.9 —
# системный требует согласованной лицензии Xcode и снят с поддержки
PY=""
for c in /usr/local/bin/python3.13 /opt/homebrew/bin/python3.13 \
         /usr/local/bin/python3 /opt/homebrew/bin/python3 /usr/bin/python3; do
  [ -x "$c" ] && { PY="$c"; break; }
done
[ -z "$PY" ] && { echo "✗ python3 не найден"; exit 1; }
echo "✓ python: $PY ($("$PY" -V 2>&1))"

if [ -z "$CACHE" ] || [ ! -d "$CACHE" ]; then
  echo "⚠ кеш TorrServe не найден автоматически."
  echo "  Откройте TorrServe → Настройки → Каталог кеша и запустите:"
  echo "  bash $0 \"<путь к кешу>\" $PORT"
  CACHE="$HOME/Library/Application Support/TorrServe"
fi
echo "✓ кеш TorrServe: $CACHE"

# ---------- Порт ----------
if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "⚠ порт $PORT занят — выбираю 8093"
  PORT=8093
fi

# ---------- launchd ----------
PLIST="$HOME/Library/LaunchAgents/ru.vnoskino.kinoserver.plist"
mkdir -p "$HOME/Library/LaunchAgents"
cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>ru.vnoskino.kinoserver</string>
  <key>ProgramArguments</key><array>
    <string>$PY</string>
    <string>$APP_DIR/kino_server.py</string>
    <string>--torr</string><string>http://127.0.0.1:8090</string>
    <string>--cache</string><string>$CACHE</string>
    <string>--max-gb</string><string>${MAX_GB:-30}</string>
    <string>--port</string><string>$PORT</string>
  </array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>$APP_DIR/kino.log</string>
  <key>StandardErrorPath</key><string>$APP_DIR/kino.err</string>
</dict></plist>
EOF
echo "✓ автозапуск: $PLIST"

launchctl unload "$PLIST" 2>/dev/null || true
launchctl load "$PLIST"
sleep 2

# ---------- Проверка ----------
echo "--- проверка ---"
curl -s -m 5 "http://127.0.0.1:$PORT/status" | head -c 300 || echo "нет ответа"
echo
echo "✓ панель: http://$(ipconfig getifaddr en0 2>/dev/null || echo localhost):$PORT/"
echo "  лог: $APP_DIR/kino.log"
