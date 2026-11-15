import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TuiSwitch } from '@taiga-ui/kit';
import { SignalChannelDto } from '../../core/models';
import { SignalAnalysisChannelsStore } from './signal-analysis-channels.store';

/** Monitoring toggle with optimistic update and rollback on error (Taiga tuiSwitch). */
@Component({
  selector: 'app-signal-channel-monitoring-toggle',
  standalone: true,
  imports: [FormsModule, TuiSwitch],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <input
      type="checkbox"
      tuiSwitch
      [ngModel]="channel().monitoringEnabled"
      [disabled]="disabled()"
      [title]="title()"
      (ngModelChange)="onChange($event)"
    />
  `
})
export class SignalChannelMonitoringToggleComponent {
  readonly channel = input.required<SignalChannelDto>();
  private readonly store = inject(SignalAnalysisChannelsStore);

  /** Toggle is disabled when the subscription is lost (cannot collect without membership). */
  readonly disabled = () => !this.channel().subscribed;
  readonly title = () => this.channel().subscribed
    ? 'Включить/выключить сохранение сообщений канала'
    : 'Подписка потеряна — мониторинг недоступен';

  onChange(enabled: boolean): void {
    void this.store.setMonitoring(this.channel(), enabled);
  }
}