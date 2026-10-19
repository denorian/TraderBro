import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import {
  BacktestRunDetail,
  BacktestRunSummary,
  AngulationEventDto,
  ChartResponse,
  CompareRowDto,
  CreateBacktestResponse,
  DivergenceEventDto,
  InstrumentDto,
  LatestIndicatorDto,
  PairResultDto,
  SignalDto,
  SignalRecordDto,
  StatusResponse,
  StrategyDto,
  TradeRecordDto,
  BacktestRequest
} from './models';

@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly base = '/api';

  constructor(private readonly http: HttpClient) {}

  instruments(): Observable<InstrumentDto[]> {
    return this.http.get<InstrumentDto[]>(`${this.base}/instruments`);
  }

  chart(figi: string, interval = '1h', limit = 500,
        opts?: { from?: string; to?: string }): Observable<ChartResponse> {
    let params = new HttpParams().set('interval', interval).set('limit', String(limit));
    if (opts?.from) { params = params.set('from', opts.from); }
    if (opts?.to) { params = params.set('to', opts.to); }
    return this.http.get<ChartResponse>(`${this.base}/chart/${figi}`, { params });
  }

  latestIndicators(interval = '1h'): Observable<LatestIndicatorDto[]> {
    return this.http.get<LatestIndicatorDto[]>(`${this.base}/indicators/latest`, {
      params: new HttpParams().set('interval', interval)
    });
  }

  status(): Observable<StatusResponse> {
    return this.http.get<StatusResponse>(`${this.base}/status`);
  }

  signals(limit = 20): Observable<SignalDto[]> {
    const from = new Date(Date.now() - 24 * 3600_000).toISOString();
    const to = new Date().toISOString();
    return this.http.get<SignalDto[]>(`${this.base}/signals`, {
      params: new HttpParams().set('from', from).set('to', to)
    });
  }

  // ---------------------------------------------------------------- backtests
  strategies(): Observable<StrategyDto[]> {
    return this.http.get<StrategyDto[]>(`${this.base}/backtests/strategies`);
  }

  createBacktest(request: BacktestRequest): Observable<CreateBacktestResponse> {
    return this.http.post<CreateBacktestResponse>(`${this.base}/backtests`, request);
  }

  listBacktests(params?: { status?: string; limit?: number }): Observable<BacktestRunSummary[]> {
    let p = new HttpParams();
    if (params?.status) { p = p.set('status', params.status); }
    p = p.set('limit', String(params?.limit ?? 20));
    return this.http.get<BacktestRunSummary[]>(`${this.base}/backtests`, { params: p });
  }

  backtest(id: string): Observable<BacktestRunDetail> {
    return this.http.get<BacktestRunDetail>(`${this.base}/backtests/${id}`);
  }

  backtestResults(id: string): Observable<PairResultDto[]> {
    return this.http.get<PairResultDto[]>(`${this.base}/backtests/${id}/results`);
  }

  backtestSignals(id: string, figi?: string, strategyId?: string): Observable<SignalRecordDto[]> {
    let p = new HttpParams().set('limit', '10000');
    if (figi) { p = p.set('figi', figi); }
    if (strategyId) { p = p.set('strategyId', strategyId); }
    return this.http.get<SignalRecordDto[]>(`${this.base}/backtests/${id}/signals`, { params: p });
  }

  backtestTrades(id: string, figi?: string, strategyId?: string): Observable<TradeRecordDto[]> {
    let p = new HttpParams().set('limit', '10000');
    if (figi) { p = p.set('figi', figi); }
    if (strategyId) { p = p.set('strategyId', strategyId); }
    return this.http.get<TradeRecordDto[]>(`${this.base}/backtests/${id}/trades`, { params: p });
  }

  deleteBacktest(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/backtests/${id}`);
  }

  compareBacktests(ids: string[]): Observable<CompareRowDto[]> {
    return this.http.get<CompareRowDto[]>(`${this.base}/backtests/compare`, {
      params: new HttpParams().set('ids', ids.join(','))
    });
  }

  // ---------------------------------------------------------------- Williams events
  divergences(opts?: { figi?: string; interval?: string; status?: string; limit?: number })
    : Observable<DivergenceEventDto[]> {
    let p = new HttpParams().set('limit', String(opts?.limit ?? 100));
    if (opts?.figi) { p = p.set('figi', opts.figi); }
    if (opts?.interval) { p = p.set('interval', opts.interval); }
    if (opts?.status) { p = p.set('status', opts.status); }
    return this.http.get<DivergenceEventDto[]>(`${this.base}/divergences`, { params: p });
  }

  latestDivergences(interval = '1h'): Observable<DivergenceEventDto[]> {
    return this.http.get<DivergenceEventDto[]>(`${this.base}/divergences/latest`, {
      params: new HttpParams().set('interval', interval)
    });
  }

  angulation(opts?: { figi?: string; interval?: string; limit?: number })
    : Observable<AngulationEventDto[]> {
    let p = new HttpParams().set('limit', String(opts?.limit ?? 100));
    if (opts?.figi) { p = p.set('figi', opts.figi); }
    if (opts?.interval) { p = p.set('interval', opts.interval); }
    return this.http.get<AngulationEventDto[]>(`${this.base}/angulation`, { params: p });
  }
}