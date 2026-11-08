# Промт: TraderBro — этап 4.0, «Анализ сигналов»: личный Telegram-аккаунт, каналы и история изменений сообщений

> Скопируйте текст ниже (начиная с раздела «РОЛЬ») целиком в AI-ассистента / кодового агента. Промт рассчитан на **доработку существующего проекта TraderBro**. Это первый этап нового раздела: сбор сообщений из выбранных Telegram-каналов личного аккаунта, сохранение текущего состояния и версий, отслеживание редактирований и удалений, UI со списком подписок и настройками мониторинга. Парсинг торговых сигналов, LLM и автоматические сделки в этот этап **не входят**.

## РОЛЬ

Ты — ведущий Java 25 / Spring Boot / Angular-разработчик с опытом Telegram Client API, TDLib, event-driven ingestion, PostgreSQL и построения аудируемых хранилищ сообщений.

Твоя задача — реализовать **этап 4.0 TraderBro**: новый прикладной раздел `com.traderbro.signalanalysis`, который:

1. авторизуется в Telegram через **личный аккаунт пользователя**, а не через Bot API;
2. получает каналы, на которые аккаунт подписан, включая архивированные;
3. позволяет в UI включать мониторинг только выбранных каналов;
4. сохраняет новые сообщения и первичную историю выбранных каналов в PostgreSQL;
5. фиксирует редактирования как новые версии без перезаписи истории;
6. фиксирует удаления и потерю доступности без физического удаления сохранённого содержимого;
7. показывает в новом UI-разделе «Анализ сигналов» список каналов, состояние подписки, мониторинга и синхронизации.

Пиши production-ready код: полная обработка ошибок, идемпотентность, транзакционность, защита секретов, ограничение нагрузки на Telegram, отсутствие влияния на торговый контур. Не выдумывай методы TDLib/TDLight: сверяй их с версией зависимости и сгенерированным `TdApi`; при неуверенности оставь точечный `// TODO: verify API` и предложи рабочую альтернативу.

## 1. КОНТЕКСТ: ЧТО УЖЕ ЕСТЬ — НЕ ЛОМАТЬ

- **TraderBro**, `groupId=com.traderbro`, artifact `traderbro`, root package `com.traderbro`, главный класс `com.traderbro.app.TraderBroApplication`.
- **Мономодульный Maven**, один `pom.xml`. `signalanalysis` — новый package-boundary внутри того же артефакта, не новый Maven-модуль.
- Java 25, Spring Boot, Spring Data JDBC, PostgreSQL 17 + TimescaleDB, **Liquibase YAML**, Lombok, JUnit 5, Mockito, Testcontainers, ArchUnit, Micrometer/Actuator.
- Для MOEX источник данных и исполнения — только T-Bank Invest API. Этот этап не добавляет другой источник рыночных котировок.
- Только `OrderManager` выставляет заявки и только после `RiskGate`. Новый раздел не должен вызывать торговый контур вообще.
- Бэктест изолирован от `BrokerGateway`, боевых `orders`/`positions`.
- Уже есть Telegram-бот уведомлений в `com.traderbro.notify` с bot token, шаблонами и read-only командами. **Не переиспользуй бота для чтения личного аккаунта и не смешивай две авторизации.**
- UI: Angular 20+, standalone components, signals, strict TypeScript, Taiga UI 4.x, OpenAPI-generated TypeScript client, русская локаль, тёмная тема. Существующие маршруты `/dashboard`, `/chart/:figi`, `/backtest`, `/login` сохранить.
- UI не управляет торговлей. В этом этапе UI может только читать данные Telegram-импорта, изменять флаг мониторинга канала и запрашивать синхронизацию списка каналов.
- Аудит 2026-10-26 предложил технические этапы 3.8/3.9. Не предполагай, что они уже выполнены, и не включай их исправления в объём 4.0. Этап 4.0 независим от Alligator, бэктестов и криптовалютного юниверса.

### 1.1. Почему TDLib, а не Bot API

Bot API не даёт читать произвольную историю и обновления всех каналов личного аккаунта. Поэтому используй **Telegram Client API через TDLib**.

Основная реализация: **TDLight Java** как Java-обёртка над TDLib с нативными зависимостями. На момент подготовки спецификации найден релиз `3.5.9+td.1.8.68`; перед реализацией проверь его доступность и актуальность, зафиксируй конкретную версию в `<properties>`, не используй `LATEST`/`RELEASE`. Для целевого Linux x86_64/OpenSSL 3 проверь classifier `linux_amd64_gnu_ssl3`; для других платформ документируй отдельный classifier.

Официальный TDLib JNI остаётся допустимой заменой только за адаптером `TelegramUserClient`; не смешивай две реализации. Python/Telethon/Pyrogram, scraping Telegram Web и неофициальные HTTP-прокси не добавляй.

### 1.2. Принцип этапа

Это **один проект и одно основное Spring Boot-приложение TraderBro**: тот же мономодульный Maven-проект, один `pom.xml`, один JAR, один Spring ApplicationContext и один основной процесс. REST, UI, торговая система, существующий notification-бот и новый Telegram ingestion запускаются вместе.

Разделение выполняется только на уровне пакетов и внутренних компонентов:

- `com.traderbro.signalanalysis` — прикладная граница нового раздела;
- отдельные bounded executor/scheduler для Telegram callbacks, inbox и history backfill;
- `signal-analysis.ingestion.enabled` — feature flag для включения/отключения сбора без изменения остального приложения;
- отдельный health contributor и метрики Telegram ingestion.

Не создавай второй микросервис, второй Spring Boot-проект, второй JAR или обязательный отдельный процесс. Сбой Telegram-авторизации и ошибки обработки сообщений переводят только ingestion в `ERROR`/`AUTH_REQUIRED`, не останавливая остальные функции приложения.

## 2. ЗАДАЧА 1: ПАКЕТНАЯ АРХИТЕКТУРА И ИЗОЛЯЦИЯ

