import { Routes } from '@angular/router';
import { loginGuard } from './core/login.guard';

export const routes: Routes = [
  { path: 'login', loadComponent: () => import('./features/login/login.component').then(m => m.LoginComponent) },
  {
    path: 'dashboard',
    canActivate: [loginGuard],
    loadComponent: () => import('./features/dashboard/dashboard.component').then(m => m.DashboardComponent)
  },
  {
    path: 'chart/:figi',
    canActivate: [loginGuard],
    loadComponent: () => import('./features/chart/chart.component').then(m => m.ChartComponent)
  },
  {
    path: 'backtest',
    canActivate: [loginGuard],
    loadComponent: () => import('./features/backtest/backtest-list.component').then(m => m.BacktestListComponent)
  },
  {
    path: 'backtest/new',
    canActivate: [loginGuard],
    loadComponent: () => import('./features/backtest/backtest-form.component').then(m => m.BacktestFormComponent)
  },
  {
    path: 'backtest/:id',
    canActivate: [loginGuard],
    loadComponent: () => import('./features/backtest/backtest-result.component').then(m => m.BacktestResultComponent)
  },
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
  { path: '**', redirectTo: 'dashboard' }
];