import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router } from '@angular/router';
import { ApiService } from './api.service';
import { apiKeyInterceptor, API_KEY_HEADER } from './api.interceptor';
import { TokenService } from './token.service';

describe('ApiService + interceptor', () => {
  let http: HttpTestingController;
  let api: ApiService;
  let token: TokenService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([apiKeyInterceptor])),
        provideHttpClientTesting(),
        { provide: Router, useValue: { navigate: jest.fn() } }
      ]
    });
    http = TestBed.inject(HttpTestingController);
    api = TestBed.inject(ApiService);
    token = TestBed.inject(TokenService);
    token.set('secret-key');
  });

  afterEach(() => http.verify());

  it('adds X-Api-Key header', () => {
    api.instruments().subscribe();
    const req = http.expectOne('/api/instruments');
    expect(req.request.headers.get(API_KEY_HEADER)).toBe('secret-key');
    req.flush([]);
  });

  it('handles 401 by clearing token and redirecting to /login', () => {
    const router = TestBed.inject(Router);
    api.instruments().subscribe({ error: () => undefined });
    const req = http.expectOne('/api/instruments');
    req.flush({ error: 'unauthorized' }, { status: 401, statusText: 'Unauthorized' });
    expect(token.has()).toBe(false);
    expect(router.navigate).toHaveBeenCalledWith(['/login']);
  });
});