Создай пакеты:

```text
com.traderbro.signalanalysis
├── api                  # REST DTO/controllers; без TDLib типов
├── domain               # канал, сообщение, версия, события, enum'ы
├── ingestion            # orchestration, inbox, history, discovery
├── repository           # Spring Data JDBC
├── service              # мониторинг, проекции, статусы
├── telegram             # порты TelegramUserClient и доменные snapshot/update типы
└── telegram.tdlib       # единственный пакет, зависящий от TDLight/TdApi
```

Основные компоненты:

- `TelegramUserClient` — внутренний порт, наружу не exposes `TdApi`;
- `TdLibTelegramUserClient` — реализация адаптера;
- `TelegramSessionManager` — lifecycle и состояние авторизации;
- `TelegramInteractiveLoginRunner` — одноразовый локальный вход;
- `TelegramChannelDiscoveryService` — подписки Main + Archive;
- `TelegramUpdateRouter` — маршрутизация updates;
- `TelegramIngestEventWriter` — durable inbox;
- `TelegramIngestEventProcessor` — идемпотентная обработка;
- `TelegramMessageSnapshotMapper` — преобразование содержимого;
- `TelegramMessageService` — current projection + append-only versions;
- `TelegramHistorySyncService` — backfill и tail reconcile;
- `TelegramMonitoringService` — флаги мониторинга и effective status;
- `TelegramChannelQueryService` — пагинированный UI-список без N+1;
- `SignalIngestionLifecycleManager` — запуск/остановка единственного TDLib client внутри TraderBro, heartbeat и восстановление;
- `SignalAnalysisHealthIndicator` — отдельный health contributor.

Классы ingestion, требующие native client, создаются только при `signal-analysis.ingestion.enabled=true`. REST и UI регистрируются в том же Spring ApplicationContext и остаются доступны при выключенном ingestion или состоянии `AUTH_REQUIRED`. Для callbacks, inbox processing и history sync создай выделенные ограниченные пулы потоков; не используй общий `ForkJoinPool.commonPool()` и не блокируй HTTP/торговые executor'ы.

**ArchUnit:** `signalanalysis` не должен зависеть от `execution`, `BrokerGateway`, `OrderManager`, `RiskGate`, боевых стратегий и таблиц `signals/orders/positions`. Типы `TdApi` не должны выходить за `signalanalysis.telegram.tdlib`.

## 3. ЗАДАЧА 2: АВТОРИЗАЦИЯ ЛИЧНОГО АККАУНТА И SESSION LIFECYCLE

### 3.1. Подготовка

Пользователь вручную создаёт приложение на `https://my.telegram.org` и задаёт:

```text
TELEGRAM_API_ID
TELEGRAM_API_HASH
TELEGRAM_TDLIB_DATABASE_ENCRYPTION_KEY
```

Это не bot token. Не добавляй их в frontend, OpenAPI, `sessionStorage`, БД приложения, логи или Actuator.

### 3.2. TDLib session

Настрой отдельные persistent directories:

```text
/var/lib/traderbro/telegram-user/tdlib-db
/var/lib/traderbro/telegram-user/files
```

Требования:

- включить локальные chat-info/message databases TDLib, если это поддерживается выбранной версией;
- задать database encryption key через env;
- права каталогов `0700`, владелец — пользователь контейнера;
- каталоги не коммитить, не публиковать через static resources и не копировать в образ;
- `use_secret_chats=false`: секретные чаты не обрабатываются;
- одна директория сессии = один активный TDLib client;
- graceful shutdown: прекратить history requests, дождаться завершения текущей DB-транзакции, закрыть client по lifecycle выбранной библиотеки.

### 3.3. Интерактивный первичный вход

Для первичной авторизации используй тот же проект и тот же JAR: добавь запускаемый вручную `TelegramInteractiveLoginRunner` с профилем `signal-auth` для локальной/Docker TTY-авторизации. Это лишь режим первичной настройки существующего приложения, не отдельный проект или постоянный второй процесс. После создания session основной TraderBro запускается обычным образом и сам управляет TDLib client. Обработай состояния TDLib:

- wait parameters;
- wait phone number;
- wait code;
- wait 2FA password;
- ready;
- logging out / closed / fatal error.

Телефон можно передать через env или ввести в консоли. Код подтверждения и 2FA password вводятся **только интерактивно**, не сохраняются, не передаются через REST и не пишутся в логи. После успешной авторизации ingestion в основном приложении должен запускаться без повторного ввода кода, пока сессия действительна.

Не реализуй регистрацию нового аккаунта, QR-вход, remote login через браузер, logout из UI и отправку сообщений. При невалидной/отозванной сессии ingestion останавливается в безопасное состояние `AUTH_REQUIRED`, а UI получает только санитизированный статус.

Не логируй `TdApi.Object.toString()`: там могут быть коды, содержимое сообщений и персональные данные. Логируй только тип update, технические IDs, код ошибки и санитизированное описание.

## 4. ЗАДАЧА 3: DISCOVERY КАНАЛОВ ЛИЧНОГО АККАУНТА

### 4.1. Загрузка подписок

Используй TDLib chat lists **Main и Archive**. Загружай чаты порциями через актуальный механизм `loadChats`/`getChats` выбранной версии. Учти, что `loadChats` возвращает данные через updates и может вернуть ошибку 404, когда больше чатов нет; это штатное завершение, а не failure.

Не считай синхронизацию завершённой сразу после последнего RPC: дождись обработки связанных `updateNewChat`, chat-list и supergroup updates либо примени явный watermark/quiet-period с тайм-аутом.

Фильтруй только broadcast channels:

- `chat.type = supergroup` и `isChannel=true`;
- обычные группы, discussion groups, личные чаты, боты и secret chats исключить;
- подписка определяется актуальным membership status, а не только наличием чата в списке;
- `member`, `creator`, `administrator`, а также restricted с фактическим membership считаются подпиской;
- `left`/`banned`/недоступный чат — не активная подписка;
- приватный канал может не иметь username — это нормально;
- один канал, найденный в Main и Archive, сохраняется один раз.

