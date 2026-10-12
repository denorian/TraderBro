import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { TokenService } from './token.service';

export const API_KEY_HEADER = 'X-Api-Key';

/**
 * Adds the API key header to every request and redirects to the login screen on 401
 * (and clears the stored key). The key never appears in URLs or logs.
 */
export const apiKeyInterceptor: HttpInterceptorFn = (req, next) => {
  const token = inject(TokenService);
  const router = inject(Router);
  const key = token.get();
  const authenticated = key
    ? req.clone({ headers: req.headers.set(API_KEY_HEADER, key) })
    : req;
  return next(authenticated).pipe(
    catchError((err) => {
      if (err && err.status === 401) {
        token.clear();
        void router.navigate(['/login']);
      }
      return throwError(() => err);
    })
  );
};