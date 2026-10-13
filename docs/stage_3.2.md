# Промт: TraderBro — этап 3.2, бэктестинг стратегий с UI (мультивыбор стратегий, пины сигналов на графике)

> Скопируйте текст ниже (начиная с раздела «РОЛЬ») целиком в AI-ассистента / кодового агента. Промт рассчитан на **доработку существующего проекта** (этапы 1–3: мономодульный Spring Boot, `com.traderbro`, Java 25, Ta4j + T-Bank Invest API, Liquibase YAML, Lombok, фьючерсы, Telegram, Alligator/AO, Angular 20 + Taiga UI 4). Если код предыдущих этапов доступен в контексте — опирайся на него; если нет — соблюдай соглашения из раздела 1. Проверьте значения в блоке «ПАРАМЕТРЫ ПО УМОЛЧАНИЮ» перед использованием.

---

## РОЛЬ

Ты — ведущий full-stack разработчик (Senior Java + Angular) с опытом построения систем бэктестинга торговых стратегий. Твоя задача — реализовать **этап 3.2 проекта TraderBro**: полноценный интерактивный бэктестинг стратегий с веб-интерфейсом — запуск бэктестов по произвольной комбинации стратегий и инструментов из UI, сравнение результатов и визуализация сигналов (вход/выход) пинами прямо на графике цены. Пишешь production-ready код в соглашениях проекта: строгая типизация, журналирование, никаких заглушек без `TODO:`.

---

## 1. КОНТЕКСТ: ЧТО УЖЕ ЕСТЬ (этапы 1–3 — не ломать)

- Мономодульный Maven-проект **TraderBro**, `com.traderbro`, Java 25, Spring Boot, ta4j-core 0.24.1, Lombok, Liquibase YAML;
- `core`: домен, стратегии `SmaCrossStrategy`, `EmaMomentumStrategy`, `BollingerMeanReversionStrategy`, `DonchianBreakoutStrategy`, `AlligatorAoStrategy`; `StrategyRegistry`; **`BacktestRunner`** (издержки, проскальзывание, метрики CAGR/Шарп/Сортино/Калмар/MaxDD/win-rate/profit factor, walk-forward, отчёт в `backtest_runs`); индикаторы `AlligatorIndicator`, `AwesomeOscillatorIndicator`, `IndicatorSnapshotService`;
- `data.tbank`: история + стрим (акции TQBR, фьючерсы FORTS); инструменты: `SBER, GAZP, LKOH, YDEX, VTBR, T, PLZL, NVTK` + фьючерсы MIX/BR/GOLD/CR;
- `execution`, `notify` (Telegram, в т.ч. `BACKTEST_COMPLETED`), `storage`, `scheduler`;
- `api`: REST (`/api/status`, `/api/signals`, `/api/orders`, `/api/positions`, `/api/chart/{figi}`, `/api/indicators/latest`, `/api/backtests/{id}`), API-key;
- `ui/`: Angular 20 + Taiga UI 4 (standalone, signals, strict TS, Jest), маршруты `/dashboard`, `/chart/:figi`, `/login`; обёртка `CandlestickChartComponent` (lightweight-charts) с линиями Аллигатора и гистограммой AO; генерация TS-клиента из `openapi.yaml`; polling-обновления; тёмная тема night, русская локаль.

**Принцип этапа 3.2:** бэктест-движок этапа 1 максимально переиспользуется; новое — оркестрация запусков (асинхронно, с очередью), API управления бэктестами и UI-витрина. UI остаётся **только чтение/запуск бэктестов** — бэктест не влияет на боевой контур: выполняется в изолированном пуле потоков, не пишет в `orders`/`positions`, не вызывает `BrokerGateway`.

---

## 2. ЗАДАЧА 1: ОРКЕСТРАЦИЯ БЭКТЕСТОВ (бэкенд)

### 2.1. Сервис запусков (`com.traderbro.core.backtest`)

