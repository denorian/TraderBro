# Повторное ревью TraderBro (после stage 3.6–3.7.9)

Дата ревью: 2026-11-01
Объект: рабочее дерево `/Users/alexanderbrovko/IdeaProjects/TraderBro`, коммиты `d64a8e7..539f2af`
Постановки: `docs/stage_1.md`, `docs/stage_2.md`, `docs/stage_3.0.md`, `docs/stage_3.2.md`,
`docs/stage_3.6.md` (новая), `docs/ao_divergence_reversal_strategy.md` (новая)
Предыдущее ревью: `docs/REVIEW.md` (2026-10-21) — ниже сверен статус каждого его дефекта.
Метод: чтение кода/конфигурации, сверка с постановками, полный прогон `mvn test`,
точечный прогон Testcontainers-IT, попытка prod-сборки UI.

---

## 1. Общий вывод

Апдейт качественный: **6 из 8 дефектов прошлого ревью исправлены** (знак проскальзывания,
чекбокс walk-forward, prod-раздача UI, вердикт сигнала при отказе RiskGate, fallback на
фьючерсы в `instrumentByFigi`, переподключение стрима — решено переходом на авто-реконнект
SDK `MarketDataStreamManager`). Миграция на java-sdk-core 1.51 (`TBankApi`) выполнена аккуратно,
включая нетривиальный TLS-фикс (`connection.ssl.secure-internal=false`) и корректную маршрутизацию
sandbox/live.

При этом:

- **2 дефекта прошлого ревью не исправлены**: формула риска в `FuturesPositionSizer` по-прежнему
  смешивает тики и пункты, а сам сайзер по-прежнему **не подключён к боевому контуру** — фьючерсные
  заявки сайзятся акционным `PositionSizer` без учёта ГО и стопа.
- Этап 3.6 (крипта) реализован по постановке почти полностью, но содержит **один критический
  дефект данных**: финальные крипто-бары никогда не попадают в `bars` (см. B1) — это искажает
  индикаторы, дашборд и бэктесты по крипте.
- **Prod-сборка UI сломана**: после апгрейда на Angular 22 в `pom.xml` остался Node v22.11.0,
  а Angular CLI требует ≥ v22.22.3 — профиль `build-ui` падает (см. B2).
- Тесты: `mvn test` — 111 тестов, 4 падения, и это ровно тот «известный» список из AGENTS.md
  (регрессий нет). Testcontainers-IT проходит, но **не подключён ни к одной фазе сборки** (B4).

---

## 2. Статус дефектов прошлого ревью

| # | Дефект (docs/REVIEW.md) | Статус | Доказательство |
|---|---|---|---|
| A1 | Знак проскальзывания на выходе в бэктесте акций | ✅ Исправлен | `BacktestRunner.java:66-68` и `:141-143` — выход считается `execPrice(..., true)` (продажа ниже рынка), вход `false`; добавлен поясняющий комментарий |
| A2 | Чекбокс walk-forward не выключается | ✅ Исправлен | `backtest-form.component.ts:112` — `(change)="toggleWalkForward($event)"`, обработчик `:185-187` читает `.checked` |
| A3 | Prod-раздача UI (outputPath) | ✅ Исправлен | `ui/angular.json:16` — `"outputPath": { "base": "dist/ui", "browser": "" }`; `pom.xml:382` копирует `ui/dist/ui` → `static/ui`. Проверено сборкой (Node 26): бандл ложится ровно в `ui/dist/ui/` с `index.html` в корне. См. однако B2 — сама Maven-сборка падает из-за версии Node |
| A4 | Обрыв стрима без восстановления | ✅ Решён иначе | `TBankMarketDataProvider.java:97-111` — переход на SDK `MarketDataStreamManager` с авто-реконнектом; ручной error-callback удалён. Остаточные проблемы мониторинга — см. B5/C2 |
| A5 | Вердикт сигнала не обновлялся при REJECTED | ✅ Исправлен | `SignalEngine.java:184-190` — `signalRepository.updateVerdict(id, REJECTED, reason)`; метод добавлен в `SignalRepository.java:65-71`; `Signal.id` генерируется стратегиями (`AbstractTradingStrategy.java:65,89`) |
| A6 | `instrumentByFigi` падал для фьючерсов | ✅ Исправлен | `TBankBrokerGateway.java:168-181` — fallback `getShareByFigiSync` → `getFutureByFigiSync` |
| A7 | Формула риска `FuturesPositionSizer` смешивает тики и пункты | ❌ **Не исправлен** | `FuturesPositionSizer.java:61-63` — см. раздел 3 |
| A8 | `FuturesPositionSizer` не подключён к боевому контуру | ❌ **Не исправлен** | `SignalEngine.java:164-167`, `OrderManager.java:200-205` — см. раздел 3 |

