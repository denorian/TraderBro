# TraderBro

Stage-1 trading engine for MOEX equities (TQBR board) that talks **only** to the
T-Bank Invest API (market data + execution), runs technical analysis on **ta4j**, backtests
with costs and walk-forward validation, enforces a hard risk perimeter, and places orders in
**sandbox mode**. Java 25, Spring Boot, mono-module, PostgreSQL/TimescaleDB.

Stage 2 adds **futures (FORTS)** support and **Telegram notifications** with a persistent,
retryable outbound queue and read-only bot commands.

Stage 3.2 adds **interactive backtesting with a web UI**: run backtests over any combination of
strategies × instruments from the UI, compare runs, and pin ENTRY/EXIT signals straight onto the
price chart. Backtests run in an isolated thread pool and **never touch the live trading path**
(no writes to `orders`/`positions`, no `BrokerGateway` calls).

> LLM layers are explicitly out of scope. They plug in later via the `SignalFilter` seam
> (`NoOpSignalFilter` ships as the no-op implementation).

## Architecture

```
                    ┌────────────────────────────────────────────────────────────┐
                    │                         api (REST + wiring)                │
                    │  MonitoringController · KillSwitchController · ApiKeyFilter │
                    │  ApplicationConfig (composition root) · PortfolioService   │
                    └──────────────┬──────────────────────────────┬──────────────┘
                                   │                              │
              ┌────────────────────▼────────────┐   ┌────────────▼──────────────┐
              │            scheduler            │   │          storage           │
              │  SignalEngine · StreamManager · │   │  Bar/Order/Signal/Trade · │
              │  Reconcile · Reload · Snapshot  │   │  BacktestRun/Audit ·       │
              └──┬────────────────────┬─────────┘   │  Instrument/StrategyConfig│
                 │                    │             └─────────────┬─────────────┘
   ┌─────────────▼──────────┐  ┌─────▼──────────────────────────┐ │  (Spring Data JDBC)
   │        core            │  │        execution               │ │  + Liquibase
   │  domain (SPI)          │  │  OrderManager (posts orders,   │ │  + TimescaleDB
   │  strategies (ta4j)     │  │    only after RiskGate)        │ │
   │  backtest · risk rules │  │  KillSwitch · Reconciler ·     │ │
   └─────────────▲──────────┘  │  StateRecovery                 │ │
                 │             └────────────┬────────────────────┘ │
                 │ MarketDataProvider       │ BrokerGateway         │
                 │ / SignalFilter (SPI)     │ (SPI)                 │
        ┌────────┴──────────────────────────┴──────────────┐        │
        │                    data                           │        │
        │  TBankMarketDataProvider · TBankBrokerGateway ·   │        │
        │  mapper (SDK⇄domain) · HistoryChunker · Loader    │        │
        └───────────────────────┬───────────────────────────┘        │
                                │ gRPC (invest-public-api.tinkoff.ru) │
                        ┌───────▼────────┐        ┌───────────────────▼──────┐
                        │  T-Bank Invest │        │  PostgreSQL 17 +         │
                        │  API (sandbox) │        │  TimescaleDB 2.x         │
                        └────────────────┘        └──────────────────────────┘
```

Package-dependency invariants (enforced by ArchUnit, not the compiler):

- `core` never imports the broker SDK or Spring Web; it depends only on domain SPIs + ta4j.
- `data` never imports `execution`.
- The **only** component that calls order placement is `OrderManager`, and it does so only
  after `RiskGate` allows.

## Requirements

- JDK 25 (LTS) and Maven 3.9+
- Docker + docker-compose (for PostgreSQL/TimescaleDB and integration tests)

## Run

### 1. Get a T-Bank token

Create a token at https://www.tinkoff.ru/invest/settings/ → "Токен для Tinkoff Invest API".
Export it (never commit it):

```bash
export TBANK_TOKEN='...'
```

### 2. Start the database

```bash
cp .env.example .env        # edit TBANK_TOKEN and TB_API_KEY
docker compose up -d timescaledb
```

### 3. Build

```bash
mvn -B package               # compiles, runs unit + integration tests
mvn -B verify                # adds jacoco coverage check, checkstyle, spotbugs
mvn -B -P owasp verify       # CI profile: OWASP dependency-check (CVSS>=7 fails the build)
```

### 4. Apply migrations

Migrations are applied by the docker-compose `migrate` service (or the standalone
`scripts/migrate.sh`), not by the app at startup:

```bash
docker compose run --rm migrate     # or: scripts/migrate.sh
```

To backfill history on first run:

```bash
export HISTORY_LOAD_ON_STARTUP=true
java -jar target/traderbro.jar
```

### 5. Backtest

Backtests are run through the **`BacktestOrchestrator`** (async queue, statuses
`QUEUED → RUNNING → COMPLETED / FAILED / CANCELLED`) and exposed over REST + the web UI.
Each run stores per-pair metrics, trades, signals and an equity curve; runs are reproducible
(identical request → identical metrics). Results are retained for `backtest.results-retention-days`
(180) and purged by a scheduler.

**Safety:** backtests execute in an isolated daemon thread pool
(`backtest.executor-pool-size`, default 2) and never write to `orders`/`positions` nor call
`BrokerGateway`. Starting a backtest is the only write operation the UI can perform, and it is
safe by construction. The UI is otherwise **read-only** for trading.

