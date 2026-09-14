
---

## РОЛЬ

Ты — ведущий Java-разработчик (Senior/Lead) с опытом построения алгоритмических торговых систем, работы со срочным рынком MOEX (FORTS) и интеграции Telegram Bot API. Твоя задача — реализовать **этап 2 проекта TraderBro**: добавить поддержку фьючерсов и отправку торговых сигналов через Telegram-бота. Пишешь production-ready код в тех же соглашениях, что и этап 1: строгая типизация, идемпотентность, обработка всех ошибок, журналирование каждого решения, никаких заглушек без `TODO:` с описанием.

---

## 1. КОНТЕКСТ: ЧТО УЖЕ ЕСТЬ (этап 1 — не трогать без необходимости)

Проект **TraderBro**, мономодульный Maven, Java 25, Spring Boot, корневой пакет `com.traderbro`:

- `core` — домен (`Instrument`, `Bar`, `Signal`, `Order`, `Position`, `Portfolio`), стратегии Ta4j (SmaCross, EmaMomentum, BollingerMeanReversion, DonchianBreakout), бэктест с walk-forward, `RiskGate`/`RiskRule`, интерфейсы `MarketDataProvider`, `BrokerGateway`, `SignalFilter` (+ `NoOpSignalFilter`);
- `data.tbank` — клиент T-Bank Invest API (исторические свечи с чанкованием, стрим свечей с переподключением, справочник инструментов, sandbox/boевой режимы), маппер `Quotation → BigDecimal`;
- `execution` — `OrderManager` (статусная машина, дедупликация по UUID), `Reconciler` (60 с), `KillSwitch`, `StateRecovery`;
- `storage` — Spring Data JDBC, Liquibase YAML (`db.changelog-master.yaml`), таблицы `instruments`, `bars` (гипертаблица), `signals`, `orders`, `trades`, `strategy_config`, `backtest_runs`, `portfolio_snapshots`, `audit_events`;
- `scheduler`, `api` — планировщики и REST мониторинга;
- ArchUnit-тесты пакетных границ; Lombok по правилам этапа 1; токен в env `TBANK_TOKEN`; режим по умолчанию `sandbox`.

**Принцип этапа 2:** максимальное переиспользование существующих абстракций. Фьючерс — это тот же `Instrument` с расширенными атрибутами; стратегии Ta4j не должны знать, акция перед ними или фьючерс; различия (лотность, ГО, шаг цены, экспирация) инкапсулируются в домене и `PositionSizer`. Telegram — новый пакет `com.traderbro.notify`, никак не связанный с торговым контуром (только читает события).

---

## 2. ЗАДАЧА 1: ПОДДЕРЖКА ФЬЮЧЕРСОВ (FORTS)

### 2.1. Доменная модель

1. Расширь `Instrument`: добавь `InstrumentType { SHARE, FUTURE }` и для фьючерсов — value-объект `FutureSpec` (Lombok `@Value`):
    - `figi`, `ticker`, базовый актив (`basicAsset`, например `IMOEX`/`Si`/`BR`);
    - `lot` (кол-во базового актива в контракте), `minPriceIncrement` (шаг цены), `minPriceIncrementAmount` (стоимость шага, руб.);
    - `expirationDate` (дата экспирации), `firstTradeDate`;
    - `initialMargin` (гарантийное обеспечение, `BigDecimal`) — из атрибутов инструмента T-Bank API;
    - метод `BigDecimal pointValue()` — стоимость одного пункта цены.
2. Таблица `instruments`: Liquibase-changeset (новый YAML, `id` по конвенции `YYYYMMDD-N`) — колонки `instrument_type`, `basic_asset`, `lot`, `min_price_increment`, `min_price_increment_amount`, `expiration_date`, `initial_margin`, `first_trade_date`. Существующие акции мигрируются с `instrument_type='SHARE'`.
3. Справочник `TBankMarketDataProvider.findInstruments` — добавить загрузку фьючерсов через `InstrumentsService` (futures): фильтр по списку базовых активов из конфига + **автовыбор ближайшего ликвидного контракта** (ближайшая экспирация не ранее чем через N дней — параметр `futures.min-days-to-expiry`, по умолчанию 7); при приближении экспирации — событие «требуется роллирование» (см. 2.4).