Обрабатывай как минимум:

- `updateNewChat`;
- добавление/удаление чата из chat list;
- `updateChatPosition` там, где это требуется для определения list membership;
- `updateSupergroup` и изменение собственного membership;
- `updateChatTitle` и другие релевантные изменения metadata.

Периодическая reconciliation синхронизация списка — каждые 15 минут и вручную из UI. Не удаляй канал из БД при отписке: сохрани историю, установи `is_subscribed=false` и состояние недоступности. Пользовательский `monitoring_enabled` не затирай; effective ingestion останавливается, пока подписка не восстановится.

### 4.2. Статусы канала

Введи:

```java
public enum SignalIngestionStatus {
    DISABLED,
    WAITING_AUTH,
    PENDING,
    BACKFILL,
    ACTIVE,
    DEGRADED,
    UNAVAILABLE,
    ERROR
}
```

Effective status вычисляется из `monitoring_enabled`, подписки, состояния авторизации Telegram и history state. Например, включённый мониторинг при отозванной сессии — `WAITING_AUTH`, при потере подписки — `UNAVAILABLE`.

## 5. ЗАДАЧА 4: МОДЕЛЬ ХРАНЕНИЯ POSTGRESQL

Создай Liquibase YAML changesets с новыми уникальными ID `YYYYMMDD-N`. Все timestamps — `TIMESTAMPTZ`, хранение в UTC. Telegram IDs — `BIGINT`; в JSON/TypeScript передавай их строками. Используй UUID для внутренних PK/FK, `JSONB` для структурированных entities/media/raw payload.

### 5.1. `telegram_channels`

Минимальные поля:

```text
id UUID PK
telegram_chat_id BIGINT NOT NULL UNIQUE
telegram_supergroup_id BIGINT NULL
title TEXT NOT NULL
username TEXT NULL
is_channel BOOLEAN NOT NULL DEFAULT TRUE
is_subscribed BOOLEAN NOT NULL
is_archived BOOLEAN NOT NULL DEFAULT FALSE
membership_status TEXT NOT NULL
monitoring_enabled BOOLEAN NOT NULL DEFAULT FALSE
monitoring_updated_at TIMESTAMPTZ NULL
first_seen_at TIMESTAMPTZ NOT NULL
last_seen_at TIMESTAMPTZ NOT NULL
subscription_synced_at TIMESTAMPTZ NULL
created_at / updated_at TIMESTAMPTZ NOT NULL
```

Добавь индексы для списка активных подписок и мониторируемых каналов. Username не является уникальным ключом и может меняться/отсутствовать.

### 5.2. `telegram_channel_sync_state`

Отдельно от metadata канала:

```text
channel_id UUID PK/FK
oldest_loaded_message_id BIGINT NULL
oldest_loaded_message_sent_at TIMESTAMPTZ NULL
newest_loaded_message_id BIGINT NULL
full_backfill_completed BOOLEAN NOT NULL DEFAULT FALSE
last_history_request_message_id BIGINT NULL
last_live_event_at TIMESTAMPTZ NULL
last_tail_sync_at TIMESTAMPTZ NULL
last_successful_sync_at TIMESTAMPTZ NULL
last_error_code TEXT NULL
last_error_message TEXT NULL       # только санитизированное описание
sync_attempts INTEGER NOT NULL DEFAULT 0
updated_at TIMESTAMPTZ NOT NULL
```

### 5.3. `telegram_messages` — текущая проекция

```text
id UUID PK
channel_id UUID FK NOT NULL
telegram_message_id BIGINT NOT NULL
sender_type TEXT NULL
sender_id BIGINT NULL
author_signature TEXT NULL
sent_at TIMESTAMPTZ NOT NULL
source_edited_at TIMESTAMPTZ NULL
reply_to_message_id BIGINT NULL
forward_metadata JSONB NULL
content_type TEXT NOT NULL
text_content TEXT NULL             # текст либо caption
formatted_entities JSONB NULL
media_metadata JSONB NULL          # без скачивания файлов
content_hash VARCHAR(64) NOT NULL
current_version_no INTEGER NOT NULL
availability_status TEXT NOT NULL  # ACTIVE / INACCESSIBLE / DELETED
first_observed_at TIMESTAMPTZ NOT NULL
last_observed_at TIMESTAMPTZ NOT NULL
deletion_observed_at TIMESTAMPTZ NULL
is_permanently_deleted BOOLEAN NOT NULL DEFAULT FALSE
created_at / updated_at TIMESTAMPTZ NOT NULL
UNIQUE(channel_id, telegram_message_id)
```

Добавь индексы `(channel_id, sent_at DESC)` и для удалённых сообщений. Содержимое удалённого сообщения не стирается. Физическое удаление сообщения/версий в этом этапе запрещено.

### 5.4. `telegram_message_versions` — append-only история

```text
id UUID PK
message_id UUID FK NOT NULL
version_no INTEGER NOT NULL
change_type TEXT NOT NULL          # LIVE_NEW / BACKFILL / EDIT / SYNC_CORRECTION
source_edited_at TIMESTAMPTZ NULL
observed_at TIMESTAMPTZ NOT NULL   # когда TraderBro увидел версию
content_type TEXT NOT NULL
text_content TEXT NULL
formatted_entities JSONB NULL
media_metadata JSONB NULL
forward_metadata JSONB NULL
content_hash VARCHAR(64) NOT NULL
source_event_id UUID NULL
created_at TIMESTAMPTZ NOT NULL
UNIQUE(message_id, version_no)
```

Не добавляй unique только по `(message_id, content_hash)`: автор может вернуть прежний текст поздней правкой, и это должно стать новой версией.

При первичном backfill старая версия до редактирования недоступна: сохрани только текущий snapshot с `change_type=BACKFILL`. Не выдумывай прежние версии и не считай `source_edited_at` временем, когда система узнала о правке.

