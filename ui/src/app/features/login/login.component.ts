import { ChangeDetectionStrategy, Component } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { TuiButton } from '@taiga-ui/core';
import { TuiInputModule } from '@taiga-ui/kit';
import { TokenService } from '../../core/token.service';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [ReactiveFormsModule, TuiButton, TuiInputModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="login-wrap">
      <form class="login-card" (ngSubmit)="submit()">
        <h2>TraderBro</h2>
        <p class="hint">Введите API-ключ для доступа к мониторингу.</p>
        <tui-input [formControl]="keyControl" placeholder="API-ключ"></tui-input>
        <button
          tuiButton
          type="submit"
          size="m"
          appearance="primary"
          class="login-submit"
          [disabled]="keyControl.value.length === 0"
        >Войти</button>
      </form>
    </div>
  `,
  styles: [`
    .login-wrap { display: flex; height: 100%; align-items: center; justify-content: center; }
    .login-card { display: flex; flex-direction: column; gap: 1rem; width: 340px; }
    .hint { color: var(--tui-text-02); margin: 0; }
  `]
})
export class LoginComponent {
  readonly keyControl = new FormControl('');

  constructor(
    private readonly router: Router,
    private readonly token: TokenService
  ) {}

  submit(): void {
    const key = (this.keyControl.value ?? '').trim();
    if (!key) {
      return;
    }
    this.token.set(key);
    void this.router.navigate(['/dashboard']);
  }
}