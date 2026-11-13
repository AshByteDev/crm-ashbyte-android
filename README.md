# AshCRM для Android

Приложение `ru.ashcrm.app` для сотрудников AshCRM: та же PWA внутри WebView плюс то, чего PWA на Android не умеет —
**надёжные уведомления** (свой канал FCM вместо пуша браузера), просьба не ограничивать работу батареей, автозапуск у
Xiaomi/Huawei/Samsung и др., каналы уведомлений, «Прочитано» прямо из уведомления, обновление APK из CRM.

Батарею приложение не тратит: в фоне оно ничего не делает, его будит только пришедший push (общее соединение
сервисов Google). Постоянного соединения нет.

* Договорённость с CRM — [`docs/ANDROID_CONTRACT.md`](docs/ANDROID_CONTRACT.md) (версия **1.7**). Главный экземпляр —
  `crm-ashbyte/docs/contracts/ANDROID_CONTRACT.md` (там же все контракты CRM); здесь — его точная копия.
* Что доработать в CRM — задание хранится вместе с CRM (закрытый репозиторий).
* Firebase — [`docs/FIREBASE.md`](docs/FIREBASE.md).

## Как устроено

| Часть | Где |
|---|---|
| Адрес портала, WebView, файлы, микрофон, скачивания, мост со страницей | `web/CrmWeb.kt`, `MainActivity.kt` |
| Подключение устройства (код со страницы `desktop-connect` → ключи), ключ в Android Keystore | `net/DeviceAuth.kt`, `data/SecureBox.kt` |
| Регистрация `fcm:<токен>` + ключи шифрования в CRM | `push/Register.kt`, `push/PushKeys.kt` |
| Приём push: расшифровка RFC 8291 → уведомление / снятие | `push/PushService.kt`, `push/WebPushCrypto.kt`, `push/Notifier.kt` |
| «Прочитано» | `push/ActionReceiver.kt` |
| Первый запуск и настройки (разрешения, батарея, автозапуск, проверка) | `ui/Screens.kt`, `ui/Oem.kt` |
| Версия и обновление APK (релизы GitVerse) | `update/Updater.kt` |
| Вход по QR: сканер и разбор кода | `ui/Scanner.kt`, `data/QrLogin.kt` |

Стек: Kotlin 2.4, Jetpack Compose (BOM 2026.09), AGP 9.5, Gradle 9.9, JDK 25 (из Android Studio), `compileSdk`/
`targetSdk` 37, `minSdk` 26 (Android 8), Firebase BoM 35, OkHttp 5, kotlinx.serialization, CameraX + ZXing (сканер QR, без Google Play Services). Без аналитики.

## Сборка

Нужны Android Studio (её JDK) и SDK в `~/Android/Sdk` — Makefile их находит сам.

```bash
make test      # юнит-тесты
make debug     # app/build/outputs/apk/debug/app-debug.apk (пакет ru.ashcrm.app.debug, ставится рядом с боевым)
make install   # поставить debug на подключённый телефон/эмулятор
make dist      # тесты + lint + подписанный APK → dist/ashcrm.apk и dist/android.json
make publish   # то же + релиз на GitVerse и GitHub (см. «Выпуск версии»)
```

## Ключ подписи — сохранить!

`~/.android-keys/ashcrm-release.jks` (RSA 4096, до 2126 г.), пароль — в `keystore.properties` в корне проекта
(в git не попадают). **Скопируйте оба в менеджер паролей и в резервную копию вне этого компьютера.** Потеряете ключ —
установленные приложения больше не обновить: каждому сотруднику придётся удалить приложение и поставить заново.

Отпечаток сертификата (SHA-256): `EF:66:1C:CF:BE:58:D2:B0:28:EF:21:58:D5:2A:E4:19:09:BC:E5:88:0A:D1:13:75:B1:28:D2:08:67:F6:12:10`.

## Выпуск версии

Версии раздаются релизами на **GitVerse** — https://gitverse.ru/AshByte/crm-ashbyte-android (с 0.5.0). API GitVerse
закрыт даже для чтения открытого репозитория, поэтому приложение при запуске читает файл ветки `main`
`release/android.json` (`gitverse.ru/api/repos/…/raw/branch/main/…`), а он указывает на `ashcrm.apk` релиза
(`gitverse.ru/api/attachments/<uuid>`). На частые запросы GitVerse отвечает 429 — приложение молча ждёт и повторяет.

Пока телефоны с 0.4.x не обновились, каждый выпуск **дублируется на GitHub** (релиз с `ashcrm.apk` и `android.json`):
версии до 0.4.x проверяют только его. 0.5.0 — мост, обязательный для всех (`min_version_code` 8). Через несколько
версий дубль на GitHub отключается. Сборка и подпись — только на этом компьютере; токен GitVerse —
`~/.android-keys/gitverse.token` (права «Репозитории: чтение и запись»), в репозиторий не попадает.

1. `app/build.gradle.kts`: поднять `versionCode` (+1) и `versionName`. Менялся обмен с CRM — сначала
   контракт в `crm-ashbyte/docs/contracts/`, его копия в `docs/ANDROID_CONTRACT.md`, затем `CONTRACT`.
2. `release/notes.txt` — «Что нового», до 5 коротких строк (их видит сотрудник в предложении обновиться).
3. `release/min_version_code` — поднять до нового `versionCode`, если старые версии **обязаны** обновиться
   (несовместимое изменение); иначе не трогать.
4. Коммит и `git push`, затем `make publish`: тесты, lint, подписанная сборка, релиз `v<versionName>` на
   GitHub (`ashcrm.apk` + `android.json`) и на GitVerse (`ashcrm.apk`), затем коммит `release/android.json` со ссылкой
   на APK GitVerse и `git push` в оба репозитория. Телефоны предложат обновление при следующем запуске.

## Проверка на эмуляторе

```bash
make emulator                                  # Medium_Phone_API_37.0 без окна
~/Android/Sdk/platform-tools/adb reverse tcp:8780 tcp:8780
make install                                   # в приложении адрес: http://localhost:8780
```
* Именно `localhost`, а не `10.0.2.2`: страница по `http://10.0.2.2` — не «безопасный контекст», и PWA не
  работает (база в браузере, Service Worker). Для `https://` портала это не важно.
* Android 17 спрашивает «доступ к устройствам поблизости» для адресов локальной сети (`ACCESS_LOCAL_NETWORK`) —
  только если адрес портала локальный.
* Страница в debug-сборке видна в `chrome://inspect` на компьютере.

### Проверка без Firebase
Debug-сборка принимает зашифрованный push через `adb` и обрабатывает его так же, как сообщение FCM:
```bash
adb shell am broadcast -n ru.ashcrm.app.debug/ru.ashcrm.app.push.DebugPushReceiver -a ru.ashcrm.app.debug.KEYS
adb logcat -s AshPush            # → KEYS p256dh=… auth=…
# зашифровать как CRM (webpush-go, RecordSize 2700) — tools/webpush-encrypt, собрать где есть Go:
P=$(webpush-encrypt "<p256dh>" "<auth>" '{"type":"dm","title":"Мария","body":"Привет","url":"/team","tag":"chat-1"}')
adb shell am broadcast -n ru.ashcrm.app.debug/ru.ashcrm.app.push.DebugPushReceiver -a ru.ashcrm.app.debug.PUSH --es p "$P"
```

## Что дальше (не сделано)
* Запасной канал для телефонов без сервисов Google — WebSocket `notify.*` в foreground service, только по выбору
  пользователя (контракт 1.1).
* RuStore: сборка без изменений; RuStore Push — отдельным каналом в CRM.
