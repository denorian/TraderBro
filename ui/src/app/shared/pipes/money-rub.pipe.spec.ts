import { MoneyRubPipe } from './money-rub.pipe';

describe('MoneyRubPipe', () => {
  const pipe = new MoneyRubPipe();

  it('formats money with ruble sign', () => {
    expect(pipe.transform(1234567.89)).toBe('1\u00a0234\u00a0567,89 ₽');
  });

  it('returns dash for null', () => {
    expect(pipe.transform(null)).toBe('—');
  });
});