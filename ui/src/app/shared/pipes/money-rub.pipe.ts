import { Pipe, PipeTransform } from '@angular/core';

/** Formats money with thousands separators and a ruble sign. */
@Pipe({ name: 'moneyRub', standalone: true })
export class MoneyRubPipe implements PipeTransform {
  transform(value: number | null | undefined): string {
    if (value === null || value === undefined || Number.isNaN(value)) {
      return '—';
    }
    return new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 2 }).format(value) + ' ₽';
  }
}