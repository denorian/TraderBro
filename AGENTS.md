# AGENTS.md — TraderBro

Guidance for AI agents / contributors working in this repository. The goal is to make safe,
consistent changes to a **trading engine** that can run against a live broker, so invariants
matter more than style. Read this before editing.

## What this project is

TraderBro is a **read-only-first trading monitor + backtester** for MOEX equities (via the
T-Bank Invest API) and top crypto spot pairs (via public Binance). It streams live candles,
runs technical analysis on **ta4j**, backtests with costs + walk-forward, enforces a hard risk
perimeter, and — only in explicit modes — places orders against the T-Bank **sandbox**.

**Default posture is non-destructive:** by default the app runs in sandbox + observe-only
(signals are published, orders are never placed). Live trading is an explicit opt-in
(`APP_SANDBOX=false` + `TRADING_LIVE_ACK`). Keep it that way.

## Tech stack (current)

| Area | Choice |
|---|---|
| Language / JDK | Java **25** (`maven.compiler.release=25`) |
| Build | Maven (mono-module), JAVA_HOME=`/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home` |
| Framework | Spring Boot **4.1.2** (set via parent POM — no separate `spring-boot.version` property) |
| Web container | embedded **Jetty** 12.1.14 (`spring-boot-starter-jetty`; Tomcat excluded) — corporate standard |
| JSON | **Jackson 3** — package is `tools.jackson.*`, **not** `com.fasterxml.jackson.*` |
| TA | ta4j **0.27.0** |
| T-Bank API | java-sdk-core **1.51** (low-level gRPC, see below) |
| Crypto | Binance REST + combined WebSocket; CoinGecko for market cap |
| DB | PostgreSQL 17 + TimescaleDB 2.x (docker, host port **25432**) |
| Migrations | Liquibase YAML (applied by docker-compose `migrate` / `scripts/migrate.sh`, **not** at app startup) |
| Boilerplate | Lombok (use `@Slf4j`, builders) |
| UI | Angular 22 + Taiga UI 5 + lightweight-charts (in `ui/`, Jest) |
| Quality | SpotBugs + JaCoCo (0.70 line) in `verify`; OWASP on `-P owasp`. **Checkstyle was removed** (2026-10) |

## Build / test / run

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
mvn -q clean compile          # fast compile check
mvn -q test                   # unit + integration tests (needs docker for Testcontainers)
mvn -B verify                 # + spotbugs + jacoco coverage gate

# DB (Postgres + TimescaleDB on 25432):
docker compose up -d timescaledb
docker compose run --rm migrate          # apply Liquibase migrations
```

Run the app (sandbox, observe-only safe):
```bash
export TBANK_TOKEN='...'                 # from tinkoff.ru/invest/settings
export APP_SANDBOX=true
mvn spring-boot:run
```
UI: `cd ui && npm ci && npm start` (ng serve on :4200); tests `cd ui && npm test` (jest).

## Module / layer architecture

```
api        — REST controllers, ApiKeyFilter, ApplicationConfig (composition root), StreamHealth
scheduler  — SignalEngine, StreamManager, Reconcile/Reload/Snapshot schedulers, CryptoStreamRunner
storage    — Spring Data JDBC repositories + Liquibase migrations
core       — domain (SPI), strategies (ta4j), backtest, risk rules
execution  — OrderManager (posts orders ONLY after RiskGate), KillSwitch, Reconciler, StateRecovery
data       — TBankMarketDataProvider, BinanceMarketDataProvider, TBankBrokerGateway, TBankOrderEventSource,
             TBankApi, mappers (SDK⇄domain), HistoryChunker, loaders
