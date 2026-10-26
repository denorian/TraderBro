import { capLabel, priceDecimals, typeLabel } from './crypto-format';

describe('crypto-format', () => {
  it('labels instrument types, including crypto', () => {
    expect(typeLabel('CRYPTO')).toBe('крипто');
    expect(typeLabel('FUTURE')).toBe('фьючерс');
    expect(typeLabel('SHARE')).toBe('акция');
    expect(typeLabel('')).toBe('акция');
  });

  it('uses 4 decimals for crypto prices, 2 otherwise', () => {
    expect(priceDecimals('CRYPTO')).toBe(4);
    expect(priceDecimals('SHARE')).toBe(2);
    expect(priceDecimals('FUTURE')).toBe(2);
  });

  it('formats market capitalisation compactly', () => {
    expect(capLabel(2.3e12)).toBe('$2.3 трлн');
    expect(capLabel(450e9)).toBe('$450 млрд');
    expect(capLabel(90e6)).toBe('$90 млн');
    expect(capLabel(5000)).toBe('$5000');
  });
});