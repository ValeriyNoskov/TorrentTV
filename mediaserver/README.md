# 🍿 КиноСервер — надзиратель над TorrServe

Маленький сервис на MacBook Air (192.168.3.78), который добавляет к TorrServe
то, чего ему не хватает: **очередь закачек, место на диске, автоочистку,
веб-панель**. Стрим остаётся на TorrServe — он с ним справляется.

Только стандартная библиотека Python — **ничего устанавливать не нужно**.

## Установка на Mac-сервере (192.168.3.78)

1. Скопировать папку `mediaserver` на сервер (AirDrop/флешка/`scp -r`).
2. Узнать, где TorrServe держит кеш: открыть TorrServe UI
   (`http://192.168.3.78:8090`) → Настройки → «Каталог кеша» (путь показать в панели ниже).
3. Запустить (в Терминале на сервере):

```bash
python3 kino_server.py --torr http://127.0.0.1:8090 \
    --cache <ПУТЬ-КЕША-ИЗ-TORRSERVE> --max-gb 100
```

4. Проверка с телефона/компа: `http://192.168.3.78:8091/` — панель:
   раздачи, скачано, место, кнопки удаления, последние события.

## Автозапуск при перезагрузке (launchd)

```bash
mkdir -p ~/bin && cp kino_server.py ~/bin/
cat > ~/Library/LaunchAgents/ru.vnoskino.kinoserver.plist <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
 "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>ru.vnoskino.kinoserver</string>
  <key>ProgramArguments</key><array>
    <string>/usr/bin/python3</string>
    <string>$HOME/bin/kino_server.py</string>
    <string>--torr</string><string>http://127.0.0.1:8090</string>
    <string>--cache</string><string>ПУТЬ-КЕША-TORRSERVE</string>
    <string>--max-gb</string><string>100</string>
  </array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
</dict></plist>
EOF
launchctl load ~/Library/LaunchAgents/ru.vnoskino.kinoserver.plist
```

## API (для ВНосКино и телефона)

- `POST /queue/add` — `{"title": "Титаны", "magnets": ["magnet:?xt=…", …]}`
- `POST /keep` — `{"hashes": ["<40 hex>"]}` — не удалять при автоочистке
- `GET /status` — JSON: раздачи, очередь, место, лог
- `GET /` — панель, `GET /del?hash=…` — удалить раздачу

## Что уже установлено на Air (192.168.3.78)

| Что | Где |
|---|---|
| TorrServe (бинарник `torrserver`, порт 8090) | `~/torrserver`, данные `~/torrserver/cache` |
| Кеш TorrServe | лимит **30 ГБ** (`settings.json` → `CacheSize`) |
| КиноСервер (порт **8092**) | `~/kinoserver/kino_server.py`, автозапуск `ru.vnoskino.kinoserver` |
| Python | `/usr/local/bin/python3.13` (3.13.9 с python.org) |
| Уборка кеша по возрасту | `~/torrserver/cache-gc.sh` (мягко 25 ГБ / жёстко 30 ГБ) |
| Сон | отключён при питании от сети (`pmset -c sleep 0`) |

Переустановка одной командой (на Air):

```bash
cd ~/mediaserver && bash install_mac.sh /Users/valeriy/torrserver/cache 8092
```

## Важно про MacBook Air

- **Спящий режим** останавливает закачку: в Системных настройках →
  Батарея/Энергосбережение включить «Предотвращать автоматический переход
  в спящий режим при подключении к сети» (или `sudo pmset -c sleep 0`).
- Крышку держать открытой или настроить внешний режим (питание + клавиатура).