### 5.5. `telegram_ingest_events` — durable inbox

```text
received_seq BIGSERIAL PK
id UUID NOT NULL UNIQUE
event_type TEXT NOT NULL
telegram_chat_id BIGINT NOT NULL
telegram_message_id BIGINT NULL
dedup_key TEXT NULL UNIQUE
payload JSONB NOT NULL
received_at TIMESTAMPTZ NOT NULL
status TEXT NOT NULL               # NEW / PROCESSING / PROCESSED / RETRY / FAILED / IGNORED
attempts INTEGER NOT NULL DEFAULT 0
available_at TIMESTAMPTZ NOT NULL
locked_at / locked_by NULL
processed_at TIMESTAMPTZ NULL
last_error TEXT NULL               # санитизировано, без payload/secrets
```

Индексы: обработка `(status, available_at, received_seq)`, поиск по `(telegram_chat_id, telegram_message_id)`. Реализуй выборку пачки с блокировкой, допускающую восстановление после рестарта; если используешь `FOR UPDATE SKIP LOCKED`, оформи запрос явно и протестируй в PostgreSQL Testcontainers.

Raw payload хранится ограниченное время, по умолчанию 30 дней. Cleanup удаляет только старые `PROCESSED/IGNORED` inbox events; сообщения и версии не удаляет. `FAILED` не удалять автоматически.

### 5.6. `telegram_sync_runs`

```text
id UUID PK
channel_id UUID FK NULL            # NULL для discovery всех каналов
run_type TEXT NOT NULL             # CHANNEL_DISCOVERY / FULL_BACKFILL / TAIL_RECONCILE
status TEXT NOT NULL               # RUNNING / COMPLETED / FAILED / CANCELLED
requested_at / started_at / finished_at
cursor_message_id BIGINT NULL
loaded_count / created_count / edited_count INTEGER NOT NULL DEFAULT 0
error_code / error_message NULL
```

При рестарте не оставляй вечный `RUNNING`: отметь прерванный запуск и безопасно создай новый. Для одного канала не запускай одновременно два backfill.

## 6. ЗАДАЧА 5: INGESTION НОВЫХ СООБЩЕНИЙ, EDIT И DELETE

### 6.1. Durable pipeline

1. TDLib callback только преобразует update в технический envelope и передаёт в очередь записи; никаких длительных БД/RPC операций в native callback.
2. До обработки событие сохраняется в `telegram_ingest_events` со статусом `NEW`.
3. Processor читает события пачками, повторяет transient errors с exponential backoff + jitter, poison event переводит в `FAILED` после заданного числа попыток, не останавливая весь ingestion.
4. Обработка одного сообщения идемпотентна и сериализуется per `(channel_id, telegram_message_id)`. Используй блокировку текущей строки/транзакционный upsert, чтобы два одновременных edits не создали одинаковый `version_no`.
5. Очередь ограничена; при backpressure приостанавливается history loading. Нельзя молча терять edit/delete events.
6. Это at-least-once обработка с идемпотентной записью, не заявляй exactly-once.

Сообщения из каналов с `monitoring_enabled=false` не сохраняй. Metadata подписок обновляется независимо от мониторинга, чтобы UI показывал полный список каналов без сбора их содержимого.

### 6.2. Новое сообщение

Для `updateNewMessage` из мониторируемого канала:

- нормализуй snapshot;
- создай current row и version 1 в одной транзакции;
- повторный update не создаёт дубликат;
- если сообщение уже создано backfill, обнови только `last_observed_at` и технические поля;
- сохрани UTC `sent_at` из Telegram и отдельный `first_observed_at` от TraderBro.

### 6.3. Редактирование

Обрабатывай и изменение `editDate`, и изменение content. В TDLib эти данные могут приходить разными updates (`updateMessageEdited` и `updateMessageContent`), поэтому:

- сохрани оба inbox events;
- для получения полного согласованного состояния вызови `getMessage` либо используй полный новый content, если он гарантированно содержит всё необходимое;
- не создавай две версии из-за двух updates одной фактической правки;
- если canonical snapshot не изменился, новую версию не создавай;
- если изменился text/caption/entities/media/forward metadata или source edit state, в транзакции заблокируй current row, создай `version_no=current+1` и обнови current projection;
- более поздний duplicate/stale update не должен откатывать уже сохранённую новую версию;
- правка, пришедшая после permanent delete, не воскрешает сообщение, но факт события остаётся в inbox.

Canonical content hash — SHA-256 от стабильно сериализованного доменного snapshot с нормализованными полями. Не используй Java `hashCode()` и не сериализуй произвольный `TdApi.toString()`.

### 6.4. Удаление и cache eviction

Для `updateDeleteMessages` учитывай оба флага:

| Условие | Интерпретация | Действие |
|---|---|---|
| `from_cache=true` | Сообщение удалено только из локального кэша TDLib и может быть получено снова | Не помечать как удалённое в Telegram; сохранить технический event |
| `from_cache=false`, `is_permanent=false` | Сообщение стало недоступно, но это не подтверждение permanent deletion | `availability_status=INACCESSIBLE`; `is_permanently_deleted=false` |
| `from_cache=false`, `is_permanent=true` | Получено подтверждение permanent deletion | `availability_status=DELETED`, `is_permanently_deleted=true`, `deletion_observed_at=now` |

Telegram не передаёт фактическое время удаления в этом update, поэтому поле называется именно `deletion_observed_at`.

Не определяй удаление по отсутствию сообщения в очередной странице `getChatHistory`: отсутствие может быть следствием пагинации, доступности или локального кэша. Для пропущенных offline-событий допускается только статус «не синхронизировано», а не выдуманное удаление.

### 6.5. Нормализация содержимого

В первом этапе поддержи:

- text + formatted entities;
- photo/video/document/audio/voice/animation и другие media через `content_type` + caption + metadata;
- ссылки, упоминания, URL entities;
- reply/forward metadata, если доступны;
- служебные и неподдержанные типы как `content_type` + raw inbox payload, без падения pipeline.