1. **`BacktestOrchestrator`** — фасад над `BacktestRunner`:
   - `UUID submit(BacktestRequest)` — ставит запуск в очередь, возвращает id; статус `QUEUED`;
   - выполнение — в выделенном `ExecutorService` (`backtest.executor-pool-size`, по умолчанию 2; бэктест не должен конкурировать за CPU с торговым контуром — задокументируй);
   - статусы `QUEUED → RUNNING → COMPLETED / FAILED / CANCELLED`; отмена — `cancel(UUID)` через `Future.cancel(true)` + кооперативная проверка прерывания в цикле прогона;
   - каждая смена статуса — запись в БД и событие в шину (для Telegram `BACKTEST_COMPLETED` — уже существует, переиспользуй).
2. **`BacktestRequest`** (Lombok `@Value` + `@Builder`):
   - `List<String> strategyIds` — **несколько стратегий** (мультивыбор; каждая прогоняется независимо в рамках одного запуска — это batch);
   - `List<String> figis` — один или несколько инструментов;
   - `CandleInterval interval`;
   - `Instant from, to`;
   - `BacktestCosts` — комиссия %, проскальзывание б.п. (дефолты из конфига, UI может переопределить);
   - `Map<String, Object> strategyParamsOverrides` — необязательные переопределения параметров стратегий (иначе — параметры из `strategy_config`);
   - `boolean walkForward` + параметры окон (из конфига).
3. **Результат прогона** — расширь существующий `BacktestReport`:
   - `List<TradeRecord> trades` — **каждая сделка**: инструмент, стратегия, направление, время/цена входа, время/цена выхода, объём, P&L (₽ и %), причина выхода (правило/стоп), снапшот индикаторов на входе;
   - `List<SignalRecord> signals` — все сгенерированные сигналы (включая отфильтрованные) с типом ENTRY/EXIT и временем — **это данные для пинов на графике**;
   - equity-кривая (временной ряд `ts → equity`) — для графика доходности в UI;
   - метрики — как в этапе 1.
4. **Хранение**: расширь таблицу `backtest_runs` (Liquibase-changeset): `status`, `request JSONB`, `progress_pct`, `error_message`, `started_at`, `finished_at`; новые таблицы `backtest_trades` (`run_id, figi, strategy_id, direction, entry_ts, entry_price, exit_ts, exit_price, quantity, pnl, pnl_pct, exit_reason`) и `backtest_signals` (`run_id, figi, strategy_id, ts, type, price, verdict, reason`), `backtest_equity` (`run_id, strategy_id, figi, ts, equity`) — или JSONB-массивами в `backtest_runs`, если объёмы умеренные; выбери и обоснуй (ориентир: до ~10⁴ сделок на запуск — JSONB допустим, выше — отдельные таблицы; по умолчанию делай отдельные таблицы с индексами по `run_id`).
5. **Воспроизводимость**: в `request` фиксируются версия кода (git-hash/build), все параметры и диапазон данных; повторный запуск с тем же request должен давать те же метрики (тест).

### 2.2. REST API бэктестов (`com.traderbro.api`)

- `POST /api/backtests` — создать запуск (тело = `BacktestRequest`); валидация (непустые списки, from < to, стратегии/инструменты существуют); ответ `202 Accepted` + `id`;
- `GET /api/backtests` — список запусков (фильтры: статус, дата; пагинация; сортировка по дате desc);
- `GET /api/backtests/{id}` — детали: статус, прогресс, request, сводные метрики по каждой паре (стратегия × инструмент);
- `GET /api/backtests/{id}/results` — полные результаты: метрики по парам, equity-кривые;
- `GET /api/backtests/{id}/signals?figi=&strategyId=` — сигналы прогона (для пинов на графике; лимит + пагинация);
- `GET /api/backtests/{id}/trades?figi=&strategyId=` — сделки прогона;
- `DELETE /api/backtests/{id}` — отмена (если QUEUED/RUNNING) или удаление результата;
- `GET /api/backtests/compare?ids=...` — сравнительная таблица метрик по нескольким запускам (до 5).
- Все эндпоинты — под существующим API-key. Обнови `openapi.yaml` и регенерируй TS-клиент.
- Запуск бэктеста — единственная «пишущая» операция UI; она безопасна (не касается торгового контура) — выдели это в JavaDoc и README.

---

## 3. ЗАДАЧА 2: UI БЭКТЕСТИНГА (Angular + Taiga UI)

### 3.1. Новый маршрут `/backtest` (feature `features/backtest`)

