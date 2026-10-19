import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  EventEmitter,
  Input,
  OnDestroy,
  Output,
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
import { ChartResponse, DivergenceEventDto, SignalRecordDto } from '../../core/models';
import { strategyColor } from '../../shared/strategy-colors';

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

  @Input() set signals(v: SignalRecordDto[]) {
    this._signals = v;
    this.rerender();
  }
  get signals(): SignalRecordDto[] {
    return this._signals;
  }
  @Input() set selectedStrategies(v: string[]) {
    this._selectedStrategies = v;
    this.rerender();
  }
  get selectedStrategies(): string[] {
    return this._selectedStrategies;
  }
  @Input() set showRejected(v: boolean) {
    this._showRejected = v;
    this.rerender();
  }
  get showRejected(): boolean {
    return this._showRejected;
  }
  @Input() set divergences(v: DivergenceEventDto[]) {
    this._divergences = v;
    this.rerender();
  }
  get divergences(): DivergenceEventDto[] {
    return this._divergences;
  }
  @Input() set showDivergences(v: boolean) {
    this._showDivergences = v;
    this.rerender();
  }
  get showDivergences(): boolean {
    return this._showDivergences;
  }

  @Output() loadOlder = new EventEmitter<void>();
  @Output() divergenceClick = new EventEmitter<DivergenceEventDto>();

  private _data: ChartResponse | null = null;
  private _signals: SignalRecordDto[] = [];
  private _selectedStrategies: string[] = [];
  private _showRejected = false;
  private _divergences: DivergenceEventDto[] = [];
  private _showDivergences = true;
  private chart: IChartApi | null = null;
  private aoChart: IChartApi | null = null;
  private lastFitFigi: string | null = null;
  /** Epoch (sec) of the earliest bar the chart was last fitted to; used to detect when
   *  newly arriving data extends history further left and warrants a fresh fitContent. */
  private lastFitFrom = Infinity;
  private lastBarIndex = -1;
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
    this.watchEdges();
    if (this._data) {
      this.render(this._data);
    }
  }

  private rerender(): void {
    if (this.chart && this.aoChart && this._data) {
      this.render(this._data);
    }
  }

  private watchEdges(): void {
    if (!this.chart) {
      return;
    }
    let prevFrom = -1;
    this.chart.timeScale().subscribeVisibleLogicalRangeChange((range) => {
      if (!range) {
        return;
      }
      if (this.lastBarIndex >= 0 && range.to > this.lastBarIndex) {
        this.chart!.timeScale().setVisibleLogicalRange({
          from: range.from,
          to: this.lastBarIndex
        });
      }
      const from = range.from;
      if (prevFrom >= 0 && from <= 1 && from < prevFrom) {
        this.loadOlder.emit();
      }
      prevFrom = from;
    });
  }

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
    this.lastBarIndex = data.bars.length - 1;
    const prevRange = this.chart.timeScale().getVisibleRange();
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

    const markers = [...this.applyMarkers(candle, data.figi), ...this.divergenceMarkers(data.figi)];
    if (markers.length) {
      createSeriesMarkers(candle, markers);
    }
    this.applyAoLine(data.figi);

    // Refit whenever a new instrument appears OR the incoming data reaches further back than
    // what we last fitted to (e.g. the first poll returns a truncated live window and a later
    // backtest poll delivers the full period). Otherwise preserve the current viewport.
    const firstEpoch = data.bars.length ? (toTime(data.bars[0].ts) as number) : 0;
    const historyExpanded = firstEpoch > 0 && firstEpoch < this.lastFitFrom - 60;
    if (data.figi !== this.lastFitFigi || historyExpanded) {
      this.lastFitFigi = data.figi;
      this.lastFitFrom = firstEpoch;
      this.chart.timeScale().fitContent();
      this.aoChart.timeScale().fitContent();
    } else if (prevRange) {
      this.chart.timeScale().setVisibleRange(prevRange);
      this.aoChart.timeScale().setVisibleRange(prevRange);
    }
  }

  private applyMarkers(candle: ISeriesApi<any>, figi: string): SeriesMarker<Time>[] {
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
        text: s.type === 'ENTRY' ? 'Buy' : 'Sell'
      });
    }
    if (markers.length > 500) {
      console.warn(`[chart] ${markers.length} markers exceed the 500 sanity cap; trimming.`);
      markers.length = 500;
    }
    return markers;
  }

  private divergenceMarkers(figi: string): SeriesMarker<Time>[] {
    if (!this.showDivergences) {
      return [];
    }
    const markers: SeriesMarker<Time>[] = [];
    for (const d of this.divergences) {
      if (d.figi && d.figi !== figi) {
        continue;
      }
      const bull = d.direction === 'BULLISH';
      const color = d.status === 'EXPIRED' ? '#8a8f9e' : bull ? '#3dd68c' : '#ff5c5c';
      markers.push({
        time: toTime(d.ts),
        position: bull ? ('belowBar' as const) : ('aboveBar' as const),
        color: d.status === 'UNCONFIRMED' ? color + '66' : color,
        shape: 'circle' as const,
        text: '◆'
      });
    }
    return markers;
  }

  private applyAoLine(figi: string): void {
    if (!this.showDivergences || !this.aoChart) {
      return;
    }
    const points: { time: Time; value: number }[] = [];
    for (const d of this.divergences) {
      if (d.figi && d.figi !== figi) {
        continue;
      }
      points.push({ time: toTime(d.aoPeak1Ts), value: d.aoPeak1Value });
      points.push({ time: toTime(d.aoPeak2Ts), value: d.aoPeak2Value });
    }
    if (points.length) {
      const line = this.aoChart.addSeries(LineSeries, {
        color: '#ffd23f',
        lineWidth: 1,
        lineStyle: 2
      });
      line.setData(points);
      this.aoSeries.push(line);
    }
  }

  ngOnDestroy(): void {
    this.chart?.remove();
    this.aoChart?.remove();
  }
}

function alpha(hex: string, a: string): string {
  return hex + a;
}

function toTime(v: string): Time {
  return Math.floor(new Date(v).getTime() / 1000) as UTCTimestamp;
}