### 2.2. Данные и стрим

- История и стрим свечей по фьючерсам — теми же методами `getHistory`/`subscribeCandles` (SDK единообразен по FIGI); проверь и задокументируй отличия расписания торгов: срочный рынок имеет **вечернюю сессию** — торговые окна для фьючерсов конфигурируются отдельно от акций (`risk.trading-windows.futures`, по умолчанию 10:00–23:50 МСК с перерывами на клиринг 14:00–14:05 и 18:45–19:00; вынеси в конфиг, не хардкодь).
- `bars` уже содержит `figi` — миграция не нужна, но добавь индекс `(figi, interval, ts)` если отсутствует.

### 2.3. Стратегии и сайзинг для фьючерсов

- Существующие стратегии работают на `BarSeries` без изменений — убедись тестами, что им безразличен тип инструмента.
- `PositionSizer` — добавь стратегию сайзинга для фьючерсов `FuturesPositionSizer`:
    - размер позиции в контрактах = `floor(риск-капитал / (стоп-расстояние в пунктах × pointValue))`, где риск-капитал = % портфеля (параметр стратегии, по умолчанию 1%);
    - **проверка ГО**: суммарное ГО всех открытых фьючерсных позиций + новой ≤ лимита (`risk.futures.max-margin-pct`, по умолчанию 30% портфеля); при превышении — уменьшение числа контрактов, при нуле — verdict `REJECTED` с причиной `MARGIN_LIMIT`;
    - учёт встроенного плеча: экспозиция фьючерса = `цена × lot × контракты`, а не ГО — лимит экспозиции `RiskGate` считает именно её;
    - шорт для фьючерсов разрешён (флаг `strategies.<id>.allow-short`, по умолчанию `false`; для фьючерсных стратегий из конфига этапа 2 — `true`), для акций по-прежнему лонг-онли.
- `RiskGate`: правило торгового окна учитывает тип инструмента (акции — окно акций, фьючерсы — окно фьючерсов); добавь правило **запрета открытия новых позиций по фьючерсу за `futures.no-new-positions-days-before-expiry` дней до экспирации** (по умолчанию 2).

### 2.4. Роллирование

- Планировщик `FuturesRolloverWatcher` (ежедневно 09:00 МСК): для каждого торгуемого фьючерса, если до экспирации ≤ `futures.rollover-days-before` (по умолчанию 5), — событие в `audit_events` + Telegram-уведомление уровня `WARNING` (см. задачу 2) с рекомендацией роллирования на следующий контракт (тикер нового контракта определяется автовыбором из 2.1). Автоматическое роллирование позиций в этом этапе **не реализуем** — только уведомление; пометь `TODO(этап 3): auto-rollover`.

### 2.5. Бэктест фьючерсов

- `BacktestRunner` поддерживает фьючерсы: издержки — комиссия за контракт (руб., конфиг, по умолчанию 1 руб./контракт) + проскальзывание в шагах цены (по умолчанию 2 шага); P&L сделки = `Δцена × pointValue × контракты`; метрики те же. В отчёте указывать тип инструмента.

---

## 3. ЗАДАЧА 2: TELEGRAM-БОТ СИГНАЛОВ

### 3.1. Архитектура

- Новый пакет `com.traderbro.notify` (telegram, formatter, model). **Направление зависимостей: только `notify → core` (чтение доменных событий); `core`/`execution` о Telegram не знают.** ArchUnit-правило добавить.
- Библиотека: **TelegramBots Java API** (`org.telegram:telegrambots-longpolling`, последняя стабильная 9.x — проверь на Maven Central, зафиксируй в `<properties>`). Long polling (не webhook) — приложение живёт за NAT.
- Транспорт событий: внутренняя шина — Spring `ApplicationEventPublisher`. Издатели: стратегии (через существующий `SignalEvaluator`/оркестратор сигналов), `OrderManager`, `RiskGate`, `KillSwitch`, `Reconciler`, `FuturesRolloverWatcher`. Подписчик `TelegramNotificationListener` (`@Async`, `@TransactionalEventListener` где уместно) — очередь с дедупликацией и rate-limiting.
- **Надёжность доставки**: каждое уведомление — запись в таблицу `notifications` (тип, payload JSONB, статус `PENDING/SENT/FAILED`, попытки, `created_at`, `sent_at`, `telegram_message_id`). Отправка — фоновый воркер с retry (3 попытки, бэкофф 5с/30с/5мин); Telegram 429 (rate limit) — честная пауза по `retry_after`; недоступность Telegram **не влияет** на торговый контур (fire-and-forget с персистентной очередью). При старте — доотправка `PENDING/FAILED`.
- Конфиг: `telegram.bot-token` из env `TELEGRAM_BOT_TOKEN`, `telegram.chat-id` из env `TELEGRAM_CHAT_ID`, `telegram.enabled` (по умолчанию `false`), `telegram.min-level` (по умолчанию `INFO`), `telegram.max-messages-per-minute` (по умолчанию 20). Если `enabled=false` или токен отсутствует — бин бота не создаётся, события молча скипаются (метрика `notifications.skipped`).

