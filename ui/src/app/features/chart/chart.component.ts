import { ChangeDetectionStrategy, Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { TuiButton, TuiBadge } from '@taiga-ui/core';
import { TuiSegmentedModule } from '@taiga-ui/kit';
import { startWith, switchMap, timer } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { ChartResponse } from '../../core/models';
import { CandlestickChartComponent } from './candlestick-chart.component';

type Tf = '15m' | '1h' | '1d';

@Component({
  selector: 'app-chart',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink, TuiButton, TuiBadge, TuiSegmentedModule, CandlestickChartComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="chart-page">
      <header class="chart-header">
        <a tuiButton appearance="flat" size="s" routerLink="/dashboard">← Дашборд</a>
        <div class="title">
          <strong>{{ figi() }}</strong>
          <tui-badge appearance="info">{{ interval() }}</tui-badge>
        </div>
        <tui-segmented class="tf" [formControl]="tfControl">
          <button tuiSegmentedOption value="15m">15м</button>
          <button tuiSegmentedOption value="1h">1ч</button>
          <button tuiSegmentedOption value="1d">1д</button>
        </tui-segmented>
      </header>
      <div class="chart-body">
        @if (loading()) {
          <div class="state">Загрузка…</div>
        } @else if (data()) {
          <app-candlestick-chart [data]="data()"></app-candlestick-chart>
        } @else {
          <div class="state">Нет данных для отображения.</div>
        }
      </div>
    </div>
  `,
  styles: [`
    .chart-page { display: flex; flex-direction: column; height: 100%; }
    .chart-header { display: flex; align-items: center; gap: 1rem; padding: .5rem 1rem; }
    .title { display: flex; align-items: center; gap: .5rem; flex: 1; }
    .chart-body { flex: 1; position: relative; }
    .state { padding: 2rem; text-align: center; color: var(--tui-text-02); }
  `]
})
export class ChartComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(ApiService);
  private readonly destroyRef = inject(DestroyRef);

  readonly figi = signal('');
  readonly interval = signal('1ч');
  readonly data = signal<ChartResponse | null>(null);
  readonly loading = signal(true);
  readonly tfControl = new FormControl<Tf>('1h');

  ngOnInit(): void {
    const figi = this.route.snapshot.paramMap.get('figi') ?? '';
    this.figi.set(figi);
    this.tfControl.setValue('1h');

    this.tfControl.valueChanges.subscribe((tf) => {
      this.interval.set(tf === '15m' ? '15м' : tf === '1d' ? '1д' : '1ч');
      this.loading.set(true);
    });

    timer(0, 10_000).pipe(
      switchMap(() => this.api.chart(figi, this.tfControl.value ?? '1h', 500)),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe({
      next: (res) => {
        this.data.set(res);
        this.loading.set(false);
      },
      error: () => this.loading.set(false)
    });
  }
}