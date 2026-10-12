# Промт: TraderBro — этап 3, индикаторы Alligator + Awesome Oscillator по всем инструментам и веб-UI

> Скопируйте текст ниже (начиная с раздела «РОЛЬ») целиком в AI-ассистента / кодового агента. Промт рассчитан на **доработку существующего проекта** (этапы 1–2: мономодульный Spring Boot, `com.traderbro`, Java 25, Ta4j + T-Bank Invest API, Liquibase YAML, Lombok, фьючерсы, Telegram-уведомления). Если код предыдущих этапов доступен в контексте — опирайся на него; если нет — соблюдай соглашения из раздела 1. Проверьте значения в блоке «ПАРАМЕТРЫ ПО УМОЛЧАНИЮ» перед использованием.

---

## РОЛЬ

Ты — ведущий Java-разработчик (Senior/Lead) с опытом построения торговых систем и лёгких веб-интерфейсов мониторинга. Твоя задача — реализовать **этап 3 проекта TraderBro**: (1) индикаторы Билла Вильямса — **Alligator** и **Awesome Oscillator** — как компоненты Ta4j, вычисляемые по **всем инструментам** (акции и фьючерсы) на всех рабочих таймфреймах; (2) новую стратегию на их основе; (3) **веб-UI** для визуального мониторинга: графики цен с наложенными индикаторами, панель сигналов, позиции и состояние системы. Пишешь production-ready код в соглашениях проекта: строгая типизация, журналирование, никаких заглушек без `TODO:`.

---

## 1. КОНТЕКСТ: ЧТО УЖЕ ЕСТЬ (этапы 1–2 — не ломать)

- Мономодульный Maven-проект **TraderBro**, `com.traderbro`, Java 25, Spring Boot, ta4j-core 0.24.1, Lombok (правила этапа 1), Liquibase YAML;
- `core`: домен (`Instrument` с `InstrumentType {SHARE, FUTURE}` + `FutureSpec`), стратегии `SmaCrossStrategy`, `EmaMomentumStrategy`, `BollingerMeanReversionStrategy`, `DonchianBreakoutStrategy`, `StrategyRegistry` (конфиг из БД, горячая перечитка), `BacktestRunner` (walk-forward, издержки), `RiskGate`;
- `data.tbank`: история + стрим свечей по всем инструментам (акции TQBR и фьючерсы FORTS), справочник с автовыбором фьючерсного контракта;
- `execution`: `OrderManager`, `Reconciler`, `KillSwitch`, `StateRecovery`;
- `notify`: Telegram-бот (20 типов уведомлений, read-only команды);
- `storage`: `bars` (гипертаблица), `signals`, `orders`, `trades`, `instruments`, `strategy_config`, `audit_events`, `notifications`;
- `api`: REST мониторинга (`/api/status`, `/api/signals`, `/api/orders`, kill-switch);
- инструменты: акции `SBER, GAZP, LKOH, YDEX, VTBR, T, PLZL, NVTK`; фьючерсы — автовыбор по `IMOEX (MIX), BR, GOLD, CR`; таймфреймы: 15-мин, 1ч, дневной.

**Принцип этапа 3:** индикаторы — переиспользуемые компоненты `core.indicators`; UI — **Angular SPA с Taiga UI** в подпроекте `ui/`, **только чтение** (мониторинг), никакого управления торговлей из браузера (управление — через существующий REST kill-switch с API-key).

---

## 2. ЗАДАЧА 1: ИНДИКАТОРЫ BILL WILLIAMS ALLIGATOR И AWESOME OSCILLATOR

### 2.1. Реализация в `com.traderbro.core.indicators`

В базовом каталоге ta4j этих индикаторов нет — реализуй как наследники `AbstractIndicator<Num>` / `CachedIndicator<Num>` (кэширование обязательно, как у всех индикаторов ta4j):

1. **`MedianPriceIndicator`** — `(High + Low) / 2` (база для AO и Аллигатора; Вильямс строит оба по медианной цене, не по close).
2. **`AwesomeOscillatorIndicator`** — `AO = SMA(5, median) − SMA(34, median)`. Параметры (5, 34) — конструктором, дефолты Вильямса. Дополнительно реализуй производные признаки, нужные стратегии и UI:
   - цвет бара AO (`aoRising`: текущее значение > предыдущего) — для раскраски гистограммы;
   - детектор «блюдца» (saucer): три бара, средний ниже соседних, все выше нуля (бычий) / зеркально медвежий;
   - пересечение нулевой линии — через `CrossedUpIndicatorRule`/`CrossedDownIndicatorRule` ta4j поверх индикатора.
