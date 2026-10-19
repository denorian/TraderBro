import { ChangeDetectionStrategy, Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { TuiButton } from '@taiga-ui/core';
import { TuiBadge } from '@taiga-ui/kit';
import { switchMap, timer } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { BacktestRunSummary, InstrumentDto } from '../../core/models';
import { MskTimePipe } from '../../shared/pipes/msk-time.pipe';
import { instrumentLabel as il, instrumentMap } from '../../shared/instrument-label';

@Component({
  selector: 'app-backtest-list',
  standalone: true,
  imports: [RouterLink, TuiButton, TuiBadge, MskTimePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <header class="head">
        <h2>Бэктесты</h2>
        <a tuiButton appearance="primary" size="m" routerLink="/backtest/new">+ Новый запуск</a>
      </header>
      <div class="list">
        @for (run of runs(); track run.id) {
          <div class="card">
            <div class="top">
              <strong>{{ run.id.slice(0, 8) }}</strong>
              <tui-badge [appearance]="badge(run.status)">{{ run.status }}</tui-badge>
              <span class="muted">{{ run.createdAt | mskTime }}</span>
            </div>
            <div class="row">Стратегии: <span>{{ run.request.strategyIds.join(', ') }}</span></div>
            <div class="row">Инструменты: <span>{{ figiLabels(run.request.figis) }}</span></div>
            <div class="row">Период: <span>{{ fmtDate(run.request.from) }} → {{ fmtDate(run.request.to) }}
              ({{ run.request.interval }})</span></div>
            @if (run.status === 'RUNNING') {
              <div class="progress">
                <div class="bar"><div class="fill" [style.width.%]="run.progressPct"></div></div>
                <span>{{ run.progressPct }}%</span>
              </div>
            }
            @if (run.errorMessage) {
              <div class="error">{{ run.errorMessage }}</div>
            }
            <div class="actions">
              <button tuiButton appearance="flat" size="s" (click)="open(run.id)">Открыть</button>
              @if (run.status === 'QUEUED' || run.status === 'RUNNING') {
                <button tuiButton appearance="flat" size="s" (click)="cancel(run.id)">Отменить</button>
              }
              @if (run.status === 'COMPLETED' || run.status === 'FAILED' || run.status === 'CANCELLED') {
                <button tuiButton appearance="flat" size="s" (click)="remove(run.id)">Удалить</button>
              }
            </div>
          </div>
        } @empty {
          <div class="muted">Запусков пока нет.</div>
        }
      </div>
    </div>
  `,
  styles: [`
    .page { padding: 1rem 1.5rem; }
    .head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 1rem; }
    .list { display: flex; flex-direction: column; gap: .75rem; max-width: 860px; }
    .card { border: 1px solid var(--tui-border-normal); border-radius: var(--tui-radius-m);
      padding: .75rem 1rem; display: flex; flex-direction: column; gap: .35rem; }
    .top { display: flex; align-items: center; gap: .75rem; }
    .row { color: var(--tui-text-secondary); }
    .muted { color: var(--tui-text-secondary); }
    .error { color: var(--tui-status-negative); }
    .progress { display: flex; align-items: center; gap: .5rem; }
    .bar { flex: 1; height: 6px; background: var(--tui-background-base-alt);
      border-radius: 3px; overflow: hidden; }
    .fill { height: 100%; background: var(--tui-status-info); }
    .actions { display: flex; gap: .25rem; margin-top: .25rem; }
  `]
})
export class BacktestListComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  readonly runs = signal<BacktestRunSummary[]>([]);
  readonly instruments = signal<InstrumentDto[]>([]);

  readonly instrumentLabel = il;

  instrMap(): Map<string, InstrumentDto> {
    return instrumentMap(this.instruments());
  }

  /** Comma-joined human-readable labels for a list of figis. */
  figiLabels(figis: string[]): string {
    return figis.map((f) => this.instrumentLabel(f, this.instrMap())).join(', ');
  }

  ngOnInit(): void {
    timer(0, 10_000).pipe(
      switchMap(() => this.api.listBacktests()),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe((runs) => this.runs.set(runs));
    void this.api.instruments().subscribe((list) => this.instruments.set(list));
  }

  badge(status: string): string {
    switch (status) {
      case 'COMPLETED': return 'success';
      case 'FAILED': return 'error';
      case 'RUNNING': return 'info';
      case 'CANCELLED': return 'neutral';
      default: return 'neutral';
    }
  }

  fmtDate(iso: string): string {
    return new Date(iso).toISOString().slice(0, 10);
  }

  open(id: string): void {
    void this.router.navigate(['/backtest', id]);
  }

  cancel(id: string): void {
    void this.api.deleteBacktest(id).subscribe(() => this.refresh());
  }

  remove(id: string): void {
    void this.api.deleteBacktest(id).subscribe(() => this.refresh());
  }

  private refresh(): void {
    void this.api.listBacktests().subscribe((runs) => this.runs.set(runs));
  }
}