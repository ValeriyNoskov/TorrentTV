#!/bin/bash
# ============================================================
# HOME SERVER для ВНосКино: TorrServer на Mac одной командой
#
# Запуск на серверном Mac:
#   bash setup_home_server.sh
#
# Что делает:
#   1) Скачивает TorrServer (правильной архитектуры) в ~/torrserver
#   2) Настраивает автозапуск (launchd) — переживает перезагрузку
#   3) Запрещает Mac засыпать (нужен пароль администратора)
#   4) Показывает адрес для ВНосКино (⚙ → TorrServe)
#
# Смотреть логи сервера:  cat ~/torrserver/torrserver.log
# Перезапуск:             launchctl kickstart -k gui/$(id -u)/ru.torrserver
# Остановить:             launchctl bootout gui/$(id -u)/ru.torrserver
# ============================================================
set -e

DIR="$HOME/torrserver"
VERSION_URL="https://github.com/YouROK/TorrServer/releases/latest/download"

echo "=== ВНосКино: установка TorrServer на Mac ==="

# --- 1. Архитектура ---
case "$(uname -m)" in
    arm64)  BIN="TorrServer-darwin-arm64" ;;
    x86_64) BIN="TorrServer-darwin-amd64" ;;
    *) echo "Неизвестная архитектура: $(uname -m)"; exit 1 ;;
esac
echo "Архитектура: $(uname -m) → $BIN"

# --- 2. Скачивание ---
mkdir -p "$DIR"
echo "Скачиваю TorrServer..."
curl -fsSL "$VERSION_URL/$BIN" -o "$DIR/torrserver"
chmod +x "$DIR/torrserver"
echo "OK: $DIR/torrserver"

# --- 3. Автозапуск через launchd (переживает перезагрузку) ---
PLIST="$HOME/Library/LaunchAgents/ru.torrserver.plist"
cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
    <key>Label</key><string>ru.torrserver</string>
    <key>ProgramArguments</key><array>
        <string>$DIR/torrserver</string>
        <string>-dir</string><string>$DIR</string>
        <string>-port</string><string>8090</string>
        <string>-logpath</string><string>$DIR/torrserver.log</string>
    </array>
    <key>RunAtLoad</key><true/>
    <key>KeepAlive</key><true/>
</dict></plist>
EOF
launchctl bootout "gui/$(id -u)/ru.torrserver" 2>/dev/null || true
launchctl load "$PLIST"
echo "OK: автозапуск настроен (launchd)"

# --- 4. Mac не должен засыпать ---
echo ""
echo "Теперь запрещу Mac засыпать (нужен пароль администратора):"
if sudo -v 2>/dev/null; then
    sudo pmset -a sleep 0
    sudo pmset -a womp 1      # пробуждение по сети
    echo "OK: сон отключён"
else
    echo "⚠ Пропущено: не удалось получить sudo. Выполните вручную:"
    echo "   sudo pmset -a sleep 0 && sudo pmset -a womp 1"
fi

# --- 5. Проверка и адрес ---
sleep 2
if curl -fsS -m 5 "http://127.0.0.1:8090/echo" > /dev/null 2>&1; then
    echo ""
    echo "✅ TorrServer работает!"
else
    echo ""
    echo "⚠ Сервер пока не отвечает — проверьте лог: $DIR/torrserver.log"
fi

IP=$(ipconfig getifaddr en0 || ipconfig getifaddr en1 || echo "?")
echo ""
echo "============================================================"
echo "  В ВНосКино (на телевизоре):"
echo "  ⚙ → TorrServe → адрес:"
echo ""
echo "      http://$IP:8090"
echo ""
echo "  (закрепите этот IP в роутере, чтобы не менялся)"
echo "============================================================"
