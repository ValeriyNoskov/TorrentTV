#!/bin/bash
# Чтение логов ВНосКино из приватного репозитория ChipN/tv-logs (GitVerse).
# Приложение на ТВ кладёт логи в logs/*.txt через Contents API.
#
#   tools/tvlogs.sh list          — последние 15 логов
#   tools/tvlogs.sh get <имя>     — показать лог (или его хвост)
#   tools/tvlogs.sh tail <имя>    — последние 50 строк
# Имя можно копировать из списка (например 20261030-204513.log.txt)

set -euo pipefail
API="https://api.gitverse.ru/repos/ChipN/tv-logs/contents/${SUB:-logs}"
H1="Accept: application/vnd.gitverse.object+json;version=1"
# Токен: из git remote gitverse ( ChipN:<token>@... )
TOKEN=$(cd "$(dirname "$0")/.." && git remote get-url gitverse | sed -E 's|https://[^:]+:([^@]+)@.*|\1|')

cmd="${1:-list}"

case "$cmd" in
  list)
    curl -sf -m 30 -H "Authorization: Bearer $TOKEN" -H "$H1" "$API" \
      | python3 -c "
import json, sys
try:
    items = json.load(sys.stdin)
except Exception:
    print('логов пока нет (каталог logs пуст)'); sys.exit(0)
items.sort(key=lambda x: x.get('name',''))
if not items:
    print('логов пока нет (каталог logs пуст)')
for it in items[-15:]:
    print(it['name'], f\"({it.get('size',0)} байт)\")
"
    ;;
  get|tail)
    name="${2:?укажи имя файла: tools/tvlogs.sh get <имя>}"
    curl -sf -m 60 -H "Authorization: Bearer $TOKEN" -H "$H1" "$API/$name" \
      | python3 -c "
import json, sys, base64
d = json.load(sys.stdin)
text = base64.b64decode(d['content']).decode('utf-8', 'replace')
lines = text.splitlines()
if sys.argv[1] == 'tail':
    lines = lines[-50:]
print('\n'.join(lines))
print(f'--- всего строк: {len(text.splitlines())} ---', file=sys.stderr)
" "$cmd"
    ;;
  *)
    echo "Использование: tools/tvlogs.sh list | get <имя> | tail <имя>"
    exit 1
    ;;
esac
