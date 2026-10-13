import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Input,
  OnDestroy,
  ViewChild
} from '@angular/core';
import {
  CandlestickSeries,
  createChart,
  createSeriesMarkers,
  HistogramSeries,
  IChartApi,
  ISeriesApi,
  LineSeries,
  SeriesMarker,
  Time,
  UTCTimestamp
} from 'lightweight-charts';
import { ChartResponse, SignalRecordDto } from '../../core/models';
import { strategyColor } from '../../shared/strategy-colors';

/**
 * Angular wrapper around lightweight-charts v5: candlesticks + the three Alligator lines and
 * an Awesome Oscillator histogram pane, with visible-range synchronisation.
 */
@Component({
  selector: 'app-candlestick-chart',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="chart-wrap">
      <div class="pane main-pane">
        <div #main class="canvas"></div>
      </div>
      <div class="pane ao-pane">
        <div class="ao-label">Awesome Oscillator</div>
        <div #ao class="canvas"></div>
      </div>
    </div>
  `,
  styles: [`
    :host { display: block; width: 100%; height: 100%; }
    .chart-wrap { display: flex; flex-direction: column; width: 100%; height: 100%; }
    .pane { width: 100%; min-height: 0; display: flex; flex-direction: column; }
    .main-pane { flex: 3; }
    .ao-pane { flex: 1; border-top: 1px solid var(--tui-border-normal); }
    .ao-label { font-size: .75rem; color: var(--tui-text-secondary); padding: .15rem .5rem; }
    .canvas { width: 100%; min-height: 0; flex: 1; }
  `]
})
export class CandlestickChartComponent implements AfterViewInit, OnDestroy {
  @ViewChild('main', { static: true }) private mainEl!: ElementRef<HTMLDivElement>;
  @ViewChild('ao', { static: true }) private aoEl!: ElementRef<HTMLDivElement>;

  @Input() set data(value: ChartResponse | null) {
    this._data = value;
    if (this.chart && this.aoChart) {
      this.render(value);
    }
  }
  get data(): ChartResponse | null {
    return this._data;
  }

  /** ENTRY/EXIT signal points to pin on the candles (live or backtest). */
  @Input() signals: SignalRecordDto[] = [];
  /** Only render signals for these strategy ids (empty = all). */
  @Input() selectedStrategies: string[] = [];
  /** Render rejected/filtered signals semi-transparently. */
  @Input() showRejected = false;

  private _data: ChartResponse | null = null;
  private chart: IChartApi | null = null;
  private aoChart: IChartApi | null = null;
  private lastFitFigi: string | null = null;
  private readonly mainSeries: ISeriesApi<any>[] = [];
  private readonly aoSeries: ISeriesApi<any>[] = [];

  private static readonly options = {
    autoSize: true,
    layout: { background: { color: '#1a1b25' }, textColor: '#d1d2dc' },
    grid: { vertLines: { color: '#262a3a' }, horzLines: { color: '#262a3a' } },
    rightPriceScale: { borderColor: '#30344c' },
    timeScale: { borderColor: '#30344c', timeVisible: true }
  };

  ngAfterViewInit(): void {
    this.chart = createChart(this.mainEl.nativeElement, CandlestickChartComponent.options);
    this.aoChart = createChart(this.aoEl.nativeElement, {
      ...CandlestickChartComponent.options,
      timeScale: { visible: false }
    });
    this.syncTimeScales();
    if (this._data) {
      this.render(this._data);
    }
  }

  /**
   * Keeps the horizontal (time) range of both panes in lock-step — zooming/panning on either
   * chart moves the other. Vertical scales stay independent (price vs AO units differ).
   */
  private syncTimeScales(): void {
    let syncing = false;
    const link = (source: IChartApi, target: IChartApi) => {
      source.timeScale().subscribeVisibleLogicalRangeChange((range) => {
        if (range && !syncing) {
          syncing = true;
          target.timeScale().setVisibleLogicalRange(range);
          syncing = false;
        }
      });
    };
    if (this.chart && this.aoChart) {
      link(this.chart, this.aoChart);
      link(this.aoChart, this.chart);
    }
  }

  private render(data: ChartResponse | null): void {
    if (!this.chart || !this.aoChart || !data) {
      return;
    }
    for (const s of this.mainSeries) {
      this.chart.removeSeries(s);
    }
    for (const s of this.aoSeries) {
      this.aoChart.removeSeries(s);
    }
    this.mainSeries.length = 0;
    this.aoSeries.length = 0;

    const candle = this.chart.addSeries(CandlestickSeries, {});
    candle.setData(data.bars.map((b) => ({
      time: toTime(b.ts),
      open: b.open,
      high: b.high,
      low: b.low,
      close: b.close
    })));

    const jaw = this.chart.addSeries(LineSeries, { color: '#3d8bff', lineWidth: 2 });
    const teeth = this.chart.addSeries(LineSeries, { color: '#ff5c5c', lineWidth: 2 });
    const lips = this.chart.addSeries(LineSeries, { color: '#3dd68c', lineWidth: 2 });
    const lineData = (key: 'jaw' | 'teeth' | 'lips') =>
      data.alligator
        .filter((p) => p[key] !== null)
        .map((p) => ({ time: toTime(p.ts), value: (p[key] as number) }));
    jaw.setData(lineData('jaw'));
    teeth.setData(lineData('teeth'));
    lips.setData(lineData('lips'));

    const hist = this.aoChart.addSeries(HistogramSeries, {});
    hist.setData(data.ao.map((p) => ({
      time: toTime(p.ts),
      value: p.ao ?? 0,
      color: p.rising ? '#3dd68c' : '#ff5c5c'
    })));

    this.mainSeries.push(candle, jaw, teeth, lips);
    this.aoSeries.push(hist);

    this.applyMarkers(candle, data.figi);

    // Fit the full loaded range when the instrument changes (on a wide screen lightweight-charts
    // would otherwise show only the trailing bars, cutting off the left side). Keep the user's
    // zoom/pan across subsequent polls of the same instrument.
    if (data.figi !== this.lastFitFigi) {
      this.lastFitFigi = data.figi;
      this.chart.timeScale().fitContent();
      this.aoChart.timeScale().fitContent();
    }
  }

  /** Renders ENTRY/EXIT pins on the candle series, filtered by selected strategies. */
  private applyMarkers(candle: ISeriesApi<any>, figi: string): void {
    const markers: SeriesMarker<Time>[] = [];
    for (const s of this.signals) {
      if (s.figi && s.figi !== figi) {
        continue;
      }
      if (this.selectedStrategies.length && !this.selectedStrategies.includes(s.strategyId)) {
        continue;
      }
      const rejected = s.verdict !== 'ACCEPTED';
      if (rejected && !this.showRejected) {
        continue;
      }
      const color = rejected ? alpha(strategyColor(s.strategyId), '40') : strategyColor(s.strategyId);
      const position = s.type === 'ENTRY'
        ? ('belowBar' as const)
        : ('aboveBar' as const);
      const shape = s.type === 'ENTRY'
        ? ('arrowUp' as const)
        : ('circle' as const);
      markers.push({
        time: toTime(s.ts),
        position,
        color,
        shape,
        text: s.strategyId.replace('Strategy', '')
      });
    }
    if (markers.length > 500) {
      // TODO(stage 4): aggregate/paginate markers by the visible range to keep the chart fast.
      console.warn(`[chart] ${markers.length} markers exceed the 500 sanity cap; trimming.`);
      markers.length = 500;
    }
    if (markers.length) {
      createSeriesMarkers(candle, markers);
    }
  }

  ngOnDestroy(): void {
    this.chart?.remove();
    this.aoChart?.remove();
  }
}

/** Appends an 8-digit alpha channel to a #RRGGBB colour. */
function alpha(hex: string, a: string): string {
  return hex + a;
}

function toTime(v: string): Time {
  return Math.floor(new Date(v).getTime() / 1000) as UTCTimestamp;
}