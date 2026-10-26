import { ChangeDetectionStrategy, Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { TuiButton, TuiNotification } from '@taiga-ui/core';
import { TuiBadge } from '@taiga-ui/kit';
import { TuiTable, TuiTableTbody, TuiTableTd, TuiTableTh, TuiTableThead, TuiTableTr } from '@taiga-ui/addon-table';
import { firstValueFrom, forkJoin, switchMap, timer } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { DivergenceEventDto, InstrumentDto, LatestIndicatorDto, PositionDto, SignalDto, StatusResponse } from '../../core/models';
import { PricePipe } from '../../shared/pipes/price.pipe';
import { MoneyRubPipe } from '../../shared/pipes/money-rub.pipe';
import { MskTimePipe } from '../../shared/pipes/msk-time.pipe';
import { instrumentLabel as il, instrumentMap } from '../../shared/instrument-label';
import { capLabel as capLabelFn, priceDecimals as priceDecimalsFn, typeLabel as typeLabelFn } from './crypto-format';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [
    TuiButton, TuiNotification, TuiBadge,
    TuiTable, TuiTableThead, TuiTableTbody, TuiTableTr, TuiTableTh, TuiTableTd,
    PricePipe, MoneyRubPipe, MskTimePipe
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="dash-header">
      <div class="brand">TraderBro</div>
      <div class="badges">
        <tui-badge [appearance]="status()?.dataStream?.status === 'OK' ? 'success' : 'error'">
          Stream {{ status()?.dataStream?.status ?? '—' }}
        </tui-badge>
        <tui-badge [appearance]="status()?.broker?.status === 'OK' ? 'success' : 'error'">
          Broker {{ status()?.broker?.status ?? '—' }}
        </tui-badge>
        <tui-badge [appearance]="status()?.db?.status === 'OK' ? 'success' : 'error'">
          DB {{ status()?.db?.status ?? '—' }}
        </tui-badge>
        <tui-badge [appearance]="status()?.killSwitchActive ? 'error' : 'success'">
          {{ status()?.killSwitchActive ? 'KILL-SWITCH' : 'OK' }}
        </tui-badge>
      </div>
      <div class="pnl">
        <span>P&L дня:</span>
        <span [class.tp-profit]="(pnl() ?? 0) >= 0" [class.tp-loss]="(pnl() ?? 0) < 0">
          {{ pnl() | moneyRub }}
        </span>
        <span class="updated">обновлено {{ lastUpdated() | mskTime }}</span>
      </div>
    </header>

    <div class="filter">
      <label>Тип:</label>
      <select #typeSel [value]="typeFilter()" (change)="setTypeFilter(typeSel.value)">
        <option value="">Все</option>
        <option value="SHARE">Акции</option>
        <option value="FUTURE">Фьючерсы</option>
        <option value="CRYPTO">Криптовалюты</option>
      </select>
    </div>

    @if (error()) {
      <tui-notification appearance="error" class="notice">
        Ошибка загрузки данных.
        <button tuiButton appearance="flat" size="s" (click)="reload()">Повторить</button>
      </tui-notification>
    }

    <div class="tables">
      <table tuiTable [columns]="columns" class="inst">
        <thead tuiThead>
          <tr>
            <th tuiTh>Инструмент</th>
            <th tuiTh>Тип</th>
            <th tuiTh>Цена</th>
            <th tuiTh>AO</th>
            <th tuiTh>Фаза</th>
            <th tuiTh>Дивергенция AO</th>
            <th tuiTh>Ангуляция</th>
            <th tuiTh>Капитализация</th>
          </tr>
        </thead>
        <tbody tuiTbody>
          @for (row of filtered(); track row.figi) {
            <tr tuiTr class="tp-table-row" (click)="openChart(row.ticker)">
              <td tuiTd>
                <strong>{{ row.ticker }}</strong>
                <div class="inst-name">{{ row.name }}</div>
              </td>
              <td tuiTd>
                <span class="type-badge" [class.type-crypto]="row.type === 'CRYPTO'"
                  [class.type-future]="row.type === 'FUTURE'">{{ typeLabel(row.type) }}</span>
              </td>
              <td tuiTd>
                {{ row.price | price:priceDecimals(row.type) }}
              </td>
              <td tuiTd>
                <span [class.tp-profit]="(row.ao ?? 0) > 0" [class.tp-loss]="(row.ao ?? 0) < 0">
                  {{ row.ao | price:2 }}
                </span>
              </td>
              <td tuiTd><tui-badge [appearance]="stateAppearance(row.state)">{{ row.state }}</tui-badge></td>
              <td tuiTd>
                @if (row.divergence) {
                  <tui-badge [appearance]="divergenceAppearance(row.divergence)">
                    {{ divergenceLabel(row.divergence) }}
                  </tui-badge>
                } @else {
                  <span class="muted">—</span>
                }
              </td>
              <td tuiTd>
                @if (row.angulationAtr != null) {
                  <tui-badge [appearance]="row.angulationAtr >= 0.1 ? 'success' : 'neutral'">
                    {{ row.angulationAtr.toFixed(2) }} {{ angDir(row.angulationDir) }}
                  </tui-badge>
                } @else {
                  <span class="muted">—</span>
                }
              </td>
              <td tuiTd>
                @if (row.marketCapUsd != null) {
                  <span>{{ capLabel(row.marketCapUsd) }}</span>
                } @else {
                  <span class="muted">—</span>
                }
              </td>
            </tr>
          } @empty {
            <tr tuiTr><td tuiTd colspan="8">Нет инструментов</td></tr>
          }
        </tbody>
      </table>

      <div class="side">
        <section>
          <h3>Позиции</h3>
          <ul>
            @for (p of status()?.positions ?? []; track p.figi) {
              <li>{{ positionLabel(p) }} — {{ p.lots }} лот</li>
            } @empty {
              <li>Нет открытых позиций</li>
            }
          </ul>
        </section>
        <section>
          <h3>Последние сигналы</h3>
          <ul>
            @for (s of signals().slice(0, 20); track s.id) {
              <li>
                <tui-badge [appearance]="s.verdict === 'ACCEPTED' ? 'success' : 'warning'">
                  {{ s.direction }}
                </tui-badge>
                {{ s.strategyId }} {{ instrumentLabel(s.figi, instrMap()) }} · {{ s.price | price }} ({{ s.ts | mskTime }})
              </li>
            } @empty {
              <li>Сигналов нет</li>
            }
          </ul>
        </section>
      </div>
    </div>
  `,
  styles: [`
    .dash-header { display: flex; align-items: center; gap: 1rem; padding: .75rem 1rem; }
    .brand { font-weight: 700; }
    .badges { display: flex; gap: .5rem; flex: 1; flex-wrap: wrap; }
    .pnl { display: flex; gap: .5rem; align-items: baseline; }
    .updated { color: var(--tui-text-secondary); font-size: .8rem; }
    .filter { padding: 0 1rem 1rem; display: flex; gap: .5rem; align-items: center; }
    .notice { margin: 0 1rem 1rem; }
    .inst-name { color: var(--tui-text-secondary); font-size: .78rem; font-weight: 400; }
    .tables { display: grid; grid-template-columns: 1fr 340px; gap: 1rem; padding: 0 1rem; }
    .side { display: flex; flex-direction: column; gap: 1rem; }
    .side ul { list-style: none; margin: 0; padding: 0; }
    .muted { color: var(--tui-text-secondary); }
    .type-badge { display: inline-block; padding: .1rem .4rem; border-radius: var(--tui-radius-m);
      font-size: .72rem; background: var(--tui-background-neutral-1); color: var(--tui-text-primary); }
    .type-crypto { background: #4b2e83; color: #fff; }
    .type-future { background: #1f3a5f; color: #fff; }
    @media (max-width: 1280px) { .tables { grid-template-columns: 1fr; } .tables table { display: block; overflow-x: auto; } }
  `]
})
export class DashboardComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  readonly columns = ['ticker', 'type', 'price', 'ao', 'state', 'divergence', 'angulation', 'cap'];
  readonly typeFilter = signal('');
  readonly status = signal<StatusResponse | null>(null);
  readonly instruments = signal<InstrumentDto[]>([]);
  readonly latest = signal<LatestIndicatorDto[]>([]);
  readonly signals = signal<SignalDto[]>([]);
  readonly divergences = signal<DivergenceEventDto[]>([]);
  readonly error = signal(false);
  readonly lastUpdated = signal<string>(new Date().toISOString());
  readonly filtered = signal<Row[]>([]);

  pnl(): number | null {
    return this.status()?.portfolio?.dayPnl ?? null;
  }

  /** Pure formatting helpers exposed for the template (avoiding name clash with the imports). */
  readonly typeLabel = typeLabelFn;
  readonly priceDecimals = priceDecimalsFn;
  readonly capLabel = capLabelFn;

  /** Exposed for templates; maps a figi to "TICKER — Name". */
  readonly instrumentLabel = il;

  instrMap(): Map<string, InstrumentDto> {
    return instrumentMap(this.instruments());
  }

  /** Label for a position: prefers the broker-provided ticker/name, else the tracked catalog,
   * else the raw figi. */
  positionLabel(p: PositionDto): string {
    if (p.ticker && p.name) {
      return `${p.ticker} — ${p.name}`;
    }
    return this.instrumentLabel(p.figi, this.instrMap());
  }

  ngOnInit(): void {
    timer(0, 10_000).pipe(
      switchMap(() => this.reload()),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe({ error: () => this.error.set(true) });
  }

  async reload(): Promise<void> {
    try {
      const [status, instruments, latest, signals, divergences] = await firstValueFrom(forkJoin([
        this.api.status(),
        this.api.instruments(),
        this.api.latestIndicators('1h'),
        this.api.signals(20),
        this.api.latestDivergences('1h')
      ]));
      this.status.set(status);
      this.instruments.set(instruments);
      this.latest.set(latest);
      this.signals.set(signals);
      this.divergences.set(divergences);
      this.error.set(false);
      this.lastUpdated.set(new Date().toISOString());
      this.buildRows();
    } catch {
      this.error.set(true);
    }
  }

  setTypeFilter(value: string): void {
    this.typeFilter.set(value);
    this.buildRows();
  }

  private buildRows(): void {
    const byFigi = new Map(this.latest().map((l) => [l.figi, l]));
    const divByFigi = new Map(this.divergences().map((d) => [d.figi, d]));
    const rows = this.instruments().map((inst) => {
      const snap = byFigi.get(inst.figi);
      const all = (snap?.alligator ?? {}) as Record<string, unknown>;
      const ao = (snap?.ao ?? {}) as Record<string, unknown>;
      return {
        figi: inst.figi,
        ticker: inst.ticker,
        name: inst.name,
        type: inst.type,
        price: inst.price ?? null,
        marketCapUsd: inst.marketCapUsd ?? null,
        ao: typeof ao['ao'] === 'number' ? ao['ao'] : null,
        state: String(all['state'] ?? 'SLEEPING'),
        divergence: divByFigi.get(inst.figi) ?? null,
        angulationAtr: snap?.angulationAngleAtr ?? null,
        angulationDir: snap?.angulationDirection ?? null
      };
    });
    const tf = this.typeFilter();
    this.filtered.set(rows.filter((r) => !tf || r.type === tf));
  }

  divergenceLabel(d: DivergenceEventDto): string {
    const dir = d.direction === 'BEARISH' ? 'МЕДВЕЖЬЯ' : 'БЫЧЬЯ';
    return `${dir} · ${d.degree === 2 ? 'двойная' : 'одинарная'} · ${d.status}`;
  }

  divergenceAppearance(d: DivergenceEventDto): string {
    if (d.status === 'EXPIRED') { return 'neutral'; }
    if (d.status === 'UNCONFIRMED') { return 'warning'; }
    return d.direction === 'BEARISH' ? 'error' : 'success';
  }

  angDir(dir: string | null): string {
    return dir === 'AWAY_UP' ? '↑' : dir === 'AWAY_DOWN' ? '↓' : '';
  }

  stateAppearance(state: string): string {
    switch (state) {
      case 'EATING_BULL': return 'success';
      case 'EATING_BEAR': return 'error';
      case 'AWAKENING': return 'warning';
      default: return 'neutral';
    }
  }

  openChart(ticker: string): void {
    void this.router.navigate(['/chart', ticker]);
  }
}

interface Row {
  figi: string;
  ticker: string;
  name: string;
  type: string;
  price: number | null;
  marketCapUsd: number | null;
  ao: number | null;
  state: string;
  divergence: DivergenceEventDto | null;
  angulationAtr: number | null;
  angulationDir: string | null;
}