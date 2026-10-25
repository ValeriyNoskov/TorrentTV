#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
КиноСервер 2.0 — домашний медиасервер поверх TorrServe (macOS/Air).

Что умеет:
  * Библиотека: раздачи TorrServe превращаются в тайтлы → сезоны → серии
    (понимает «S05E06», «1x06», «06. Название.S05…»).
  * Стрим с пульта/телефона: /api/stream?hash=…&index=… (302 в TorrServe,
    Range-запросы работают напрямую — без двойного трафика через Python).
  * Докачка серии: /api/preload (держим preload-соединение, пока файл не скачан).
  * Очередь закачек: /queue/add — качает даже когда телевизор выключен.
  * «Не удалять»: /keep — хэши избранного.
  * Автоочистка: кеш > --max-gb → старейшие раздачи (не из keep) удаляются.
  * Панель: библиотека, серии, прогресс, место, лог, удаление.

Только стандартная библиотека Python 3.9+.

Запуск:
  python3 kino_server.py --torr http://127.0.0.1:8090 \
      --cache ~/torrserver/cache --max-gb 30 --port 8092
"""

import argparse
import html
import json
import os
import re
import shutil
import socket
import threading
import time
import urllib.parse
import urllib.request
from collections import OrderedDict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOCK = threading.Lock()

# Очередь задач: title -> {magnets, added_at, status, detail}
QUEUE = OrderedDict()
# Хэши «не удалять» (избранное с ТВ)
KEEP = set()
# Журнал добавления раздач: hash -> timestamp (для LRU-очистки)
ADDED = {}
# Активные докачки: hash:index -> поток
PRELOADS = {}
# Последние события для панели
LOG = []

VIDEO_EXT = {".mkv", ".mp4", ".avi", ".mov", ".m4v", ".ts", ".webm", ".wmv", ".mpg", ".mpeg"}


def log(msg):
    stamp = time.strftime("%H:%M:%S")
    LOG.append("[%s] %s" % (stamp, msg))
    del LOG[:-300]
    print("[%s] %s" % (stamp, msg))


# ---------------------------------------------------------------- TorrServe API

def torr(api, body, timeout=8):
    """POST в TorrServe /torrents. api — база (http://host:8090) или полный путь."""
    url = api if api.rstrip("/").endswith("/torrents") else api.rstrip("/") + "/torrents"
    try:
        req = urllib.request.Request(
            url, data=json.dumps(body).encode(),
            headers={"Content-Type": "application/json"},
        )
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read().decode("utf-8", "replace")
        return json.loads(raw) if raw.strip() else {}
    except Exception as e:
        log("torrserve %s: %s" % (body.get("action"), e))
        return None


def torr_list(api):
    r = torr(api, {"action": "list"})
    return r if isinstance(r, list) else []


def hash_of(magnet):
    m = re.search(r"urn:btih:([0-9a-fA-F]{40})", magnet or "")
    return m.group(1).lower() if m else None


# ------------------------------------------------------------- Библиотека/серии

def parse_episode(path):
    """(сезон, серия) из пути или (None, None).

    Взято проверенное из Lampa (EpisodeParser): если в имени файла сезона нет,
    сезон ищется в ИМЕНИ ПАПКИ («Season 5», «5 сезон», «S05»). Без этого паки
    вида «Титаны/Season 5/01.mkv» получали серию 1 вместо S05E01.
    """
    parts = path.replace("_", " ").split("/")
    name = parts[-1]
    folder = parts[-2] if len(parts) > 1 else ""
    stem = name.rsplit(".", 1)[0]

    m = re.search(r"s(\d{1,2})[._ -]?e(\d{1,3})", stem, re.I)
    if m:
        return int(m.group(1)), int(m.group(2))

    m = re.search(r"(\d{1,2})x(\d{1,3})(\D|$)", stem, re.I)
    if m:
        return int(m.group(1)), int(m.group(2))

    # Сезон в имени (S05) + номер серии в начале («06. Титаны.S05…»)
    sm = re.search(r"s(\d{1,2})(\D|$)", stem, re.I)
    if sm:
        em = re.match(r"0?(\d{1,3})(\D|$)", stem)
        if em and 1 <= int(em.group(1)) <= 99:
            return int(sm.group(1)), int(em.group(1))

    # Сезон только в папке (Lampa: folder_regexps): «Season 5», «5 сезон», «S05»
    if folder:
        fm = re.search(r"(?:season|сезон)\s*(\d{1,2})", folder, re.I)
        if not fm:
            fm = re.search(r"(\d{1,2})\s*(?:season|сезон)", folder, re.I)
        if not fm:
            fm = re.search(r"s(\d{1,2})(\D|$)", folder, re.I)
        if fm:
            season = int(fm.group(1))
            em = re.match(r"0?(\d{1,3})(\D|$)", stem)
            if em and 1 <= int(em.group(1)) <= 99:
                return season, int(em.group(1))

    # «06. Название» без сезона
    em = re.match(r"0?(\d{1,3})(\D|$)", stem)
    if em and 1 <= int(em.group(1)) <= 99:
        return 1, int(em.group(1))
    return None, None


# Кеш пофайлового прогресса: hash -> (timestamp, {file_id: percent})
PROGRESS = {}


def file_progress(api, hash_, files):
    """Сколько процентов каждого файла реально лежит в кеше TorrServe.

    TorrServe не отдаёт «скачано по файлу», но /cache отдаёт карту кусков
    (Pieces: Id → Completed). Сопоставляем границы файла с кусками — так же,
    как это делает клиент TorrServe/Lampa при показе буфера.
    """
    now = time.time()
    hit = PROGRESS.get(hash_)
    if hit and now - hit[0] < 15:
        return hit[1]

    data = torr(api, {"action": "get", "hash": hash_}, timeout=6) or {}
    # /cache живёт отдельным путём, а не в /torrents
    try:
        base = api.rstrip("/")
        if base.endswith("/torrents"):
            base = base[: -len("/torrents")]
        req = urllib.request.Request(
            base + "/cache", data=json.dumps({"action": "get", "hash": hash_}).encode(),
            headers={"Content-Type": "application/json"},
        )
        with urllib.request.urlopen(req, timeout=6) as r:
            data = json.loads(r.read().decode("utf-8", "replace") or "{}")
    except Exception as e:
        log("прогресс: /cache недоступен — %s" % e)
        return {}

    pieces = data.get("Pieces") or {}
    plen = int(data.get("PiecesLength") or 4194304) or 4194304
    done = {int(p.get("Id", k)) for k, p in pieces.items() if p.get("Completed")}

    out = {}
    offset = 0
    for f in sorted(files, key=lambda x: int(x.get("id") or 0)):
        length = int(f.get("length") or 0)
        fid = int(f.get("id") or 0)
        if length <= 0:
            offset += max(0, length)
            continue
        first = offset // plen
        last = (offset + length - 1) // plen
        total = last - first + 1
        have = sum(1 for p in range(first, last + 1) if p in done)
        out[fid] = int(have * 100 / total) if total else 0
        offset += length
    PROGRESS[hash_] = (now, out)
    return out


def is_video(path):
    ext = os.path.splitext(path)[1].lower()
    return ext in VIDEO_EXT


def library(api):
    """Раздачи TorrServe → тайтлы → сезоны → серии."""
    titles = OrderedDict()
    for t in torr_list(api):
        files = t.get("file_stats") or []
        vids = [f for f in files if is_video(f.get("path", ""))]
        if not vids:
            continue
        title = (t.get("title") or t.get("name") or "без названия").strip()
        total = int(t.get("torrent_size") or 0)
        loaded = int(t.get("preloaded_bytes") or 0)
        info = {
            "hash": t.get("hash"),
            "name": t.get("name"),
            "poster": t.get("poster") or "",
            "size": total,
            "loaded": loaded,
            "peers": int(t.get("total_peers") or 0),
            "speed": int(t.get("download_speed") or 0),
            "stat": t.get("stat_string") or "",
            "added": int(t.get("timestamp") or 0),
            "episodes": [],
            "other": [],
        }
        prog = file_progress(api, t.get("hash"), files)
        for f in vids:
            season, episode = parse_episode(f.get("path", ""))
            fid = int(f.get("id") or 0)
            pct = prog.get(fid, 0)
            entry = {
                "hash": t.get("hash"),
                "index": fid,
                "path": f.get("path", ""),
                "name": f.get("path", "").split("/")[-1],
                "length": int(f.get("length") or 0),
                "season": season,
                "episode": episode,
                # Точный прогресс по файлу: 100 — серия целиком в кеше сервера
                "loaded": pct,
                "completed": pct >= 100,
            }
            if season is None:
                info["other"].append(entry)
            else:
                info["episodes"].append(entry)
        titles.setdefault(title, {"title": title, "poster": info["poster"], "torrents": []})
        if info["poster"] and not titles[title]["poster"]:
            titles[title]["poster"] = info["poster"]
        titles[title]["torrents"].append(info)

    out = []
    for title, data in titles.items():
        seasons = {}
        for tor in data["torrents"]:
            for ep in tor["episodes"]:
                if ep["season"] not in seasons:
                    seasons[ep["season"]] = {"season": ep["season"], "episodes": []}
                seasons[ep["season"]]["episodes"].append(ep)
        for s in seasons.values():
            # Одна серия может быть в нескольких раздачах. Склеиваем: оставляем
            # самый скачанный источник, число источников — в "sources"
            # (в панели «3 источника»). Иначе список серий пестрит дублями.
            best = {}
            for ep in s["episodes"]:
                score = (1 if ep.get("completed") else 0, ep.get("loaded") or 0, ep["length"])
                cur = best.get(ep["episode"])
                if cur is None:
                    ep["sources"] = 1
                    best[ep["episode"]] = [score, ep]
                else:
                    cur[1]["sources"] = cur[1].get("sources", 1) + 1
                    if score > cur[0]:
                        ep["sources"] = cur[1]["sources"]
                        best[ep["episode"]] = [score, ep]
            s["episodes"] = [v[1] for _, v in sorted(best.items())]
        out.append({
            "title": title,
            "poster": data["poster"],
            "seasons": [seasons[k] for k in sorted(seasons.keys())],
            "torrents": [
                {
                    "hash": t["hash"], "name": t["name"], "size": t["size"],
                    "loaded": t["loaded"], "peers": t["peers"], "speed": t["speed"],
                    "stat": t["stat"], "files": len(t["episodes"]) + len(t["other"]),
                }
                for t in data["torrents"]
            ],
            "other": [e for t in data["torrents"] for e in t["other"]],
        })
    out.sort(key=lambda x: x["title"].lower())
    return out


def lan_ip():
    """Внешний IP машины (для редиректа стрима с ТВ/телефона)."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def stream_url(api_base, hash_, index, name, mode="play"):
    """Ссылка на поток TorrServe.

    mode="play"    — воспроизведение (обязательный &play, иначе TorrServe
                     отвечает 200 с Content-Length: 0 — проверено на Air);
    mode="preload" — только докачка в кеш (&preload).
    Так же строит ссылки официальный клиент TorrServe
    (TorrentHelper.kt: getTorrentPlayLink / getTorrentPreloadLink).
    """
    tail = "&preload" if mode == "preload" else "&play"
    return "%s/stream/%s?link=%s&index=%d%s" % (
        api_base.rstrip("/"), urllib.parse.quote(name), hash_, index, tail,
    )


def preload_worker(api_base, hash_, index, name, seconds):
    """Держим preload-соединение: TorrServe качает файл в кеш последовательно."""
    key = "%s:%d" % (hash_, index)
    try:
        log("докачка: «%s» (%d) начата" % (name[:50], index))
        url = stream_url(api_base, hash_, index, name, mode="preload")
        req = urllib.request.Request(url)
        deadline = time.time() + seconds
        with urllib.request.urlopen(req, timeout=30) as r:
            while time.time() < deadline:
                chunk = r.read(1024 * 256)
                if not chunk:
                    break
        log("докачка: «%s» завершена/остановлена" % name[:50])
    except Exception as e:
        log("докачка: «%s» ошибка — %s" % (name[:40], e))
    finally:
        with LOCK:
            PRELOADS.pop(key, None)


# ------------------------------------------------------------------- Очередь/GC

def queue_worker(api, cache_dir):
    while True:
        task = None
        with LOCK:
            for title, t in QUEUE.items():
                if t["status"] == "ждёт":
                    task = (title, t)
                    break
        if not task:
            time.sleep(3)
            continue
        title, t = task
        t["status"] = "качаю"
        log("очередь: качаю «%s» (%d раздач)" % (title, len(t["magnets"])))
        for m in t["magnets"]:
            h = hash_of(m)
            if h:
                with LOCK:
                    ADDED[h] = time.time()
            torr(api, {"action": "add", "link": m, "title": title, "save_to_cache": True})
        while True:
            time.sleep(10)
            lst = torr_list(api)
            active = [x for x in lst
                      if x.get("title") == title and int(x.get("stat", 0)) in (1, 2)]
            if not active:
                break
        t["status"] = "готово"
        log("очередь: «%s» готово" % title)


def dir_size(path):
    total = 0
    for root, _, files in os.walk(path):
        for f in files:
            try:
                total += os.path.getsize(os.path.join(root, f))
            except OSError:
                pass
    return total


def cleanup_loop(api, cache_dir, max_gb):
    while True:
        time.sleep(300)
        if not cache_dir or not os.path.isdir(cache_dir):
            continue
        try:
            used = dir_size(cache_dir)
        except Exception as e:
            log("очистка: не посчитать кеш — %s" % e)
            continue
        free = shutil.disk_usage(cache_dir).free
        log("очистка: кеш %.1f ГБ, свободно %.1f ГБ (лимит %d ГБ)"
            % (used / 2**30, free / 2**30, max_gb))
        if used <= max_gb * 2**30 or free > 20 * 2**30:
            continue
        with LOCK:
            removable = sorted(((ts, h) for h, ts in ADDED.items() if h not in KEEP))
        for _, h in removable:
            if used <= max_gb * 2**30 and free > 20 * 2**30:
                break
            log("очистка: сношу раздачу %s… (старая, не в избранном)" % h[:8])
            torr(api, {"action": "rem", "hash": h})
            with LOCK:
                ADDED.pop(h, None)
            break  # пересчёт размера на следующем круге


def gb(n):
    return "%.1f" % (n / 2**30)


# ----------------------------------------------------------------------- Панель

CSS = """body{font-family:-apple-system,sans-serif;background:#0e1116;color:#e6e6e6;
margin:0;padding:18px}h1{font-size:22px;margin:0 0 6px}h2{font-size:17px;margin:22px 0 8px;
color:#a9d7f5}.t{background:#151a22;border:1px solid #223;border-radius:12px;padding:12px;
margin:10px 0}.s{color:#8b98a8;font-size:12px}a{color:#7fd;text-decoration:none}
a:hover{text-decoration:underline}table{border-collapse:collapse;width:100%;font-size:13px}
td,th{padding:5px 8px;border-bottom:1px solid #1e2531;text-align:left}
th{color:#8b98a8;font-weight:500}.chip{display:inline-block;background:#1f2430;border-radius:8px;
padding:3px 9px;margin:2px 3px;font-size:12px}.ok{color:#7fd}.warn{color:#f2c14e}
.del{color:#f2777a}.btn{display:inline-block;background:#1f2430;border:1px solid #2b3444;
border-radius:8px;padding:3px 10px;margin:0 4px}"""


def page(api, cache_dir, max_gb):
    lib = library(api)
    lst = torr_list(api)
    du = shutil.disk_usage(cache_dir) if cache_dir and os.path.isdir(cache_dir) else None
    cache_gb = gb(dir_size(cache_dir)) if cache_dir and os.path.isdir(cache_dir) else "?"

    rows = ""
    for t in lst:
        loaded = int(t.get("preloaded_bytes") or 0)
        total = int(t.get("torrent_size") or 0)
        pct = int(loaded * 100 / total) if total else 0
        rows += ("<tr><td>%s</td><td>%s / %s ГБ (%d%%)</td><td>%d</td>"
                 "<td>%s</td><td><a class='del' href='/del?hash=%s'>✕</a></td></tr>") % (
            html.escape((t.get("title") or "")[:40]),
            gb(loaded), gb(total), pct,
            int(t.get("total_peers") or 0),
            html.escape((t.get("stat_string") or "")[:28]),
            t.get("hash"),
        )

    blocks = ""
    for item in lib:
        body = ""
        for s in item["seasons"]:
            chips = ""
            for ep in s["episodes"]:
                url = "/api/stream?hash=%s&index=%d" % (ep["hash"], ep["index"])
                mark = "✓ " if ep.get("completed") else ("%d%% " % ep["loaded"] if ep.get("loaded") else "")
                src = " · %d ист." % ep["sources"] if ep.get("sources", 1) > 1 else ""
                chips += ("<a class='chip' href='%s'>%sS%02dE%02d · %d МБ%s</a>"
                          % (url, mark, ep["season"], ep["episode"],
                             ep["length"] // 2**20, src))
            body += "<div class='s'>Сезон %d</div><div>%s</div>" % (s["season"], chips)
        if not item["seasons"]:
            for ep in item["other"]:
                body += ("<a class='chip' href='/api/stream?hash=%s&index=%d'>%s (%d МБ)</a>"
                         % (ep["hash"], ep["index"], html.escape(ep["name"][:40]),
                            ep["length"] // 2**20))
        tors = "".join(
            "<div class='s'>%s… · %s/%s ГБ · %d пиров <a class='del' href='/del?hash=%s'>✕ удалить</a></div>"
            % (html.escape(t["hash"][:8]), gb(t["loaded"]), gb(t["size"]), t["peers"], t["hash"])
            for t in item["torrents"]
        )
        blocks += ("<div class='t'><h2>%s</h2>%s%s</div>"
                   % (html.escape(item["title"]), tors, body))

    queue_rows = "".join(
        "<div class='s'>%s — %s</div>" % (html.escape(k), v["status"])
        for k, v in QUEUE.items()
    )
    log_rows = "<br>".join(html.escape(x) for x in LOG[-15:])
    free = gb(du.free) if du else "?"
    return """<html><head><meta charset=utf-8><title>КиноСервер</title>
<meta name=viewport content="width=device-width,initial-scale=1"><style>%s</style></head>
<body><h1>🍿 КиноСервер</h1>
<div class="s">Библиотека: %d тайтлов · кеш %s ГБ (лимит %d) · свободно %s ГБ ·
TorrServe %d раздач</div>
<h2>Библиотека</h2>%s
<h2>Раздачи на сервере</h2>
<table><tr><th>Название</th><th>Скачано</th><th>Пиры</th><th>Статус</th><th></th></tr>%s</table>
<h2>Очередь</h2>%s
<h2>События</h2><div class="s">%s</div>
</body></html>""" % (CSS, len(lib), cache_gb, max_gb, free, len(lst),
                    blocks or "<div class='s'>пока пусто</div>", rows, queue_rows, log_rows)


# ---------------------------------------------------------------------- Сервер

class Handler(BaseHTTPRequestHandler):
    api = None
    api_base = None          # внутренний адрес API (127.0.0.1)
    public_base = None       # внешний адрес для стрима (192.168.x.x:8090)
    cache_dir = None
    max_gb = 100
    preload_seconds = 1800

    def log_message(self, *a):
        pass

    def _send(self, code, body, ctype="text/html; charset=utf-8", headers=None):
        data = body.encode() if isinstance(body, str) else body
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(data)

    def _json(self, obj):
        self._send(200, json.dumps(obj, ensure_ascii=False), "application/json; charset=utf-8")

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        q = urllib.parse.parse_qs(parsed.query)

        if path in ("/status", "/api/status"):
            lst = torr_list(self.api)
            du = (shutil.disk_usage(self.cache_dir)
                  if self.cache_dir and os.path.isdir(self.cache_dir) else None)
            with LOCK:
                qq = {t: {k: v for k, v in x.items() if k != "magnets"} for t, x in QUEUE.items()}
            self._json({
                "torrents": lst,
                "queue": qq,
                "keep": sorted(KEEP),
                "preloads": sorted(PRELOADS.keys()),
                "disk": {
                    "used_gb": gb(du.used) if du else "?",
                    "free_gb": gb(du.free) if du else "?",
                    "cache_gb": gb(dir_size(self.cache_dir))
                    if self.cache_dir and os.path.isdir(self.cache_dir) else "?",
                },
                "log": LOG[-40:],
            })
            return

        if path in ("/api/library", "/library"):
            self._json({"titles": library(self.api)})
            return

        if path == "/api/stream":
            h = (q.get("hash") or [""])[0]
            try:
                idx = int((q.get("index") or ["0"])[0])
            except ValueError:
                return self._send(400, "bad index", "text/plain")
            if not h:
                return self._send(400, "no hash", "text/plain")
            t = next((x for x in torr_list(self.api) if x.get("hash") == h), None)
            if not t:
                return self._send(404, "раздача не найдена", "text/plain")
            f = next((x for x in (t.get("file_stats") or []) if int(x.get("id", 0)) == idx), None)
            if not f:
                return self._send(404, "файл не найден", "text/plain")
            name = f.get("path", "").split("/")[-1]
            # 302: ТВ идёт прямо в TorrServe (Range работает без прослойки)
            self.send_response(302)
            self.send_header("Location", stream_url(self.public_base, h, idx, name))
            self.send_header("Content-Length", "0")
            self.end_headers()
            return

        if path == "/":
            self._send(200, page(self.api, self.cache_dir, self.max_gb))
            return

        if path == "/del":
            h = (q.get("hash") or [""])[0]
            if h:
                torr(self.api, {"action": "rem", "hash": h})
                with LOCK:
                    ADDED.pop(h.lower(), None)
            self._send(200, "ok", "text/plain")
            return

        self._send(404, "нет", "text/plain")

    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        try:
            body = json.loads(self.rfile.read(n).decode() or "{}")
        except Exception:
            return self._send(400, "bad json", "text/plain")

        path = urllib.parse.urlparse(self.path).path

        if path in ("/queue/add", "/api/add"):
            title = body.get("title") or "без названия"
            magnets = [m for m in body.get("magnets", []) if m]
            if not magnets:
                return self._send(400, "нет магнитов", "text/plain")
            with LOCK:
                QUEUE[title] = {"magnets": magnets, "added_at": time.time(),
                                "status": "ждёт", "detail": ""}
            log("очередь: + «%s» (%d раздач)" % (title, len(magnets)))
            return self._send(200, "ok", "text/plain")

        if path == "/keep":
            with LOCK:
                KEEP.clear()
                KEEP.update(h.lower() for h in body.get("hashes", []))
            return self._send(200, "keep=%d" % len(KEEP), "text/plain")

        if path == "/api/preload":
            h = body.get("hash")
            try:
                idx = int(body.get("index"))
            except (TypeError, ValueError):
                return self._send(400, "bad index", "text/plain")
            if not h:
                return self._send(400, "no hash", "text/plain")
            t = next((x for x in torr_list(self.api) if x.get("hash") == h), None)
            if not t:
                return self._send(404, "раздача не найдена", "text/plain")
            f = next((x for x in (t.get("file_stats") or []) if int(x.get("id", 0)) == idx), None)
            if not f:
                return self._send(404, "файл не найден", "text/plain")
            key = "%s:%d" % (h, idx)
            with LOCK:
                running = key in PRELOADS
                if not running:
                    PRELOADS[key] = time.time()
            if running:
                return self._send(200, "уже качается", "text/plain")
            name = f.get("path", "").split("/")[-1]
            threading.Thread(
                target=preload_worker,
                args=(self.api_base, h, idx, name, self.preload_seconds),
                daemon=True,
            ).start()
            return self._send(200, "started", "text/plain")

        if path == "/api/rem":
            h = body.get("hash")
            if h:
                torr(self.api, {"action": "rem", "hash": h})
                with LOCK:
                    ADDED.pop(h.lower(), None)
            return self._send(200, "ok", "text/plain")

        self._send(404, "нет", "text/plain")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--torr", default="http://127.0.0.1:8090")
    ap.add_argument("--cache", default=os.path.expanduser("~/torrserver/cache"))
    ap.add_argument("--max-gb", type=int, default=30)
    ap.add_argument("--port", type=int, default=8092)
    ap.add_argument("--preload-min", type=int, default=30, help="сколько минут держать докачку")
    ap.add_argument("--public", default="", help="внешний адрес TorrServe для стрима (по умолчанию http://<IP>:8090)")
    a = ap.parse_args()

    Handler.api = a.torr.rstrip("/")
    Handler.api_base = a.torr.rstrip("/")
    # Стрим отдаём через редирект на ВНЕШНИЙ адрес: 127.0.0.1 на телевизоре
    # указывает на сам телевизор и не откроется
    Handler.public_base = (a.public or ("http://%s:8090" % lan_ip())).rstrip("/")
    Handler.cache_dir = os.path.expanduser(a.cache)
    Handler.max_gb = a.max_gb
    Handler.preload_seconds = a.preload_min * 60

    threading.Thread(target=queue_worker, args=(Handler.api, Handler.cache_dir), daemon=True).start()
    threading.Thread(target=cleanup_loop, args=(Handler.api, Handler.cache_dir, a.max_gb), daemon=True).start()

    log("КиноСервер 2.0: панель http://0.0.0.0:%d/, TorrServe %s (стрим через %s), кеш %s, лимит %d ГБ"
        % (a.port, a.torr, Handler.public_base, Handler.cache_dir, a.max_gb))
    ThreadingHTTPServer(("0.0.0.0", a.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
