import { Injectable } from '@angular/core';

const TOKEN_KEY = 'traderbro.apiKey';

/** Stores the UI API key in sessionStorage only (never in URL or logs). */
@Injectable({ providedIn: 'root' })
export class TokenService {
  get(): string {
    return sessionStorage.getItem(TOKEN_KEY) ?? '';
  }

  set(key: string): void {
    sessionStorage.setItem(TOKEN_KEY, key);
  }

  clear(): void {
    sessionStorage.removeItem(TOKEN_KEY);
  }

  has(): boolean {
    return this.get().length > 0;
  }
}