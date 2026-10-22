# Ревью реализации TraderBro по постановкам (этапы 1, 2, 3.0, 3.2)

Дата ревью: 2026-10-21
Объект: рабочее дерево `/Users/alexanderbrovko/IdeaProjects/TraderBro`
Постановки: `docs/stage_1.md`, `docs/stage_2.md`, `docs/stage_3.0.md`, `docs/stage_3.2.md`
Метод: чтение кода и конфигурации, сверка с чек-листами постановок; `mvn -DskipTests compile` — **успешно** (проект компилируется). Тесты в рамках ревью не запускались.

---

## 1. Общий вывод

Каркас проекта реализован качественно и в целом соответствует постановкам: пакетные границы выдержаны и зафиксированы ArchUnit-тестами, контур исполнения (OrderManager / RiskGate / KillSwitch / Reconciler / StateRecovery) соответствует инвариантам этапа 1, Telegram-контур и бэктест-оркестрация этапов 2/3.2 реализованы полно, UI на Angular 20 + Taiga UI 4 + lightweight-charts 5 присутствует и работает.

При этом найдены **9 критических/важных дефектов**, часть из которых искажает результаты бэктестов (неверный знак проскальзывания), часть — ломает заявленные сценарии (обрыв стрима без восстановления, фьючерсный контур без ГО-лимита и с падающим `instrumentByFigi`, вероятно сломанная prod-раздача UI). Ниже — детали с указанием файлов и строк.

---

## 2. Критические дефекты

### A1. Бэктест акций: неверный знак проскальзывания на выходе
**Файл:** `src/main/java/com/traderbro/core/backtest/BacktestRunner.java`

Метод `execPrice(raw, slippageBps, isSell)` корректно реализован (`isSell ? raw - slip : raw + slip`, стр. 302–305), но в циклах симуляции вызывается **всегда с `isSell=false`**:
- `runDetailed`, стр. 65 — единая цена для входа и выхода;
- `simulateShares`, стр. 137 — то же.

Итог: выход из позиции (продажа) исполняется по цене **выше** рынка на величину проскальзывания. Все бэктесты акций систематически завышены. В `buyAndHoldReturn` (стр. 409) продажа посчитана правильно (`isSell=true`) — эта несогласованность подтверждает, что в циклах ошибка, а не осознанное решение.

**Исправление:** при выходе вызывать `execPrice(..., slippageBps, true)` (в обоих методах); добавить тест с контрольными метриками, чувствительный к знаку проскальзывания (на симметричной серии доходность стратегии должна ухудшаться при росте `slippageBps`).

### A2. UI: чекбокс walk-forward невозможно выключить
**Файл:** `ui/src/app/features/backtest/backtest-form.component.ts:100`

```html
<input type="checkbox" [checked]="walkForward()" (change)="walkForward.set(!!$event.target)">
```
`!!$event.target` — всегда `true` (объект события ненулевой). После первого клика walk-forward остаётся включённым навсегда.

**Исправление:** `walkForward.set(($event.target as HTMLInputElement).checked)` — через метод-обработчик, как это сделано для остальных чекбоксов формы (`toggle(...)`).

### A3. Prod-раздача UI, вероятно, сломана (несостыковка outputPath и копирования)
**Файлы:** `ui/angular.json:16`, `pom.xml:382–402`

`@angular-devkit/build-angular:application` со строковым `outputPath: "dist"` складывает браузерный бандл в `ui/dist/browser/`. Профиль `build-ui` в `pom.xml` копирует `${project.basedir}/ui/dist` целиком в `target/classes/static/ui/` → `index.html` окажется по пути `static/ui/browser/index.html`, а `UiWebConfig` ищет `classpath:/static/ui/index.html`. SPA по `/ui/` не откроется (404 / пустой fallback).

**Исправление (на выбор):** в `angular.json` задать `"outputPath": { "base": "dist/ui", "browser": "" }` (Angular 20 это поддерживает) и копировать `ui/dist/ui`; либо копировать `ui/dist/browser`. Проверить сквозным прогоном `mvn -Pbuild-ui package` и открытием `http://localhost:8080/ui/`.

