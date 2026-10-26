---

## РОЛЬ

Ты — ведущий Java/Angular-разработчик торговых систем с опытом интеграции криптобирж. Твоя задача — **этап 3.6 проекта TraderBro**: добавить криптовалютные котировки (топ-5 по капитализации: **BTC, ETH, BNB, SOL, XRP** к USDT) через **публичный API Binance** (spot) — как дополнительный источник рыночных данных наряду с T-Bank Invest API. Криптовалюты должны: храниться в общей модели баров, участвовать в расчёте индикаторов (Alligator, AO, дивергенции, ангуляция), отображаться в UI (Dashboard, график) и быть доступны для бэктестинга стратегий. Торговля криптовалютой (исполнение) в этом этапе **не реализуется** — только данные, аналитика и бэктест. Пишешь production-ready код в соглашениях проекта.

---

## 1. КОНТЕКСТ: ЧТО УЖЕ ЕСТЬ (этапы 1–3.4 — не ломать)

- `com.traderbro`, мономодуль, Java 25, Spring Boot, ta4j 0.24.1, Lombok, Liquibase YAML;
- `core`: домен (`Instrument` с `InstrumentType {SHARE, FUTURE}` + `FutureSpec`), стратегии (SmaCross, EmaMomentum, BollingerMeanReversion, DonchianBreakout, AlligatorAo, AoDivergenceReversal), `StrategyRegistry`, `SignalFilter`, индикаторы (Alligator, AO, `AoTwinPeaksDetector`, `AngulationDetector`), `IndicatorSnapshotService`, `BacktestOrchestrator`;
- `data.tbank`: `MarketDataProvider` (история + стрим свечей), `TBankBrokerGateway`, маппер `Quotation → BigDecimal`;
- `storage`: гипертаблица `bars` (`figi, interval, ts`), `instruments`, `signals`, `indicator_snapshots`, `divergence_events`, `angulation_events`, `backtest_*`;
- `api`: REST с API-key, OpenAPI → TS-клиент;
- `ui`: Angular 20 + Taiga UI 4, маршруты `/dashboard`, `/chart/:figi`, `/backtest`, тёмная тема, русская локаль, polling;
- `notify`: Telegram (включая дивергенции/ангуляции);
- ArchUnit: `core` не зависит от SDK брокера, `data` не зависит от `execution`, `notify` не импортирует `execution`/`data`.

**Принцип этапа 3.6:** криптовалюта — это тот же `Instrument` с новым типом `CRYPTO`, новым `CryptoSpec` (аналог `FutureSpec`) и новым `MarketDataProvider` (Binance). Существующий код стратегий, индикаторов, бэктеста и UI не должен знать, откуда пришли бары — только через абстракции. Исполнение заявок по крипте — `TODO(этап 4)`.

---

## 2. ИСТОЧНИК ДАННЫХ: BINANCE PUBLIC API (spot)

### 2.1. Почему Binance

- **Бесплатно без API-ключа** для рыночных данных: REST `GET /api/v3/klines` (история) и WebSocket `wss://stream.binance.com:9443` (real-time) — ключ нужен только для торговли;
- **Rate limit**: 1200 weight/мин на IP для REST; WebSocket — без ограничений по weight, до 1024 подписок на соединение;
- **Покрытие**: все топ-5 пар (BTCUSDT, ETHUSDT, BNBUSDT, SOLUSDT, XRPUSDT) — спотовые, максимальная ликвидность;
- **Гранулярность**: klines от 1s до 1M; история с 2017 года (BTC/ETH) — достаточно для бэктеста;
- **Latency**: <50 мс по WebSocket — достаточно для минутных/часовых стратегий TraderBro.

### 2.2. Топ-5 криптовалют по капитализации (октябрь 2026)

| # | Тикер Binance | Актив | Капитализация (ориентир) | Примечание |
|---|---|---|---|---|
| 1 | **BTCUSDT** | Bitcoin | ~$2,3 трлн | Основной индикатор рынка |
| 2 | **ETHUSDT** | Ethereum | ~$450–500 млрд | Смарт-контракты, газ |
| 3 | **BNBUSDT** | BNB (Binance) | ~$90–100 млрд | Токен биржи, сжигание |
| 4 | **SOLUSDT** | Solana | ~$80–90 млрд | Высокая скорость, DeFi |
| 5 | **XRPUSDT** | XRP (Ripple) | ~$70–80 млрд | Платёжный протокол |

*USDT (Tether) исключён — стейблкоин, не торговый актив для стратегий. Капитализации указаны ориентировочно по состоянию на октябрь 2026; актуальные значения подтягивать из CoinGecko/CoinMarketCap при старте (см. 2.4).*

---

## 3. РЕАЛИЗАЦИЯ

### 3.1. Доменная модель

