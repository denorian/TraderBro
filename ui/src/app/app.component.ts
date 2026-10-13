import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { TuiRoot } from '@taiga-ui/core';
import { filter } from 'rxjs';
import { TokenService } from './core/token.service';

/**
 * App shell: a top navigation menu over the routed content. The menu is shown only when an
 * API key is present (i.e. not on the login screen). Sections: dashboard and charts.
 */
@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, TuiRoot],
  template: `
    <tui-root class="trader-app">
      @if (authed()) {
        <nav class="top-nav">
          <span class="brand">TraderBro</span>
          <a
            class="nav-link"
            routerLink="/dashboard"
            routerLinkActive="nav-active"
            [routerLinkActiveOptions]="{ exact: true }"
          >Дашборд</a>
          <a
            class="nav-link"
            routerLink="/chart/SBER"
            routerLinkActive="nav-active"
          >Графики</a>
          <a
            class="nav-link"
            routerLink="/backtest"
            routerLinkActive="nav-active"
          >Бэктест</a>
          <span class="spacer"></span>
          <a class="nav-link" (click)="logout()">Выйти</a>
        </nav>
      }
      <router-outlet></router-outlet>
    </tui-root>
  `,
  styles: [`
    .top-nav { display: flex; align-items: center; gap: 1rem; padding: .5rem 1rem;
      border-bottom: 1px solid var(--tui-border-normal); }
    .brand { font-weight: 700; margin-right: 1rem; color: var(--tui-text-primary); }
    .nav-link { color: var(--tui-text-secondary); text-decoration: none; padding: .35rem .25rem;
      cursor: pointer; }
    .nav-link:hover { color: var(--tui-text-primary); }
    .nav-link.nav-active { color: var(--tui-text-primary);
      box-shadow: inset 0 -2px 0 var(--tui-status-info); }
    .spacer { flex: 1; }
  `]
})
export class AppComponent {
  private readonly router = inject(Router);
  private readonly token = inject(TokenService);
  private readonly destroyRef = inject(DestroyRef);

  readonly authed = signal(this.token.has());

  constructor() {
    this.router.events.pipe(
      filter((e): e is NavigationEnd => e instanceof NavigationEnd),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe(() => this.authed.set(this.token.has()));
  }

  logout(): void {
    this.token.clear();
    void this.router.navigate(['/login']);
  }
}