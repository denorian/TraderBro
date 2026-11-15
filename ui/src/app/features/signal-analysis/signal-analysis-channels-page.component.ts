import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TuiButton, TuiNotification } from '@taiga-ui/core';
import { TuiBadge } from '@taiga-ui/kit';
import { interval } from 'rxjs';
import { SignalIngestionStatus } from '../../core/models';
import { ApiService } from '../../core/api.service';
import { SignalAnalysisChannelsStore } from './signal-analysis-channels.store';
import { SignalChannelsTableComponent } from './signal-channels-table.component';

/**
 * «Анализ сигналов» — список Telegram-каналов с настройками мониторинга. Polls ingestion status
 * every 10s and the channel list every 30s; both stop when the component is destroyed.
 */
@Component({
  selector: 'app-signal-analysis-channels-page',
  standalone: true,
  imports: [TuiButton, TuiBadge, TuiNotification, SignalChannelsTableComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <header class="head">
        <div>
          <h1>Каналы Telegram</h1>
          <p class="hint">Сохраняются сообщения только каналов с включённым мониторингом.</p>
        </div>
        <div class="head-right">
          <span tuiBadge [appearance]="globalAppearance()">{{ globalLabel() }}</span>
          <button tuiButton size="s" (click)="onSync()" [disabled]="store.syncing()">
            {{ store.syncing() ? 'Синхронизация…' : 'Обновить список каналов' }}
          </button>
        </div>
      </header>

      @if (store.error()) {
        <div tuiNotification appearance="error" class="err">{{ store.error() }}</div>
      }

      @if (store.status(); as st) {
        @if (st.authorizationState === 'WAIT_CODE' || st.authorizationState === 'WAIT_PASSWORD') {
          <div class="auth-box">
            <div class="auth-title">
              {{ st.authorizationState === 'WAIT_CODE' ? 'Введите код из Telegram' : 'Требуется 2FA-пароль Telegram' }}
            </div>
            <p class="auth-hint">
              Введите {{ st.authorizationState === 'WAIT_CODE' ? 'код подтверждения' : 'пароль двухэтапной авторизации' }}.
              Он передаётся по локальному каналу (127.0.0.1), не сохраняется и не отправляется наружу.
            </p>
            <div class="auth-row">
              <input
                #auth type="{{ st.authorizationState === 'WAIT_PASSWORD' ? 'password' : 'text' }}"
                class="auth-input"
                [placeholder]="st.authorizationState === 'WAIT_CODE' ? 'Код из Telegram' : '2FA-пароль'"
                [disabled]="authSending()" (keydown.enter)="onAuthSubmit(st.authorizationState, auth.value, auth)"
              />
              <button tuiButton size="s" [disabled]="authSending()"
                      (click)="onAuthSubmit(st.authorizationState, auth.value, auth)">Отправить</button>
            </div>
            @if (authError()) {
              <div class="auth-error">{{ authError() }}</div>
            }
          </div>
        }
      }

      <div class="filters">
        <input
          class="search" type="search" placeholder="Поиск по названию / username"
          [value]="store.query()" (input)="store.setQuery($any($event.target).value)"
        />
        <select [value]="store.monitoring()"
                (change)="store.setMonitoringFilter($any($event.target).value)">
          <option value="all">Все каналы</option>
          <option value="on">Мониторируемые</option>
          <option value="off">Не мониторируемые</option>
        </select>
        <select [value]="store.archived()"
                (change)="store.setArchivedFilter($any($event.target).value)">
          <option value="all">В основном списке и в архиве</option>
          <option value="main">В основном списке</option>
          <option value="archived">В архиве</option>
        </select>
        <select [value]="store.ingestionStatus()"
                (change)="store.setStatusFilter($any($event.target).value)">
          <option value="">Любой статус</option>
          @for (s of statuses; track s) {
            <option [value]="s">{{ statusLabel(s) }}</option>
          }
        </select>
        <button tuiButton size="s" appearance="secondary" (click)="store.resetFilters()">
          Сбросить
        </button>
      </div>

      <div class="note">
        История изменений доступна с момента наблюдения TraderBro. При первичной загрузке
        сохраняется текущая версия сообщения; прежние редакции и уже удалённые сообщения Telegram
        может не предоставить.
      </div>

      @if (store.selected().length > 0) {
        <div class="bulk">
          <span class="bulk-count">Выбрано каналов: {{ store.selected().length }}</span>
          <button tuiButton size="s" appearance="primary" [disabled]="store.bulkBusy()"
                  (click)="onBulk(true)">Включить мониторинг</button>
          <button tuiButton size="s" appearance="secondary" [disabled]="store.bulkBusy()"
                  (click)="onBulk(false)">Выключить</button>
          <button tuiButton size="s" appearance="secondary" [disabled]="store.bulkBusy()"
                  (click)="store.clearSelection()">Снять выбор</button>
        </div>
      }

      @if (bulkMessage()) {
        <div tuiNotification appearance="info" class="bulk-msg">{{ bulkMessage() }}</div>
      }

      @if (store.loading()) {
        <div class="loading">Загрузка…</div>
      } @else if (store.page()?.items?.length === 0) {
        <div class="empty">Нет подписок / ничего не найдено.</div>
      } @else {
        <app-signal-channels-table [channels]="store.page()!.items"></app-signal-channels-table>
      }

      @if (store.page(); as p) {
        <footer class="pager">
          <span>{{ p.page + 1 }} из {{ totalPages(p.total) }} — {{ p.total }} каналов</span>
          <button tuiButton size="s" appearance="secondary" [disabled]="p.page === 0"
                  (click)="store.setPage(p.page - 1)">Назад</button>
          <button tuiButton size="s" appearance="secondary"
                  [disabled]="p.page + 1 >= totalPages(p.total)"
                  (click)="store.setPage(p.page + 1)">Вперёд</button>
        </footer>
      }
    </div>
  `,
  styles: [`
    .page { padding: 1rem 1.5rem; max-width: 1200px; }
    .head { display: flex; justify-content: space-between; align-items: center; gap: 1rem; }
    h1 { margin: 0; font-size: 1.4rem; color: var(--tui-text-primary); }
    .hint { margin: .25rem 0 0; color: var(--tui-text-secondary); font-size: .85rem; }
    .head-right { display: flex; align-items: center; gap: .75rem; }
    .filters { display: flex; gap: .5rem; align-items: center; margin: 1rem 0; flex-wrap: wrap; }
    .search { flex: 1; min-width: 16rem; padding: .5rem .6rem;
      background: var(--tui-background-base); color: var(--tui-text-primary);
      border: 1px solid var(--tui-border-normal); border-radius: .5rem; }
    select { padding: .5rem .6rem; background: var(--tui-background-base);
      color: var(--tui-text-primary); border: 1px solid var(--tui-border-normal);
      border-radius: .5rem; }
    .note { font-size: .8rem; color: var(--tui-text-secondary); margin-bottom: .75rem; }
    .err { margin-bottom: .75rem; }
    .auth-box { margin-bottom: .75rem; padding: .75rem 1rem; border: 1px solid
      var(--tui-status-warning); border-radius: .5rem; background: var(--tui-background-neutral-1); }
    .auth-title { font-weight: 600; margin-bottom: .25rem; }
    .auth-hint { margin: 0 0 .5rem; color: var(--tui-text-secondary); font-size: .85rem; }
    .auth-row { display: flex; gap: .5rem; align-items: center; }
    .auth-input { flex: 1; max-width: 20rem; padding: .5rem .6rem;
      background: var(--tui-background-base); color: var(--tui-text-primary);
      border: 1px solid var(--tui-border-normal); border-radius: .5rem; }
    .auth-error { margin-top: .5rem; color: var(--tui-text-negative); font-size: .85rem; }
    .bulk { display: flex; align-items: center; gap: .75rem; padding: .5rem .75rem; margin-bottom: .75rem;
      background: var(--tui-background-neutral-1); border-radius: .5rem; }
    .bulk-count { font-weight: 500; margin-right: .5rem; }
    .bulk-msg { margin-bottom: .75rem; }
    .loading, .empty { color: var(--tui-text-secondary); padding: 1.5rem 0; text-align: center; }
    .pager { display: flex; align-items: center; gap: .75rem; justify-content: flex-end;
      margin-top: .75rem; color: var(--tui-text-secondary); font-size: .85rem; }
  `]
})
export class SignalAnalysisChannelsPageComponent implements OnInit {
  readonly store = inject(SignalAnalysisChannelsStore);
  private readonly api = inject(ApiService);
  private readonly destroyRef = inject(DestroyRef);
  readonly bulkMessage = signal<string | null>(null);
  readonly authSending = signal(false);
  readonly authError = signal<string | null>(null);

  readonly statuses: SignalIngestionStatus[] = [
    'DISABLED', 'WAITING_AUTH', 'PENDING', 'BACKFILL', 'ACTIVE', 'DEGRADED', 'UNAVAILABLE', 'ERROR'
  ];

  constructor() {
    interval(10_000).pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => void this.store.refreshStatus());
    interval(30_000).pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => void this.store.load());
  }

  ngOnInit(): void {
    void this.store.refreshStatus();
    void this.store.load();
  }

  onSync(): void {
    void this.store.syncChannels();
  }

  async onBulk(enabled: boolean): Promise<void> {
    const result = await this.store.applyBulkMonitoring(enabled);
    if (result) {
      const skipped = result.skippedUnsubscribed > 0
        ? `, пропущено отписавшихся: ${result.skippedUnsubscribed}` : '';
      this.bulkMessage.set(`Применено к ${result.applied} каналам${skipped}`);
    }
  }

  /** Submit the login code or 2FA password over the local WebSocket; clears the field. */
  onAuthSubmit(state: string, value: string, input: HTMLInputElement): void {
    if (!value || this.authSending()) { return; }
    const type = state === 'WAIT_PASSWORD' ? 'password' : 'code';
    this.authSending.set(true);
    this.authError.set(null);
    const ws = new WebSocket(this.api.signalAuthWebSocketUrl());
    ws.onopen = () => ws.send(JSON.stringify({ type, value }));
    ws.onmessage = (event) => {
      try {
        const res = JSON.parse(event.data as string) as { ok?: boolean; error?: string };
        if (res.ok) {
          input.value = '';
          void this.store.refreshStatus();
        } else {
          this.authError.set(res.error === 'EMPTY' ? 'Пустое значение'
            : res.error === 'NOT_WAITING' ? 'Уже не требуется'
            : 'Не удалось отправить');
        }
      } catch {
        this.authError.set('Не удалось прочитать ответ');
      }
      this.authSending.set(false);
      ws.close();
    };
    ws.onerror = () => {
      this.authError.set('Не удалось подключиться к локальному каналу (127.0.0.1:8080). '
        + 'Бэкенд запущен?');
      this.authSending.set(false);
    };
  }

  totalPages(total: number): number {
    return Math.max(1, Math.ceil(total / this.store.pageSize()));
  }

  globalLabel(): string {
    const s = this.store.status();
    if (!s?.enabled) { return 'Выключен'; }
    if (s.authorizationState !== 'READY') { return 'Требуется авторизация'; }
    return s.ingestionActive ? 'Подключён' : 'Недоступен';
  }

  globalAppearance(): string {
    const s = this.store.status();
    if (!s?.enabled) { return 'neutral'; }
    if (s.authorizationState !== 'READY') { return 'warning'; }
    return s.ingestionActive ? 'positive' : 'neutral';
  }

  statusLabel(s: SignalIngestionStatus): string {
    return s === 'DISABLED' ? 'Выключен'
      : s === 'WAITING_AUTH' ? 'Нужна авторизация'
      : s === 'PENDING' ? 'Ожидание'
      : s === 'BACKFILL' ? 'Загрузка истории'
      : s === 'ACTIVE' ? 'Активен'
      : s === 'DEGRADED' ? 'Неполный'
      : s === 'UNAVAILABLE' ? 'Недоступен'
      : 'Ошибка';
  }
}