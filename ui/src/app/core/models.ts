/** DTOs mirroring the backend REST records in openapi.yaml. */

export interface InstrumentDto {
  figi: string;
  ticker: string;
  name: string;
  type: 'SHARE' | 'FUTURE';
  currency?: string;
  lot: number;
  tradable: boolean;
  streamUp: boolean;
}

export interface BarDto {
  ts: string;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
}

export interface AlligatorPointDto {
  ts: string;
  jaw: number | null;
  teeth: number | null;
  lips: number | null;
}

export interface AoPointDto {
  ts: string;
  ao: number | null;
  rising: boolean;
}

export interface ChartResponse {
  figi: string;
  interval: string;
  bars: BarDto[];
  alligator: AlligatorPointDto[];
  ao: AoPointDto[];
}

export interface LatestIndicatorDto {
  figi: string;
  interval: string;
  ts: string | null;
  alligator: Record<string, unknown>;
  ao: Record<string, unknown>;
}

export interface ComponentStatusDto {
  status: 'OK' | 'DEGRADED' | 'DOWN';
  detail?: string;
}

export interface PositionDto {
  figi: string;
  quantity: number;
  averagePrice?: number;
  currentPrice?: number;
  expectedYield?: number;
}

export interface PortfolioDto {
  totalValue?: number;
  cash?: number;
  dayPnl?: number;
  dayPnlPercent?: number;
}

export interface StatusResponse {
  dataStream: ComponentStatusDto;
  broker: ComponentStatusDto;
  db: ComponentStatusDto;
  killSwitchActive: boolean;
  killSwitchReason?: string;
  positions: PositionDto[];
  portfolio: PortfolioDto;
  streamLag?: string;
}

export interface SignalDto {
  id: string;
  createdAt: string;
  strategyId: string;
  figi: string;
  ts: string;
  direction: 'LONG' | 'SHORT';
  price: number;
  verdict: 'ACCEPTED' | 'FILTERED' | string;
  reason?: string;
}