import { InstrumentDto } from '../core/models';

/** Map of figi → instrument for quick label lookups in templates. */
export function instrumentMap(list: InstrumentDto[]): Map<string, InstrumentDto> {
  return new Map(list.map((i) => [i.figi, i]));
}

/** Human-readable label for a figi: "TICKER — Name", falling back to the raw figi. */
export function instrumentLabel(figi: string, map: Map<string, InstrumentDto>): string {
  const inst = map.get(figi);
  return inst ? `${inst.ticker} — ${inst.name}` : figi;
}