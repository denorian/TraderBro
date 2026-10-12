import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { of } from 'rxjs';
import { DashboardComponent } from './dashboard.component';
import { ApiService } from '../../core/api.service';
import { InstrumentDto, StatusResponse } from '../../core/models';

describe('DashboardComponent', () => {
  let fixture: ComponentFixture<DashboardComponent>;
  let component: DashboardComponent;
  const apiMock = {
    status: () => of({ portfolio: { dayPnl: 123 } } as StatusResponse),
    instruments: () =>
      of([
        { figi: 'F1', ticker: 'SBER', type: 'SHARE', lot: 10, tradable: true, streamUp: true },
        { figi: 'F2', ticker: 'MIX', type: 'FUTURE', lot: 1, tradable: true, streamUp: true }
      ] as unknown as InstrumentDto[]),
    latestIndicators: () =>
      of([
        { figi: 'F1', interval: '1h', ts: null, alligator: { state: 'EATING_BULL' }, ao: { ao: 1.2 } },
        { figi: 'F2', interval: '1h', ts: null, alligator: { state: 'EATING_BEAR' }, ao: { ao: -0.4 } }
      ]),
    signals: () => of([])
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
    expect(component.stateAppearance('EATING_BULL')).toBe('success');
    expect(component.stateAppearance('EATING_BEAR')).toBe('error');
    expect(component.stateAppearance('AWAKENING')).toBe('warning');
    expect(component.stateAppearance('SLEEPING')).toBe('neutral');
  });

  it('builds rows from instruments + latest snapshots and filters by type', async () => {
    await component.reload();
    expect(component.filtered().length).toBe(2);
    component.typeFilter.set('FUTURE');
    component.buildRows();
    expect(component.filtered().length).toBe(1);
    expect(component.filtered()[0].ticker).toBe('MIX');
    expect(component.filtered()[0].state).toBe('EATING_BEAR');
    expect(component.filtered()[0].ao).toBe(-0.4);
  });
});