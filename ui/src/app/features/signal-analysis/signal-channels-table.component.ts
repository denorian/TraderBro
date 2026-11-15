import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';
import { DatePipe } from '@angular/common';
import { TuiTable, TuiTableTbody, TuiTableTd, TuiTableTh, TuiTableThead, TuiTableTr } from '@taiga-ui/addon-table';
import { SignalChannelDto } from '../../core/models';
import { SignalAnalysisChannelsStore } from './signal-analysis-channels.store';
import { SignalChannelMonitoringToggleComponent } from './signal-channel-monitoring-toggle.component';
import { SignalIngestionStatusBadgeComponent } from './signal-ingestion-status-badge.component';

/** Channel table for the "Анализ сигналов" page: multi-select, metadata, monitoring, status. */
@Component({
  selector: 'app-signal-channels-table',
  standalone: true,
  imports: [
    TuiTable, TuiTableThead, TuiTableTbody, TuiTableTr, TuiTableTh, TuiTableTd,
    DatePipe,
    SignalChannelMonitoringToggleComponent,
    SignalIngestionStatusBadgeComponent
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <table tuiTable class="channels-table">
      <thead tuiThead>
        <tr>
          <th tuiTh>
            <input type="checkbox" class="sel"
              [checked]="store.allPageSelected()"
              (change)="store.toggleSelectPage()"
              title="Выбрать все на странице" />
          </th>
          <th tuiTh>Канал</th>
          <th tuiTh>Подписка</th>
          <th tuiTh>Мониторинг</th>
          <th tuiTh>Статус</th>
          <th tuiTh>Сообщений</th>
          <th tuiTh>Изменено</th>
          <th tuiTh>Удалено</th>
          <th tuiTh>Последнее сообщение</th>
          <th tuiTh>Последняя синхр.</th>
          <th tuiTh>Ошибка</th>
        </tr>
      </thead>
      <tbody tuiTbody>
        @for (ch of channels(); track ch.id) {
          <tr tuiTr [class.selected]="store.isSelected(ch.id)">
            <td tuiTd>
              <input type="checkbox" class="sel"
                [checked]="store.isSelected(ch.id)"
                (change)="store.toggleSelect(ch.id, $any($event.target).checked)"
                [title]="ch.title" />
            </td>
            <td tuiTd>
              <div class="chan-title" [title]="ch.title">{{ ch.title }}</div>
              <div class="chan-username">{{ ch.username ?? 'Приватный канал' }}</div>
            </td>
            <td tuiTd>{{ subLabel(ch) }}</td>
            <td tuiTd>
              <app-signal-channel-monitoring-toggle [channel]="ch"></app-signal-channel-monitoring-toggle>
            </td>
            <td tuiTd>
              <app-signal-ingestion-status-badge [status]="ch.ingestionStatus"></app-signal-ingestion-status-badge>
            </td>
            <td tuiTd>{{ ch.messageCount }}</td>
            <td tuiTd>{{ ch.editedMessageCount }}</td>
            <td tuiTd>{{ ch.deletedMessageCount }}</td>
            <td tuiTd>{{ ch.lastMessageAt ? (ch.lastMessageAt | date: 'dd.MM.yyyy HH:mm') : '—' }}</td>
            <td tuiTd>{{ ch.lastSyncAt ? (ch.lastSyncAt | date: 'dd.MM.yyyy HH:mm') : '—' }}</td>
            <td tuiTd [title]="ch.lastErrorCode ?? ''">{{ ch.lastErrorCode ?? '' }}</td>
          </tr>
        } @empty {
          <tr tuiTr><td tuiTd [colSpan]="11" class="empty">Нет каналов</td></tr>
        }
      </tbody>
    </table>
  `,
  styles: [`
    :host { display: block; }
    tr.selected { background: var(--tui-background-neutral-1); }
    input.sel { width: 1rem; height: 1rem; accent-color: var(--tui-background-accent); cursor: pointer; }
    .chan-title { font-weight: 500; max-width: 21rem; overflow: hidden; text-overflow: ellipsis;
      white-space: nowrap; }
    .chan-username { color: var(--tui-text-secondary); font-size: .8rem;
      max-width: 21rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .empty { text-align: center; color: var(--tui-text-secondary); }
  `]
})
export class SignalChannelsTableComponent {
  readonly channels = input.required<SignalChannelDto[]>();
  readonly store = inject(SignalAnalysisChannelsStore);

  subLabel(ch: SignalChannelDto): string {
    if (!ch.subscribed) { return 'Отписан'; }
    return ch.archived ? 'Архив' : 'Активна';
  }
}