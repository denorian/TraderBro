import { ChangeDetectionStrategy, Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { TuiButton } from '@taiga-ui/core';
import { TuiBadge } from '@taiga-ui/kit';
import { firstValueFrom, Subscription, switchMap, take, timer } from 'rxjs';
import { ApiService } from '../../core/api.service';
import {
  AlligatorPointDto,
  AoPointDto,
  BarDto,
  ChartResponse,
  DivergenceEventDto,
  InstrumentDto,
  SignalRecordDto
} from '../../core/models';
import { CandlestickChartComponent } from './candlestick-chart.component';

type Tf = '15m' | '1h' | '4h' | '1d';

/** Maps a backtest interval enum to the chart timeframe, or undefined if unknown. */
function intervalToTf(interval: string | null): Tf | undefined {
  switch (interval) {
    case 'FIFTEEN_MIN': return '15m';
    case 'ONE_HOUR': return '1h';
    case 'FOUR_HOUR': return '4h';
    case 'ONE_DAY': return '1d';
    default: return undefined;
  }
}

@Component({
  selector: 'app-chart',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink, TuiButton, TuiBadge, CandlestickChartComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="chart-page">
      <header class="chart-header">
        <a tuiButton appearance="flat" size="s" routerLink="/dashboard">← Дашборд</a>
        <div class="title">
          <label class="inst-select-label" for="inst">Инструмент:</label>
          <select id="inst" [formControl]="selControl">
            @for (inst of instruments(); track inst.figi) {
              <option [value]="inst.ticker">{{ inst.ticker }} — {{ inst.name }}</option>
            }
          </select>
          <span tuiBadge appearance="info">{{ intervalLabel() }}</span>
        </div>
        <div class="tf">
          <button tuiButton size="s" [appearance]="tf() === '15m' ? 'primary' : 'flat'"
            (click)="setTf('15m')">15м</button>
          <button tuiButton size="s" [appearance]="tf() === '1h' ? 'primary' : 'flat'"
            (click)="setTf('1h')">1ч</button>
          <button tuiButton size="s" [appearance]="tf() === '4h' ? 'primary' : 'flat'"
            (click)="setTf('4h')">4ч</button>
          <button tuiButton size="s" [appearance]="tf() === '1d' ? 'primary' : 'flat'"
            (click)="setTf('1d')">1д</button>
        </div>
      </header>
      @if (backtestRun()) {
        <div class="signals-bar">
          <span class="muted">Сигналы:</span>
          @for (sid of availableStrategies(); track sid) {
            <label class="row">
              <input type="checkbox" [checked]="selectedStrategies().includes(sid)"
                (change)="toggleStrategy(sid, $event)" />
              <span>{{ sid }}</span>
            </label>
          }
          <label class="row">
            <input type="checkbox" #rejChk [checked]="showRejected()"
              (change)="showRejected.set(rejChk.checked)" />
            <span>Показывать отклонённые</span>
          </label>
          <label class="row">
            <input type="checkbox" #divChk [checked]="showDivergences()"
              (change)="showDivergences.set(divChk.checked)" />
            <span>Дивергенции AO</span>
          </label>
        </div>
      }
      <div class="chart-body">
        @if (loading()) {
          <div class="state">Загрузка…</div>
        } @else if (data()) {
          <app-candlestick-chart [data]="data()" [signals]="signals()"
            [selectedStrategies]="selectedStrategies()" [showRejected]="showRejected()"
            [divergences]="divergences()" [showDivergences]="showDivergences()"
            (loadOlder)="onLoadOlder()"></app-candlestick-chart>
        } @else {
          <div class="state">Нет данных для отображения.</div>
        }
      </div>
    </div>
  `,
  styles: [`
    .chart-page { display: flex; flex-direction: column; height: 100vh; }
    .chart-header { display: flex; align-items: center; gap: 1rem; padding: .5rem 1rem;
      border-bottom: 1px solid var(--tui-border-normal); }
    .title { display: flex; align-items: center; gap: .5rem; flex: 1; }
    .inst-select-label { color: var(--tui-text-secondary); font-size: .85rem; }
    .title select {
      padding: .35rem .5rem;
      border: 1px solid var(--tui-border-normal);
      border-radius: var(--tui-radius-m);
      background: var(--tui-background-base);
      color: var(--tui-text-primary);
    }
    .tf { display: flex; gap: .25rem; }
    .signals-bar { display: flex; align-items: center; gap: 1rem; padding: .35rem 1rem;
      border-bottom: 1px solid var(--tui-border-normal); flex-wrap: wrap; }
    .row { display: flex; align-items: center; gap: .3rem; font-size: .85rem; cursor: pointer; }
    .muted { color: var(--tui-text-secondary); }
    .chart-body { flex: 1; position: relative; min-height: 0; }
    .state { padding: 2rem; text-align: center; color: var(--tui-text-secondary); }
  `]
})
export class ChartComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly api = inject(ApiService);
  private readonly destroyRef = inject(DestroyRef);

  readonly ticker = signal('');
  readonly instruments = signal<InstrumentDto[]>([]);
  readonly tf = signal<Tf>('1h');
  readonly intervalLabel = signal('1ч');
  readonly data = signal<ChartResponse | null>(null);
  readonly loading = signal(true);
  readonly selControl = new FormControl('');

  readonly backtestRun = signal('');
  readonly signals = signal<SignalRecordDto[]>([]);
  readonly selectedStrategies = signal<string[]>([]);
  readonly showRejected = signal(false);
  readonly divergences = signal<DivergenceEventDto[]>([]);
  readonly showDivergences = signal(true);

  readonly availableStrategies = () =>
    [...new Set(this.signals().map((s) => s.strategyId))].sort();

  /** Number of bars fetched per page (initial window and each "older" page). */
  private static readonly PAGE = 500;
  /** Larger page for older-history fetches so one left-pan reaches far back despite the
   * non-trading-hour gaps (a 500-bar window covers only ~3 weeks of 1h bars). */
  private static readonly OLDER_PAGE = 2000;

  /** Accumulated series, oldest→newest, with alligator/ao aligned by index with {@link accBars}. */
  private accBars: BarDto[] = [];
  private accAlligator: AlligatorPointDto[] = [];
  private accAo: AoPointDto[] = [];

  private pollSub: Subscription | null = null;
  private loadingOlder = false;
  private noMoreHistory = false;
  /** Strategy from the ?strategy= query param, kept so signal pins reload on instrument switch. */
  private backtestStrategy: string | null = null;
  /** Start of the backtest period (from ?from=), used to load the full covered range. */
  private backtestFrom: string | null = null;

  ngOnInit(): void {
    // Sync the select with the route param and re-poll on instrument switch (the component is
    // reused across param changes, so ngOnInit does not re-run).
    this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((p) => {
      const ticker = p.get('figi') ?? 'SBER';
      this.ticker.set(ticker);
      this.selControl.setValue(ticker, { emitEvent: false });
      this.startPolling(ticker);
      // The instrument changed — reload the backtest signals for the new ticker (query params
      // like backtestRun/strategy stay unchanged, so queryParamMap won't re-fire on its own).
      this.reloadSignalsForTicker();
    });

    // Backtest mode: when the route carries ?backtestRun=..., load that run's signals to pin.
    this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((qp) => {
      const run = qp.get('backtestRun') ?? '';
      this.backtestRun.set(run);
      this.backtestStrategy = qp.get('strategy');
      if (!run) {
        this.signals.set([]);
        this.backtestFrom = null;
        return;
      }
      // Align the chart timeframe with the backtest run and load its full period so the pins
      // land on matching bars and all of them are visible.
      const from = qp.get('from') || null;
      const tf = intervalToTf(qp.get('interval'));
      const tfChanged = tf && tf !== this.tf();
      this.backtestFrom = from;
      if (tfChanged) {
        this.setTf(tf);
      } else if (from) {
        this.startPolling(this.ticker());
      }
      this.loadBacktestSignals(run, this.backtestStrategy, qp.get('figi'));
    });

    // Navigate when the user picks a different instrument from the select, keeping any backtest
    // query context (backtestRun/strategy) so the pins reload for the new instrument.
    this.selControl.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((v) => {
      if (v && v !== this.ticker()) {
        void this.router.navigate(['/chart', v], { queryParamsHandling: 'preserve' });
      }
    });

    // Load the instrument list for the select; a reactive form control re-applies the selected
    // value once the options are present, so the correct instrument stays selected.
    void firstValueFrom(this.api.instruments())
      .then((list) => this.instruments.set(list))
      .catch(() => undefined);
  }

  toggleStrategy(id: string, event: Event): void {
    const checked = (event.target as HTMLInputElement).checked;
    this.selectedStrategies.set(checked
      ? [...this.selectedStrategies(), id]
      : this.selectedStrategies().filter((v) => v !== id));
  }

  /** Maps a human-readable ticker to its figi from the loaded instrument catalog. */
  private figiForTicker(ticker: string): string | undefined {
    return this.instruments().find((i) => i.ticker === ticker)?.figi;
  }

  /**
   * Re-fetches the active backtest run's signals for the current ticker after the instrument
   * changes. Figi is resolved from the new ticker (ignoring any stale ?figi= from the previous
   * instrument). No-op outside backtest mode.
   */
  private reloadSignalsForTicker(): void {
    const run = this.backtestRun();
    if (!run) {
      return;
    }
    this.loadBacktestSignals(run, this.backtestStrategy, null);
  }

  /**
   * Loads the backtest run's signals for the current instrument. The signals endpoint expects a
   * FIGI; when the URL omits ?figi= we resolve it from the ticker via the instrument catalog
   * (waiting for the catalog if it isn't loaded yet). Falls back to loading unfiltered signals —
   * marker rendering filters them by the chart's figi anyway.
   */
  private loadBacktestSignals(run: string, strategy: string | null, figiParam: string | null): void {
    const load = (figi: string | undefined): void => {
      // Fetch all of the run's signals (no server-side strategy filter): the ?strategy= in the URL
      // may name a strategy that produced zero signals on this timeframe (e.g. divergence
      // detection rarely fires on daily bars), and filtering on the server would return an empty
      // list that hides the other strategies' pins. We filter by strategy client-side below.
      void firstValueFrom(this.api.backtestSignals(run, figi))
        .then((list) => {
          this.signals.set(list);
          const ids = [...new Set(list.map((s) => s.strategyId))].sort();
          // If the ?strategy= from the URL isn't actually among the run's signals, select all
          // strategies instead of a non-matching filter that would hide every pin.
          const selected = strategy && ids.includes(strategy) ? [strategy] : ids;
          this.selectedStrategies.set(selected);
        })
        .catch(() => this.signals.set([]));
    };
    if (figiParam) {
      load(figiParam);
      return;
    }
    const resolved = this.figiForTicker(this.ticker());
    if (resolved) {
      load(resolved);
      return;
    }
    // Instruments not ready yet — wait for them, then resolve and load.
    this.api.instruments().pipe(take(1)).subscribe((list) => {
      this.instruments.set(list);
      load(this.figiForTicker(this.ticker()));
    });
  }

  private startPolling(ticker: string): void {
    this.pollSub?.unsubscribe();
    this.accBars = [];
    this.accAlligator = [];
    this.accAo = [];
    this.noMoreHistory = false;
    this.loadingOlder = false;
    this.loading.set(true);
    this.loadDivergences();
    // In backtest mode load the full period (from the run start) so every signal pin is on the
    // chart; otherwise keep the usual bounded live window. Read ?from= from the route snapshot so
    // the very first poll (fired from the paramMap subscription, before queryParamMap has set
    // this.backtestFrom) already loads the whole period instead of a truncated live window.
    const from = this.route.snapshot.queryParamMap.get('from');
    const limit = from ? 10000 : ChartComponent.PAGE;
    const opts = from ? { from } : undefined;
    this.pollSub = timer(0, 10_000).pipe(
      switchMap(() => this.api.chart(ticker, this.tf(), limit, opts))
    ).subscribe({
      next: (res) => {
        this.mergeLatest(res);
        this.emitData(res);
        this.loading.set(false);
      },
      error: () => this.loading.set(false)
    });
  }

  /**
   * Merges the latest polled window into the accumulator: keeps the older bars already loaded and
   * replaces the trailing tail with the fresh window, so panning-left history isn't lost on poll.
   */
  private mergeLatest(res: ChartResponse): void {
    const win = res.bars;
    if (!win.length) {
      return;
    }
    const firstTs = win[0].ts;
    let keep = 0;
    while (keep < this.accBars.length && this.accBars[keep].ts < firstTs) {
      keep++;
    }
    this.accBars = [...this.accBars.slice(0, keep), ...win];
    this.accAlligator = [...this.accAlligator.slice(0, keep), ...res.alligator];
    this.accAo = [...this.accAo.slice(0, keep), ...res.ao];
  }

  /** Prepend a page of older bars (all strictly before the current oldest) to the accumulator. */
  private prependOlder(res: ChartResponse): void {
    const win = res.bars;
    if (!win.length) {
      return;
    }
    this.accBars = [...win, ...this.accBars];
    this.accAlligator = [...res.alligator, ...this.accAlligator];
    this.accAo = [...res.ao, ...this.accAo];
  }

  private emitData(res: ChartResponse): void {
    this.data.set({
      figi: res.figi,
      interval: res.interval,
      bars: this.accBars,
      alligator: this.accAlligator,
      ao: this.accAo
    });
  }

  /** Fired by the chart when the user pans past the left edge: fetch one more page of older bars. */
  onLoadOlder(): void {
    if (this.loadingOlder || this.noMoreHistory || this.accBars.length === 0) {
      return;
    }
    // Backend range is inclusive, so step one millisecond below the oldest loaded bar.
    const oldest = new Date(this.accBars[0].ts).getTime();
    const to = new Date(oldest - 1).toISOString();
    this.loadingOlder = true;
    void firstValueFrom(this.api.chart(this.ticker(), this.tf(), ChartComponent.OLDER_PAGE, { to }))
      .then((res) => {
        // We only know we've hit the start of history when the backend returns no bars at all.
        // A partial page (< PAGE) is NOT the start — a 500-hour window often yields fewer bars
        // due to non-trading hours even though older history still exists.
        if (res.bars.length === 0) {
          this.noMoreHistory = true;
          return;
        }
        this.prependOlder(res);
        this.emitData(res);
      })
      .catch(() => undefined)
      .finally(() => {
        this.loadingOlder = false;
      });
  }

  setTf(value: Tf): void {
    this.tf.set(value);
    this.intervalLabel.set(value === '15m' ? '15м' : value === '4h' ? '4ч'
      : value === '1d' ? '1д' : '1ч');
    this.loading.set(true);
    // Restart polling so the new timeframe is picked up immediately.
    this.startPolling(this.ticker());
  }

  /** Loads AO "Twin Peaks" divergences for the current instrument/timeframe to pin on the chart. */
  private loadDivergences(): void {
    const figi = this.figiForTicker(this.ticker());
    if (!figi) {
      return;
    }
    void firstValueFrom(this.api.divergences({ figi, interval: this.tf(), limit: 100 }))
      .then((list) => this.divergences.set(list))
      .catch(() => this.divergences.set([]));
  }
}