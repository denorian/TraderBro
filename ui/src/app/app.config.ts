import { provideHttpClient, withInterceptors, withXhr } from '@angular/common/http';
import { provideAnimations } from '@angular/platform-browser/animations';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { TUI_RUSSIAN_LANGUAGE, TUI_LANGUAGE } from '@taiga-ui/i18n';
import { provideTaiga } from '@taiga-ui/core/utils/miscellaneous';
import { ApplicationConfig } from '@angular/core';
import { routes } from './app.routes';
import { apiKeyInterceptor } from './core/api.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideRouter(routes, withComponentInputBinding()),
    provideAnimations(),
    provideHttpClient(withXhr(), withInterceptors([apiKeyInterceptor])),
    provideTaiga(),
    { provide: TUI_LANGUAGE, useValue: TUI_RUSSIAN_LANGUAGE }
  ]
};