import { ChangeDetectionStrategy, Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { TuiButton } from '@taiga-ui/core';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { InstrumentDto, StrategyDto } from '../../core/models';

type Tf = 'FIFTEEN_MIN' | 'ONE_HOUR' | 'FOUR_HOUR' | 'ONE_DAY';

interface ParamOverride {
  key: string;
  value: string;
}

@Component({
  selector: 'app-backtest-form',
  standalone: true,
  imports: [TuiButton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <h2>Запуск бэктеста</h2>
      <div class="card">
        <fieldset>
          <legend>Стратегии</legend>
          @for (s of strategies(); track s.id) {
            <label class="row">
              <input type="checkbox" [checked]="selectedStrategies().includes(s.id)"
                (change)="toggle(selectedStrategies, s.id, $event)" />
              <span>{{ s.name }}</span>
            </label>
          } @empty {
            <span class="muted">Нет стратегий</span>
          }
        </fieldset>

        <fieldset>
          <legend>Инструменты</legend>
          <div class="group">
            <div class="group-title">Акции</div>
            @for (inst of shares(); track inst.figi) {
              <label class="row">
                <input type="checkbox" [checked]="selectedInstruments().includes(inst.figi)"
                  (change)="toggle(selectedInstruments, inst.figi, $event)" />
                <span>{{ inst.ticker }} — {{ inst.name }}</span>
              </label>
            } @empty {
              <span class="muted">Нет акций</span>
            }
          </div>
          <div class="group">
            <div class="group-title">Фьючерсы</div>
            @for (inst of futures(); track inst.figi) {
              <label class="row">
                <input type="checkbox" [checked]="selectedInstruments().includes(inst.figi)"
                  (change)="toggle(selectedInstruments, inst.figi, $event)" />
                <span>{{ inst.ticker }} — {{ inst.name }}</span>
              </label>
            } @empty {
              <span class="muted">Нет фьючерсов</span>
            }
          </div>
          <div class="group">
            <div class="group-title">Криптовалюты</div>
            @for (inst of cryptos(); track inst.figi) {
              <label class="row">
                <input type="checkbox" [checked]="selectedInstruments().includes(inst.figi)"
                  (change)="toggle(selectedInstruments, inst.figi, $event)" />
                <span>{{ inst.ticker }} — {{ inst.name }}</span>
              </label>
            } @empty {
              <span class="muted">Нет криптовалют</span>
            }
          </div>
        </fieldset>

        <div class="field">
          <label>Таймфрейм</label>
          <div class="tf">
            <button tuiButton size="s" [appearance]="tf() === 'FIFTEEN_MIN' ? 'primary' : 'flat'"
              (click)="tf.set('FIFTEEN_MIN')">15м</button>
            <button tuiButton size="s" [appearance]="tf() === 'ONE_HOUR' ? 'primary' : 'flat'"
              (click)="tf.set('ONE_HOUR')">1ч</button>
            <button tuiButton size="s" [appearance]="tf() === 'FOUR_HOUR' ? 'primary' : 'flat'"
              (click)="tf.set('FOUR_HOUR')">4ч</button>
            <button tuiButton size="s" [appearance]="tf() === 'ONE_DAY' ? 'primary' : 'flat'"
              (click)="tf.set('ONE_DAY')">1д</button>
          </div>
        </div>

        <div class="row2">
          <div class="field">
            <label>От</label>
            <input type="date" #f [value]="from()" (input)="from.set(f.value)" />
          </div>
          <div class="field">
            <label>До</label>
            <input type="date" #t [value]="to()" (input)="to.set(t.value)" />
          </div>
        </div>

        <div class="row2">
          <div class="field">
            <label>Комиссия, %</label>
            <input type="number" step="0.01" #c [value]="commissionPct()"
              (input)="commissionPct.set(parseNum(c.value))" />
          </div>
          <div class="field">
            <label>Проскальзывание, б.п.</label>
            <input type="number" step="1" #s [value]="slippageBps()"
              (input)="slippageBps.set(parseNum(s.value))" />
          </div>
        </div>

        <div class="row2">
          <div class="field">
            <label>Стартовый капитал</label>
            <input type="number" step="10000" #cap [value]="initialCapital()"
              (input)="initialCapital.set(parseNum(cap.value))" />
          </div>
          <div class="field">
            <label>Макс. позиция, %</label>
            <input type="number" step="1" #mp [value]="maxPositionPct()"
              (input)="maxPositionPct.set(parseNum(mp.value))" />
          </div>
        </div>

        <label class="row">
          <input type="checkbox" [checked]="walkForward()" (change)="toggleWalkForward($event)">
          Walk-forward оптимизация
        </label>

        <fieldset>
          <legend>Параметры стратегий (опционально)</legend>
          <p class="hint">Для каждой выбранной стратегии можно переопределить её параметры.</p>
          @for (s of strategies(); track s.id) {
            @if (selectedStrategies().includes(s.id)) {
              <div class="strategy-params">
                <div class="strategy-title">{{ s.name }} <span class="id">{{ s.id }}</span></div>
                <div class="param-row">
                  <input type="text" #pname placeholder="ключ (например fast)" />
                  <input type="text" #pval placeholder="значение (например 5)" />
                  <button tuiButton size="s" appearance="flat" type="button"
                    (click)="addParam(s.id, pname.value, pval.value, pname, pval)">+</button>
                </div>
                @for (ov of paramOverrides()[s.id] ?? []; track ov.key) {
                  <div class="param-chip">
                    {{ ov.key }} = {{ ov.value }}
                    <button class="chip-remove" type="button"
                      (click)="removeParam(s.id, ov.key)">✕</button>
                  </div>
                }
              </div>
            }
          }
        </fieldset>

        <div class="actions">
          <button tuiButton appearance="primary" size="m"
            [disabled]="submitting() || selectedStrategies().length === 0 || selectedInstruments().length === 0"
            (click)="submit()">{{ submitting() ? 'Запуск…' : 'Запустить' }}</button>
          @if (error()) {
            <span class="error">{{ error() }}</span>
          }
        </div>
      </div>
    </div>
  `,
  styles: [`
    .page { padding: 1rem 1.5rem; max-width: 720px; }
    .card { display: flex; flex-direction: column; gap: 1rem; }
    fieldset { border: 1px solid var(--tui-border-normal); border-radius: var(--tui-radius-m);
      padding: .75rem 1rem; }
    legend { color: var(--tui-text-secondary); padding: 0 .25rem; }
    .row { display: flex; align-items: center; gap: .5rem; padding: .15rem 0; cursor: pointer; }
    .group { display: flex; flex-direction: column; gap: .1rem; }
    .group + .group { margin-top: .6rem; }
    .group-title { color: var(--tui-text-secondary); font-size: .85rem; font-weight: 600;
      margin-bottom: .15rem; }
    .row2 { display: flex; gap: 1rem; }
    .field { display: flex; flex-direction: column; gap: .25rem; flex: 1; }
    .field input { padding: .35rem .5rem; border: 1px solid var(--tui-border-normal);
      border-radius: var(--tui-radius-m); background: var(--tui-background-base);
      color: var(--tui-text-primary); }
    .tf { display: flex; gap: .25rem; }
    .actions { display: flex; align-items: center; gap: 1rem; }
    .error { color: var(--tui-status-negative); }
    .muted { color: var(--tui-text-secondary); }
    .hint { color: var(--tui-text-secondary); font-size: .85rem; margin: 0 0 .5rem; }
    .strategy-params { display: flex; flex-direction: column; gap: .35rem; padding: .5rem 0;
      border-bottom: 1px dashed var(--tui-border-normal); }
    .strategy-title { font-weight: 600; }
    .strategy-title .id { color: var(--tui-text-secondary); font-weight: 400; font-size: .8rem;
      margin-left: .35rem; }
    .param-row { display: flex; gap: .35rem; align-items: center; }
    .param-row input { flex: 1; padding: .3rem .5rem; border: 1px solid var(--tui-border-normal);
      border-radius: var(--tui-radius-m); background: var(--tui-background-base);
      color: var(--tui-text-primary); }
    .param-chip { display: inline-flex; align-items: center; gap: .4rem; padding: .15rem .5rem;
      background: var(--tui-background-neutral-1); border-radius: var(--tui-radius-s);
      font-size: .85rem; width: fit-content; }
    .chip-remove { background: none; border: none; cursor: pointer; color: var(--tui-text-secondary);
      padding: 0 .1rem; }
  `]
})
export class BacktestFormComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  readonly strategies = signal<StrategyDto[]>([]);
  readonly instruments = signal<InstrumentDto[]>([]);
  readonly selectedStrategies = signal<string[]>([]);
  readonly selectedInstruments = signal<string[]>([]);
  readonly tf = signal<Tf>('ONE_HOUR');
  readonly from = signal<string>(this.monthsAgo(12));
  readonly to = signal<string>(this.today());
  readonly commissionPct = signal(0.05);
  readonly slippageBps = signal(5);
  readonly initialCapital = signal(1000000);
  readonly maxPositionPct = signal(95);
  readonly walkForward = signal(false);
  readonly submitting = signal(false);
  readonly error = signal('');
  /** Per-strategy parameter overrides: strategyId -> list of {key, value}. */
  readonly paramOverrides = signal<Record<string, ParamOverride[]>>({});

  readonly shares = () => this.instruments().filter((i) => i.type === 'SHARE');
  readonly futures = () => this.instruments().filter((i) => i.type === 'FUTURE');
  readonly cryptos = () => this.instruments().filter((i) => i.type === 'CRYPTO');

  ngOnInit(): void {
    this.api.strategies().pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((s) => this.strategies.set(s));
    this.api.instruments().pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((ins) => this.instruments.set(ins));
  }

  toggle(signalList: ReturnType<typeof signal<string[]>>, value: string, event: Event): void {
    const checked = (event.target as HTMLInputElement).checked;
    signalList.set(checked
      ? [...signalList(), value]
      : signalList().filter((v) => v !== value));
  }

  toggleWalkForward(event: Event): void {
    this.walkForward.set((event.target as HTMLInputElement).checked);
  }

  parseNum(v: string): number {
    return Number(v);
  }

  addParam(strategyId: string, key: string, value: string,
           keyInput: HTMLInputElement, valueInput: HTMLInputElement): void {
    if (!key.trim()) {
      return;
    }
    this.paramOverrides.update((m) => {
      const list = m[strategyId] ?? [];
      return { ...m, [strategyId]: [...list.filter((p) => p.key !== key), { key: key.trim(), value: value }] };
    });
    keyInput.value = '';
    valueInput.value = '';
  }

  removeParam(strategyId: string, key: string): void {
    this.paramOverrides.update((m) => {
      const list = m[strategyId] ?? [];
      const rest = list.filter((p) => p.key !== key);
      if (rest.length === 0) {
        const { [strategyId]: _omit, ...others } = m;
        return others;
      }
      return { ...m, [strategyId]: rest };
    });
  }

  private strategyOverridesPayload(): Record<string, Record<string, unknown>> | undefined {
    const overrides = this.paramOverrides();
    const entries = Object.entries(overrides).filter(([, list]) => list.length > 0);
    if (entries.length === 0) {
      return undefined;
    }
    return Object.fromEntries(
      entries.map(([id, list]) => [id, Object.fromEntries(list.map((p) => [p.key, p.value]))])
    );
  }

  async submit(): Promise<void> {
    const fromDate = new Date(`${this.from()}T00:00:00Z`);
    const toDate = new Date(`${this.to()}T23:59:59Z`);
    if (fromDate >= toDate) {
      this.error.set('Дата «От» должна быть раньше «До».');
      return;
    }
    this.error.set('');
    this.submitting.set(true);
    try {
      const res = await firstValueFrom(this.api.createBacktest({
        strategyIds: this.selectedStrategies(),
        figis: this.selectedInstruments(),
        interval: this.tf(),
        from: fromDate.toISOString(),
        to: toDate.toISOString(),
        costs: {
          commissionPct: this.commissionPct() / 100,
          slippageBps: this.slippageBps(),
          initialCapital: this.initialCapital(),
          maxPositionPct: this.maxPositionPct() / 100
        },
        strategyParamsOverrides: this.strategyOverridesPayload(),
        walkForward: this.walkForward()
      }));
      await this.router.navigate(['/backtest', res.id]);
    } catch (e) {
      this.error.set('Не удалось запустить бэктест.');
      this.submitting.set(false);
    }
  }

  private today(): string {
    return new Date().toISOString().slice(0, 10);
  }

  private monthsAgo(n: number): string {
    const d = new Date();
    d.setMonth(d.getMonth() - n);
    return d.toISOString().slice(0, 10);
  }
}