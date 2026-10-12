import { Pipe, PipeTransform } from '@angular/core';

const MSK_OFFSET_MS = 3 * 3600_000;

/** Formats an ISO timestamp as Moscow time {@code dd.MM.yyyy HH:mm:ss}. */
@Pipe({ name: 'mskTime', standalone: true })
export class MskTimePipe implements PipeTransform {
  transform(value: string | Date | null | undefined): string {
    if (!value) {
      return '—';
    }
    const d = value instanceof Date ? value : new Date(value);
    if (Number.isNaN(d.getTime())) {
      return '—';
    }
    const msk = new Date(d.getTime() + MSK_OFFSET_MS);
    const pad = (n: number) => String(n).padStart(2, '0');
    return `${pad(msk.getUTCDate())}.${pad(msk.getUTCMonth() + 1)}.${msk.getUTCFullYear()} `
      + `${pad(msk.getUTCHours())}:${pad(msk.getUTCMinutes())}:${pad(msk.getUTCSeconds())}`;
  }
}