Файлы и изображения не скачивай. Не используй remote file ID как вечную ссылку. Содержимое сообщений не выводи в логи и не включай в обычные actuator endpoints.

## 7. ЗАДАЧА 6: ПОЛНЫЙ HISTORY BACKFILL С НАЧАЛА КАНАЛА И ПЕРИОДИЧЕСКАЯ СВЕРКА

Для **каждого канала с включённым мониторингом** загрузи всю историю, которую Telegram предоставляет этому личному аккаунту: от самого старого доступного сообщения до текущего. Искусственного лимита на число сообщений или глубину периода нет. Не останавливайся на 500 сообщениях, последнем месяце или другой произвольной границе.

При первом включении мониторинга:

1. создай возобновляемый `FULL_BACKFILL` run;
2. продолжай принимать и сохранять live updates независимо от backfill, чтобы новые сообщения не терялись во время длительной загрузки;
3. начни с самых новых сообщений и последовательно двигайся к более старым через `getChatHistory`, страница по умолчанию 100;
4. учитывай reverse chronological order и cursor `from_message_id`; следующая страница запрашивается от минимального фактически полученного `telegram_message_id`, без пропуска граничного сообщения;
5. сохраняй snapshots идемпотентно; сообщения, уже встреченные live, не дублируются;
6. после каждой успешно обработанной страницы обновляй `oldest_loaded_message_id`, `newest_loaded_message_id`, cursor запуска и счётчики; checkpoint и сохранение страницы должны позволять продолжить работу после рестарта без повторной загрузки всей истории;
7. если backfill вернул более новое содержимое уже известного сообщения, создай `SYNC_CORRECTION`, а не перезапиши версию молча;
8. продолжай постраничную загрузку, пока не будет достигнуто первое доступное сообщение канала; только после подтверждённого конца истории установи `full_backfill_completed=true` и переведи канал из `BACKFILL` в `ACTIVE`;
9. если Telegram ограничивает доступ к части истории, не считай backfill полным: сохрани `DEGRADED`/`UNAVAILABLE`, санитизированную причину и последний cursor для повторной попытки;
10. длительная загрузка не должна блокировать discovery, live ingestion и backfill других каналов. Реализуй справедливое планирование каналов и ограничение параллелизма; не выделяй по неограниченному потоку на канал.

`getChatHistory` может вернуть меньше сообщений, чем запрошено, даже до конца истории. Короткая страница **не является признаком начала канала**. Завершай полный backfill только после пустой страницы либо подтверждённого отсутствия более старых сообщений. При непустой странице без продвижения cursor выполни ограниченное число повторов, затем переведи запуск в ошибку с сохранённым checkpoint; не зацикливайся и не объявляй историю полной.

Добавь `TAIL_RECONCILE` каждые 5 минут для последних 100 сообщений каналов, чтобы сверять текущие snapshots после reconnect. Он не заменяет полный backfill и не меняет `full_backfill_completed`. Полный исторический rescan по расписанию не запускай.

При отключении мониторинга останови запланированные загрузки после текущей безопасной границы и сохрани cursor. При повторном включении:

- если `full_backfill_completed=false`, возобнови загрузку с сохранённого checkpoint до самого начала канала;
- если полная история уже загружена, заполни только пропуск между последним синхронизированным состоянием и текущими сообщениями;
- повторная установка `monitoring_enabled=true` не должна без необходимости запускать полный архив с нуля.

Telegram rate limits не хардкодь как «официальные» числа. Локальный консервативный request pacing и обработка `429`/retry-after/временных ошибок обязательны. При ошибках канал переходит в `DEGRADED`, ingestion продолжает обслуживать остальные каналы. Поскольку полный архив большого канала может загружаться долго, показывай в sync state число загруженных сообщений, дату самого старого загруженного сообщения и последний успешный checkpoint; не выдумывай процент выполнения без достоверного общего числа сообщений.

## 8. ЗАДАЧА 7: REST API И OPENAPI

Добавь endpoints под существующей API-аутентификацией TraderBro.

### 8.1. Список каналов

```http
GET /api/signal-analysis/channels
```

Query:

```text
query: string = ""                 # title/username, без LIKE-инъекций
subscribed: boolean = true
monitoring: boolean? = null
archived: boolean? = null
status: SignalIngestionStatus? = null
page: int = 0
size: int = 50                     # max 200
sort: title|lastMessageAt|lastSyncAt = title
direction: asc|desc = asc
```

Ответ `SignalChannelDto`:

```json
{
  "id": "uuid",
  "telegramChatId": "-100... as string",
  "title": "Название",
  "username": "nullable",
  "subscribed": true,
  "archived": false,
  "membershipStatus": "MEMBER",
  "monitoringEnabled": true,
  "ingestionStatus": "ACTIVE",
  "lastMessageAt": "2026-10-28T12:00:00Z",
  "lastSyncAt": "2026-10-28T12:01:00Z",
  "messageCount": 500,
  "editedMessageCount": 7,
  "deletedMessageCount": 2,
  "lastErrorCode": null
}
```

Счётчики и последние timestamps получай одной агрегатной/projection-выборкой, без N+1. Не возвращай message content в этом endpoint.

### 8.2. Включение/выключение мониторинга

```http
PATCH /api/signal-analysis/channels/{id}/monitoring
Content-Type: application/json

{"enabled": true}
```

Правила:

- `id` — внутренний UUID;
- несуществующий канал — 404;
- включение для канала без активной подписки — 409;
- включение при `AUTH_REQUIRED` допустимо, статус будет `WAITING_AUTH`;
- операция аудируется в существующем `audit_events` без message content;
- ответ — обновлённый DTO;
- `SignalIngestionLifecycleManager` замечает изменение не позже configured polling interval и запускает/останавливает sync.

### 8.3. Ручной discovery и статус ingestion

```http
POST /api/signal-analysis/channels/sync
GET  /api/signal-analysis/ingestion/status
```