3. **`AlligatorIndicator`** — составной индикатор, возвращающий три линии (медианная цена, сглаженная SMA, сдвинутая вперёд):
   - **Jaw (челюсть)**: SMA(13) со сдвигом +8 баров;
   - **Teeth (зубы)**: SMA(8) со сдвигом +5 баров;
   - **Lips (губы)**: SMA(5) со сдвигом +3 бара.
   Сдвиг реализуй аккуратно: для аналитики и UI — значение линии на баре `i` = SMA, рассчитанная на баре `i − shift` (стандартная интерпретация «сдвиг вперёд»); следи, чтобы в **боевых сигналах** не было look-ahead: решение на баре `i` использует только значения, вычислимые по данным ≤ `i`. Вынеси это в JavaDoc и покрой тестом `noLookAheadTest` по аналогии с бэктестом.
   Дополнительные признаки:
   - `AlligatorState { SLEEPING, AWAKENING, EATING_BULL, EATING_BEAR }` — классификатор фазы: «спит» — линии переплетены (макс. расстояние между линиями < порога в ATR, параметр `entanglementAtrThreshold`, по умолчанию 0.5×ATR(14)); «ест вверх/вниз» — lips > teeth > jaw (бычий веер) / зеркально;
   - расстояние веера (`fanWidth`) в долях ATR — для UI и фильтров.
4. Регистрация: все три индикатора — Spring-компонентами не делать (это чистые ta4j-объекты, создаются per `BarSeries`); общий код — в фабрике `IndicatorFactory`.

### 2.2. Вычисление по всем инструментам (`IndicatorSnapshotService`)

- Новый сервис `com.traderbro.core.indicators.IndicatorSnapshotService`: по каждой закрытой свече каждого активного инструмента и таймфрейма вычисляет снапшот: значения трёх линий Аллигатора, `AlligatorState`, `fanWidth`, значение AO, `aoRising`, признаки saucer/zero-cross — и пишет в новую таблицу `indicator_snapshots` (`figi, interval, ts, indicator, values JSONB`; гипертаблица или обычная таблица с индексом — реши и обоснуй; вставка идемпотентна `ON CONFLICT DO NOTHING`).
- Liquibase-changeset (YAML, конвенция `id` = `YYYYMMDD-N`) для таблицы.
- Хранить снапшоты двух индикаторов по всем инструментам/таймфреймам; retention — из конфига (`indicators.snapshot-retention-days`, по умолчанию 90).
- Планировщик: расчёт на событии закрытой свечи (переиспользуй механизм этапа 1) + догоняющий прогон при старте, если в `indicator_snapshots` есть пропуски относительно `bars`.

### 2.3. Стратегия `AlligatorAoStrategy` (`com.traderbro.core.strategies`)

Пятая стратегия проекта, по системе Вильямса:

- **Режимный фильтр (вход разрешён)**: `AlligatorState ∈ {AWAKENING, EATING_BULL}` для лонга / `{AWAKENING, EATING_BEAR}` для шорта (шорт — только фьючерсы, через существующий флаг `allow-short`);
- **Триггер входа**: AO пересекает ноль в сторону тренда **или** бычье/медвежье «блюдце» при уже раскрытом веере;
- **Выход**: закрытие цены за линией Teeth (красной) против позиции **или** разворотный saucer AO; стоп — за ближайшим фракталом/экстремумом N баров (параметр `stopLookback`, по умолчанию 5) — реализуй простейший `FractalIndicator` (локальный максимум/минимум по 5 барам) или экстремум-стоп, выбор задокументируй;
- параметры в `strategy_config`: окна AO (5/34), порог переплетения, `stopLookback`, таймфрейм, риск-капитал;
- все обязательства этапа 1: `SignalFilter`, запись сигнала в `signals` со снапшотом индикаторов, сайзинг через `PositionSizer`/`FuturesPositionSizer`;
- бэктест обязателен: прогон по всем инструментам из конфига (дневной и 1ч таймфреймы) с walk-forward; отчёт — в `backtest_runs`; Telegram-уведомление `BACKTEST_COMPLETED` уже существует — переиспользуй.

---

## 3. ЗАДАЧА 2: ВЕБ-UI МОНИТОРИНГА (Angular + Taiga UI)

### 3.1. Технические решения (обязательные)