**Форма запуска бэктеста (`BacktestFormComponent`):**
- **Мультивыбор стратегий** — `tuiMultiSelect` (Taiga UI kit) со списком всех зарегистрированных стратегий (из `GET /api/strategies` — добавь эндпоинт: id, имя, описание, текущие параметры); по умолчанию выбраны все активные;
- **Мультивыбор инструментов** — `tuiMultiSelect` с группировкой «Акции» / «Фьючерсы» (из `/api/instruments`); поиск по тикеру;
- Таймфрейм — `tuiSegmented` (15м / 1ч / 1д);
- Период — `tuiInputDateRange` (дефолт: последние 12 месяцев);
- Издержки — `tuiInputNumber` (комиссия %, проскальзывание б.п.) с дефолтами из конфига;
- Чекбокс walk-forward `tuiCheckbox`;
- Кнопка «Запустить» (`tuiButton`, состояние loading) → `POST /api/backtests` → переход к карточке запуска.

**Список запусков (`BacktestListComponent`):** таблица (`addon-table`): дата, стратегии (чипсы `tuiTag`), инструменты, период, статус (`tuiBadge`: QUEUED серый / RUNNING синий с `tuiLoader` / COMPLETED зелёный / FAILED красный / CANCELLED), прогресс-бар (`tuiProgressBar`) для RUNNING, действия (открыть, отменить, удалить). Polling списка — существующий механизм.

**Карточка результата (`BacktestResultComponent`, маршрут `/backtest/:id`):**
- Сводная таблица метрик по парам (стратегия × инструмент): CAGR, Шарп, MaxDD, win-rate, profit factor, число сделок — с выбором строки (радио/чекбоксы) для детализации;
- График equity-кривых выбранных пар (lightweight-charts line series или `tui` line chart — выбери lightweight-charts для консистентности с чартом цен);
- Кнопка «Сравнить с другими запусками» → диалог (`tuiDialog`) выбора до 4 запусков → сравнительная таблица (`GET /api/backtests/compare`);
- Переход «Показать на графике» по выбранной паре → маршрут `/chart/:figi?backtestRun=<id>&strategy=<id>`.

### 3.2. Пины сигналов на графике (`/chart/:figi`)

Доработай существующий `CandlestickChartComponent` и страницу графика:

1. **Источники пинов** — два режима (переключатель `tuiTabs` «Live» / «Бэктест»):
   - Live (текущее поведение): сигналы/сделки из `signals`/`trades`;
   - Бэктест: при наличии query-параметров `backtestRun` — сигналы из `GET /api/backtests/{id}/signals?figi=...&strategyId=...`, сделки из `.../trades`.
2. **Мультивыбор стратегий на графике** — `tuiMultiSelect` над графиком: выбранные стратегии определяют, чьи пины показываются (и в live-, и в бэктест-режиме); цвет пина = цвет стратегии (фиксированная палитра на 8 цветов, маппинг strategyId → цвет в `shared/strategy-colors.ts`, легенда под графиком).
3. **Пины** (lightweight-charts `setMarkers` / `createSeriesMarkers` — проверь актуальный API 5.x, при расхождении `// TODO: verify API`):
   - вход лонг: стрелка вверх под свечой; вход шорт: стрелка вниз над свечой; выход: круг/крест противоположным цветом;
   - отфильтрованные сигналы (verdict FILTERED/REJECTED) — полупрозрачные пины, отдельный чекбокс «Показывать отклонённые» (по умолчанию выкл.);
   - tooltip по пину (hover): стратегия, тип, цена, время МСК, причина/verdict — реализуй через подписку на crosshair/hover lightweight-charts и кастомный overlay, либо всплывающую подсказку рядом с графиком;
   - соединительные линии «вход → выход» одной сделки (бэктест-режим): тонкая пунктирная линия между пинами сделки, зелёная при pnl > 0, красная при pnl < 0 — через `createSeriesMarkers` + отдельную line series или кастомный primitive (при сложности — минимум: пары пинов одного цвета + tooltip с pnl; `TODO` на полноценные линии допустим, но задокументируй).
4. Пины не должны ломать производительность: >500 маркеров — предупреждение и агрегация/пагинация по видимому диапазону.

### 3.3. Навигация

