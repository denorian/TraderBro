/**
 * Deterministic per-strategy colour palette for chart pins and legends.
 * The mapping is stable by {@code strategyId} (hash → fixed index) so colours never shift
 * between reloads or sessions.
 */
export const STRATEGY_COLORS: readonly string[] = [
  '#3d8bff', // blue
  '#ff5c5c', // red
  '#3dd68c', // green
  '#ffb020', // amber
  '#a06bff', // violet
  '#ff5ca8', // pink
  '#2dd4bf', // teal
  '#f59e0b'  // orange
];

/** Returns the stable colour for a strategy id. */
export function strategyColor(strategyId: string): string {
  if (!strategyId) {
    return STRATEGY_COLORS[0];
  }
  let hash = 0;
  for (let i = 0; i < strategyId.length; i++) {
    hash = (hash * 31 + strategyId.charCodeAt(i)) | 0;
  }
  return STRATEGY_COLORS[Math.abs(hash) % STRATEGY_COLORS.length];
}