---

## 3. Неисправленные дефекты (перенос из прошлого ревью)

### A7. Формула риска `FuturesPositionSizer` занижает риск на контракт

**Файл:** `src/main/java/com/traderbro/core/strategies/FuturesPositionSizer.java:60-66`

```java
BigDecimal stopDistPts = entryPrice.subtract(stopPrice).abs()
        .divide(spec.getMinPriceIncrement(), 8, RoundingMode.HALF_UP);
BigDecimal riskPerContract = stopDistPts.multiply(spec.pointValue());
```

`FutureSpec.pointValue()` (`FutureSpec.java:36-41`) — это `minPriceIncrementAmount / minPriceIncrement`,
т.е. **рубли за один пункт цены**. Стоп-дистанция `|entry − stop|` уже выражена в пунктах цены.
Деля её ещё раз на `minPriceIncrement`, код получает **тики**, а затем умножает тики на рубли-за-пункт.
При `minPriceIncrement > 1` (у большинства фьючерсов FORTS тик > 1 пункта цены, напр. у RTS — 10)
риск на контракт занижается в `minPriceIncrement` раз → сайзер выдаёт в `minPriceIncrement` раз
**больше** контрактов, чем позволяет риск-капитал.

Правильно: `riskPerContract = |entry − stop| × pointValue()`.

Тест `FuturesPositionSizerTest` это маскирует: во всех фикстурах `minPriceIncrement = 1`
(`FuturesPositionSizerTest.java:20`), при котором деление — no-op. Следует добавить кейс с
реалистичным тиком (напр. tick=10, tickValue=13 ₽) и проверить ожидания.

**Смягчающее обстоятельство:** класс сейчас — мёртвый код (см. A8), до продакшен-пути он не
доходит. Но исправлять нужно до подключения, не после.

### A8. Фьючерсы в боевом контуре сайзятся акционным `PositionSizer`

**Файлы:** `src/main/java/com/traderbro/scheduler/SignalEngine.java:164-167`,
`src/main/java/com/traderbro/execution/order/OrderManager.java:200-205`

`SignalEngine.submitOrder` для любого инструмента вызывает акционный `PositionSizer`
(`PositionSizer.java:37-57`): бюджет = `maxPositionPct × портфель`, лоты = бюджет / (цена × лот).
Для фьючерса это:

- игнорирует ГО (`initialMargin`) и лимит маржи `futuresMarginLimitPct` — плечо фьючерса
  ничем не ограничено на этапе сайзинга;
- игнорирует стоп-дистанцию (риск-капитал на сделку не учитывается вообще);
- в `OrderManager.evaluateRisk` в `RiskContext` по-прежнему передаётся `BigDecimal.ZERO` как
  `currentFuturesMargin` (TODO на месте, `OrderManager.java:201-202`) — то есть и RiskGate
  проверяет маржу против нуля.

`FuturesPositionSizer` не создаётся ни одним бином (единственные ссылки — тест и javadoc).
Фактически весь фьючерсный риск-контур этапа 3.2 (ГО-лимит, сайзинг по стопу) существует
только на бумаге.

