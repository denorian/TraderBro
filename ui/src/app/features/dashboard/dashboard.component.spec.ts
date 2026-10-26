import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { of } from 'rxjs';
import { DashboardComponent } from './dashboard.component';
import { ApiService } from '../../core/api.service';
import { InstrumentDto, StatusResponse } from '../../core/models';

const buildRows = (c: DashboardComponent): void =>
  (c as unknown as { buildRows(): void }).buildRows();

describe('DashboardComponent', () => {
  let fixture: ComponentFixture<DashboardComponent>;
  let component: DashboardComponent;
  const apiMock = {
    status: () => of({ portfolio: { dayPnl: 123 } } as StatusResponse),
    instruments: () =>
      of([
        { figi: 'F1', ticker: 'SBER', type: 'SHARE', lot: 10, tradable: true, streamUp: true },
        { figi: 'F2', ticker: 'MIX', type: 'FUTURE', lot: 1, tradable: true, streamUp: true },
        { figi: 'BTCUSDT', ticker: 'BTCUSDT', name: 'BTC/USDT', type: 'CRYPTO', lot: 1,
          tradable: true, streamUp: true, marketCapUsd: 2.3e12, price: 98000 }
      ] as unknown as InstrumentDto[]),
    latestIndicators: () =>
      of([
        { figi: 'F1', interval: '1h', ts: null, alligator: { state: 'EATING_BULL' }, ao: { ao: 1.2 } },
        { figi: 'F2', interval: '1h', ts: null, alligator: { state: 'EATING_BEAR' }, ao: { ao: -0.4 } },
        { figi: 'BTCUSDT', interval: '1h', ts: null, alligator: { state: 'AWAKENING' }, ao: { ao: 5.5 } }
      ]),
    signals: () => of([]),
    latestDivergences: () => of([])
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [DashboardComponent],
      providers: [
        { provide: ApiService, useValue: apiMock },
        { provide: Router, useValue: { navigate: jest.fn() } }
      ]
    }).compileComponents();
    fixture = TestBed.createComponent(DashboardComponent);
    component = fixture.componentInstance;
  });

  it('maps Alligator phases to badge appearances', () => {
    expect(component.stateAppearance('EATING_BULL')).toBe('positive');
    expect(component.stateAppearance('EATING_BEAR')).toBe('negative');
    expect(component.stateAppearance('AWAKENING')).toBe('warning');
    expect(component.stateAppearance('SLEEPING')).toBe('neutral');
  });

  it('builds rows from instruments + latest snapshots and filters by type', async () => {
    await component.reload();
    expect(component.filtered().length).toBe(3);
    component.typeFilter.set('FUTURE');
    buildRows(component);
    expect(component.filtered().length).toBe(1);
    expect(component.filtered()[0].ticker).toBe('MIX');
    expect(component.filtered()[0].state).toBe('EATING_BEAR');
    expect(component.filtered()[0].ao).toBe(-0.4);
  });

  it('shows crypto rows with the crypto filter and carries price/cap', async () => {
    await component.reload();
    component.typeFilter.set('CRYPTO');
    buildRows(component);
    expect(component.filtered().length).toBe(1);
    const row = component.filtered()[0];
    expect(row.ticker).toBe('BTCUSDT');
    expect(row.type).toBe('CRYPTO');
    expect(row.price).toBe(98000);
    expect(row.marketCapUsd).toBe(2.3e12);
    expect(row.ao).toBe(5.5);
    expect(row.state).toBe('AWAKENING');
  });
});