- **Стек фронтенда**: **Angular 20+** (standalone-компоненты, без NgModules; signals для состояния) + **Taiga UI** (`@taiga-ui/core`, `@taiga-ui/kit`, `@taiga-ui/addon-table`, `@taiga-ui/addon-charts` — последняя стабильная 4.x, зафиксируй версии в `package.json`, без `^` на мажорные обновления) + **lightweight-charts** (npm-пакет `lightweight-charts`, последняя 5.x) для свечного графика — Taiga UI не имеет свечных графиков, поэтому чартинг финансовых серий — через него, обёрнутый в Angular-компонент.
- **Расположение**: фронтенд — подпроект `ui/` в корне TraderBro (рядом с `pom.xml`), собирается Angular CLI. Бэкенд остаётся мономодульным Java-приложением. Интеграция со сборкой: Maven-плагин **frontend-maven-plugin** (com.github.eirslett) — профиль `build-ui`, который ставит Node LTS локально, выполняет `npm ci && npm run build` и копирует `ui/dist/` в `target/classes/static/ui/`; профиль выключен по умолчанию для быстрых бэкенд-сборок, включён в `docker-compose`-образе и релизной сборке.
- **Разработка**: `ng serve` на `:4200` с `proxy.conf.json` → бэкенд `:8080` (проксируются `/api/**`); в проде UI отдаётся Spring'ом как статика по `/ui/**` с fallback на `index.html` для Angular-роутинга (контроллер-редирект или `PathResourceResolver` — реализуй корректно).
- **Только чтение**: UI не содержит кнопок управления торговлей; kill-switch показывается как статус (деактивация — через существующий REST вне UI).
- **Аутентификация**: тот же `X-Api-Key`, что у REST. Экран ввода ключа (компонент Taiga UI `TuiInput` + `tuiButton`); ключ хранится в `sessionStorage`; HTTP-интерцептор Angular добавляет заголовок ко всем запросам и при 401 возвращает на экран ввода. Ключ никогда не попадает в URL и логи.
- **Обновление данных**: polling REST каждые 5–15 с (конфиг) через RxJS `timer` + `switchMap`, с остановкой polling'а на неактивной вкладке (`document.visibilitychange`) — WebSocket не вводим в этом этапе (`TODO(этап 4): realtime push`).
- **Архитектура фронтенда**: feature-структура `ui/src/app/features/{dashboard,chart,signals,positions}` + `core/` (api-сервисы, интерцептор, модели-DTO) + `shared/` (пайпы форматирования, UI-утилиты). Строгий `tsconfig` (`strict: true`). Состояние — signals + RxJS в сервисах, без NgRx (масштаб не требует; `TODO(этап 4)` при росте).
- REST-эндпоинты для UI добавить в `com.traderbro.api` (все GET):
  - `GET /api/instruments` — список инструментов с типом и статусом стрима;
  - `GET /api/chart/{figi}?interval=15m&from=&to=` — свечи + значения Alligator (3 линии) + AO из `bars` и `indicator_snapshots` одним ответом (агрегирующий DTO, лимит баров, по умолчанию последние 500);
  - `GET /api/indicators/latest` — последние снапшоты по всем инструментам (для сводной таблицы);
  - существующие `/api/status`, `/api/signals`, `/api/orders`, `/api/positions` — переиспользовать.
- **OpenAPI**: сгенерируй `openapi.yaml` для UI-эндпоинтов и настрой генерацию TypeScript-клиента (`ng-openapi-gen` или openapi-generator-cli в `npm run gen-api`), чтобы фронт не дублировал DTO руками.

### 3.2. Страницы UI (маршруты Angular)

**Маршрут `/dashboard` (по умолчанию):**
- Шапка (`tuiAppBar` или кастомный header): логотип TraderBro, состояние системы (data-stream, broker, db — бейджи Taiga UI `tuiBadge` зелёный/жёлтый/красный по OK/DEGRADED/DOWN), kill-switch (крупный бейдж), режим sandbox/live, P&L дня (`tuiMoney`-стилизация), время последнего обновления;
- **Сводная таблица по всем инструментам** (`@taiga-ui/addon-table`): тикер, тип (акция/фьючерс — `tuiTag`), последняя цена, изменение за день %, фаза Аллигатора (бейдж: Sleeping — серый / Eating Bull — зелёный / Eating Bear — красный / Awakening — жёлтый), значение AO с цветом (растёт — зелёный, падает — красный), последний сигнал (тип, время, verdict). Сортировка по колонкам (`tuiTableSort`), фильтр по типу инструмента; строка кликабельна → `router.navigate(['/chart', figi])`;
- Панель открытых позиций (`tuiCard`-сетка или компактная таблица): тикер, направление, объём, плавающий P&L;
- Лента последних 20 сигналов (таблица: время, стратегия, инструмент, направление, verdict `tuiBadge`, причина — с `tuiTooltip` для длинных текстов).

