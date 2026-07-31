# AI Unblock — описание проекта

## Назначение

AI Unblock — Android-приложение без root-доступа, которое использует локальный
`VpnService` для выборочной маршрутизации трафика приложений через Smart DNS и
GeoHide-шлюзы.

Приложение не расшифровывает TLS. Для HTTPS оно читает только имя сервера (SNI)
из `TLS ClientHello`, после чего передаёт исходный TLS-поток на выбранный
Smart DNS/GeoHide-адрес.

## Пользовательские приложения

В главном экране доступна кнопка **«Добавить приложения»**.

При открытии списка:

- отображаются установленные приложения с launcher-активностью;
- доступен поиск по названию и имени пакета;
- приложения можно добавлять и удалять флажком;
- выбор сохраняется в `SharedPreferences`;
- встроенные AI-приложения отображаются как поддерживаемые автоматически и не
  требуют ручного добавления;
- если VPN уже включён, изменения применяются после выключения и повторного
  включения.

Пользовательские пакеты объединяются со встроенным списком. Перед созданием VPN
интерфейса приложение проверяет, что выбранные пакеты установлены.

## Встроенные приложения

- `com.google.android.apps.bard`;
- `com.google.android.googlequicksearchbox`;
- `com.google.android.apps.labs.language.tailwind`;
- `com.openai.chatgpt`;
- `com.anthropic.claude`.

## Маршрутизация

### HTTPS и SNI

Для TCP/443 приложение извлекает SNI и выбирает маршрут:

- Google AI и Gemini — `GOOGLE_AI`;
- NotebookLM — `NOTEBOOK_LM`;
- ChatGPT/OpenAI — `CHATGPT`;
- Claude/Anthropic — `CLAUDE`;
- домены GeoHide из пользовательской базы — `GEOHIDE`;
- остальные домены — напрямую.

Шлюзы автоматически проверяются при включении и затем раз в 30 минут.
Последний успешный выбор сохраняется в настройках.

### Игры и нестандартные порты

Для точечных записей Smart DNS из hosts-файлов локальный DNS-обработчик
подменяет ответ `A` адресом нужного шлюза. Поэтому соединение приложения
получает Smart DNS-адрес даже тогда, когда оно использует TCP или UDP не на
порту 443.

IPv6-запрос `AAAA` для такой записи получает пустой успешный ответ, чтобы
приложение использовало IPv4-маршрут Smart DNS.

### Прямой трафик

- домены, отсутствующие в списках, идут напрямую;
- обычные DNS-запросы пересылаются через указанный DNS `1.1.1.1`;
- UDP/443 блокируется для исключения QUIC, который нельзя безопасно
  маршрутизировать по SNI;
- исходящие сокеты реле защищаются через `VpnService.protect()`.

## Списки доменов

В assets проекта хранятся:

- `app/src/main/assets/geohide_domains.txt` — 304 домена из
  `E:\Downloads\GeoHide-25-07-2026.txt`;
- `app/src/main/assets/geohide_hosts.txt` — 199 уникальных полезных записей из
  `E:\Downloads\hosts` и `E:\Downloads\hosts (1).txt`.

Из hosts-файлов исключены записи с адресами `0.0.0.0` и `127.0.0.1`, поскольку
они являются блокирующими или локальными, а не маршрутами Smart DNS.

## Архитектура исходников

- `MainActivity.kt` — Compose-интерфейс, включение VPN, список приложений и
  настройки;
- `AppSelection.kt` — поиск launcher-приложений и сохранение пользовательского
  списка;
- `AiUnblockVpnService.kt` — создание VPN-интерфейса и запуск native TUN-движка;
- `LocalSocksRelay.kt` — локальный SOCKS5-релей, TLS/SNI-маршрутизация и UDP;
- `DnsOverride.kt` — подмена DNS-ответов для точечных Smart DNS-записей;
- `RoutingRules.kt` — встроенные правила Google AI, NotebookLM, ChatGPT и Claude;
- `GeoHideRoutingRules.kt` — загрузка и сопоставление GeoHide/hosts-правил;
- `GatewaySelector.kt` — проверка и автоматический выбор рабочих шлюзов;
- `TlsClientHello.kt` — безопасный разбор SNI без расшифровки TLS;
- `TunnelState.kt` — состояние VPN и отображение статуса в интерфейсе.

## Технологии и требования

- Kotlin 2.3.10;
- Jetpack Compose 1.9.0 и Material 3 1.4.0;
- Android Gradle Plugin 9.1.1;
- Gradle 9.3.1;
- Java 17 или новее;
- `compileSdk 36`;
- `targetSdk 36`;
- `minSdk 26`;
- native `hev-socks5-tunnel`;
- ABI: `arm64-v8a`, `armeabi-v7a`.

## Сборка

Из корня проекта:

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-configuration-cache
.\gradlew.bat :app:assembleRelease --no-configuration-cache
```

Имена APK:

- debug:
  `ru.ecubz.aiunblock-7-0.1.6-debug.apk`;
- release:
  `ru.ecubz.aiunblock-7-0.1.6-release.apk`;
- промежуточный неподписанный файл:
  `ru.ecubz.aiunblock-7-0.1.6-release-unsigned.apk`.

## Ключ подписи релиза

Закрытый ключ и его пароли не хранятся в публичном репозитории. Локальные
параметры подписи записаны в игнорируемом файле `SIGNING.local.md`.
Подписанный APK создаётся после release сборки отдельным шагом `apksigner`.

Подпись проверена через Android Build Tools 37.0.0:

- APK Signature Scheme v2: включена;
- APK Signature Scheme v3: включена;
- сертификат: RSA 2048;
- SHA-256 сертификата:
  `b99c5d90f7ac290c69f817ffc032f3824ae5583b72b92760d1b9277215617036`.

## Проверка

Перед передачей сборки выполняются:

- unit-тесты маршрутизации, разбора TLS ClientHello и DNS-подмены;
- Android Lint;
- debug-сборка;
- release-сборка;
- установка debug APK на подключённое Android-устройство при наличии телефона.

## Автоматическая публикация APK на GitHub

Workflow `.github/workflows/release-apk.yml` запускается:

- автоматически после каждого `push` в ветку `main`;
- вручную через вкладку **Actions** → **Публикация подписанного APK** →
  **Run workflow**.

Workflow выполняет unit-тесты, release-сборку, подпись APK и создаёт GitHub
Release с тегом вида `v0.1.6-build-номер`.

В настройках репозитория должны быть созданы Secrets:

- `RELEASE_KEYSTORE_BASE64` — JKS в формате Base64;
- `RELEASE_KEYSTORE_PASSWORD` — пароль хранилища;
- `RELEASE_KEY_ALIAS` — алиас ключа;
- `RELEASE_KEY_PASSWORD` — пароль ключа.

Секреты не попадают в коммиты и не выводятся в логи Actions.

## Ограничения

- Android допускает только один активный VPN;
- пользователь должен подтвердить системное разрешение VPN;
- во время работы показывается foreground-уведомление;
- полностью проверить работу Smart DNS и игровых протоколов можно только на
  физическом устройстве и в разных сетях;
- приложения, использующие собственный DoT/DoH или жёстко зашитые IP-адреса,
  могут обходить локальный DNS-обработчик.