Дополнительно, косметика: в `SignalEngine.submitOrder` в Telegram-payload кладутся заглушки
`payload.put("stop", signal.getPrice())` и `payload.put("stopPct", "0.00")`
(`SignalEngine.java:201-202`) — в уведомлении «Стоп» равен цене входа с нулевым процентом.

---

## 4. Новые дефекты (stage 3.6–3.7.9)

### B1. 🔴 Критично: финальные крипто-бары никогда не сохраняются в `bars`

**Файлы:** `src/main/java/com/traderbro/scheduler/CryptoStreamRunner.java:59-68`,
`src/main/java/com/traderbro/storage/BarRepository.java:22-26`

`CryptoStreamRunner.onBar` делает `barStore.upsert(List.of(bar))` **на каждый тик** WebSocket,
включая незакрытые свечи (`is_final=false`). Но `UPSERT_SQL` — это
`ON CONFLICT (figi, interval, ts) DO NOTHING`: первый тик новой свечи вставляется, а все
последующие — **включая финальный бар с `x=true`** — отбрасываются конфликтом.

Итог в таблице `bars` по крипте навсегда остаются:
- OHLCV **первого тика** свечи (не финальные значения);
- `is_final = false`.

Что это ломает:
- `IndicatorSnapshotService` на закрытии бара перечитывает историю из `BarStore` → Alligator/AO/
  дивергенции/ангуляции по крипте считаются по «первотиковым» свечам;
- `UiController.instruments()` (`latestCloses`) → цена на Dashboard по крипте — первый тик
  текущей свечи, часовой давности;
- бэктесты по крипте (`BacktestOrchestrator` читает те же `bars`) — по искажённым данным;
- `CryptoHistoryLoader` инкрементальность по `latestTs` формально не страдает (первый тик имеет
  тот же ts), но качество истории деградирует с каждым дельта-обновлением.

Постановка (§3.3) прямо требовала «обновление `bars` с флагом `is_final`» — т.е. семантику UPDATE.
T-Bank контур этой ловушки избегает: `StreamManager.onBar` (`StreamManager.java:100-104`)
персистит **только** финальные бары.

**Исправление (на выбор):**
1. В `CryptoStreamRunner` персистить только `bar.isFinal_()` (как в `StreamManager`) — просто и
   консистентно; либо
2. Сменить `UPSERT_SQL` на `ON CONFLICT DO UPDATE SET open/high/low/close/volume/is_final`
   (обновлять, когда пришедший бар новее) — если незакрытые свечи в БД действительно нужны.
Плюс IT на Testcontainers: тик `is_final=false` → финальный бар по тому же ts → в БД финальные
значения и `is_final=true`.

### B2. 🟠 Prod-сборка UI по Maven сломана (версия Node)

**Файл:** `pom.xml:349`

Профиль `build-ui` пинает `nodeVersion=v22.11.0`. После апгрейда на Angular 22 (коммит `e5432e22`,
stage 3.7.5) Angular CLI требует Node ≥ v22.22.3 / v24.15.0 / v26.0.0. Проверено запуском
`mvn -B -Pbuild-ui -DskipTests package`:

```
[ERROR] Node.js version v22.11.0 detected.
[ERROR] The Angular CLI requires a minimum Node.js version of v22.22.3 or v24.15.0 or v26.0.0.
```

**Исправление:** поднять `nodeVersion` в `pom.xml` до `v24.15.0+` (LTS) и перепроверить
`mvn -Pbuild-ui package`. Сама Angular-конфигурация при этом **верна**: ручной прогон
`npm ci && npm run build` под Node v26.13.0 собрал бандл успешно, и `index.html` вместе со всеми
чанками лёг ровно в `ui/dist/ui/` (без вложенного `browser/`) — то есть фикс A3 рабочий, ломает
сборку только устаревший пин Node. Общий размер бандла 597 kB (initial) — в пределах бюджетов
`angular.json`.

Заодно: `npm ci` сообщает о **38 уязвимостях (2 critical, 1 high)** в lock-файле — стоит прогнать
`npm audit` и обновить зависимости UI.