To run from the UI, start the stack and open the **Бэктест** page (`/backtest`): pick strategies
and instruments, a timeframe, a period and optional costs/walk-forward, then launch. From the
result card you can view per-pair metrics + equity, compare up to 4 runs, and open any pair
"on chart" to see ENTRY/EXIT pins.

### 6. Start sandbox trading

```bash
java -jar target/traderbro.jar          # app.sandbox=true by default
```

Live mode requires an explicit acknowledgement and is deliberately not the default:

```bash
export TRADING_LIVE_ACK=I_UNDERSTAND_RISK
# set app.sandbox=false (e.g. APP_SANDBOX=false)
```

### Monitoring REST API (all endpoints require header `X-Api-Key: <TB_API_KEY>`)

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/status` | component health, kill-switch, positions, P&L, stream lag |
| GET | `/api/signals?from=..&to=..` | signals with verdict + reason |
| GET | `/api/orders?date=..` | open orders |
| GET | `/api/backtests/{id}` | backtest report |
| GET | `/api/strategies` | registered strategies (id, name, description, params) |
| POST | `/api/backtests` | create a backtest run (body = `BacktestRequest`), returns `202` + id |
| GET | `/api/backtests` | list runs (filter by status/date, paginated, desc) |
| GET | `/api/backtests/{id}/results` | full per-pair results + equity curves |
| GET | `/api/backtests/{id}/signals?figi=&strategyId=` | run signals (for chart pins) |
| GET | `/api/backtests/{id}/trades?figi=&strategyId=` | run trades |
| GET | `/api/backtests/compare?ids=..` | comparative metrics table (up to 5 runs) |
| DELETE | `/api/backtests/{id}` | cancel (QUEUED/RUNNING) or delete a run |
| POST | `/api/killswitch/activate` | body `{"confirm":true}` |
| POST | `/api/killswitch/deactivate` | body `{"confirm":true}` (manual only) |

## Configuration

Runtime config: `src/main/resources/application.yml` (all secrets come from env).
Sample with comments: `config/application.yml`.

Key stage-2 additions:

| Section | Purpose |
|---|---|
| `futures.*` | contract auto-selection (basic assets, min-days-to-expiry), rollover window, expiry lock |
| `risk.trading-windows.{shares,futures}` | per-class trading windows (futures have an evening session + clearing breaks) |
| `risk.futures.max-margin-pct` | total GO limit (default 30%) |
| `telegram.*` | bot token/chat-id env vars, min level, per-minute cap, retry |

Key stage-3.2 additions (`backtest.*`):

| Key | Purpose |
|---|---|
| `backtest.executor-pool-size` | isolated backtest thread pool size (default 2) |
| `backtest.default-commission-pct` | default commission per trade (0.05%) |
| `backtest.default-slippage-bps` | default slippage in basis points (5) |
| `backtest.max-signals-per-run` | sanity cap on stored signals (100000) |
| `backtest.results-retention-days` | retention for purge scheduler (180) |
| `backtest.walk-forward.{train-days,test-days}` | walk-forward window defaults (180/60) |

### Telegram setup

1. Create a bot via [@BotFather](https://t.me/BotFather) → `/newbot` → copy the token.
2. Get your chat id: message your bot once, then call
   `https://api.telegram.org/bot<TOKEN>/getUpdates` and read `message.chat.id`
   (or use `@userinfobot`).
3. Enable and set env vars, then restart:

```bash
export TELEGRAM_ENABLED=true
export TELEGRAM_BOT_TOKEN='<from BotFather>'
export TELEGRAM_CHAT_ID='<your chat id>'
```

The bot is **long-polling** (works behind NAT), answers read-only commands
(`/status`, `/positions`, `/signals [N]`, `/killswitch`, `/help`) and never exposes risk control.

## Tests

- Unit: `QuotationMapperTest`, `HistoryChunkerTest`, `PositionSizerTest`, `RiskGateTest`,
  `SmaCrossStrategyTest`, `BacktestRunnerNoLookAheadTest`, `OrderManagerTest`,
  `FuturesPositionSizerTest`, `FuturesContractSelectorTest`, `FuturesExpiryLockRuleTest`,
  `TelegramMessageFormatterTest`, `NotificationDeduplicatorTest`, `NotificationRateLimiterTest`.
- Integration (Testcontainers + TimescaleDB): `BarRepositoryIT` — applies all Liquibase
  migrations on a clean container and verifies idempotent bar upsert.
- Backtest stage 3.2: `BacktestOrchestratorTest` (queue, cancel, FAILED on bad params, pool
  concurrency), `BacktestReproducibilityTest` (same request → same metrics),
  `BacktestControllerTest` (REST validation, signal pagination, compare ≤ 5 runs).
- Architecture: `ArchitectureTest` (ArchUnit) — core⇏SDK/Web, data⇏execution, core⇏execution.

> Stage 1–2 Java-25/ta4j/Spring-Boot tests: a subset of pre-existing tests (6 ArchUnit cases on
> JDK-25 bytecode, plus `SmaCrossStrategy`/`RiskGate`/`OrderManager`/`TelegramMessageFormatter`)
> fail under the JDK 25 toolchain and are unrelated to the stage 3.2 changes. The stage 3.2
> backtest tests pass. Verify with `mvn verify` on a JDK 25 toolchain before trusting the build;
> SDK method names are marked `// TODO: verify API`.