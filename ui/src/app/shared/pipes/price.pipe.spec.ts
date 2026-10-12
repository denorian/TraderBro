import { PricePipe } from './price.pipe';

describe('PricePipe', () => {
  const pipe = new PricePipe();

  it('formats with ru-RU grouping', () => {
    expect(pipe.transform(1234.5, 2)).toBe('1\u00a0234,50');
  });

  it('honours decimals', () => {
    expect(pipe.transform(12.3456, 3)).toBe('12,346');
  });

  it('returns dash for null/undefined/NaN', () => {
    expect(pipe.transform(null)).toBe('—');
    expect(pipe.transform(undefined)).toBe('—');
    expect(pipe.transform(Number.NaN)).toBe('—');
  });
});