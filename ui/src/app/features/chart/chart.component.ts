import { ChangeDetectionStrategy, Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { TuiButton } from '@taiga-ui/core';
import { TuiBadge } from '@taiga-ui/kit';
import { firstValueFrom, Subscription, switchMap, timer } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { ChartResponse, InstrumentDto, SignalRecordDto } from '../../core/models';
import { CandlestickChartComponent } from './candlestick-chart.component';

type Tf = '15m' | '1h' | '1d';

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
          <tui-badge appearance="info">{{ intervalLabel() }}</tui-badge>
        </div>
        <div class="tf">
          <button tuiButton size="s" [appearance]="tf() === '15m' ? 'primary' : 'flat'"
            (click)="setTf('15m')">15м</button>
          <button tuiButton size="s" [appearance]="tf() === '1h' ? 'primary' : 'flat'"
            (click)="setTf('1h')">1ч</button>
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
        </div>
      }
      <div class="chart-body">
        @if (loading()) {
          <div class="state">Загрузка…</div>
        } @else if (data()) {
          <app-candlestick-chart [data]="data()" [signals]="signals()"
            [selectedStrategies]="selectedStrategies()" [showRejected]="showRejected()"></app-candlestick-chart>
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

  readonly availableStrategies = () =>
    [...new Set(this.signals().map((s) => s.strategyId))].sort();

  private pollSub: Subscription | null = null;

  ngOnInit(): void {
    // Sync the select with the route param and re-poll on instrument switch (the component is
    // reused across param changes, so ngOnInit does not re-run).
    this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((p) => {
      const ticker = p.get('figi') ?? 'SBER';
      this.ticker.set(ticker);
      this.selControl.setValue(ticker, { emitEvent: false });
      this.startPolling(ticker);
    });

    // Backtest mode: when the route carries ?backtestRun=..., load that run's signals to pin.
    this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((qp) => {
      const run = qp.get('backtestRun') ?? '';
      this.backtestRun.set(run);
      if (!run) {
        this.signals.set([]);
        return;
      }
      const figi = qp.get('figi') ?? this.ticker();
      const strategy = qp.get('strategy');
      void firstValueFrom(this.api.backtestSignals(run, figi, strategy ?? undefined))
        .then((list) => {
          this.signals.set(list);
          const ids = [...new Set(list.map((s) => s.strategyId))].sort();
          this.selectedStrategies.set(strategy ? [strategy] : ids);
        })
        .catch(() => this.signals.set([]));
    });

    // Navigate when the user picks a different instrument from the select.
    this.selControl.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((v) => {
      if (v && v !== this.ticker()) {
        void this.router.navigate(['/chart', v]);
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

  private startPolling(ticker: string): void {
    this.pollSub?.unsubscribe();
    this.loading.set(true);
    this.pollSub = timer(0, 10_000).pipe(
      switchMap(() => this.api.chart(ticker, this.tf(), 500))
    ).subscribe({
      next: (res) => {
        this.data.set(res);
        this.loading.set(false);
      },
      error: () => this.loading.set(false)
    });
  }

  setTf(value: Tf): void {
    this.tf.set(value);
    this.intervalLabel.set(value === '15m' ? '15м' : value === '1d' ? '1д' : '1ч');
    this.loading.set(true);
    // Restart polling so the new timeframe is picked up immediately.
    this.startPolling(this.ticker());
  }
}