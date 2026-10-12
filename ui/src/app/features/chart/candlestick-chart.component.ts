import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Input,
  OnDestroy,
  ViewChild
} from '@angular/core';
import { createChart, IChartApi, ISeriesApi, Time, UTCTimestamp } from 'lightweight-charts';
import { ChartResponse } from '../../core/models';

/**
 * Thin Angular wrapper around lightweight-charts for a candlestick chart with the three
 * Alligator lines and an Awesome Oscillator histogram pane, with visible-range sync.
 *
 * TODO: verify lightweight-charts 5.x API (createChart/addCandlestickSeries/addHistogramSeries
 *       and subscribeVisibleLogicalRangeChange) against the pinned version.
 */
@Component({
  selector: 'app-candlestick-chart',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<div #chart class="chart" [class.loading]="!data"></div>`,
  styles: [`.chart { width: 100%; height: 100%; }`]
})
export class CandlestickChartComponent implements AfterViewInit, OnDestroy {
  @ViewChild('chart', { static: true }) private container!: ElementRef<HTMLDivElement>;

  @Input() set data(value: ChartResponse | null) {
    this._data = value;
    if (this.chart) {
      this.render(value);
    }
  }
  get data(): ChartResponse | null {
    return this._data;
  }

  private _data: ChartResponse | null = null;
  private chart: IChartApi | null = null;
  private aoChart: IChartApi | null = null;

  ngAfterViewInit(): void {
    const el = this.container.nativeElement;
    const theme = 'dark';
    this.chart = createChart(el, {
      layout: { background: { color: '#1a1b25' }, textColor: '#d1d2dc' },
      grid: { vertLines: { color: '#262a3a' }, horzLines: { color: '#262a3a' } },
      rightPriceScale: { borderColor: '#30344c' },
      timeScale: { borderColor: '#30344c', timeVisible: true }
    });
    // AO pane — a second chart synced by logical range.
    this.aoChart = createChart(el, {
      layout: { background: { color: '#1a1b25' }, textColor: '#d1d2dc' },
      grid: { vertLines: { color: '#262a3a' }, horzLines: { color: '#262a3a' } },
      rightPriceScale: { borderColor: '#30344c' },
      timeScale: { visible: false }
    });
    this.chart.timeScale().subscribeVisibleLogicalRangeChange((range) => {
      if (range && this.aoChart) {
        this.aoChart.timeScale().setVisibleLogicalRange(range);
      }
    });
    if (this._data) {
      this.render(this._data);
    }
  }

  private render(data: ChartResponse | null): void {
    if (!this.chart || !this.aoChart || !data) {
      return;
    }
    this.chart.remove();
    this.aoChart.remove();

    // Re-create to keep it simple and deterministic per data set.
    this.chart = createChart(this.container.nativeElement, {
      layout: { background: { color: '#1a1b25' }, textColor: '#d1d2dc' },
      grid: { vertLines: { color: '#262a3a' }, horzLines: { color: '#262a3a' } },
      rightPriceScale: { borderColor: '#30344c' },
      timeScale: { borderColor: '#30344c', timeVisible: true }
    });
    const candle = this.chart.addCandlestickSeries();
    candle.setData(data.bars.map((b) => ({
      time: ts(b.ts),
      open: b.open,
      high: b.high,
      low: b.low,
      close: b.close
    })));

    const jaw = this.chart.addLineSeries({ color: '#3d8bff', lineWidth: 2 });
    const teeth = this.chart.addLineSeries({ color: '#ff5c5c', lineWidth: 2 });
    const lips = this.chart.addLineSeries({ color: '#3dd68c', lineWidth: 2 });
    const points = (key: 'jaw' | 'teeth' | 'lips') =>
      data.alligator
        .filter((p) => p[key] !== null)
        .map((p) => ({ time: ts(p.ts), value: (p[key] as number) }));
    jaw.setData(points('jaw'));
    teeth.setData(points('teeth'));
    lips.setData(points('lips'));

    this.aoChart = createChart(this.container.nativeElement, {
      layout: { background: { color: '#1a1b25' }, textColor: '#d1d2dc' },
      grid: { vertLines: { color: '#262a3a' }, horzLines: { color: '#262a3a' } },
      rightPriceScale: { borderColor: '#30344c' },
      timeScale: { visible: false }
    });
    const hist = this.aoChart.addHistogramSeries({
      priceFormat: { type: 'price' },
      color: '#3dd68c'
    });
    hist.setData(data.ao.map((p) => ({
      time: ts(p.ts),
      value: p.ao ?? 0,
      color: p.rising ? '#3dd68c' : '#ff5c5c'
    })));
  }

  ngOnDestroy(): void {
    this.chart?.remove();
    this.aoChart?.remove();
  }

  private ts(v: string): Time {
    return Math.floor(new Date(v).getTime() / 1000) as UTCTimestamp;
  }
}