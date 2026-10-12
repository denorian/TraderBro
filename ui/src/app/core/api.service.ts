import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import {
  ChartResponse,
  InstrumentDto,
  LatestIndicatorDto,
  SignalDto,
  StatusResponse
} from './models';

@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly base = '/api';

  constructor(private readonly http: HttpClient) {}

  instruments(): Observable<InstrumentDto[]> {
    return this.http.get<InstrumentDto[]>(`${this.base}/instruments`);
  }

  chart(figi: string, interval = '1h', limit = 500): Observable<ChartResponse> {
    const params = new HttpParams().set('interval', interval).set('limit', String(limit));
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
}