1. **`InstrumentType.CRYPTO`** — добавить в enum.
2. **`CryptoSpec`** (Lombok `@Value`): `figi` (для крипты = тикер пары Binance, e.g. `BTCUSDT`), `baseAsset` (BTC), `quoteAsset` (USDT), `tickSize` (мин. шаг цены, из `exchangeInfo`), `stepSize` (мин. шаг объёма), `minNotional` (мин. сумма ордера, USDT), `onboardDate` (дата листинга — из `exchangeInfo`).
3. Таблица `instruments`: Liquibase-changeset — колонки для крипты (`base_asset`, `quote_asset`, `tick_size`, `step_size`, `min_notional`, `onboard_date`); существующие записи не трогаем.

### 3.2. Клиент Binance (`com.traderbro.data.binance`)

Реализуй `MarketDataProvider` для крипты:

- **`BinanceMarketDataProvider implements MarketDataProvider`**:
  - `List<Bar> getHistory(String figi, Instant from, Instant to, CandleInterval interval)` — REST `GET /api/v3/klines?symbol={figi}&interval={interval}&startTime={ms}&endTime={ms}&limit=1000`; чанкование (1000 баров за запрос); retry с бэкоффом при 429/418; маппинг `openTime, open, high, low, close, volume` → `Bar`;
  - `StreamSubscription subscribeCandles(List<String> figis, CandleInterval interval, Consumer<Bar> onBar)` — WebSocket `wss://stream.binance.com:9443/stream?streams=btcusdt@kline_15m/ethusdt@kline_15m/...`; переподключение при обрыве (бэкофф, макс. 5 попыток); обработка `PING/PONG` (Binance шлёт ping каждые 3 мин, ответ обязателен);
  - `List<Instrument> findInstruments(List<String> tickers)` — REST `GET /api/v3/exchangeInfo?symbols=["BTCUSDT","ETHUSDT",...]`; фильтр `status=TRADING`, `isSpotTradingAllowed=true`; кэш в `instruments`, обновление раз в сутки.
- **Rate limiting**: `RateLimiter` (Guava или Resilience4j) на 1100 weight/мин (запас от 1200); weight ответа учитывай из заголовка `X-MBX-USED-WEIGHT-1M`.
- **Таймфреймы**: маппинг `CandleInterval` → Binance (`1m, 5m, 15m, 1h, 4h, 1d`); 15м и 1ч — приоритет для TraderBro.
- **Часовой пояс**: Binance возвращает `openTime` в ms UTC — конвертируй в `Instant`, храни как UTC (согласовано с этапом 1).

### 3.3. Загрузчик истории и стрим

- `CryptoHistoryLoader` (`CommandLineRunner` за флагом `crypto.history.load-on-startup`): заливка истории по 5 парам, 3 года дневных + 90 дней 15-минутных (как в этапе 1); идемпотентная вставка в `bars`.
- Стрим: подписка на `kline_15m` и `kline_1h` для 5 пар; обновление `bars` с флагом `is_final` (Binance шлёт `kline.x` — true при закрытии свечи); при закрытии — событие в `IndicatorSnapshotService` (расчёт индикаторов, дивергенций, ангуляции).

### 3.4. Метаданные: капитализация и ранг

- Для отображения в UI (Dashboard) и отчётов: раз в сутки обновлять рыночную капитализацию и ранг из **CoinGecko API** (бесплатный ключ не нужен для `/api/v3/coins/markets?vs_currency=usd&ids=bitcoin,ethereum,binancecoin,solana,ripple`); хранить в `instruments` (колонки `market_cap_usd`, `market_cap_rank`, `market_cap_updated_at`).
- При недоступности CoinGecko — пропускать обновление (не блокировать поток котировок).

---

## 4. ИНТЕГРАЦИЯ С СУЩЕСТВУЮЩИМИ КОНТУРАМИ

### 4.1. Индикаторы и сигналы

- `IndicatorSnapshotService`: включить крипто-пары в расчёт Alligator, AO, дивергенций, ангуляции — по всем таймфреймам (15м, 1ч, 1д);
- `WilliamsContextService`: контекст по крипте — как по акциям/фьючерсам;
- Стратегии: крипта доступна для `SmaCrossStrategy`, `EmaMomentumStrategy`, `BollingerMeanReversionStrategy`, `DonchianBreakoutStrategy`, `AlligatorAoStrategy`, `AoDivergenceReversalStrategy` — через `strategy_config` (поле `instrumentTypes: [SHARE, FUTURE, CRYPTO]` или явный список `figis`).

### 4.2. Бэктестинг

- `BacktestOrchestrator`: крипто-пары валидны в `BacktestRequest.figis`; издержки — комиссия Binance spot 0.1% (по умолчанию, конфиг `crypto.taker-fee-pct`), проскальзывание 5 б.п.;
- UI `/backtest`: крипта в мультиселекте инструментов (группа «Криптовалюты»).

### 4.3. UI (Angular + Taiga UI)

- **Dashboard**: крипто-пары в сводной таблице с тегом `CRYPTO` (`tuiTag` фиолетовый); колонки «Цена», «Изм. 24ч %», «Капитализация» (из CoinGecko), «Фаза Аллигатора», «AO», «Дивергенция», «Ангуляция» — как для акций/фьючерсов;
- **График `/chart/:figi`**: крипто-пары открываются по тикеру (`BTCUSDT`); свечи + индикаторы + пины сигналов/дивергенций — без изменений, данные из общего REST;
- **Фильтр типа инструмента** на Dashboard и в `/backtest` — добавить «Криптовалюты».

