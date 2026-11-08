import { Injectable, inject, signal } from '@angular/core';
import { DOCUMENT } from '@angular/common';

export type Theme = 'dark' | 'light';

const STORAGE_KEY = 'traderbro.theme';

/**
 * Light/dark theme switch backed by Taiga UI's {@code [tuiTheme]} attribute on {@code <body>}.
 * The choice persists in localStorage and is applied before the first paint where possible.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly document = inject(DOCUMENT);
  readonly theme = signal<Theme>(this.initial());

  constructor() {
    this.apply(this.theme());
  }

  toggle(): void {
    this.theme.update((t) => (t === 'dark' ? 'light' : 'dark'));
    this.apply(this.theme());
  }

  private initial(): Theme {
    const saved = this.document.defaultView?.localStorage.getItem(STORAGE_KEY);
    if (saved === 'light' || saved === 'dark') {
      return saved;
    }
    return 'dark';
  }

  private apply(t: Theme): void {
    // Attribute must be on <body>, not on <html>: Taiga's light palette also targets :root (<html>)
    // and the light rule comes later in CSS, so it would win over the dark theme on <html>.
    this.document.body.setAttribute('tuiTheme', t);
    this.document.defaultView?.localStorage.setItem(STORAGE_KEY, t);
  }
}