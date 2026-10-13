import { AfterViewInit, ChangeDetectionStrategy, Component, DestroyRef, ElementRef,
  inject, OnInit, signal, ViewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { TuiButton } from '@taiga-ui/core';
import { TuiBadge } from '@taiga-ui/kit';
import { createChart, IChartApi, LineSeries, UTCTimestamp } from 'lightweight-charts';
import { firstValueFrom, switchMap, timer } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { BacktestRunSummary, CompareRowDto, PairResultDto } from '../../core/models';
import { strategyColor as sc } from '../../shared/strategy-colors';
import { MskTimePipe } from '../../shared/pipes/msk-time.pipe';

@Component({
  selector: 'app-backtest-result',
  standalone: true,
  imports: [TuiButton, TuiBadge, RouterLink, MskTimePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <header class="head">
        <a tuiButton appearance="flat" size="s" routerLink="/backtest">← Список</a>
        <h2>Бэктест {{ runId() }}</h2>
        <tui-badge [appearance]="badge(run()?.status ?? '')">{{ run()?.status ?? '' }}</tui-badge>
      </header>

      <section>
        <h3>Результаты по парам</h3>
        <table class="metrics">
          <thead><tr>
            <th>Стратегия</th><th>Инструмент</th><th>CAGR</th><th>Шарп</th><th>MaxDD</th>
            <th>Win-rate</th><th>Profit factor</th><th>Сделки</th>
          </tr></thead>
          <tbody>
            @for (p of results(); track p.strategyId + p.figi; let i = $index) {
              <tr [class.selected]="i === selected()" (click)="select(i)">
                <td><span [style.color]="strategyColor(p.strategyId)">{{ p.strategyId }}</span></td>
                <td>{{ p.figi }}</td>
                <td>{{ pct(p.metrics.cagr) }}</td>
                <td>{{ p.metrics.sharpe.toFixed(2) }}</td>
                <td>{{ pct(p.metrics.maxDrawdown) }}</td>
                <td>{{ pct(p.metrics.winRate) }}</td>
                <td>{{ num(p.metrics.profitFactor) }}</td>
                <td>{{ p.metrics.numTrades }}</td>
              </tr>
            } @empty {
              <tr><td colspan="8">Нет результатов</td></tr>
            }
          </tbody>
        </table>
      </section>

      @if (selectedPair()) {
        <section class="detail">
          <div class="detail-head">
            <h3>Equity — {{ selectedPair()!.strategyId }} × {{ selectedPair()!.figi }}</h3>
            <button tuiButton appearance="primary" size="s"
              (click)="openOnChart(selectedPair()!)">Показать на графике</button>
          </div>
          <div #equity class="equity"></div>
        </section>
      }

      <section>
        <h3>Сравнить с другими запусками</h3>
        <div class="compare-pick">
          @for (cand of candidates(); track cand.id) {
            <label class="row">
              <input type="checkbox" [checked]="picked().includes(cand.id)"
                [disabled]="!picked().includes(cand.id) && picked().length >= 4"
                (change)="togglePick(cand.id, $event)" />
              <span>{{ cand.id.slice(0, 8) }} — {{ cand.status }} ({{ cand.createdAt | mskTime }})</span>
            </label>
          } @empty {
            <span class="muted">Других запусков нет.</span>
          }
          @if (picked().length > 0) {
            <button tuiButton appearance="secondary" size="s" (click)="compare()">Сравнить</button>
          }
        </div>
        @if (compareRows().length > 0) {
          <table class="metrics">
            <thead><tr>
              <th>Запуск</th><th>Стратегия</th><th>Инструмент</th><th>CAGR</th>
              <th>Шарп</th><th>MaxDD</th><th>Сделки</th>
            </tr></thead>
            <tbody>
              @for (r of compareRows(); track r.runId + r.strategyId + r.figi) {
                <tr>
                  <td>{{ r.runId.slice(0, 8) }}</td>
                  <td>{{ r.strategyId }}</td>
                  <td>{{ r.figi }}</td>
                  <td>{{ pct(r.metrics.cagr) }}</td>
                  <td>{{ r.metrics.sharpe.toFixed(2) }}</td>
                  <td>{{ pct(r.metrics.maxDrawdown) }}</td>
                  <td>{{ r.metrics.numTrades }}</td>
                </tr>
              }
            </tbody>
          </table>
        }
      </section>
    </div>
  `,
  styles: [`
    .page { padding: 1rem 1.5rem; }
    .head { display: flex; align-items: center; gap: .75rem; }
    .metrics { border-collapse: collapse; width: 100%; margin-top: .5rem; }
    .metrics th, .metrics td { border: 1px solid var(--tui-border-normal); padding: .4rem .6rem;
      text-align: left; }
    .metrics tr.selected { background: var(--tui-background-base-alt); cursor: pointer; }
    .metrics tbody tr { cursor: pointer; }
    .detail .detail-head { display: flex; align-items: center; justify-content: space-between; }
    .equity { height: 260px; margin-top: .5rem; }
    .compare-pick { display: flex; flex-direction: column; gap: .35rem; margin-top: .5rem; }
    .row { display: flex; align-items: center; gap: .5rem; }
    .muted { color: var(--tui-text-secondary); }
  `]
})
export class BacktestResultComponent implements OnInit, AfterViewInit {
  @ViewChild('equity', { static: false }) private equityEl?: ElementRef<HTMLDivElement>;

  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly api = inject(ApiService);
  private readonly destroyRef = inject(DestroyRef);

  readonly runId = signal('');
  readonly run = signal<BacktestRunSummary | null>(null);
  readonly results = signal<PairResultDto[]>([]);
  readonly candidates = signal<BacktestRunSummary[]>([]);
  readonly picked = signal<string[]>([]);
  readonly compareRows = signal<CompareRowDto[]>([]);
  readonly selected = signal(0);

  /** Exposed to the template for per-strategy pin colours. */
  readonly strategyColor = sc;

  private chart: IChartApi | null = null;

  readonly selectedPair = () =>
    this.results()[this.selected()] ?? null;

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id') ?? '';
    this.runId.set(id);
    timer(0, 10_000).pipe(
      switchMap(() => this.api.backtest(id)),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe((d) => {
      this.run.set(d.run);
      this.results.set(d.results);
      this.renderEquity();
    });
    this.api.listBacktests({ limit: 50 }).pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((runs) => this.candidates.set(runs.filter((r) => r.id !== id)));
  }

  ngAfterViewInit(): void {
    this.renderEquity();
  }

  select(i: number): void {
    this.selected.set(i);
    this.renderEquity();
  }

  async openOnChart(p: PairResultDto): Promise<void> {
    // The chart route takes a human-readable ticker; map the FIGI to it (fall back to FIGI).
    let ticker = p.figi;
    try {
      const list = await firstValueFrom(this.api.instruments());
      const found = list.find((i) => i.figi === p.figi);
      if (found) {
        ticker = found.ticker;
      }
    } catch {
      // keep FIGI as fallback
    }
    await this.router.navigate(['/chart', ticker], {
      queryParams: { backtestRun: this.runId(), strategy: p.strategyId, figi: p.figi }
    });
  }

  togglePick(id: string, event: Event): void {
    const checked = (event.target as HTMLInputElement).checked;
    if (checked) {
      if (this.picked().length < 4) {
        this.picked.set([...this.picked(), id]);
      }
    } else {
      this.picked.set(this.picked().filter((v) => v !== id));
    }
  }

  async compare(): Promise<void> {
    const ids = [this.runId(), ...this.picked()];
    const rows = await firstValueFrom(this.api.compareBacktests(ids));
    this.compareRows.set(rows);
  }

  badge(status: string): string {
    return status === 'COMPLETED' ? 'success' : status === 'FAILED' ? 'error'
      : status === 'RUNNING' ? 'info' : 'neutral';
  }

  pct(v: number): string {
    return `${(v * 100).toFixed(2)}%`;
  }

  num(v: number): string {
    return Number.isFinite(v) ? v.toFixed(2) : '∞';
  }

  private renderEquity(): void {
    const pair = this.selectedPair();
    const el = this.equityEl?.nativeElement;
    if (!pair || !el) {
      return;
    }
    this.chart?.remove();
    this.chart = createChart(el, {
      autoSize: true,
      layout: { background: { color: '#1a1b25' }, textColor: '#d1d2dc' },
      grid: { vertLines: { color: '#262a3a' }, horzLines: { color: '#262a3a' } },
      timeScale: { borderColor: '#30344c', timeVisible: true }
    });
    const line = this.chart.addSeries(LineSeries, { color: sc(pair.strategyId), lineWidth: 2 });
    line.setData(pair.equity.map((e) => ({
      time: Math.floor(new Date(e.ts).getTime() / 1000) as UTCTimestamp,
      value: e.equity
    })));
  }
}