### B3. 🟡 Ежедневный рефреш крипто-инструментов затирает капитализацию

**Файлы:** `src/main/java/com/traderbro/storage/InstrumentRepository.java:49`,
`src/main/java/com/traderbro/scheduler/CryptoRefreshScheduler.java:29-50`

`UPSERT` по `instruments` перезаписывает `market_cap_usd`/`market_cap_rank` значениями из доменного
объекта, а у свежеразрешённых из `exchangeInfo` инструментов они `null`. В
`CryptoRefreshScheduler.refresh()` сначала идёт `upsertAll(resolved)` (зануляет капитализацию),
и только потом `updateMarketCap(...)`. Если CoinGecko недоступен (штатный, предусмотренный
постановкой сценарий «пропустить обновление»), капитализация остаётся занулённой до следующих
суток — Dashboard показывает «—».

**Исправление:** убрать `market_cap_usd`/`market_cap_rank` из SET-части `UPSERT` (эти колонки
ведёт только `updateMarketCap`) или обернуть в `COALESCE(EXCLUDED.market_cap_usd, instruments.market_cap_usd)`.

### B4. 🟡 Testcontainers-IT не подключён к сборке; IT по крипте отсутствуют

`src/test/java/com/traderbro/storage/BarRepositoryIT.java` — единственный IT, и он не выполняется
ни в `mvn test` (surefire по умолчанию исключает `*IT`), ни в `mvn verify` (**failsafe-плагин в
`pom.xml` отсутствует**). При явном запуске `mvn test -Dtest=BarRepositoryIT` — проходит
(38 с, Liquibase применяет все 22 changeset чисто, включая крипто-миграцию).

Постановка 3.6 (§7) требовала Testcontainers-IT: заливка истории крипты, расчёт индикаторов по
BTCUSDT, дивергенция AO на синтетике, бэктест EmaMomentum на BTCUSDT с контрольными метриками.
Из этого есть только юнит-тест `CryptoBacktestTest` (без БД). Интеграционное покрытие крипто-контура
отсутствует — именно поэтому B1 дошёл до ревью незамеченным.

**Исправление:** добавить `maven-failsafe-plugin` (goal `integration-test`/`verify`, include `**/*IT`)
и дописать крипто-IT по списку из §7 (в первую очередь — на upsert финальных баров, см. B1).

### B5. 🟡 Мониторинг стримов деградировал после миграции на SDK

- `TBankMarketDataProvider.degraded` (`:42`) никогда не выставляется в `true` — `isDegraded()`
  мёртв (не читается никем). `StreamHealth.markDegraded()` (`StreamHealth.java:31-33`) тоже не
  вызывается ниоткуда: статусы `DEGRADED` в `/api/status` и `DAILY_SUMMARY` недостижимы,
  остаются только OK/DOWN.
- По крипто-стриму мониторинга сталости нет вовсе: `CryptoStreamRunner` не трогает `StreamHealth`,
  а `BinanceStreamSubscription.markDegraded()` (`BinanceMarketDataProvider.java:396-398`) пишет
  флаг, который никто не читает. Отвал Binance-стрима будет заметен только по логам.
- В `BinanceMarketDataProvider` счётчик попыток `activeAttempt` (`:344,374,386`) **не сбрасывается**
  после успешного переподключения — 5 накопленных за всю жизнь подписки обрывов (даже разнесённых
  неделями) навсегда переводят её в «degraded». Кроме того, `reconnect` делает `Thread.sleep`
  в callback-потоке WebSocket (`:177-185`) — на время бэкоффа блокируется общий исполнитель
  `HttpClient`, общий с REST-вызовами того же провайдера.

**Исправление:** сбрасывать `activeAttempt` в `onOpen`/после успешного `connect`; бэкофф делать на
выделенном `ScheduledExecutorService`; пробросить degraded крипто-стрима в `StreamHealth`/
метрики (и решить судьбу мёртвого флага в T-Bank провайдере — либо выставлять, либо удалить).

