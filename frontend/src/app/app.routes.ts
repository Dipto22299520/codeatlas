import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', redirectTo: 'workspace', pathMatch: 'full' },
  {
    path: 'workspace',
    loadComponent: () => import('./pages/workspace').then((m) => m.Workspace),
  },
  {
    path: 'estate',
    loadComponent: () => import('./pages/estate').then((m) => m.Estate),
  },
  {
    path: 'knowledge',
    loadComponent: () => import('./pages/knowledge').then((m) => m.Knowledge),
  },
  {
    path: 'trust',
    loadComponent: () => import('./pages/trust').then((m) => m.Trust),
  },
  { path: '**', redirectTo: 'workspace' },
];