`POST` возвращает 202 и `syncRunId`; повторный запрос при уже выполняющемся discovery возвращает текущий run, а не создаёт дубль.

Status DTO содержит только: `enabled`, `authorizationState` в безопасном enum, `ingestionActive`, `lastIngestionHeartbeatAt`, `lastDiscoveryAt`, `pendingEventCount`, `failedEventCount`. Никаких phone, api hash, session path, encryption key или stack trace.

Обнови `openapi.yaml`, сгенерируй TypeScript client и используй его в UI. `int64` Telegram IDs представлены `type: string`, чтобы исключить потерю точности JavaScript.

## 9. ЗАДАЧА 8: ANGULAR UI «АНАЛИЗ СИГНАЛОВ»

### 9.1. Навигация и маршрут

Добавь раздел **«Анализ сигналов»** и маршрут:

```text
/signal-analysis/channels
```

Создай standalone components:

- `SignalAnalysisChannelsPageComponent`;
- `SignalChannelsTableComponent`;
- `SignalChannelMonitoringToggleComponent`;
- `SignalIngestionStatusBadgeComponent`;
- `SignalAnalysisChannelsStore` на signals для query/filter/page/loading/error.

Не добавляй React/Vue, server-side templates и новый UI framework.

### 9.2. Состав страницы

Вверху:

- заголовок «Каналы Telegram»;
- компактный badge состояния сбора: `Подключён`, `Требуется авторизация`, `Недоступен`, `Ошибка`;
- кнопка «Обновить список каналов» → POST sync, spinner до 202/завершения, дедупликация кликов;
- пояснение: «Сохраняются сообщения только каналов с включённым мониторингом».

Фильтры:

- поиск по названию/username;
- `Все / Мониторируемые / Не мониторируемые`;
- `В основном списке / В архиве`;
- фильтр по ingestion status;
- сброс фильтров.

Таблица Taiga UI:

1. название канала и username либо «Приватный канал»;
2. подписка: `Активна`, `Архив`, `Отписан`;
3. toggle «Мониторинг»;
4. ingestion status badge;
5. количество сохранённых сообщений;
6. количество отредактированных;
7. количество удалённых;
8. последнее сообщение;
9. последняя синхронизация;
10. санитизированный код последней ошибки/доступный tooltip.

Требования к поведению:

- pagination и сортировка через API, не только сортировка текущей страницы;
- toggle оптимистично не «залипает»: при ошибке откати состояние и покажи уведомление;
- при `AUTH_REQUIRED` toggle можно включить, но badge объясняет ожидание авторизации;
- при потере подписки toggle disabled с tooltip;
- polling статуса — 10 секунд, списка — 30 секунд; останавливай polling при скрытой вкладке/destroy component;
- empty states: нет подписок, ничего не найдено, Telegram ingestion не авторизован;
- loading skeleton, error state с retry;
- русская локаль дат, тёмная тема, keyboard/focus/accessibility для toggle и кнопок;
- длинные названия обрезай визуально с полным tooltip, не ломая таблицу;
- не выводи содержимое сообщений и не добавляй страницу торговых сигналов в этом этапе.

Добавь в UI пояснение ограничения: **«История изменений доступна с момента наблюдения TraderBro. При первичной загрузке сохраняется текущая версия сообщения; прежние редакции и уже удалённые сообщения Telegram может не предоставить».**

## 10. ЗАДАЧА 9: DOCKER, НАБЛЮДАЕМОСТЬ И ЭКСПЛУАТАЦИЯ

### 10.1. Запуск

Обнови существующие Dockerfile/compose для **одного приложения TraderBro**:

- сохрани один application service `traderbro`; не добавляй `traderbro-signal-ingest` или другой Telegram service;
- в этот же контейнер подключи persistent volume для TDLib session;
- обеспечь native dependencies OpenSSL/zlib/libc++ в соответствии с выбранным classifier;
- добавь startup smoke check native loading при включённом ingestion, но не выполняй Telegram login при сборке;
- сохрани `restart: unless-stopped`, resource limits и healthcheck основного приложения;
- не помещай session files и секреты в image layers;
- при штатном shutdown сначала останови новые history requests, заверши текущую DB-транзакцию, затем закрой TDLib client вместе с остальным Spring lifecycle.

`SignalIngestionLifecycleManager` гарантирует один активный TDLib client на одну session directory внутри приложения. Повторный запуск ingestion не создаёт второй client; восстановление использует inbox/history checkpoints из той же PostgreSQL. Не добавляй distributed leader election как обязательную инфраструктуру этого этапа.

### 10.2. Метрики

Добавь Micrometer:

```text
traderbro_signal_ingest_events_total{type,result}
traderbro_signal_ingest_messages_total{operation}
traderbro_signal_ingest_event_lag_seconds
traderbro_signal_ingest_inbox_pending
traderbro_signal_ingest_inbox_failed
traderbro_signal_ingest_active_channels
traderbro_signal_ingest_lifecycle_heartbeat
traderbro_signal_ingest_history_requests_total{result}
```

Не используй title/username/message text как labels. `SignalAnalysisHealthIndicator` отражает состояние TDLib client, авторизации и heartbeat внутри общего приложения, но не переводит общий health/readiness TraderBro в DOWN при ошибке Telegram. Состояние ingestion доступно через `/api/signal-analysis/ingestion/status` и отдельную группу health details.

## 11. ТЕСТЫ — ОБЯЗАТЕЛЬНЫ

### 11.1. Юнит-тесты

