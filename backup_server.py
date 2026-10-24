#!/usr/bin/env python3
"""
ВНосКино: серверный бэкап настроек (домашний Mac).
GET  /health  — проверка
GET  /backup  — отдать сохранённый JSON
PUT  /backup  — сохранить JSON (тело запроса)
Авторизация: заголовок X-Backup-Token (значение — в ~/vnoskino-backup/token).
"""
import http.server
import json
import os
import socketserver

BASE = os.path.expanduser("~/vnoskino-backup")
FILE = os.path.join(BASE, "settings.json")
TOKEN_FILE = os.path.join(BASE, "token")
PORT = 8091

os.makedirs(BASE, exist_ok=True)
if not os.path.exists(TOKEN_FILE):
    with open(TOKEN_FILE, "w") as f:
        f.write(os.urandom(16).hex())
    os.chmod(TOKEN_FILE, 0o600)
TOKEN = open(TOKEN_FILE).read().strip()


class Handler(http.server.BaseHTTPRequestHandler):
    def _send(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _auth_ok(self):
        return self.headers.get("X-Backup-Token", "") == TOKEN

    def do_GET(self):
        if self.path == "/health":
            return self._send(200, {"ok": True, "file": os.path.exists(FILE)})
        if not self._auth_ok():
            return self._send(403, {"error": "bad token"})
        if self.path == "/backup":
            if not os.path.exists(FILE):
                return self._send(404, {"error": "empty"})
            body = open(FILE, "rb").read()
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self._send(404, {"error": "not found"})

    def do_PUT(self):
        if not self._auth_ok():
            return self._send(403, {"error": "bad token"})
        if self.path != "/backup":
            return self._send(404, {"error": "not found"})
        try:
            n = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            return self._send(400, {"error": "bad length"})
        data = self.rfile.read(n)
        try:
            json.loads(data.decode("utf-8"))
        except Exception:
            return self._send(400, {"error": "bad json"})
        tmp = FILE + ".tmp"
        with open(tmp, "wb") as f:
            f.write(data)
        os.replace(tmp, FILE)
        self._send(200, {"ok": True, "size": len(data)})

    def do_POST(self):
        self.do_PUT()

    def log_message(self, *args):
        pass


socketserver.TCPServer.allow_reuse_address = True
with socketserver.ThreadingTCPServer(("0.0.0.0", PORT), Handler) as srv:
    srv.serve_forever()
