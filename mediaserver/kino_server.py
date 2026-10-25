#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
КиноСервер — надзиратель над TorrServe на домашнем Mac.

Зачем: TorrServe качает и стримит отлично, но не умеет очередь, место
на диске и автоочистку. Этот сервис добавляет поверх его API:

  /            — веб-панель: раздачи, прогресс, место, кнопки
  /queue/add   — POST {"title": "...", "magnets": ["magnet:...", ...]}
                 поставить раздачу в очередь (ВНосКино и телефон)
  /keep        — POST {"hashes": [...]} — список «не трогать» (избранное ТВ)
  /status      — GET — JSON-сводка (место, очередь, раздачи)

Автоочистка: если кеш-папка TorrServe заняла больше MAX_GB — самые старые
раздачи (не из keep) удаляются через TorrServe rem.

Только стандартная библиотека — ничего устанавливать не нужно.
Запуск:  python3 kino_server.py --torr http://127.0.0.1:8090 \
             --cache ~/Library/Caches/TorrServe --max-gb 100 --port 8091
"""

import argparse
import json
import os
import shutil
import threading
import time
import urllib.request
from collections import OrderedDict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOCK = threading.Lock()

# Очередь задач: OrderedDict title -> {magnets, added_at, status, detail}
QUEUE = OrderedDict()
# Хэши «не удалять» (избранное с ТВ)
KEEP = set()
# Журнал добавления раздач: hash -> timestamp (для LRU-очистки)
ADDED = {}
# Последние события для панели
LOG = []


def log(msg):
    stamp = time.strftime("%H:%M:%S")
    LOG.append(f"[{stamp}] {msg}")
    del LOG[:-200]
    print(f"[{stamp}] {msg}")


def torr(api, body):
    """POST в TorrServe /torrents, вернуть JSON или None."""
    # api может быть базой (http://127.0.0.1:8090) или полным путём:
    # без "/torrents" TorrServe отвечает 404 на любой action
    url = api if api.rstrip("/").endswith("/torrents") else api.rstrip("/") + "/torrents"
    try:
        req = urllib.request.Request(
            url, data=json.dumps(body).encode(),
            headers={"Content-Type": "application/json"},
        )
        with urllib.request.urlopen(req, timeout=8) as r:
            raw = r.read().decode("utf-8", "replace")
        return json.loads(raw) if raw.strip() else {}
    except Exception as e:
        log(f"torrserve {body.get('action')}: {e}")
        return None


def torr_list(api):
    r = torr(api, {"action": "list"})
    if isinstance(r, list):
        return r
    return r or []


def hash_of(magnet):
    import re
    m = re.search(r"urn:btih:([0-9a-fA-F]{40})", magnet or "")
    return m.group(1).lower() if m else None


def queue_worker(api, cache_dir):
    """Последовательная закачка очереди: add магнита и ожидание готовности."""
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
        log(f"очередь: качаю «{title}» ({len(t['magnets'])} раздач)")
        for m in t["magnets"]:
            h = hash_of(m)
            if h:
                with LOCK:
                    ADDED[h] = time.time()
            torr(api, {"action": "add", "link": m, "title": title,
                       "save_to_cache": True})
        # Ждём, пока все раздачи задачи докачаются (или застынут)
        while True:
            time.sleep(10)
            lst = torr_list(api)
            active = [x for x in lst
                      if x.get("title") == title and int(x.get("stat", 0)) in (1, 2)]
            if not active:
                break
        t["status"] = "готово"
        log(f"очередь: «{title}» готово")


def cleanup_loop(api, cache_dir, max_gb):
    """Раз в 5 мин: занято больше лимита — сносим старейшие не-keep раздачи."""
    while True:
        time.sleep(300)
        if not cache_dir or not os.path.isdir(cache_dir):
            continue
        try:
            used = dir_size(cache_dir)
        except Exception as e:
            log(f"очистка: не посчитать кеш — {e}")
            continue
        free = shutil.disk_usage(cache_dir).free
        log(f"очистка: кеш {used / 2**30:.1f} ГБ, свободно {free / 2**30:.1f} ГБ (лимит {max_gb} ГБ)")
        if used <= max_gb * 2**30 or free > 20 * 2**30:
            continue
        with LOCK:
            removable = sorted(
                ((ts, h) for h, ts in ADDED.items() if h not in KEEP),
                reverse=False,
            )
        for _, h in removable:
            if used <= max_gb * 2**30 and free > 20 * 2**30:
                break
            log(f"очистка: сношу раздачу {h[:8]}… (старая, не в избранном)")
            torr(api, {"action": "rem", "hash": h})
            with LOCK:
                ADDED.pop(h, None)
            used = max(0, used - 1)  # пересчитается на следующем круге
            break  # один за круг — размер пересчитаем по-честному


def dir_size(path):
    total = 0
    for root, _, files in os.walk(path):
        for f in files:
            try:
                total += os.path.getsize(os.path.join(root, f))
            except OSError:
                pass
    return total


def gb(n):
    return f"{n / 2**30:.1f}"


class Handler(BaseHTTPRequestHandler):
    api = None
    cache_dir = None
    max_gb = 100

    def log_message(self, *a):
        pass

    def _send(self, code, body, ctype="text/html; charset=utf-8"):
        data = body.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path.startswith("/status"):
            lst = torr_list(self.api)
            du = (shutil.disk_usage(self.cache_dir)
                  if self.cache_dir and os.path.isdir(self.cache_dir) else None)
            with LOCK:
                q = {t: {k: v for k, v in x.items() if k != "magnets"}
                     for t, x in QUEUE.items()}
            self._send(200, json.dumps({
                "torrents": lst,
                "queue": q,
                "keep": sorted(KEEP),
                "disk": {"used_gb": gb(du.used), "free_gb": gb(du.free),
                         "cache_gb": gb(dir_size(self.cache_dir))
                         if self.cache_dir and os.path.isdir(self.cache_dir) else "?"},
                "log": LOG[-30:],
            }, ensure_ascii=False), "application/json; charset=utf-8")
            return

        if self.path == "/":
            lst = torr_list(self.api)
            rows = ""
            for t in lst:
                pb = int(t.get("preloaded_bytes", 0))
                rows += (
                    f"<tr><td>{t.get('title', '')[:60]}</td>"
                    f"<td>{pb // 2**20} МБ</td><td>{t.get('total_peers', 0)} пиров</td>"
                    f"<td><a href='/del?hash={t.get('hash')}'>✕ удалить</a></td></tr>"
                )
            free = (shutil.disk_usage(self.cache_dir).free
                    if self.cache_dir and os.path.isdir(self.cache_dir) else 0)
            html = f"""<html><head><meta charset=utf-8>
