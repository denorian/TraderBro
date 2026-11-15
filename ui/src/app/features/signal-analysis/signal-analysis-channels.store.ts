import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../../core/api.service';
import {
  ChannelPageDto, IngestionStatusDto, MonitoringBulkResultDto, SignalChannelDto,
  SignalIngestionStatus
} from '../../core/models';

/**
 * Client state for the "Анализ сигналов" channels page: query/filter/page/loading/error, plus
 * the ingestion status. All reads are signals; HTTP calls are fire-and-forget (UI polls).
 */
@Injectable({ providedIn: 'root' })
export class SignalAnalysisChannelsStore {
  private readonly api = inject(ApiService);

  readonly page = signal<ChannelPageDto | null>(null);
  readonly status = signal<IngestionStatusDto | null>(null);
  readonly loading = signal(false);
  readonly syncing = signal(false);
  readonly error = signal<string | null>(null);

  // Multi-select for bulk monitoring operations (channel ids).
  private readonly selectedIds = signal<Set<string>>(new Set());
  readonly bulkBusy = signal(false);

  // Filters
  readonly query = signal('');
  readonly monitoring = signal<'all' | 'on' | 'off'>('all');
  readonly archived = signal<'all' | 'main' | 'archived'>('all');
  readonly ingestionStatus = signal<SignalIngestionStatus | ''>('');
  readonly pageIndex = signal(0);
  readonly pageSize = signal(50);

  async load(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const result = await firstValueFrom(this.api.signalChannels({
        query: this.query(),
        subscribed: true,
        monitoring: this.monitoring() === 'on' ? true
          : this.monitoring() === 'off' ? false : undefined,
        archived: this.archived() === 'archived' ? true
          : this.archived() === 'main' ? false : undefined,
        status: this.ingestionStatus() || undefined,
        page: this.pageIndex(),
        size: this.pageSize(),
        sort: 'title',
        direction: 'asc'
      }));
      this.page.set(result ?? null);
    } catch (e) {
      this.error.set('Не удалось загрузить каналы');
    } finally {
      this.loading.set(false);
    }
  }

  async refreshStatus(): Promise<void> {
    try {
      this.status.set(await firstValueFrom(this.api.ingestionStatus()) ?? null);
    } catch {
      // Status is best-effort; keep last value.
    }
  }

  /** Optimistic monitoring toggle with rollback on failure. */
  async setMonitoring(channel: SignalChannelDto, enabled: boolean): Promise<boolean> {
    const page = this.page();
    if (!page) { return false; }
    const previous = channel.monitoringEnabled;
    channel.monitoringEnabled = enabled;
    try {
      const updated = await firstValueFrom(this.api.setChannelMonitoring(channel.id, enabled));
      channel.monitoringEnabled = updated.monitoringEnabled;
      channel.ingestionStatus = updated.ingestionStatus;
      return true;
    } catch {
      channel.monitoringEnabled = previous;
      this.error.set('Не удалось изменить мониторинг');
      return false;
    }
  }

  async syncChannels(): Promise<void> {
    if (this.syncing()) { return; }
    this.syncing.set(true);
    try {
      await firstValueFrom(this.api.syncChannels());
      await this.load();
    } catch {
      this.error.set('Синхронизация недоступна: ingestion выключен');
    } finally {
      this.syncing.set(false);
    }
  }

  // ---------------------------------------------------------------- selection + bulk

  selected(): string[] {
    return Array.from(this.selectedIds());
  }

  isSelected(id: string): boolean {
    return this.selectedIds().has(id);
  }

  toggleSelect(id: string, selected: boolean): void {
    const next = new Set(this.selectedIds());
    if (selected) { next.add(id); } else { next.delete(id); }
    this.selectedIds.set(next);
  }

  /** Whether every channel on the current page is selected (for a "select all" header checkbox). */
  allPageSelected(): boolean {
    const items = this.page()?.items ?? [];
    return items.length > 0 && items.every((c) => this.selectedIds().has(c.id));
  }

  toggleSelectPage(): void {
    const items = this.page()?.items ?? [];
    const selectAll = !this.allPageSelected();
    const next = new Set(this.selectedIds());
    for (const c of items) {
      if (selectAll) { next.add(c.id); } else { next.delete(c.id); }
    }
    this.selectedIds.set(next);
  }

  clearSelection(): void {
    this.selectedIds.set(new Set());
  }

  /** Apply monitoring to all currently selected channels; reloads the list afterwards. */
  async applyBulkMonitoring(enabled: boolean): Promise<MonitoringBulkResultDto | null> {
    const ids = this.selected();
    if (ids.length === 0 || this.bulkBusy()) { return null; }
    this.bulkBusy.set(true);
    try {
      const result = await firstValueFrom(this.api.setChannelMonitoringBulk(ids, enabled));
      this.clearSelection();
      await this.load();
      return result;
    } catch {
      this.error.set('Не удалось применить мониторинг к выбранным каналам');
      return null;
    } finally {
      this.bulkBusy.set(false);
    }
  }

  setQuery(value: string): void {
    this.query.set(value);
    this.pageIndex.set(0);
  }

  setMonitoringFilter(value: 'all' | 'on' | 'off'): void {
    this.monitoring.set(value);
    this.pageIndex.set(0);
  }

  setArchivedFilter(value: 'all' | 'main' | 'archived'): void {
    this.archived.set(value);
    this.pageIndex.set(0);
  }

  setStatusFilter(value: SignalIngestionStatus | ''): void {
    this.ingestionStatus.set(value);
    this.pageIndex.set(0);
  }

  setPage(index: number): void {
    this.pageIndex.set(index);
  }

  resetFilters(): void {
    this.query.set('');
    this.monitoring.set('all');
    this.archived.set('all');
    this.ingestionStatus.set('');
    this.pageIndex.set(0);
  }
}