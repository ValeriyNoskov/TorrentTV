# Правила проекта TorrentTV (ВНосКино, Android TV)

## Публикация релиза — ОБЯЗАТЕЛЬНАЯ часть работы

«Закоммитить» в этом проекте всегда означает полный цикл, без напоминаний пользователя:

1. Поднять `versionCode` (+1) и `versionName` в `app/build.gradle.kts`.
2. Собрать: `./gradlew assembleRelease` (debug-сборка не публикуется).
3. Закоммитить: сообщение в формате `vX.Y.Z: краткий список изменений по-русски`.
4. `git tag vX.Y.Z && git push origin main vX.Y.Z`.
5. `gh release create vX.Y.Z <apk> --title "Release VNosKino vX.Y.Z"`:
   - APK переименовать в `VNosKino-X.Y.Z.apk`;
   - notes: `**Full Changelog**: https://github.com/ValeriyNoskov/TorrentTV/compare/vPREV...vX.Y.Z`.
6. Убедиться, что релиз помечен **Latest** — ТВ обновляется по воздуху с GitHub Releases.

## Проверка логов с ТВ

- `gh gist list` — логи устройства приходят как Gist «ВНосКино лог <дата>» (автовыгрузка
  при ошибке плеера, ручная — из настроек). Читать: `gh gist view <id> -f vnoskino_log.txt`.
- Приложение пишет события с префиксами: `[player]`, `[search]`, `[smart]`, `[netcheck]`,
  `[update]`, `[upload]`, `[app]`. Хвост файлового лога и последний краш — в конце Gist.
- ТВ работает не с локальным кодом, а с последним релизом: новые правки на устройстве
  не проверяются, пока не опубликован релиз.

## Конвенции кода

- Комментарии и строки UI — по-русски; причина изменения — в комментарии, а не только в diff.
- Запросы к базам фильмов (КП/TMDB) — только через общий кеш `KinopoiskClient.shared*`
  (свежий кеш без сети, фолбэк на устаревший кеш при сбое источника).
- Сетевые вызовы — в `Dispatchers.IO`, ответы кешируются (лимиты API не расходовать).
- `delay`/suspend — только в корутинах; внутри колбэков ExoPlayer — `CoroutineScope(...).launch`.
- `requestFocus()` на Compose `FocusRequester` — всегда в `runCatching` (ТВ-фокус бывает не готов).
- Временные/тестовые блоки UI помечать `🧪 ТЕСТ (временно)` и убирать в следующем релизе.

## Окружение

- macOS, shell zsh; Gradle wrapper: `./gradlew`.
- Ключи API (КП, TMDB) вшиты через gradle-свойства/CI-секреты — в репозиторий не класть.
- Сессии `ses_*.json`, keystore и пароли — в `.gitignore`, не трогать и не коммитить.