### B6. 🔵 Кэш `exchangeInfo` не ключуется по символам

**Файл:** `src/main/java/com/traderbro/data/binance/BinanceMarketDataProvider.java:236-260`

`findInstruments(tickers)` при свежем часовом кэше возвращает закэшированный список **независимо
от запрошенных тикеров**. Сейчас вызывается всегда с одним и тем же списком из конфига, поэтому
не стреляет; при первом же вызове с другим набором вернёт чужие данные. Латентный дефект —
ключ кэша должен включать сортированный список символов.

### B7. 🔵 Дефолтные издержки крипты не применяются при запуске из UI

**Файл:** `src/main/java/com/traderbro/core/backtest/BacktestOrchestrator.java:157-164`

Переопределение комиссии на Binance-дефолт (0.1% / 5 б.п.) срабатывает только при
`request.costs() == null`. UI-форма всегда отправляет `costs` (`backtest-form.component.ts:209-212`),
поэтому бэктест крипты из UI идёт с форменными 0.05% — вдвое ниже реального taker-фи Binance.
Постановка §4.2 допускает конфиг «по умолчанию», но фактически умолчание недостижимо из
основного сценария. Вариант: применять крипто-дефолт, когда комиссия в запросе равна
«акционному» дефолту, или подставлять 0.1% в саму форму при выборе крипто-инструментов.

### B8. 🔵 Отклонения от постановки 3.6 по стратегиям/сигналам на крипте

- §4.1 предусматривала привязку стратегий к типам инструментов
  («`strategy_config` поле `instrumentTypes: [SHARE, FUTURE, CRYPTO]` или явный список `figis`»).
  Такого поля в `StrategyConfig` нет — все активные стратегии гоняются по всем tradable-инструментам
  (`SignalEngine.recomputeAll`, `SignalEngine.java:65-72`), а живой поток закрытых крипто-баров
  в `SignalEngine.onClosedBar` вообще не поступает (`CryptoStreamRunner` вызывает только
  `IndicatorSnapshotService`). Живых сигналов/Telegram-событий по крипте «по закрытию бара» нет;
  §6 (набор стратегий на крипте на старте) не отражён в конфигурации. Защита от ордеров по крипте
  при этом корректна (`SignalEngine.java:109-115`).
- §4.3: формат цены крипты «2–8 знаков по `tickSize`» упрощён до фиксированных 4 знаков
  (`crypto-format.ts:15-17`) — для пар с мелким тиком (PEPE-подобных) будет обрезать; для текущего
  топ-5 приемлемо.

### B9. 🔵 Замечания по 3.7.x (без правок кода)

- `TBankOrderEventSource` по-прежнему маппит **каждый** трейд в `OrderStatus.PARTIALLY_FILLED`
  (`TBankOrderEventSource.java:60-68`): финального `FILLED` из стрима не приходит, заказ
  «дозревает» только через реконсиляцию `getOpenOrders`, а TTL-таймер (`OrderManager.scheduleTimeout`)
  может попытаться отменить уже исполненный ордер (ошибка ловится и логируется, но шумно).
  Поведение унаследовано от старого SDK-контура — стоит завести отдельную задачу.
- `docs/REVIEW.md` (ревью от 2026-10-21) устарел: 6 из 8 его дефектов исправлены. Рекомендую
  пометить его как архивный или заменить настоящим документом.
- В `mvn test` падают ровно 4 известных теста из AGENTS.md (`SmaCrossStrategyTest`, `RiskGateTest`,
  `OrderManagerTest`, `TelegramMessageFormatterTest`). По `TelegramMessageFormatterTest` видно, что
  шаблон рендерит «СИГНАЛ: ВХОД —» вместо «СИГНАЛ: ВХОД 📈 LONG» — похоже на реальный (пусть и
  «известный») дефект payload/шаблона, а не на шум; рекомендую всё же закрыть эти 4 теста.

---