### 4.4. Telegram

- Новые события по крипте — в существующие типы (`SIGNAL_ENTRY`, `AO_DIVERGENCE_CONFIRMED`, etc.) с указанием типа инструмента;
- Шаблон `DAILY_SUMMARY` — добавить секцию «Криптовалюты» (P&L дня, открытые позиции — когда появится исполнение; пока — заглушка «не торгуется»).

---

## 5. КОНФИГУРАЦИЯ (дополнения к `application.yml`)

```yaml
crypto:
  enabled: true
  binance:
    base-url: https://api.binance.com
    ws-url: wss://stream.binance.com:9443
    rate-limit-weight-per-min: 1100
    connect-timeout-ms: 5000
    read-timeout-ms: 10000
  pairs: [BTCUSDT, ETHUSDT, BNBUSDT, SOLUSDT, XRPUSDT]
  timeframes: [15m, 1h, 1d]
  history:
    load-on-startup: false
    days-daily: 1095        # 3 года
    days-intraday: 90
  taker-fee-pct: 0.1        # комиссия Binance spot
  coingecko:
    enabled: true
    base-url: https://api.coingecko.com/api/v3
    update-cron: "0 0 8 * * *"   # 08:00 UTC
```

---

## 6. ПАРАМЕТРЫ ПО УМОЛЧАНИЮ (проверь и замени)

- Пары: `BTCUSDT, ETHUSDT, BNBUSDT, SOLUSDT, XRPUSDT` (топ-5 без стейблкоинов);
- Таймфреймы: 15м, 1ч, 1д;
- История: 3 года дневных + 90 дней 15-минутных;
- Комиссия в бэктесте: 0.1% (taker); проскальзывание: 5 б.п.;
- Стратегии на крипте на старте: `EmaMomentumStrategy` (15м), `DonchianBreakoutStrategy` (1ч), `AoDivergenceReversalStrategy` (1ч, выключена до бэктеста).

---

## 7. ТЕСТЫ (обязательны)

- **Binance-клиент**: маппинг klines → `Bar` (граничные случаи: `openTime` на границе суток, объём 0); rate limiter (не превышает 1100 weight/мин); переподключение WebSocket; обработка `PING`; mock REST/WebSocket сервер (MockWebServer / MockWebSocketServer).
- **Домен**: `CryptoSpec` валидация (`tickSize`, `stepSize` > 0); маппинг `exchangeInfo` → `Instrument`.
- **Интеграция (Testcontainers)**: заливка истории крипты в `bars`; расчёт индикаторов по BTCUSDT; дивергенция AO на синтетических данных крипты; бэктест `EmaMomentumStrategy` на BTCUSDT с контрольными метриками.
- **UI (Jest)**: отображение крипты в таблице (тег, формат цены с 2–8 знаками по `tickSize`); фильтр «Криптовалюты»; график BTCUSDT с индикаторами.
- **ArchUnit**: `data.binance` не зависит от `data.tbank`; оба реализуют `MarketDataProvider` без перекрёстных импортов.

---

## 8. ФОРМАТ ОТВЕТА

1. Резюме изменений (до 1 страницы): новые компоненты, изменения существующих, обратная совместимость.
2. Файлы **целиком** с заголовками-путями: `CryptoSpec` + миграция → `BinanceMarketDataProvider` (REST + WebSocket) → `CryptoHistoryLoader` → обновление `IndicatorSnapshotService`, `WilliamsContextService`, `BacktestOrchestrator` → REST (без изменений, но проверь) → UI (Dashboard, фильтры, график) → Telegram (шаблоны) → тесты → `application.yml`, `docker-compose.yml` (если менялся), README. Без сокращений «// аналогично».
3. Заверши: инструкцией по проверке (`crypto.enabled=true`, `crypto.history.load-on-startup=true` → запуск → в логах загрузка истории 5 пар → Dashboard показывает крипту с капитализацией → график BTCUSDT с Аллигатором и AO → бэктест EmaMomentum на BTCUSDT → Telegram `DAILY_SUMMARY` с секцией крипты), чек-листом и списком `TODO` (этап 4: исполнение по крипте через Binance Trade API, арбитраж T-Bank/Binance, реальные позиции).

## 9. ОГРАНИЧЕНИЯ

- **Только чтение данных**: никакого исполнения заявок по крипте (Binance Trade API не используется); `BrokerGateway` не расширяется — крипта только для анализа и бэктеста.
- Источник котировок крипты — **только Binance Public API**; CoinGecko — только для капитализации/ранга (раз в сутки).
- Не выдумывай API Binance: если не уверен в полях `exchangeInfo` или формате `kline` — помечай `// TODO: verify API` с альтернативой.
- Lombok, Liquibase YAML, `com.traderbro.*`, без слова `robot`, без LLM.
- Секреты: API-ключей не требуется; если позже добавишь торговлю — ключи только из env (`BINANCE_API_KEY`, `BINANCE_SECRET_KEY`).
