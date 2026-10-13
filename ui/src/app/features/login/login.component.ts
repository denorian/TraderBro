import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { TuiButton } from '@taiga-ui/core';
import { finalize } from 'rxjs';
import { TokenService } from '../../core/token.service';
import { API_KEY_HEADER } from '../../core/api.interceptor';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [ReactiveFormsModule, TuiButton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="login-wrap">
      <form class="login-card" (ngSubmit)="submit()">
        <h2>TraderBro</h2>
        <p class="hint">Введите API-ключ для доступа к мониторингу.</p>
        <input
          type="password"
          class="key-input"
          [formControl]="keyControl"
          placeholder="API-ключ"
          autocomplete="off"
        />
        @if (error()) {
          <p class="error">Неверный API-ключ. Проверьте значение и попробуйте снова.</p>
        }
        <button
          tuiButton
          type="button"
          size="m"
          appearance="primary"
          [disabled]="loading() || (keyControl.value ?? '').length === 0"
          (click)="submit()"
        >{{ loading() ? 'Проверка…' : 'Войти' }}</button>
      </form>
    </div>
  `,
  styles: [`
    .login-wrap { display: flex; height: 100%; align-items: center; justify-content: center; }
    .login-card { display: flex; flex-direction: column; gap: 1rem; width: 340px; }
    .hint { color: var(--tui-text-secondary); margin: 0; }
    .error { color: var(--tui-status-negative); margin: 0; font-size: .9rem; }
    .key-input {
      padding: .75rem 1rem;
      border: 1px solid var(--tui-border-normal);
      border-radius: var(--tui-radius-m);
      background: var(--tui-background-base);
      color: var(--tui-text-primary);
    }
  `]
})
export class LoginComponent {
  readonly keyControl = new FormControl('');
  readonly error = signal(false);
  readonly loading = signal(false);

  constructor(
    private readonly router: Router,
    private readonly token: TokenService,
    private readonly http: HttpClient
  ) {}

  submit(): void {
    const key = (this.keyControl.value ?? '').trim();
    if (!key || this.loading()) {
      return;
    }
    this.error.set(false);
    this.loading.set(true);
    // Validate the key against a real endpoint before storing it. The interceptor keeps an
    // explicit header we set here (token is not yet in sessionStorage), so a wrong key fails
    // with 401 instead of silently bouncing back to the login screen.
    this.http.get('/api/instruments', {
      headers: new HttpHeaders().set(API_KEY_HEADER, key)
    }).pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: () => {
          this.token.set(key);
          void this.router.navigate(['/dashboard']);
        },
        error: () => this.error.set(true)
      });
  }
}