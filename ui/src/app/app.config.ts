import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideAnimations } from '@angular/platform-browser/animations';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { TUI_RUSSIAN_LANGUAGE, TUI_LANGUAGE } from '@taiga-ui/i18n';
import { provideTuiRoot } from '@taiga-ui/core';
import { ApplicationConfig } from '@angular/core';
import { routes } from './app.routes';
import { apiKeyInterceptor } from './core/api.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideRouter(routes, withComponentInputBinding()),
    provideAnimations(),
    provideHttpClient(withInterceptors([apiKeyInterceptor])),
    { provide: TUI_LANGUAGE, useValue: TUI_RUSSIAN_LANGUAGE },
    // TODO: verify Taiga UI 4 provideTuiRoot options shape.
    provideTuiRoot({})
  ]
};