- discovery: Main + Archive, дедупликация, private channel без username, обычная группа исключена, discussion group исключена;
- membership: member/admin/creator/restricted-member/left/banned;
- new message: создание current + version 1, повтор без дубликата;
- edit: одна фактическая правка через два TDLib updates создаёт одну новую версию;
- два разных edits создают последовательные версии;
- возврат к прежнему тексту создаёт новую версию, несмотря на совпадающий hash старой версии;
- stale/duplicate update не откатывает current;
- edit после permanent delete не воскрешает сообщение;
- delete matrix для `from_cache` и `is_permanent` из раздела 6.4;
- отсутствие сообщения в history page не считается удалением;
- canonical hash стабилен при другом порядке JSON fields;
- text/caption/entities/media/unsupported content mapping;
- full backfill: несколько страниц до первого доступного сообщения, без лимита 500/периода;
- короткая непустая страница не завершает full backfill; пустая страница подтверждает начало истории;
- restart/resume с `oldest_loaded_message_id` без повторной загрузки всего архива;
- повторное включение мониторинга возобновляет незавершённый full backfill, а завершённый не перезапускает с нуля;
- недоступность старой части истории не устанавливает `full_backfill_completed=true`;
- отсутствие прогресса cursor, live/backfill race и справедливое планирование нескольких каналов;
- выключенный мониторинг: message updates не сохраняются, channel metadata обновляется;
- DTO `telegramChatId` сериализуется строкой;
- REST validation, 404/409, max page size, whitelist sort;
- Angular: filters, toggle success/error rollback, auth-required/unavailable states, polling cleanup.

### 11.2. Testcontainers PostgreSQL

- все Liquibase migrations на чистой БД;
- уникальность `(channel_id, telegram_message_id)` и `(message_id, version_no)`;
- параллельные edits одного сообщения не создают дубли version numbers;
- inbox claim/retry/recovery после рестарта;
- raw-event cleanup не удаляет messages/versions/FAILED;
- агрегатный channel list не выполняет N+1;
- отписка не удаляет сообщения;
- прерванный sync run восстанавливается корректно.

### 11.3. Контрактные и сквозные тесты

TDLib не вызывать из обычного CI и не использовать реальный личный аккаунт в тестах. Реализуй `FakeTelegramUserClient` с сценарием:

```text
discovery Main+Archive
→ включение мониторинга
→ full backfill нескольких страниц до первого доступного сообщения
→ live new
→ edit
→ cache eviction
→ permanent delete
→ повторная доставка всех events
```

Проверь REST/UI projection и точные counts: сообщения, версии, edited/deleted, inbox dedup.

Отдельный opt-in native smoke test проверяет загрузку TDLight/TDLib в целевом Docker image без сети и секретов. Ручной acceptance test с тестовым личным аккаунтом документируй, но не автоматизируй в CI.

### 11.4. ArchUnit и изоляция

Добавь тесты, что:

- `signalanalysis` не ссылается на боевое исполнение, стратегии, `BrokerGateway`, `OrderManager`, `RiskGate`;
- никакой компонент раздела не пишет в `signals`, `orders`, `positions`;
- `TdApi` используется только в `signalanalysis.telegram.tdlib`;
- при `signal-analysis.ingestion.enabled=false` общий Spring context не создаёт native Telegram client;
- при `enabled=true` TDLib client и все ingestion beans создаются в том же ApplicationContext, что и остальной TraderBro;
- не существует второго Spring Boot application class или отдельного application service для ingestion.

## 12. КОНФИГУРАЦИЯ — ДОПОЛНЕНИЕ К `application.yml`

Сгенерируй полный новый блок и корректно объедини с существующей конфигурацией, не затирая прежние настройки:

```yaml
signal-analysis:
  enabled: true
  api:
    default-page-size: 50
    max-page-size: 200
    channel-list-refresh-ms: 30000
    status-refresh-ms: 10000
  ingestion:
    enabled: ${SIGNAL_ANALYSIS_INGESTION_ENABLED:false}
    session-database-directory: ${TELEGRAM_TDLIB_DB_DIR:/var/lib/traderbro/telegram-user/tdlib-db}
    session-files-directory: ${TELEGRAM_TDLIB_FILES_DIR:/var/lib/traderbro/telegram-user/files}
    database-encryption-key: ${TELEGRAM_TDLIB_DATABASE_ENCRYPTION_KEY:}
    api-id: ${TELEGRAM_API_ID:0}
    api-hash: ${TELEGRAM_API_HASH:}
    phone-number: ${TELEGRAM_PHONE_NUMBER:}
    monitoring-poll-interval: 5s
    inbox:
      queue-capacity: 10000
      claim-batch-size: 100
      max-attempts: 10
      initial-retry-delay: 2s
      max-retry-delay: 5m
      raw-event-retention-days: 30
      cleanup-interval: 1h
    discovery:
      page-size: 100
      sync-interval: 15m
      completion-timeout: 2m
    history:
      enabled: true
      page-size: 100
      full-backfill-enabled: true
      # Полная доступная история каждого monitored channel до первого сообщения.
      # Числовой лимит глубины намеренно отсутствует.
      max-concurrent-channel-backfills: 2
      tail-size: 100
      tail-sync-interval: 5m
      request-pacing-delay: 250ms
      max-no-progress-pages: 3
    lifecycle:
      heartbeat-interval: 10s
      heartbeat-timeout: 45s
      callback-threads: 1
      inbox-threads: 2
      history-threads: 2
```

`request-pacing-delay` — локальный защитный параметр, не утверждение о лимите Telegram. Проверь валидацию конфигурации: при включённом ingestion отсутствующие API credentials/encryption key должны давать понятную ошибку до создания native client; при `enabled=false` всё приложение, включая REST и UI раздела, должно стартовать без них. Отдельного Spring-профиля для штатного ingestion нет.

## 13. ПАРАМЕТРЫ ПО УМОЛЧАНИЮ

