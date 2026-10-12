import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { TokenService } from './token.service';

/** Redirects to the login screen when no API key is stored. */
export const loginGuard: CanActivateFn = () => {
  const token = inject(TokenService);
  const router = inject(Router);
  if (token.has()) {
    return true;
  }
  return router.createUrlTree(['/login']);
};