**Маршрут `/chart/:figi`:**
- Свечной график (lightweight-charts в обёртке `CandlestickChartComponent`) с переключателем таймфрейма (15м / 1ч / 1д — `tuiSegmented` или `tuiTabs`) и глубины истории;
- наложенные линии Аллигатора: Jaw — синяя, Teeth — красная, Lips — зелёная (классическая раскраска Вильямса), легенда;
- нижняя панель: гистограмма AO (бары зелёные/красные по `aoRising`, нулевая линия) — отдельная серия lightweight-charts с синхронизацией видимого диапазона времени между основным графиком и гистограммой (`subscribeVisibleLogicalRangeChange`);
- маркеры сигналов и сделок на графике (стрелки входа/выхода из `signals`/`trades` с verdict — `setMarkers` lightweight-charts);
- заголовок страницы: тикер, тип, текущая фаза Аллигатора и значение AO (бейджи Taiga UI).

**Маршрут `/login`** — экран ввода API-ключа.

### 3.3. Требования к качеству UI

- **Тема**: тёмная тема Taiga UI по умолчанию (`tuiTheme="night"` / атрибут `[tuiTheme]`), нейтральная палитра, без «кислотных» цветов; акцентные цвета — только семантические (прибыль/убыток/статусы);
- **Локализация**: интерфейс на русском (`TUI_LANGUAGE` = `TUI_RUSSIAN_LANGUAGE` из `@taiga-ui/i18n`); все числа форматируются пайпами: цены — с точностью инструмента, деньги — разделители разрядов + «₽», время — `dd.MM.yyyy HH:mm:ss` МСК (собственные пайпы `PricePipe`, `MoneyRubPipe`, `MskTimePipe` в `shared/`);
- адаптивность: читаемо от 1280px; таблицы — горизонтальный скролл на узких экранах;
- состояния загрузки/ошибки/пустых данных — явные: `tuiLoader` на загрузку, `tuiNotification` + retry-кнопка на ошибку, осмысленный empty-state на пустые данные;
- доступность: семантические заголовки, фокус-стили Taiga UI не переопределять;
- производительность: `ChangeDetectionStrategy.OnPush` везде, виртуализация длинных таблиц (`@taiga-ui/kit` CDK scrolling или `tuiTable` с пагинацией), отписки через `takeUntilDestroyed()`.

---

## 4. ТЕСТЫ (обязательны)

- **Индикаторы**: `AwesomeOscillatorIndicator` и `AlligatorIndicator` — контрольные значения на ручном наборе баров (посчитай ожидаемые значения в тесте явно); граничные случаи (начало серии, `getIndex() < период`); тест отсутствия look-ahead для линий со сдвигом; тест классификатора `AlligatorState` на синтетических сериях (переплетение → SLEEPING, веер → EATING_*).
- **Снапшоты**: идемпотентная запись, догоняющий прогон после «пропуска» баров, retention.
- **Стратегия**: вход только при раскрытом веере + триггер AO; выход за Teeth; стоп по экстремуму; шорт заблокирован для акций; сквозной бэктест с контрольными метриками.
- **REST для UI**: MockMvc-тесты `/api/chart` (структура DTO, лимит баров), `/api/indicators/latest`; проверка API-key на новых эндпоинтах.
- **UI (фронтенд)**: юнит-тесты Angular (Jest или Karma+Jasmine — выбери Jest через `@angular-builders/jest`): пайпы форматирования (`PricePipe`, `MoneyRubPipe`, `MskTimePipe`) с контрольными значениями; api-сервисы с `HttpClientTestingModule` (заголовок `X-Api-Key`, обработка 401); компонент сводной таблицы (сортировка, цветовая логика фаз/AO); обёртка графика (маппинг DTO → серии lightweight-charts, маркеры сигналов) с моком библиотеки. E2E — `TODO(этап 4): Playwright`.
- **Бэкенд для UI**: MockMvc-тесты `/api/chart` (структура DTO, лимит баров), `/api/indicators/latest`; проверка API-key на новых эндпоинтах; тест, что собранный UI отдаётся по `/ui/` и fallback на `index.html` работает для Angular-маршрутов.
- **ArchUnit**: `core.indicators` не зависит от Spring-web и SDK брокера; UI-контроллеры живут только в `api`.

