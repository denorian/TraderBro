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
  angulationAngleAtr?: number | null;
  angulationDirection?: string | null;
}

export interface DivergenceEventDto {
  id: string;
  figi: string;
  interval: string;
  ts: string;
  direction: 'BULLISH' | 'BEARISH';
  degree: number;
  aoPeak1Ts: string;
  aoPeak1Value: number;
  aoPeak2Ts: string;
  aoPeak2Value: number;
  priceAtPeak1: number;
  priceAtPeak2: number;
  strength: number;
  status: 'UNCONFIRMED' | 'CONFIRMED' | 'EXPIRED';
  confirmedAt: string | null;
  createdAt: string;
}

export interface AngulationEventDto {
  figi: string;
  interval: string;
  ts: string;
  divergenceEventId: string;
  direction: 'AWAY_UP' | 'AWAY_DOWN';
  priceSlopeAtr: number;
  jawSlopeAtr: number;
  angleAtr: number;
  anchorTs: string;
  barsInWindow: number;
  createdAt: string;
}

export interface ComponentStatusDto {
  status: 'OK' | 'DEGRADED' | 'DOWN';
  detail?: string;
}

export interface PositionDto {
  figi: string;
  lots: number;
  shares: number;
  averagePrice?: number;
  currentPrice?: number;
  ticker?: string;
  name?: string;
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

// ---------------------------------------------------------------- backtests

export interface BacktestCosts {
  commissionPct?: number;
  slippageBps?: number;
  initialCapital?: number;
  maxPositionPct?: number;
}

export interface BacktestRequest {
  strategyIds: string[];
  figis: string[];
  interval: 'FIFTEEN_MIN' | 'ONE_HOUR' | 'FOUR_HOUR' | 'ONE_DAY';
  from: string;
  to: string;
  costs?: BacktestCosts;
  strategyParamsOverrides?: Record<string, Record<string, unknown>>;
  walkForward: boolean;
  walkForwardTrainDays?: number;
  walkForwardTestDays?: number;
}

export interface CreateBacktestResponse {
  id: string;
  status: string;
}

export interface BacktestMetricsDto {
  totalReturn: number;
  cagr: number;
  sharpe: number;
  sortino: number;
  calmar: number;
  maxDrawdown: number;
  winRate: number;
  profitFactor: number;
  numTrades: number;
  avgBarsHeld: number;
  finalEquity: number;
}

export interface BacktestRunSummary {
  id: string;
  status: 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
  progressPct: number;
  errorMessage: string | null;
  request: BacktestRequest;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
}

export interface EquityPointDto {
  ts: string;
  equity: number;
}

export interface TradeRecordDto {
  strategyId: string;
  figi: string;
  direction: 'LONG' | 'SHORT';
  entryTs: string;
  entryPrice: number;
  exitTs: string;
  exitPrice: number;
  quantity: number;
  pnl: number;
  pnlPct: number;
  exitReason: string;
}

export interface SignalRecordDto {
  strategyId: string;
  figi: string;
  ts: string;
  type: 'ENTRY' | 'EXIT';
  price: number;
  verdict: string;
  reason: string;
}

export interface PairResultDto {
  strategyId: string;
  figi: string;
  metrics: BacktestMetricsDto;
  barsCount: number;
  equity: EquityPointDto[];
  trades: TradeRecordDto[];
  signals: SignalRecordDto[];
}

export interface BacktestRunDetail {
  run: BacktestRunSummary;
  results: PairResultDto[];
}

export interface CompareRowDto {
  runId: string;
  strategyId: string;
  figi: string;
  metrics: BacktestMetricsDto;
}

export interface StrategyDto {
  id: string;
  name: string;
  description: string;
  params: Record<string, string>;
}