### A4. Обрыв стрима котировок не приводит к переподключению
**Файл:** `src/main/java/com/traderbro/data/tbank/TBankMarketDataProvider.java:106–110`

Error-callback стрима только логирует ошибку и сбрасывает флаг `connected`:
```java
streamService.newStream(streamId, processor, err -> {
    log.warn("candle stream error: {}", err.getMessage());
    sub.connected.set(false);
});
```
Экспоненциальный бэкофф (`connectWithBackoff`, макс. 5 попыток) срабатывает **только при синхронном сбое подключения**. `StreamManager` ресабскрайб не выполняет — только следит за staleness и при lag > 60 с активирует kill-switch (`StreamManager.monitorStaleness`).

Итоговый сценарий при обрыве: стрим умирает → через 60 с kill-switch останавливает торговлю → данные никогда не восстанавливаются → ручная деактивация kill-switch ничего не даст (подписка мертва). Прямое нарушение этапа 1 (п. 4.1 «обработка переподключения при обрыве стрима» и п. 5.1 «обрыв стрима → переподключение с бэкоффом»).

**Исправление:** в error-callback (и/или по onComplete) запускать `connectWithBackoff(sub, 0)` для неотменённых подписок; по исчерпании попыток — `degraded=true` (уже есть) + событие `DATA_STREAM_DOWN`. Учесть, что kill-switch от потери стрима и восстановление стрима должны сосуществовать: стрим обязан восстановиться независимо от состояния kill-switch.

### A5. Сигнал остаётся ACCEPTED при отказе RiskGate
**Файл:** `src/main/java/com/traderbro/scheduler/SignalEngine.java:101–108, 177–180`

Сигнал вставляется в `signals` с verdict из стратегии (ACCEPTED) **до** отправки заявки; если `OrderManager` вернул `REJECTED` по решению RiskGate, запись сигнала не обновляется — в БД и REST остаётся `ACCEPTED`, хотя этап 1 (п. 4.5) требует: «Любой отказ → событие в аудит с причиной, сигнал получает verdict REJECTED». Уведомление `SIGNAL_REJECTED` при этом публикуется — рассинхрон БД и нотификаций.

**Исправление:** `SignalRepository` дополнить `updateVerdict(id, REJECTED, reason)` и вызывать его в ветке `order.getStatus() == REJECTED`.

### A6. Фьючерсный сайзинг не подключён к боевому контуру
**Файлы:** `src/main/java/com/traderbro/scheduler/SignalEngine.java:157–197`, `src/main/java/com/traderbro/execution/order/OrderManager.java:200–205`

`SignalEngine.submitOrder` всегда использует акционный `PositionSizer` (доля портфеля / лотность). `FuturesPositionSizer` (риск-стоп-сайзинг + лимит ГО) существует, но задействован **только в тестах** (`FuturesPositionSizerTest`). В `OrderManager.evaluateRisk` `currentFuturesMargin = ZERO` с честным TODO — лимит ГО (`risk.futures.max-margin-pct`, 30%) при реальных заявках не проверяется нигде. Сейчас это безопасно лишь потому, что фьючерсы выключены конфигом (`futures.basic-assets: []`).

**Исправление:** в `SignalEngine` для `instrument.isFuture()` вызывать `FuturesPositionSizer` (risk-capital, стоп из сигнала/параметров, текущее ГО из портфеля); при `marginLimitHit`/0 контрактов — verdict REJECTED с причиной `MARGIN_LIMIT`. Текущее ГО агрегировать из открытых фьючерсных позиций (PositionLedger/PortfolioProvider) и пробрасывать в `RiskContext`.

### A7. `TBankBrokerGateway.instrumentByFigi` умеет только акции
**Файл:** `src/main/java/com/traderbro/data/tbank/TBankBrokerGateway.java:183–189`

```java
Share s = api.getInstrumentsService().getShareByFigiSync(figi);
```
Для FIGI фьючерса вернётся пустой ответ/исключение → `OrderManager.evaluateRisk` бросит `IllegalStateException("unknown instrument")` → заявка по фьючерсу невозможна в принципе. Латентный блокер этапа 2.

**Исправление:** fallback на `getFutureByFigiSync` (или `getInstrumentByFigiSync`, который уже используется в `names()`), маппинг через `SdkInstrumentMapper.toInstrument(Future)`.

