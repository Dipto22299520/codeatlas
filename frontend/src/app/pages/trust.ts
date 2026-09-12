import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { Api } from '../core/api';
import { Answer, CoverageFinding, RefreshResult } from '../core/models';

/** Refresh control, job history, generations, coverage, and the model profile. */
@Component({
  selector: 'app-trust',
  imports: [DatePipe],
  templateUrl: './trust.html',
})
export class Trust {
  readonly api = inject(Api);
  readonly status = signal<Answer | null>(null);
  readonly jobs = signal<Record<string, unknown>[]>([]);
  readonly findings = signal<CoverageFinding[]>([]);
  readonly refreshing = signal(false);
  readonly result = signal<RefreshResult | null>(null);
  readonly error = signal<string | null>(null);

  constructor() { this.reload(); }

  reload(): void {
    this.api.status().subscribe({
      next: (s) => this.status.set(s),
      error: () => this.error.set('Could not load platform status.'),
    });
    this.api.jobs().subscribe({ next: (j) => this.jobs.set(j), error: () => {} });
    this.api.coverage().subscribe({ next: (f) => this.findings.set(f), error: () => {} });
  }

  isOwner(): boolean { return this.api.identity()?.role === 'OWNER'; }

  runRefresh(): void {
    this.refreshing.set(true);
    this.result.set(null);
    this.error.set(null);
    this.api.refresh('full').subscribe({
      next: (r) => { this.refreshing.set(false); this.result.set(r); this.reload(); },
      error: (err) => {
        this.refreshing.set(false);
        this.error.set(err.status === 403
          ? 'Refresh requires the platform owner role.'
          : 'The refresh request failed.');
      },
    });
  }

  statusData(key: string): unknown {
    return this.status()?.data?.[key];
  }

  assetRows(): Record<string, unknown>[] {
    return (this.statusData('assets') as Record<string, unknown>[]) ?? [];
  }

  findingTypes(): { type: string; count: number }[] {
    const counts = new Map<string, number>();
    for (const f of this.findings()) {
      counts.set(f.finding_type, (counts.get(f.finding_type) ?? 0) + 1);
    }
    return [...counts.entries()].map(([type, count]) => ({ type, count }));
  }
}