notify     — Telegram (long-polling bot + outbound queue)
```

**Layer invariants (enforced by ArchUnit, not just convention):**
- `core` never imports the broker SDK or Spring Web — depends only on domain SPIs + ta4j.
- `data` never imports `execution`.
- The **only** component that can place an order is `OrderManager`, and only after `RiskGate` allows.
- `data.binance` never imports `data.tbank` (separate providers).

## Domain model essentials

- **`Instrument`** is identified by **figi**; has `type` (`SHARE`/`FUTURE`/`CRYPTO`), `ticker`,
  optional `futureSpec` (FORTS) and `cryptoSpec` (Binance). `InstrumentType.CRYPTO` instruments
  are streamed/backfilled by the Binance provider and are **analysis-only**.
- **`Bar`** → persisted idempotently to the `bars` hypertable via `BarStore.upsert`
  (`ON CONFLICT (figi, interval, ts) DO NOTHING`).
- **`CandleInterval`** (domain enum) maps to both the T-Bank proto interval (`toSdk`) and the
  stream subscription interval (`toSubscriptionInterval`) in `TBankMarketDataProvider`.
- Two `MarketDataProvider` beans exist: T-Bank (`@Primary`) and Binance. Crypto beans inject the
  **concrete** `BinanceMarketDataProvider`; T-Bank consumers rely on `@Primary`.
- Backtests run on an isolated pool and **never** write `orders`/`positions` nor call
  `BrokerGateway`.

## T-Bank SDK 1.51 — critical, non-obvious

The old high-level `InvestApi` facade is **gone**. The new SDK is a low-level gRPC wrapper:

- Entry: `ConnectorConfiguration.loadFromProperties(Properties)` → `ServiceStubFactory.create(...)`
  → `newResilienceSyncService(XxxServiceGrpc::newBlockingStub, resilience)` returns a
  `ResilienceSyncStubWrapper`; call `wrapper.callSyncMethod(XxxServiceGrpc.getXxxMethod(),
  stub -> stub.xxx(request))`.
- All broker access goes through our own **`TBankApi`** (in `data/tbank`) — an adapter that hides
  this. **Do not bypass it**; add new methods there.
- **TLS gotcha (REQUIRED):** the SDK defaults `connection.ssl.secure-internal=true`, which trusts
  only a single bundled root CA (`RussianTrustedRootCA.jks`) and fails every call with
  `UNAVAILABLE: io exception`. `TBankApi` must set `connection.ssl.secure-internal=false` (use the
  default JVM trust store) and pin `target`/`sandbox.target` to the app's host
  (`invest-public-api.tinkoff.ru` / `sandbox-…`). Preserve this if you touch `TBankApi`.
- **Sandbox is a flag**, not a separate service: `sandbox.enabled=true` switches the target URL.
  Order/portfolio calls route through `SandboxServiceGrpc` vs `OrdersServiceGrpc`/`OperationsServiceGrpc`
  depending on the flag (handled inside `TBankApi`).
- Retries/backoff come from the SDK's built-in resilience (resilience4j), configured by
  `connection.retry.max-attempts`. The old hand-rolled `RetryPolicy` is **dead** — don't resurrect it.
- Stub method names differ from the old SDK: `shareBy` (→ `ShareResponse`), `futureBy`
  (→ `FutureResponse`), `getInstrumentBy` descriptor is `getGetInstrumentByMethod`.
- Live candle streaming uses the SDK `MarketDataStreamManager` (auto-reconnects); order trades use
  `TradeStreamWrapperConfiguration` (resilience server-side stream). The `Instrument` type used for
  candle subscriptions accepts a figi via its `instrumentId` field.

## Crypto (Binance) — read-only

- `BinanceMarketDataProvider` reads klines REST (`/api/v3/klines`, chunked) and a combined-stream
  WebSocket (`/stream`). **The combined-stream WS nests the payload under `"data"`**, and Binance
  sends volume as a **string** — parse accordingly (see `volumeToLong`).
- `CryptoHistoryLoader` backfills incrementally, keyed on the **earliest** stored bar (not latest)
  so a live candle doesn't mask missing history.
- Crypto never places orders. Backtest costs: taker fee 0.1% + slippage 5bps.

## App-level gotchas / invariants

- **Observe-only:** `app.observe-only=true` forces no trading and skips reconciliation
  (live broker monitoring without orders). Respect it.
- **Liquibase:** migrations are applied by the docker-compose `migrate` service / `scripts/migrate.sh`,
  **not** by the app. New schema changes = new YAML changeset in `migrations/`.
- **Jackson 3:** import from `tools.jackson`, never `com.fasterxml.jackson`. Records with primitive
  fields reject nulls on deserialization — box them and use resolve-defaults.
- **Lombok:** use `@Slf4j` for logging (never `LoggerFactory`/`System.out`). Use builders.
- **No `System.out/err`** — use the class logger.
- **Pre-existing failing tests (do NOT chase):** under JDK 25, exactly 4 tests fail
  **independently of your changes**: `SmaCrossStrategyTest`, `RiskGateTest`, `OrderManagerTest`,
  `TelegramMessageFormatterTest`. `mvn test` reports these as failures; do not treat them as
  regressions. A change is a regression only if a test that was passing now fails.
  (Note: ArchUnit — `ArchitectureTest` — now **passes** with ArchUnit 1.5.1; do not add new
  architecture tests expecting them to fail.)
- **Timestamps:** `Bar.ts` is an `Instant`; proto conversions use epoch seconds + nanos.
- **Money/price:** domain uses `BigDecimal`; `QuotationMapper` converts proto `Quotation`/`MoneyValue`.

## Config

Runtime config lives in `src/main/resources/application.yml`; all secrets come from env vars
(`TBANK_TOKEN`, `TB_API_KEY`, `TELEGRAM_*`, `APP_SANDBOX`, `APP_OBSERVE_ONLY`, …). Sample with
comments: `config/application.yml`. Property groups: `app.*`, `tbank.*`, `crypto.*`, `futures.*`,
`risk.*`, `telegram.*`, `backtest.*`, `williams.*`, `strategies.*`, `indicators.*`.

## Workflow notes

- Verify changes with `mvn -q test` (and `mvn -B verify` for spotbugs/jacoco) before claiming done.
- For UI changes run `cd ui && npm test`.
- Do not commit unless explicitly asked. Propose a commit message and let the user decide.