### 3.2. Список сигналов (событий-уведомлений)

Реализуй enum `NotificationType` и уровни `NotificationLevel { INFO, WARNING, CRITICAL }`:

| # | Тип события | Уровень | Источник | Когда отправляется |
|---|---|---|---|---|
| 1 | `SIGNAL_ENTRY` | INFO | оркестратор стратегий | Сигнал ACCEPTED риск-гейтом и заявка отправлена |
| 2 | `SIGNAL_EXIT` | INFO | оркестратор стратегий | Сигнал выхода ACCEPTED, заявка отправлена |
| 3 | `SIGNAL_REJECTED` | INFO* | RiskGate | Сигнал отклонён риск-гейтом (*подавляется фильтром `min-level` и дедупликацией: не чаще 1 раза в 15 мин на пару стратегия+инструмент+причина) |
| 4 | `ORDER_FILLED` | INFO | OrderManager | Полное исполнение заявки |
| 5 | `ORDER_PARTIALLY_FILLED` | INFO | OrderManager | Частичное исполнение (по каждому траншу, с агрегацией не чаще 1/мин на заявку) |
| 6 | `ORDER_CANCELLED` / `ORDER_REJECTED` / `ORDER_EXPIRED` | WARNING | OrderManager | Отмена/отказ/истечение заявки |
| 7 | `POSITION_OPENED` | INFO | Reconciler/OrderManager | Новая позиция появилась (агрегат по fills) |
| 8 | `POSITION_CLOSED` | INFO | Reconciler/OrderManager | Позиция закрыта; включает P&L сделки |
| 9 | `STOP_LOSS_TRIGGERED` | WARNING | стратегия/OrderManager | Сработал стоп-выход стратегии |
| 10 | `RISK_LIMIT_BREACH` | CRITICAL | RiskGate | Превышен лимит (дневной убыток, экспозиция, ГО, частота заявок) |
| 11 | `KILL_SWITCH_ACTIVATED` | CRITICAL | KillSwitch | Активация (с причиной: ручная/лимит/реконсиляция/потеря стрима) |
| 12 | `KILL_SWITCH_DEACTIVATED` | WARNING | KillSwitch | Ручная деактивация |
| 13 | `RECONCILE_MISMATCH` | CRITICAL | Reconciler | Расхождение позиций с брокером (детали расхождения) |
| 14 | `DATA_STREAM_DOWN` / `DATA_STREAM_RESTORED` | CRITICAL / INFO | TBankMarketDataProvider | Потеря стрима > T сек / восстановление |
| 15 | `DAILY_SUMMARY` | INFO | планировщик (19:00 МСК акции / 23:55 МСК фьючерсы — по последней сессии) | Итоги дня: P&L, число сигналов/сделок, открытые позиции, экспозиция, ГО |
| 16 | `FUTURES_ROLLOVER_WARNING` | WARNING | FuturesRolloverWatcher | До экспирации торгуемого контракта ≤ N дней |
| 17 | `FUTURES_EXPIRY_LOCK` | WARNING | RiskGate | Запрет новых позиций из-за близости экспирации |
| 18 | `BACKTEST_COMPLETED` | INFO | BacktestRunner | Завершён бэктест (краткие метрики + ссылка на REST) |
| 19 | `APP_STARTED` / `APP_STOPPED` | INFO | lifecycle | Старт/штатная остановка (режим sandbox/live, версия) |
| 20 | `ERROR` | CRITICAL | глобальный обработчик | Непредвиденное исключение в торговом контуре (без stacktrace в Telegram — только суть и ссылка на лог-запись; stacktrace — в лог) |

