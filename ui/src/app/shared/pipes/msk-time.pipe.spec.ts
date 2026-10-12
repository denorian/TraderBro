import { MskTimePipe } from './msk-time.pipe';

describe('MskTimePipe', () => {
  const pipe = new MskTimePipe();

  it('formats ISO instant as Moscow dd.MM.yyyy HH:mm:ss', () => {
    // 2026-10-12 10:00:00 UTC == 13:00:00 MSK
    const out = pipe.transform('2026-10-12T10:00:00Z');
    expect(out).toBe('12.10.2026 13:00:00');
  });

  it('returns dash for empty input', () => {
    expect(pipe.transform(null)).toBe('—');
    expect(pipe.transform('not-a-date')).toBe('—');
  });
});