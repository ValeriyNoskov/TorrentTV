# 🍿 КиноСервер 2.0 — домашний медиасервер поверх TorrServe

Сервис на MacBook Air (192.168.3.78), который превращает раздачи TorrServe в
**библиотеку: тайтл → сезоны → серии** с точным прогрессом каждой серии.
Плюс **очередь закачек, место на диске, автоочистка, веб-панель**.
Качает и стримит TorrServe — он с этим справляется лучше самописного движка.

## Что взято из проверенных аналогов

| Откуда | Что взято |
|---|---|
| Официальный клиент TorrServe (`TorrentHelper.kt`) | `&play` для воспроизведения, `&preload` только для докачки (без `&play` TorrServe отдаёт 200 и 0 байт) |
| Lampa (`EpisodeParser`, `folder_regexps`) | сезон из **имени папки** («Season 5/01.mkv», «5 сезон/06.mkv») |
| Lampa (`/cache`) | карта кусков `Pieces` — считаем прогресс **по файлу**, а не по всему торренту |

## Библиотека

`GET /api/library` → `{"titles": [...]}`. У каждой серии:

- `season`, `episode`, `hash`, `index`, `length`, `name`;
- `loaded` — % файла в кеше сервера (по кускам `/cache`);
- `completed` — серия целиком в кеше;
- `sources` — сколько раздач содержат эту серию (дубли склеены, оставлен
  самый скачанный источник).

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
- `GET /api/library` — библиотека: тайтлы, сезоны, серии, прогресс
- `GET /api/stream?hash=…&index=…` — 302 в TorrServe (`&play`); Range работает
- `POST /api/preload` — `{"hash": "…", "index": N}` — докачка серии
- `GET /` — панель (серии с ✓/%), `GET /del?hash=…` — удалить раздачу

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