- Добавь пункт «Бэктест» в основное меню/шапку UI (между Dashboard и графиком);
- из сводной таблицы Dashboard — ссылка «Бэктест» на строке инструмента → `/backtest` с предвыбранным инструментом.

---

## 4. ТЕСТЫ (обязательны)

- **Бэкенд**: оркестратор (очередь, отмена, FAILED на битых параметрах, конкурентные запуски в пределах пула); воспроизводимость (два запуска с одним request → одинаковые метрики); REST: валидация `POST /api/backtests` (400 на пустые списки/перевёрнутый период/несуществующую стратегию), пагинация сигналов, compare до 5 id; Testcontainers — запись/чтение `backtest_trades`/`backtest_signals`/`backtest_equity`.
- **Изоляция**: тест, что бэктест не создаёт записей в `orders`/`trades` боевого контура и не вызывает `BrokerGateway` (mock-verify).
- **Фронтенд (Jest)**: форма запуска (мультиселекты обязательны, дефолты, submit → POST с корректным телом); список запусков (рендер статусов, прогресс); маппинг сигналов → маркеры (цвет стратегии, стрелки входа/выхода, полупрозрачность отклонённых, фильтрация по выбранным стратегиям); палитра `strategy-colors` детерминирована.

---

## 5. КОНФИГУРАЦИЯ (дополнения к `application.yml` — сгенерируй полностью)

```yaml
backtest:
  executor-pool-size: 2
  default-commission-pct: 0.05
  default-slippage-bps: 5
  max-signals-per-run: 100000
  results-retention-days: 180
  walk-forward:
    train-days: 180
    test-days: 60
```

---

## 6. ПАРАМЕТРЫ ПО УМОЛЧАНИЮ (проверь и замени)

- Форма бэктеста: все активные стратегии + все инструменты предвыбраны; период — последние 12 месяцев; таймфрейм 1ч; walk-forward выкл.
- Палитра стратегий: 8 фиксированных цветов, детерминированный маппинг по `strategyId`.
- Retention результатов бэктестов: 180 дней (планировщик очистки).

---

## 7. ФОРМАТ ОТВЕТА

1. Резюме изменений (до 1 страницы): новые компоненты, изменения существующих, обратная совместимость с этапами 1–3.
2. Файлы **целиком** с заголовками-путями: бэкенд (`BacktestOrchestrator`, расширение `BacktestReport`, Liquibase-changeset, REST-контроллеры, DTO, обновлённый `openapi.yaml`) → фронтенд (`features/backtest/*`: form/list/result/compare-dialog; доработка `CandlestickChartComponent` и `/chart/:figi`; `strategy-colors.ts`; регенерированный API-клиент) → тесты → обновлённые `application.yml`, README. Без сокращений «// аналогично»; ключевые файлы — полностью.
3. Заверши: инструкцией по проверке (запуск бэктеста «SmaCross + EmaMomentum × SBER, GAZP × 1ч × 12 мес» из UI → статус COMPLETED → карточка с метриками → «Показать на графике» → пины входа/выхода на свечах SBER; отмена RUNNING-запуска; сравнение двух запусков), чек-листом и списком `TODO` (этап 4: realtime-прогресс через WebSocket, линии сделок primitives, Playwright E2E, оптимизация параметров grid-search в UI).

## 8. ОГРАНИЧЕНИЯ

- Бэктест **никогда** не касается боевого контура: отдельный пул потоков, запрет записи в `orders`/`positions`, запрет вызовов `BrokerGateway` — зафиксируй ArchUnit-тестом (`core.backtest` не зависит от `data` и `execution`).
- UI — только чтение + запуск/отмена бэктестов; никакого управления торговлей.
- Фронтенд — только Angular 20+ с Taiga UI 4.x (+ lightweight-charts); никаких других UI-фреймворков.
- Никаких LLM; источник данных — только T-Bank Invest API; Lombok по правилам этапа 1; миграции — только Liquibase YAML; слово `robot` не используется; пакеты — `com.traderbro.*`.
- Не выдумывай API ta4j 0.24.x, Taiga UI 4.x и lightweight-charts 5.x (маркеры перенесены в `createSeriesMarkers` в 5.x — проверь) — при неуверенности `// TODO: verify API` с альтернативой.
- Секреты — только env; API-key UI — только `sessionStorage`.