### A8. DAILY_SUMMARY не публикуется
**Файлы:** `src/main/java/com/traderbro/core/event/NotificationType.java:26`, шаблон `src/main/resources/telegram/templates/DAILY_SUMMARY.txt`

Событие №15 этапа 2 (итоги дня, 19:00 МСК по последней сессии) объявлено в enum и имеет шаблон, но **ни один компонент его не публикует** (grep по `DAILY_SUMMARY` в `src/main` — только объявление enum).

**Исправление:** планировщик (cron `0 0 19 * * *` Europe/Moscow, из конфига), собирающий P&L дня, число сигналов/сделок, открытые позиции, экспозицию и ГО → `DAILY_SUMMARY`.

### A9. Walk-forward через оркестратор не оптимизирует параметры
**Файл:** `src/main/java/com/traderbro/core/backtest/BacktestOrchestrator.java:203–225`

`WalkForwardRunner` сам реализован правильно (подбор по grid на train, честная оценка на test), но оркестратор передаёт `grid = List.of(params)` — **единственный набор параметров** (текущий/переопределённый). Подбора нет; этап 1 (п. 4.4 «подбор параметров на train по целевой метрике») в UI-запусках не выполняется. Дополнительно: метрики WF-запуска собираются из двухточечной equity-кривой (вырожденные Sharpe/MaxDD), а trades/signals/equity сохраняются пустыми — для WF-запуска в UI нет ни пинов, ни equity-графика (есть TODO stage 4).

**Исправление:** добавить в `BacktestRequest` опциональный grid параметров (или дефолтную сетку на стратегию в конфиге); результаты по окнам персистить (trades/signals/equity по test-окнам) либо явно документировать ограничение в UI.

---

## 3. Значимые отклонения от постановок

### 3.1. Бэкенд и конфигурация