<title>КиноСервер</title><meta name=viewport content='width=device-width,initial-scale=1'>
<style>body{{font-family:-apple-system,sans-serif;background:#0e1116;color:#eee;
padding:20px}}table{{border-collapse:collapse;width:100%}}
td,th{{padding:8px;border-bottom:1px solid #223}}a{{color:#7fd}}h1{{font-size:22px}}</style></head>
<body><h1>🍿 КиноСервер</h1>
<p>Кеш: лимит {self.max_gb} ГБ · свободно на диске: <b>{gb(free)} ГБ</b></p>
<table><tr><th>Раздача</th><th>Скачано</th><th>Пиры</th><th></th></tr>{rows}</table>
<p>{'<br>'.join(LOG[-12:])}</p></body></html>"""
            self._send(200, html)
            return

        if self.path.startswith("/del?"):
            h = self.path.split("hash=")[-1]
            torr(self.api, {"action": "rem", "hash": h})
            with LOCK:
                ADDED.pop(h.lower(), None)
            self._send(200, "ok", "text/plain")
            return

        self._send(404, "нет", "text/plain")

    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        try:
            body = json.loads(self.rfile.read(n).decode())
        except Exception:
            return self._send(400, "bad json", "text/plain")

        if self.path == "/queue/add":
            title = body.get("title", "без названия")
            magnets = [m for m in body.get("magnets", []) if m]
            if not magnets:
                return self._send(400, "нет магнитов", "text/plain")
            with LOCK:
                QUEUE[title] = {"magnets": magnets, "added_at": time.time(),
                                "status": "ждёт", "detail": ""}
            log(f"очередь: + «{title}» ({len(magnets)} раздач)")
            return self._send(200, "ok", "text/plain")

        if self.path == "/keep":
            with LOCK:
                KEEP.clear()
                KEEP.update(h.lower() for h in body.get("hashes", []))
            return self._send(200, f"keep={len(KEEP)}", "text/plain")

        self._send(404, "нет", "text/plain")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--torr", default="http://127.0.0.1:8090")
    ap.add_argument("--cache", default=os.path.expanduser("~/Library/Caches/TorrServe"))
    ap.add_argument("--max-gb", type=int, default=100)
    ap.add_argument("--port", type=int, default=8091)
    a = ap.parse_args()

    Handler.api = a.torr.rstrip("/")
    Handler.cache_dir = os.path.expanduser(a.cache)
    Handler.max_gb = a.max_gb

    threading.Thread(target=queue_worker, args=(Handler.api, Handler.cache_dir),
                     daemon=True).start()
    threading.Thread(target=cleanup_loop,
                     args=(Handler.api, Handler.cache_dir, a.max_gb),
                     daemon=True).start()

    log(f"КиноСервер запущен: панель http://0.0.0.0:{a.port}/, TorrServe {a.torr}")
    ThreadingHTTPServer(("0.0.0.0", a.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
