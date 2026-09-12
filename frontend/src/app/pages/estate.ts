import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { Api } from '../core/api';
import { Asset, CoverageFinding } from '../core/models';

/** Registered scope, owners, language coverage, exclusions, and freshness. */
@Component({
  selector: 'app-estate',
  imports: [DatePipe],
  templateUrl: './estate.html',
})
export class Estate {
  private readonly api = inject(Api);
  readonly assets = signal<Asset[]>([]);
  readonly findings = signal<CoverageFinding[]>([]);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);

  constructor() {
    this.api.assets().subscribe({
      next: (assets) => { this.assets.set(assets); this.loading.set(false); },
      error: () => { this.error.set('Could not load the estate.'); this.loading.set(false); },
    });
    this.api.coverage().subscribe({ next: (f) => this.findings.set(f), error: () => {} });
  }

  findingsFor(assetId: string): CoverageFinding[] {
    return this.findings().filter((f) => f.asset_id === assetId);
  }

  isSupported(asset: Asset): boolean {
    return asset.language.toLowerCase() === 'java';
  }
}