| # | Отклонение | Где | Постановка |
|---|---|---|---|
| 1 | `git-hash` = `${maven.build.timestamp}` — не git-hash (фактически версия берётся из env `BUILD_GIT_HASH` в `ApplicationConfig:395,416`) | `pom.xml:73` | Этап 1 п. 4.4: версия кода/git-hash в `backtest_runs` |
| 2 | Версии вне `<properties>`: grpc-testing 1.69.0, frontend-maven-plugin 1.15.1, maven-resources-plugin 3.3.1, junit 4.13.2 (dependencyManagement) | `pom.xml:209,351,385,221` | Этап 1 п. 2: все версии — в `<properties>` |
| 3 | В `docker-compose.yml` нет сервиса `app` (только timescaledb + migrate) | `docker-compose.yml` | Этап 1 п. 2/3: «app + timescaledb»; этап 3.0: build-ui включён в compose-образе |
| 4 | Миграции — внешним сервисом `migrate` (Liquibase CLI), не `spring-boot-starter-liquibase`; в `application.yml` нет секции `spring.liquibase` | `docker-compose.yml:21–36`, `application.yml` | Этап 1 п. 4.8/7 (осознанное отклонение — задокументировать) |
| 5 | `/api/status`: статусы `broker` и `db` захардкожены OK; реальных проверок нет | `MonitoringController.java:59–60` | Этап 1 п. 4.7: OK/DEGRADED/DOWN по факту |
| 6 | `/api/orders?date=` игнорирует дату, отдаёт только открытые заявки; сделки за дату не отдаются | `MonitoringController.java:70–74` | Этап 1 п. 4.7: «заявки и сделки» |
| 7 | Список стратегий — `GET /api/backtests/strategies` вместо `GET /api/strategies` | `BacktestController.java:192` | Этап 3.2 п. 3.1 |
| 8 | Нет REST-подтверждения выхода из observe-only — режим «только наблюдение» нельзя снять без рестарта | `StateRecovery.java` (нет setter'а; ни один контроллер не сбрасывает) | Этап 1 п. 4.5: «до ручного подтверждения» |
| 9 | `seedStrategies` при каждом старте делает upsert в `strategy_config` — ручные правки в БД затираются рестартом | `ApplicationConfig.java:526–547` | Этап 1 п. 4.3: конфиг читается из БД (ожидается seed-if-absent) |
| 10 | `futures.basic-assets: []` (фьючерсы выключены); нет сидов фьючерсных стратегий Donchian(1ч, MIX) и EmaMomentum(15м, Si) | `application.yml:124–132`, `ApplicationConfig.seedStrategies` | Этап 2 п. 5 (дефолты). В коде есть честный комментарий «disabled until re-enabled» |
| 11 | `history.intraday-days: 400` вместо 90; окна `risk.trading-windows.shares/futures` заданы только дефолтами в `RiskProperties`, в yml не вынесены; часть расписаний — fixedDelay в ms вместо cron | `application.yml:13–15, 108–117, 143–149`, `RiskProperties.java:38–43` | Этап 1 п. 8, 4.6; этап 2 п. 4 |
| 12 | Таймаут `tbank.timeout: 10s` не применяется к gRPC-вызовам (`RetryPolicy.getTimeout()` нигде не используется); `tbank.target` игнорируется (InvestApi.create/createSandbox без endpoint) | `ApplicationConfig.java:106–118, 130–142` | Этап 1 п. 4.1: «все вызовы — с таймаутами», target-endpoint в конфиге |
| 13 | Издержки фьючерсного бэктеста: комиссия выводится как `commissionPct × цена_первого_бара`, проскальзывание — б.п.→тики конвертацией. Спека: комиссия в руб./контракт (дефолт 1 ₽), проскальзывание в тиках (дефолт 2) | `BacktestOrchestrator.java:183–201` | Этап 2 п. 2.5 |
| 14 | `FuturesPositionSizer`: `stopDistPts = |entry−stop| / minPriceIncrement` (число **тиков**) умножается на `pointValue` (цена **пункта**) — смешение единиц; для инструментов, где тик ≠ 1 пункт, размер занижается | `FuturesPositionSizer.java:60–66` | Этап 2 п. 2.3: `floor(риск-капитал / (стоп-пункты × pointValue))` |
| 15 | `OrderManager.publishOrderEvent` всегда шлёт `NotificationLevel.INFO`; ORDER_CANCELLED/REJECTED/EXPIRED по спеке — WARNING | `OrderManager.java:227–239` | Этап 2 п. 3.2, строка 6 |
| 16 | В `SIGNAL_ENTRY` фиктивный стоп (`stop = цена сигнала`, `stopPct = "0.00"`); в `SIGNAL_REJECTED` захардкожен `dirMark = "📈 LONG"`; в `KILL_SWITCH_ACTIVATED` `cancelledOrders = 0`, `positions = "—"` | `SignalEngine.java:122, 192–193`, `KillSwitch.java:60–65` | Этап 2 п. 3.3 (шаблоны) |
| 17 | Kill-switch не персистентен: после рестарта неактивен | `KillSwitch.java` (in-memory `volatile boolean`) | Не требуется явно, но риск эксплуатации |
| 18 | Нет ArchUnit-правила «`core.backtest` ↛ `data`»; при этом `BacktestOrchestrator` (core.backtest) импортирует `com.traderbro.api.config.BacktestProperties` и `com.traderbro.storage.*` — обратная зависимость слоёв, не отловленная тестами | `ArchitectureTest.java`, `BacktestOrchestrator.java:3,18–19` | Этап 3.2 п. 8 |
| 19 | Доотправка FAILED-уведомлений при рестарте под вопросом: `findPendingForRetry(maxAttempts, ...)` — FAILED после maxAttempts, скорее всего, не подхватывается | `TelegramQueueWorker.java:46`, `NotificationRepository` | Этап 2 п. 3.1: «при старте — доотправка PENDING/FAILED» |

### 3.2. UI (фронтенд)

| # | Отклонение | Где | Постановка |
|---|---|---|---|
| 1 | Live-пины сигналов/сделок не реализованы: сигналы загружаются только при `?backtestRun=`; переключателя tuiTabs «Live/Бэктест» нет | `chart.component.ts:62–83, 176–198` | Этап 3.0 п. 3.2 (маркеры на графике), этап 3.2 п. 3.2.1 |
| 2 | Мультивыбор стратегий на графике и в форме — нативные чекбоксы вместо `tuiMultiSelect`; даты/числа — нативные `<input type=date/number>` вместо `tuiInputDateRange`/`tuiInputNumber`; таймфрейм — кнопки вместо `tuiSegmented` | `backtest-form.component.ts:20–102`, `chart.component.ts:51–60, 64–81` | Этап 3.2 п. 3.1–3.2 (явно названы компоненты Taiga UI) |
| 3 | `SignalRecord` не содержит direction → пины не различают вход лонг/шорт (спека: шорт — стрелка вниз над свечой); текст EXIT всегда «Sell»; tooltip по пину отсутствует; соединительных линий вход→выход нет и TODO не задокументирован | `SignalRecord.java`, `candlestick-chart.component.ts:255–288` | Этап 3.2 п. 3.2.3 |
| 4 | >500 маркеров — `console.warn` + обрезка до первых 500 (вместо агрегации/пагинации по видимому диапазону) | `candlestick-chart.component.ts:283–286` | Этап 3.2 п. 3.2.4 |
| 5 | Остановка polling на неактивной вкладке (`document.visibilitychange`) отсутствует во всём `ui/src` | — (grep пуст) | Этап 3.0 п. 3.1 |
| 6 | Дефолт формы бэктеста «все активные стратегии + все инструменты выбраны» не реализован (старт с пустого выбора) | `backtest-form.component.ts:144–145, 158–162` | Этап 3.2 п. 3.1/6 |
| 7 | `^` в версиях tslib и ng-openapi-gen; Taiga `~4.0.0` / Angular `~20.0.0` — старые патч-линейки | `ui/package.json:34,41` | Этап 3.0 п. 3.1: «зафиксируй версии, без ^» |
| 8 | Отдельных страниц `features/signals` и `features/positions` нет (встроены в dashboard) | `ui/src/app/features/` | Этап 3.0 п. 3.1 (структура) — допустимое упрощение |
| 9 | `/api/chart/{ticker}` вместо `{figi}`; индикаторы считаются на лету из bars, а не читаются из `indicator_snapshots` | `UiController.java:84–123` | Этап 3.0 п. 3.1 (работает, но дублирует логику снапшотов) |

### 3.3. Покрытие тестами (по факту наличия файлов)

- **Нет юнит-тестов** `EmaMomentumStrategy`, `BollingerMeanReversionStrategy`, `DonchianBreakoutStrategy` — этап 1 п. 6 требует тест каждой стратегии. Есть только `SmaCrossStrategyTest` и `AlligatorAoStrategyTest`.
- **Нет контрактных тестов** TBank-клиентов через in-process gRPC (зависимость `grpc-testing` в pom есть, тестов нет) — этап 1 п. 6.
- **Нет IT** `StateRecovery` (падение → рестарт → восстановление) и отдельного прогона миграций на чистом контейнере (возможно, частично покрыто в `BarRepositoryIT` — проверить).
- **UI (Jest):** нет тестов формы/списка бэктеста и обёртки графика (этап 3.2 п. 4 требует); есть только пайпы, api-сервис, dashboard.
- Присутствуют и выглядят адекватно: `RiskGateTest`, `OrderManagerTest`, `FuturesPositionSizerTest`, `FuturesExpiryLockRuleTest`, `QuotationMapperTest`, `HistoryChunkerTest`, `BacktestOrchestratorTest`, `BacktestReproducibilityTest`, `BacktestRunnerNoLookAheadTest`, `WilliamsIndicatorsTest`, тесты дедупликации/rate-limiter/форматтера Telegram, `BarRepositoryIT`, `ArchitectureTest`.

---

## 4. Что реализовано корректно (подтверждено чтением кода)

**Этап 1:**
- `pom.xml`: Java 25, Spring Boot 4.0.1, ta4j 0.24.1, java-sdk-core 1.36 (помечен verified), Lombok provided + `lombok.config`, JaCoCo-гейт 70%, Checkstyle/SpotBugs, профиль OWASP.
- Домен и SPI (`core.domain.spi`: `MarketDataProvider`, `BrokerGateway`, `SignalFilter`, `NoOpSignalFilter` и др.); слово `robot` нигде не используется; секреты только из env (`TBANK_TOKEN`, `TB_API_KEY`, `TELEGRAM_*`); live-режим требует `TRADING_LIVE_ACK=I_UNDERSTAND_RISK` (`ApplicationConfig.java:112–115`).
- `HistoryChunker` + `HistoryLoader` (CommandLineRunner за флагом, идемпотентная вставка); маппер `Quotation → BigDecimal` с граничными случаями и тестами.
- 4 стратегии этапа 1 + `AtrStopRule` для EmaMomentum; `StrategyRegistry` с горячей перечиткой из `strategy_config`, отключением при невалидных параметрах и аудитом.
- `RiskGate`: 8 правил с приоритетами (kill-switch, дневной убыток, лимит позиции, экспозиция, rate-limit, окно по типу инструмента, живость стрима, expiry-lock), отказ → аудит.
- `OrderManager`: UUID до отправки, дедупликация, статусная машина, TTL с отменой, частичные fills через стрим, **без авто-retry**.
- `Reconciler` (60 с, алерт + авто-kill по флагу), `StateRecovery` (observe-only при расхождении), `KillSwitch` (деактивация только с `confirm`).
- Миграции: конвенция `YYYYMMDD-N`, гипертаблица отдельным changeset `runInTransaction=false`, NUMERIC(19,4)/TIMESTAMPTZ/JSONB, rollback, `ON CONFLICT DO NOTHING`.
- `BacktestRunner`: исполнение по open следующего бара (без look-ahead), есть `BacktestRunnerNoLookAheadTest`; метрики CAGR/Sharpe/Sortino/Calmar/MaxDD/win-rate/profit factor; walk-forward реализован честно на уровне `WalkForwardRunner`.
- ArchUnit: 6 правил (core↛SDK, core↛Spring-web, data↛execution, core↛execution, notify↛execution/data, core↛notify).

**Этап 2:**
- `InstrumentType {SHARE, FUTURE}`, `FutureSpec` с `pointValue()`; миграция `20260914-1_futures_columns.sql`; автовыбор контракта `FuturesContractSelector` (+ `nearestAfter` для роллирования); `FuturesExpiryLockRule`; `FuturesRolloverWatcher` (cron 09:00 МСК, audit + WARNING, авто-роллирования нет — как требует спека).
- Telegram: `telegrambots-longpolling` 9.x; персистентная очередь `notifications` (PENDING/SENT/FAILED); воркер с backoff 5с/30с/5мин и честной паузой по `retry_after`; 20 типов событий (+3 расширения Williams) и 27 шаблонов; whitelist по chat-id; команды `/status` `/positions` `/signals [N]` `/killswitch` `/help` строго read-only; при `enabled=false` бин не создаётся.
- Направление зависимостей `notify → core` выдержано и покрыто ArchUnit.

**Этап 3.0:**
- `MedianPriceIndicator`, `AwesomeOscillatorIndicator` (aoRising, saucer, zero-cross), `AlligatorIndicator` — корректная семантика сдвига без look-ahead (shifted-значения для UI/аналитики, raw — для сигналов; задокументировано в JavaDoc), `AlligatorState` с ATR-порогом, `fanWidth`; `IndicatorFactory`; индикаторы не Spring-бины.
- `IndicatorSnapshotService` + таблица `indicator_snapshots` (идемпотентная вставка, retention 90 дней, catch-up при старте).
- `AlligatorAoStrategy` по системе Вильямса (режимный фильтр, триггеры AO, выход за Teeth, фрактальный стоп, allowShort).
- REST для UI: `/api/instruments`, `/api/chart/{ticker}` (свечи + 3 линии + AO одним ответом, лимит баров), `/api/indicators/latest`; `openapi.yaml` + `npm run gen-api`; отдача статики `/ui/**` с SPA-fallback (`UiWebConfig`); профиль `build-ui` (frontend-maven-plugin, выключен по умолчанию).
- UI: standalone-компоненты, signals, strict TS; маршруты `/dashboard` `/chart/:figi` `/login` (+`/backtest*`); `X-Api-Key` интерцептор, 401 → login, ключ в sessionStorage; тёмная тема, русская локаль; OnPush, `takeUntilDestroyed`; график с линиями Jaw/Teeth/Lips в классических цветах и гистограммой AO с синхронизацией диапазонов; `createSeriesMarkers` (актуальный API lightweight-charts 5.x).

**Этап 3.2:**
- `BacktestOrchestrator`: изолированный пул (дефолт 2, задокументировано), статусы QUEUED→RUNNING→COMPLETED/FAILED/CANCELLED, `Future.cancel(true)` + кооперативная проверка прерывания, прогресс в БД, событие `BACKTEST_COMPLETED`; бэктест изолирован от боевого контура (читает только bars, не пишет orders/positions).
- REST: `POST /api/backtests` (валидация: пустые списки/from≥to/неизвестные стратегии/инструменты → 400; ответ 202+id), список с фильтрами и пагинацией, `{id}`, `{id}/results`, `{id}/signals`, `{id}/trades` (лимит+пагинация), `DELETE`, `compare` (≤5) — всё под API-key.
- Хранение: расширение `backtest_runs` (status/request JSONB/progress/error/started/finished) + отдельные таблицы `backtest_pair_results`, `backtest_trades`, `backtest_signals`, `backtest_equity` с индексами по `run_id` (выбор обоснован в комментарии changeset).
- Воспроизводимость: версия кода из env `BUILD_GIT_HASH`, есть `BacktestReproducibilityTest`.
- UI: форма запуска, список запусков со статусами/прогрессом, карточка результата с метриками по парам и переходом «Показать на графике»; пины бэктест-сигналов на графике с фильтром по стратегиям, полупрозрачными отклонёнными и чекбоксом «Показывать отклонённые»; палитра `shared/strategy-colors.ts`.

**Сверх постановок (не замечание, а контекст):** реализован дополнительный контур Williams — дивергенции AO (twin peaks) и ангуляция (`core/williams`, `AoDivergenceReversalStrategy`, миграция `20261017-1_williams_events.sql`, `WilliamsController`), очевидно соответствующий промежуточному этапу 3.1, постановки которого в `docs/` нет.

---

## 5. Приоритизированный план исправлений

**Блок A — критические дефекты (деньги/корректность):**
1. A1: знак проскальзывания на выходе в `BacktestRunner` + тест с контрольными метриками.
2. A2: чекбокс walk-forward в `backtest-form.component.ts`.
3. A3: согласовать `outputPath` и копирование UI в `pom.xml`; проверить `mvn -Pbuild-ui package`.
4. A4: переподключение стрима при обрыве (reconnect из error-callback, degraded по исчерпании попыток).
5. A5: verdict REJECTED для сигнала при отказе RiskGate.
6. A6: `FuturesPositionSizer` в боевой контур + агрегация текущего ГО (перед включением фьючерсов).
7. A7: `instrumentByFigi` — поддержка фьючерсов.
8. A8: планировщик DAILY_SUMMARY.
9. A9: grid параметров для walk-forward в `BacktestRequest` (или документированное ограничение UI).

**Блок B — отклонения от постановок:** пункты таблиц 3.1 (1–19) и 3.2 (1–9) в порядке: 5–9 (мониторинг/REST), 10–13 (конфиг/издержки), 14–16 (уведомления/сайзинг-формула), 18 (ArchUnit), UI 1–6, прочее по остаточному принципу.

**Блок C — тесты и мелочи:** тесты оставшихся стратегий; контрактные тесты TBank (grpc in-process); IT StateRecovery и миграций; Jest-тесты формы/списка/маркеров; сервис `app` в docker-compose; версии в `<properties>`; доотправка FAILED-уведомлений.

---

## 6. Открытые вопросы / принятые допущения

- Фьючерсы осознанно выключены в конфиге (`basic-assets: []`, комментарий «disabled until re-enabled») — дефекты A6/A7 и отклонения 3.1.10/13/14 станут блокерами при включении.
- Внешний Liquibase-сервис вместо `spring-boot-starter-liquibase` — осознанное отклонение; рекомендуется задокументировать в README (сейчас секция `spring.liquibase` в образце конфигурации отсутствует).
- Тесты в ревью не запускались (Testcontainers требует Docker); вердикты по ним даны по наличию и чтению кода. Рекомендуется прогнать `mvn verify` и `npm test` после исправлений блока A.