---

## 5. КОНФИГУРАЦИЯ (дополнения к `application.yml` — сгенерируй полностью)

```yaml
indicators:
  alligator:
    jaw-period: 13
    jaw-shift: 8
    teeth-period: 8
    teeth-shift: 5
    lips-period: 5
    lips-shift: 3
    entanglement-atr-threshold: 0.5
  awesome-oscillator:
    fast: 5
    slow: 34
  snapshot-retention-days: 90
strategies:
  alligator-ao:
    enabled: true
    timeframes: [1h, 1d]
    stop-lookback: 5
    risk-capital-pct: 1
    allow-short: true        # только фьючерсы
ui:
  enabled: true
  poll-interval-ms: 10000
  chart-default-bars: 500
  build-profile: build-ui       # Maven-профиль сборки фронтенда
```

---

## 6. ПАРАМЕТРЫ ПО УМОЛЧАНИЮ (проверь и замени)

- Инструменты и таймфреймы — как в этапах 1–2 (8 акций + фьючерсы MIX/BR/GOLD/CR; 15м, 1ч, 1д).
- Снапшоты индикаторов считаются по **всем** инструментам и **всем** рабочим таймфреймам.
- Стратегия `alligator-ao` на старте: таймфреймы 1ч и 1д, включена в sandbox.
- UI: Angular 20 + Taiga UI 4.x, тёмная тема (`night`), русская локаль; включён (`ui.enabled=true`), только чтение; Node LTS для сборки фронтенда подтягивается frontend-maven-plugin'ом.

---

## 7. ФОРМАТ ОТВЕТА

1. Резюме изменений (до 1 страницы): новые компоненты, изменения существующих, обратная совместимость с этапами 1–2.
2. Файлы **целиком** с заголовками-путями (`### src/main/java/com/traderbro/core/indicators/AlligatorIndicator.java`, `### ui/src/app/features/dashboard/dashboard.component.ts`): индикаторы → снапшот-сервис + Liquibase → стратегия → REST-эндпоинты и DTO + OpenAPI → Angular-подпроект `ui/` (`package.json`, `angular.json`, `proxy.conf.json`, app-конфиг с Taiga UI, core-модуль: интерцептор/сервисы/модели, shared-пайпы, features: dashboard, chart, login) → Maven-профиль `build-ui` → тесты (бэкенд и фронтенд) → обновлённые `application.yml` и `docker-compose.yml`. Без сокращений «// аналогично». Ключевые файлы фронтенда (роутинг, интерцептор, chart-компонент, dashboard) — полностью; рутинные шаблоны допустимо давать компактно, но без потери смысла.
3. Заверши: инструкцией по проверке (`npm ci && npm start` в `ui/` для разработки; `mvn -Pbuild-ui package` + запуск → `http://localhost:8080/ui`; дашборд показывает все инструменты, график SBER 1ч с Аллигатором и AO, сигнал стратегии виден маркером на графике и в Telegram), чек-листом и списком `TODO` (этап 4: realtime push, Playwright E2E).

## 8. ОГРАНИЧЕНИЯ

- Никаких LLM (этапы 4+), никаких новых источников данных — только T-Bank Invest API.
- UI — только чтение; никаких кнопок торговли/управления в браузере.
- Фронтенд — **только Angular 20+ с Taiga UI 4.x** (standalone, signals, strict TS); никаких React/Vue/Material/ Bootstrap; из сторонних JS-зависимостей разрешены только Taiga UI, lightweight-charts и инструменты генерации API-клиента.
- Не выдумывай API ta4j 0.24.x (сдвиги индикаторов, `CachedIndicator`), Taiga UI и lightweight-charts — при неуверенности помечай `// TODO: verify API` с альтернативой.
- Lombok — по правилам этапа 1; миграции — только Liquibase YAML; слово `robot` не используется; пакеты — `com.traderbro.*`.
- Look-ahead запрещён: сдвинутые линии Аллигатора в боевых сигналах используют только данные ≤ текущего бара.
- Секреты: API-key UI — только `sessionStorage`; `TELEGRAM_BOT_TOKEN`, `TBANK_TOKEN` — только env; ничего секретного в коде, URL и логах.