### 3.3. Шаблоны сообщений

Единый формат: HTML parse mode, эмодзи-маркер уровня в первой строке, моноширинные блоки для чисел, время МСК. Форматтер — `TelegramMessageFormatter` (интерфейс) + реализация по умолчанию; шаблоны — ресурсные файлы `src/main/resources/telegram/templates/*.txt` с плейсхолдерами `${...}` (свой мини-шаблонизатор или StringSubstitutor — без тяжёлых движков). Все денежные суммы — с разделителями разрядов и «₽», проценты — 2 знака.

**Маркеры уровней:** 🟢 INFO, 🟡 WARNING, 🔴 CRITICAL. Маркеры направления: 📈 LONG, 📉 SHORT.

**Шаблон `SIGNAL_ENTRY` (эталонный — остальные по аналогии):**

```
🟢 <b>СИГНАЛ: ВХОД 📈 LONG</b>
━━━━━━━━━━━━━━━
Инструмент: <b>SBER</b> (акция)
Стратегия: <code>ema-momentum-15m</code>
Цена сигнала: <code>301.25 ₽</code>
Объём: <code>30</code> лот (~<code>90 375 ₽</code>, 9.8% портфеля)
Стоп: <code>296.10 ₽</code> (−1.71%)
Индикаторы: <code>EMA12=302.10 &gt; EMA26=299.84, RSI=58</code>
Время: <code>03.09.2026 14:15:02 МСК</code>
```

**Шаблон `POSITION_CLOSED`:**

```
🟢 <b>ПОЗИЦИЯ ЗАКРЫТА 📉</b>
━━━━━━━━━━━━━━━
Инструмент: <b>MIXZ6</b> (фьючерс, IMOEX)
Направление: SHORT, <code>2</code> контр.
Вход: <code>218 450</code> → Выход: <code>217 980</code>
P&L: <b><code>+940 ₽</code></b> (+0.21% к портфелю)
Удержание: <code>2ч 14м</code>
Причина выхода: <code>сигнал стратегии donchian-breakout-1h</code>
Время: <code>03.09.2026 17:42:10 МСК</code>
```

**Шаблон `KILL_SWITCH_ACTIVATED`:**

```
🔴 <b>KILL-SWITCH АКТИВИРОВАН</b>
━━━━━━━━━━━━━━━
Причина: <code>дневной убыток −2.07% превысил лимит 2.00%</code>
Действия: отменено заявок: <code>3</code>; позиции: <b>не закрывались</b> (close-positions=false)
Открытые позиции: <code>SBER 30 лот, MIXZ6 short 2 контр</code>
Деактивация: только вручную через REST
Время: <code>03.09.2026 15:03:44 МСК</code>
```

**Шаблон `DAILY_SUMMARY`:**

```
🟢 <b>ИТОГИ ДНЯ — 03.09.2026</b>
━━━━━━━━━━━━━━━
P&L дня: <b><code>+4 215 ₽</code></b> (+0.43%)
Сделок: <code>6</code> (4 прибыльных / 2 убыточных)
Сигналов: <code>9</code> (accepted 6 / rejected 3)
Открытые позиции: <code>SBER 30 лот; MIXZ6 short 2 контр</code>
Экспозиция: <code>41.2%</code> портфеля; ГО фьючерсы: <code>58 400 ₽</code> (5.9%)
Kill-switch: <code>не активен</code> | Стрим: <code>OK</code>
```

Остальные типы (1–20) — по тем же правилам: заголовок с маркером, блок полей, время МСК. Для `SIGNAL_REJECTED` обязательны поля «причина» и «правило риск-гейта». Для `RECONCILE_MISMATCH` — таблица расхождений «инструмент: у нас X / у брокера Y».

### 3.4. Команды бота (только чтение)

Реализуй обработчик команд (whitelist по `telegram.chat-id`, чужие сообщения игнорируются с логом):

- `/status` — состояние контуров, kill-switch, P&L дня, lag стрима;
- `/positions` — открытые позиции с плавающим P&L;
- `/signals [N]` — последние N сигналов (по умолчанию 5) с verdict;
- `/killswitch` — состояние kill-switch (**без** управления: активация/деактивация только через REST — осознанное ограничение, чтобы чат не был каналом управления рисками);
- `/help` — список команд.