## 5. Что в апдейте сделано корректно (выборочно, с проверкой по коду)

**Этап 3.6 (крипта):**
- Доменная модель по принципу «крипта — тот же Instrument»: `InstrumentType.CRYPTO`,
  `CryptoSpec` с валидацией (`CryptoSpec.java:36-43`), миграция `20261022-1_crypto.sql`
  идемпотентна и имеет rollback-шаги.
- `BinanceMarketDataProvider`: правильный маппинг klines→`Bar` (в т.ч. объём-строка→long,
  `volumeToLong`), чанкование истории по 1000 баров, retry с бэкоффом на 429/418
  (`:274-304`), обработка app-level `{"ping"}`→`{"pong"}` (`:195-231`), разбор combined-stream
  (payload под `"data"`), figi из символа события, а не позиционный — всё соответствует §3.2
  и зафиксированным в AGENTS.md граблям.
- Клиентский `BinanceRateLimiter` (1100 weight/мин) — честный фикс-виндоу с блокировкой.
- Архитектурный инвариант `data.binance` ⊥ `data.tbank` закреплён ArchUnit-тестом
  (`ArchitectureTest.java:63-68`) и проходит.
- Бэктест крипты идёт акционным контуром с `lot=1` (покрыто `CryptoBacktestTest`),
  издержки из `crypto.taker-fee-pct`/`slippage-bps`.
- Крипта корректно отсечена от исполнения: `SignalEngine.java:109-115` (сигнал публикуется,
  ордер не ставится), от T-Bank стрима (`StreamManager.java:76-79`), `BrokerGateway` не расширялся.
- `CryptoHistoryLoader` ключует инкрементальность на **earliest** stored bar — живой тик не маскирует
  дыру в истории (комментарий `:77-81` точно описывает проблему).
- CoinGecko-обновление некритично к сбоям (`CryptoRefreshScheduler.java:47-49`) — как требовала §3.4.
- UI: группа «Криптовалюты» в форме бэктеста, фильтр типа и колонки цены/капитализации на
  Dashboard, форматтеры вынесены в чистые функции и покрыты Jest-тестами (`crypto-format.spec.ts`).
- Telegram `DAILY_SUMMARY` получил секцию крипты-заглушку — как предписано §4.4.

**Этапы 3.7.x:**
- `TBankApi` — аккуратный адаптер java-sdk-core 1.51: `ResilienceSyncStubWrapper` вместо
  самодельного `RetryPolicy` (удалён), TLS-фикс `connection.ssl.secure-internal=false` с
  поясняющим комментарием (`TBankApi.java:76-82`), пин target/sandbox.target, маршрутизация
  sandbox/live по флагу (`postOrderSync`, `getPortfolioSync` и т.д.).
- Стрим свечей переведён на SDK `MarketDataStreamManager` с авто-реконнектом (снимает A4);
  `waitingClose=false` (`TBankMarketDataProvider.java:84-91`) убирает ложную сталость внутри
  сессии, при этом в `bars`/`SignalEngine` идут только закрытые свечи (`StreamManager.java:100-104`).
- Монитор сталости стал сессионно-ориентированным (`RiskConfig.isInSession`,
  `StreamManager.java:117-121`) — вне торгового окна/выходных kill-switch не срабатывает;
  окна фьючерсных сессий учтены.
- Заказы переведены на resilience trades-stream с авто-переподключением (`TBankOrderEventSource`).
- Стек обновлён системно: Spring Boot 4.1.2, ta4j 0.27.0, SDK 1.51, ArchUnit 1.5.1 (тест
  архитектуры снова зелёный), удалён Checkstyle, Tomcat заменён на Jetty — всё соответствует
  обновлённому AGENTS.md.
- График: маркеры сигналов/дивергенций отфильтровываются по видимому диапазону и перевешиваются
  на панорамировании (`candlestick-chart.component.ts:238-284`) — снимает проблему сотен маркеров
  от «перестреливания» дивергенций (см. заметку в `docs/ao_divergence_reversal_strategy.md`).

