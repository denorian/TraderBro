/**
 * Pure formatting helpers for the instrument table — kept dependency-free so they are trivially
 * unit-testable. Crypto prices have up to 8 significant digits by tickSize, so they get more
 * decimals than equity/futures quotes.
 */

export function typeLabel(type: string): string {
  switch (type) {
    case 'CRYPTO': return 'крипто';
    case 'FUTURE': return 'фьючерс';
    default: return 'акция';
  }
}

export function priceDecimals(type: string): number {
  return type === 'CRYPTO' ? 4 : 2;
}

/** Compact market-cap label, e.g. "$2.3 трлн" / "$450 млрд". */
export function capLabel(usd: number): string {
  if (usd >= 1e12) {
    return `$${(usd / 1e12).toFixed(1)} трлн`;
  }
  if (usd >= 1e9) {
    return `$${(usd / 1e9).toFixed(0)} млрд`;
  }
  if (usd >= 1e6) {
    return `$${(usd / 1e6).toFixed(0)} млн`;
  }
  return `$${Math.round(usd)}`;
}