### 3.5. Тесты

- Юнит: `TelegramMessageFormatter` — каждый из 20 шаблонов рендерится без `null`/плейсхолдеров (snapshot-assert по ключевым строкам); дедупликация `SIGNAL_REJECTED`; rate-limiter очереди; маппинг retry_after.
- Интеграционные (Testcontainers): персистентная очередь — событие → запись `PENDING` → «отправка» через mock Telegram API → `SENT`; рестарт → доотправка недоставленных.
- Фьючерсы: юнит `FuturesPositionSizer` (ГО-лимит, округление контрактов, pointValue), правило экспирации в `RiskGate`, автовыбор контракта (граничные даты), бэктест фьючерса с контрольными метриками.
- ArchUnit: `notify` не импортирует `execution` и `data`; `core` не импортирует `notify`.

---

## 4. КОНФИГУРАЦИЯ (дополнения к `application.yml` — сгенерируй полностью)

```yaml
futures:
  enabled: true
  basic-assets: [IMOEX, Si, BR]          # базовые активы для автовыбора
  min-days-to-expiry: 7
  rollover-days-before: 5
  no-new-positions-days-before-expiry: 2
risk:
  futures:
    max-margin-pct: 30                    # лимит суммарного ГО, % портфеля
  trading-windows:
    shares: "10:00-18:40"
    futures: "10:00-14:00,14:05-18:45,19:00-23:50"
telegram:
  enabled: false
  bot-token: ${TELEGRAM_BOT_TOKEN:}
  chat-id: ${TELEGRAM_CHAT_ID:}
  min-level: INFO
  max-messages-per-minute: 20
```

---

## 5. ПАРАМЕТРЫ ПО УМОЛЧАНИЮ (проверь и замени)

- Фьючерсы: базовые активы `IMOEX` (индекс Мосбиржи), `Si` (USD/RUB), `BR` (нефть Brent); автовыбор ближайшего квартального контракта.
- Фьючерсные стратегии на старте: `DonchianBreakoutStrategy` (1ч, MIX, long+short), `EmaMomentumStrategy` (15-мин, Si, long+short).
- Риск-капитал на сделку по фьючерсу: 1% портфеля; лимит ГО: 30%.
- Telegram: `enabled=false` до явного включения; уровень по умолчанию `INFO`.

---

## 6. ФОРМАТ ОТВЕТА

1. Резюме изменений (до 1 страницы): что добавлено, что изменено в существующем коде и почему, обратная совместимость с этапом 1.
2. Файлы **целиком** с заголовками-путями (`### src/main/java/com/traderbro/notify/telegram/TelegramNotificationListener.java`): сначала новые/изменённые файлы домена и миграции Liquibase, затем фьючерсный контур, затем `notify` (модель, форматтер, все 20 шаблонов `*.txt`, бот, воркер очереди), затем тесты, затем обновлённые `application.yml` и `docker-compose.yml` (если менялся). Без сокращений «// аналогично».
3. Заверши: инструкцией по настройке (создание бота через @BotFather, получение chat-id, включение `telegram.enabled`), чек-листом проверки (фьючерс в sandbox: покупка/шорт/стоп/экспирация; все 20 типов уведомлений получены в чат) и списком `TODO` (включая `TODO(этап 3): auto-rollover` и заделы под LLM-уровни).

## 7. ОГРАНИЧЕНИЯ

- Никаких LLM — как и в этапе 1 (только существующий `SignalFilter`-задел).
- Единственный источник данных и торговли — T-Bank Invest API (акции и фьючерсы); никаких MOEX ISS/QUIK/Python.
- Telegram — только уведомления и read-only команды; **никакого управления торговлей через бота**.
- Слово `robot` не используется; пакеты — `com.traderbro.*`; Lombok — по правилам этапа 1; миграции — только Liquibase YAML.
- Не выдумывай методы SDK/библиотек: неуверенность в API T-Bank SDK (атрибуты фьючерсов, ГО) или TelegramBots 9.x — помечай `// TODO: verify API` с альтернативой.
- Секреты (токен бота, chat-id) — только из env; токен не логируется.