---

## 6. Результаты проверки сборкой и тестами

| Проверка | Команда | Результат |
|---|---|---|
| Компиляция + юнит-тесты | `mvn -B test` (JDK 26.0.2; на машине нет Temurin 25 из AGENTS.md) | 111 тестов, **4 падения — ровно известный список** из AGENTS.md (`SmaCrossStrategyTest`, `RiskGateTest`, `OrderManagerTest`, `TelegramMessageFormatterTest`); новых регрессий нет. Все новые тесты 3.6 зелёные: `BinanceMarketDataProviderTest` (9), `BinanceRateLimiterTest` (2), `CryptoBacktestTest`, `RiskConfigTest`, `ArchitectureTest` |
| Интеграционный тест | `mvn -B test -Dtest=BarRepositoryIT` (Docker/Testcontainers) | ✅ проходит; Liquibase применяет 22 changeset включая `20261022-1_crypto` |
| Prod-сборка UI через Maven | `mvn -B -Pbuild-ui -DskipTests package` | ❌ **падает**: пиннутый Node v22.11.0 < требуемого Angular 22 CLI (≥ v22.22.3/v24.15.0/v26.0.0) — см. B2 |
| Prod-сборка UI вручную | `npm ci && npm run build` (Node v26.13.0, /tmp) | ✅ 597 kB initial, `Output location: ui/dist/ui` — `index.html` и чанки в корне `dist/ui`; фикс A3 подтверждён сборкой |
| UI Jest-тесты | `npm test` (Node v26.13.0) | ✅ 6 suites / 15 тестов, все зелёные (включая `crypto-format.spec.ts`, `dashboard.component.spec.ts`) |
| `mvn -B verify` (spotbugs + jacoco) | — | ⚠️ не запускался в рамках ревью |

Примечание: локально проект собирался JDK 26 (`--release 25`), а не зафиксированным в AGENTS.md
Temurin 25 — на результаты тестов это не повлияло (набор падений совпал с документированным).

---

## 7. Рекомендуемый план (по приоритету)

1. **B1** — чинить немедленно: персистить в `CryptoStreamRunner` только финальные бары (или
   перевести `UPSERT` на `DO UPDATE`) + IT. Без этого вся крипто-аналитика/бэктесты считаются
   по искажённым свечам.
2. **B2** — поднять `nodeVersion` в `pom.xml` до v24.15.0+ и прогнать `mvn -Pbuild-ui package`;
   заодно `npm audit` (2 critical).
3. **A7+A8** — до любого включения фьючерсных стратегий в прод: исправить формулу
   (`riskPerContract = |entry−stop| × pointValue()`), подключить `FuturesPositionSizer` в
   `SignalEngine` для `instrument.isFuture()` (стоп — из `indicatorSnapshot`, как предполагало
   прошлое ревью), пробросить реальное текущее ГО в `OrderManager.evaluateRisk` вместо
   `BigDecimal.ZERO`. Расширить `FuturesPositionSizerTest` кейсом с тиком > 1.
4. **B3** — убрать `market_cap_*` из SET-части upsert инструментов.
5. **B4** — добавить failsafe-плагин и крипто-IT по §7 постановки 3.6.
6. **B5** — мониторинг стримов: сброс счётчика реконнектов Binance, вынос бэкоффа из
   callback-потока, проброс degraded в `StreamHealth`; удалить или задействовать мёртвый флаг
   `degraded` в `TBankMarketDataProvider`.
7. **B6–B8** — по мере развития крипто-контура (этап 4): ключ кэша exchangeInfo, дефолтные
   издержки из UI, `instrumentTypes` в `StrategyConfig`, живые сигналы по закрытию крипто-бара.
8. Техдолг: закрыть 4 «известных» падающих теста (особенно `TelegramMessageFormatterTest` —
   похож на реальный дефект шаблона), разобраться с `PARTIALLY_FILLED`-only маппингом в
   `TBankOrderEventSource` (B9).
