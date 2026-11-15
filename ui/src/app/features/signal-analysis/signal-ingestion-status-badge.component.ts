import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { TuiBadge } from '@taiga-ui/kit';
import { SignalIngestionStatus } from '../../core/models';

/** Compact Taiga badge showing a channel's effective ingestion status in Russian. */
@Component({
  selector: 'app-signal-ingestion-status-badge',
  standalone: true,
  imports: [TuiBadge],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span tuiBadge [appearance]="appearance()">{{ label() }}</span>
  `
})
export class SignalIngestionStatusBadgeComponent {
  readonly status = input.required<SignalIngestionStatus>();

  private static readonly LABELS: Record<SignalIngestionStatus, string> = {
    DISABLED: 'Выключен',
    WAITING_AUTH: 'Нужна авторизация',
    PENDING: 'Ожидание',
    BACKFILL: 'Загрузка истории',
    ACTIVE: 'Активен',
    DEGRADED: 'Неполный',
    UNAVAILABLE: 'Недоступен',
    ERROR: 'Ошибка'
  };

  private static readonly APPEARANCE: Record<SignalIngestionStatus, string> = {
    DISABLED: 'neutral',
    WAITING_AUTH: 'warning',
    PENDING: 'info',
    BACKFILL: 'info',
    ACTIVE: 'positive',
    DEGRADED: 'warning',
    UNAVAILABLE: 'neutral',
    ERROR: 'negative'
  };

  readonly label = computed(() => SignalIngestionStatusBadgeComponent.LABELS[this.status()] ?? this.status());
  readonly appearance = computed(() => SignalIngestionStatusBadgeComponent.APPEARANCE[this.status()] ?? 'neutral');
}