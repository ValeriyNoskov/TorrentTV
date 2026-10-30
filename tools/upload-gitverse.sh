#!/bin/bash
# Заливка APK релиза на GitVerse локально (не из CI).
#
# Почему так: CI-раннеры GitHub (США) до GitVerse (Cloud.ru, РФ) идут по
# нестабильному внешнему маршруту — 85 МБ часто обрываются, релиз остаётся
# без APK. Отсюда (РФ) до GitVerse канал хороший: 85 МБ ≈ минута.
#
# Использование: tools/upload-gitverse.sh 0.9.62
# Шаги: скачать APK с GitHub (с докачкой — скорость GitHub из РФ плавает
# волнами) → загрузить в GitVerse-релиз → проверить ссылку (HTTP 206).
set -uo pipefail
VER="${1:?укажи версию, например: tools/upload-gitverse.sh 0.9.62}"
REPO_GH="ValeriyNoskov/TorrentTV"
REPO_GV="ChipN/TorrentTV"
FILE="/tmp/VNosKino-${VER}.apk"
URL="https://github.com/${REPO_GH}/releases/download/v${VER}/VNosKino-${VER}.apk"

# Токен GitVerse берём из git remote (в репо секретов нет)
TOKEN=$(git remote get-url gitverse | sed -E 's|https://[^:]+:([^@]+)@.*|\1|')
[ -n "$TOKEN" ] && [ "$TOKEN" != "gitverse" ] || { echo "FAIL: токен не найден в remote gitverse"; exit 1; }

echo "1/4 Скачиваю с GitHub v$VER (с докачкой)…"
# Скорость GitHub из РФ плавает от 20 КБ/с до 3 МБ/с и обрывается —
# curl -C - накапливает: каждый ретрай продолжает с места обрыва
for i in $(seq 1 120); do
  curl -sL -C - -o "$FILE" --max-time 300 "$URL" && break
  echo "  обрыв, попытка $i, скачано: $(stat -f%z "$FILE" 2>/dev/null || echo 0) байт"
  sleep 3
done
SIZE=$(stat -f%z "$FILE" 2>/dev/null || echo 0)
EXPECT=$(curl -s "https://api.github.com/repos/${REPO_GH}/releases/tags/v${VER}" \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['assets'][0]['size'])")
[ "$SIZE" = "$EXPECT" ] || { echo "FAIL: размер $SIZE != ожидаемого $EXPECT"; exit 1; }
# Целостность: APK — ZIP (сигнатура PK)
[ "$(head -c 2 "$FILE")" = "PK" ] || { echo "FAIL: нет ZIP-сигнатуры"; exit 1; }
echo "  скачано $SIZE байт, ZIP-сигнатура ок"

echo "2/4 Ищу релиз v$VER на GitVerse…"
REL_ID=$(curl -s -m 20 -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/vnd.gitverse.object+json;version=1" \
  "https://api.gitverse.ru/repos/${REPO_GV}/releases" \
  | python3 -c "
import json,sys
for r in json.load(sys.stdin):
    if r.get('tag_name') == 'v${VER}':
        print(r['id']); break
")
[ -n "$REL_ID" ] || { echo "FAIL: релиз v$VER на GitVerse не найден (CI должен создать тег/релиз при пуше)"; exit 1; }
echo "  release id=$REL_ID"

echo "3/4 Заливаю ассет…"
RESP=$(curl -s -m 900 -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/vnd.gitverse.object+json;version=1" \
  "https://api.gitverse.ru/repos/${REPO_GV}/releases/${REL_ID}/assets?name=VNosKino-${VER}.apk" \
  -F "attachment=@$FILE" -w "\n%{http_code}")
CODE=$(echo "$RESP" | tail -1)
if [ "$CODE" = "201" ]; then
  UUID=$(echo "$RESP" | head -n -1 | python3 -c "import json,sys; print(json.load(sys.stdin)['uuid'])")
else
  # Уже есть (повторный запуск) — достаём uuid существующего ассета
  UUID=$(curl -s -m 20 -H "Authorization: Bearer $TOKEN" \
    -H "Accept: application/vnd.gitverse.object+json;version=1" \
    "https://api.gitverse.ru/repos/${REPO_GV}/releases" \
    | python3 -c "
import json,sys
for r in json.load(sys.stdin):
    if r.get('tag_name') == 'v${VER}':
        for a in (r.get('assets') or []):
            if a['name'].endswith('.apk'): print(a['uuid']); break
        break
")
  [ -n "$UUID" ] || { echo "FAIL: заливка HTTP $CODE"; exit 1; }
  echo "  ассет уже был (HTTP $CODE), использую существующий"
fi
DL="https://gitverse.ru/api/attachments/$UUID"

echo "4/4 Проверяю ссылку…"
CHK=$(curl -s -o /dev/null -m 30 -r 0-100000 -w "%{http_code}" "$DL")
[ "$CHK" = "206" ] || { echo "FAIL: ссылка не качается (HTTP $CHK)"; exit 1; }
echo "OK: $DL"