| Параметр | Значение | Комментарий |
|---|---:|---|
| Discovery interval | 15 минут | Плюс ручной запуск из UI |
| Первичная история | Вся доступная история канала | От самого старого сообщения до текущего; лимит глубины запрещён |
| Параллельные full backfill | 2 канала | Остальные ожидают; live updates продолжают обрабатываться |
| History page | 100 | Проверить ограничение выбранной TDLib |
| Tail reconcile | 100 сообщений каждые 5 минут | Без полного rescan |
| Raw inbox retention | 30 дней | Только processed/ignored |
| Inbox queue | 10 000 | Backpressure, не потеря событий |
| Inbox max attempts | 10 | Затем FAILED и alert/metric |
| Ingestion heartbeat | 10 секунд | Внутри общего приложения; offline после 45 секунд |
| UI refresh | 30 секунд | Статус ingestion — 10 секунд |
| Media download | Выключено | Только metadata/caption |
| Сбор содержимого | Только monitored channels | Metadata всех подписок отдельно |
| TDLib client | 1 в приложении | Общий Spring lifecycle, без отдельного сервиса |

## 14. ФОРМАТ ОТВЕТА

1. **Резюме архитектуры**: почему TDLib, как в одном Spring Boot-приложении сосуществуют notification-бот и личный client, как ограничены внутренние пулы потоков, какие ограничения Telegram остаются.
2. **Dependency/build**: полные изменения `pom.xml`, pinned TDLight BOM/native classifier, Dockerfile/compose и native smoke check. Не добавляй сгенерированный `TdApi.java` вручную, если он поставляется зависимостью.
3. **Домен и БД**: полные Liquibase YAML, domain enums/models, repositories и транзакционные сервисы.
4. **Telegram integration**: полные файлы адаптера, auth lifecycle, discovery, updates, inbox, history и shutdown.
5. **REST**: controllers/DTO/validation, полный обновлённый `openapi.yaml` и генерация client.
6. **Angular**: полные standalone components/templates/styles/store/tests и изменения маршрутов/навигации.
7. **Тесты**: все unit/integration/ArchUnit/fake-client/native smoke тесты.
8. **Документация**: настройка `my.telegram.org`, интерактивный login, права session volume, запуск одного приложения TraderBro со включённым ingestion, recovery и ограничения истории.
9. Файлы выдавай **целиком с заголовками-путями**. Не используй «остальной код аналогично», многоточия вместо реализации или нерабочие заглушки. Неизбежные внешние TODO оформляй только как `TODO: ...` с причиной и альтернативой.
10. Заверши чек-листом ручной приёмки:

```text
Основной TraderBro с ingestion.enabled=false стартует без TDLib и Telegram secrets
→ тот же TraderBro с ingestion.enabled=true загружает native library
→ выполнен одноразовый вход личного аккаунта
→ UI показывает каналы Main+Archive
→ включён мониторинг тестового канала
→ загружена вся доступная история от первого до последнего сообщения
→ подтверждено отсутствие более старых сообщений и установлен full_backfill_completed=true
→ после рестарта незавершённый backfill продолжился с checkpoint
→ новый пост появился в БД
→ редактирование создало version 2
→ cache eviction не изменил availability
→ permanent delete установил DELETED без удаления версий
→ повторная доставка событий не создала дублей
→ ошибка или отключение Telegram ingestion не сломала REST/UI/торговый контур того же приложения
```

## 15. ОГРАНИЧЕНИЯ

- **Никакого Bot API для чтения личного аккаунта.** Существующий `com.traderbro.notify` остаётся отдельным каналом уведомлений.
- Только чтение доступных личному аккаунту каналов. Не обходить ограничения доступа, не подписываться автоматически, не отправлять сообщения, не изменять каналы, не собирать личные чаты/группы/secret chats.
- Соблюдай условия Telegram и права владельцев контента; не реализуй массовый scraping всех доступных чатов. Содержимое сохраняется только для явно выбранных каналов.
- Никакого парсинга торговых рекомендаций, извлечения тикеров/цен, создания записей в боевой таблице `signals`, LLM, LangChain4j/Ollama и автоматических сделок в этапе 4.0.
- Новый раздел не вызывает `OrderManager`, `RiskGate`, `BrokerGateway` и не расширяет их.
- Не гарантируй восстановление редакций/удалений, произошедших до начала наблюдения. Не выдумывай пропущенные события.
- Полный backfill обязателен для каждого monitored channel: загружай всю доступную историю до первого сообщения; лимиты «последние N сообщений» или «последние N дней» запрещены.
- Не считай `from_cache=true` удалением сообщения в Telegram и не определяй удаление по отсутствию в history page.
- **Один проект, один мономодульный Maven, один основной Spring Boot-процесс.** Не создавай отдельный ingestion service, второй JAR, второй ApplicationContext или отдельный микросервис; допускаются только внутренние пакеты, feature flag и выделенные executor'ы.
- Liquibase только YAML; Lombok по правилам проекта; слово `robot` запрещено в пакетах, классах, артефактах и модулях.
- Секреты только через env; login code и 2FA password — только интерактивно. API hash, encryption key, bot token, session files, phone и message content не логируются.
- Существующий UI API key остаётся только в `sessionStorage`; Telegram credentials в браузер не передаются вообще.
- Frontend только Angular 20+ / Taiga UI 4.x, standalone/signals/strict TS, OpenAPI-generated client.
- Не обновляй Ta4j, Angular, Taiga UI, Spring Boot и T-Bank SDK в этом этапе.
- Не исправляй в этом этапе отложенные задачи 3.8/3.9 и не меняй семантику Alligator, AO Twin Peaks или бэктестов.

## 16. ИСТОЧНИКИ ДЛЯ ПРОВЕРКИ API

Перед кодированием сверить с конкретной зависимостью, а не копировать примеры вслепую:

- TDLib: https://core.telegram.org/tdlib
- Официальный Java/JNI example: https://github.com/tdlib/td/tree/master/example/java
- TDLight Java: https://github.com/tdlight-team/tdlight-java
- TDLight releases: https://github.com/tdlight-team/tdlight-java/releases
- TDLib TL schema выбранной версии: `td/generate/scheme/td_api.tl`
- Документация методов `loadChats`, `getChats`, `getChatHistory`, `getMessage` и updates `updateNewMessage`, `updateMessageEdited`, `updateMessageContent`, `updateDeleteMessages` из этой же версии.
