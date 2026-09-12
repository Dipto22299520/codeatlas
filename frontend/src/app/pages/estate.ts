import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api } from '../core/api';
import { Asset, CoverageFinding } from '../core/models';

/** Registered scope, owners, language coverage, exclusions, and freshness. */
@Component({
  selector: 'app-estate',
  imports: [DatePipe, FormsModule],
  templateUrl: './estate.html',
})
export class Estate {
  readonly api = inject(Api);
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

  // ------------------------------------------------------ registration

  readonly showForm = signal(false);
  readonly saving = signal(false);
  readonly message = signal<string | null>(null);
  readonly formError = signal<string | null>(null);

  readonly fId = signal('');
  readonly fName = signal('');
  readonly fPath = signal('');
  readonly fOwner = signal('');
  readonly fRole = signal('');
  readonly fLanguage = signal('java');
  readonly fSensitivity = signal('internal');

  isOwner(): boolean {
    return this.api.identity()?.role === 'OWNER';
  }

  toggleForm(): void {
    this.showForm.set(!this.showForm());
    this.formError.set(null);
    this.message.set(null);
  }

  /** Derives a stable id from the business name when none is supplied. */
  private derivedId(): string {
    return (this.fId() || this.fName())
      .toLowerCase().trim()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-+|-+$/g, '');
  }

  register(): void {
    const id = this.derivedId();
    if (!id || !this.fName().trim() || !this.fPath().trim()) {
      this.formError.set('A business name and a source path are required.');
      return;
    }
    this.saving.set(true);
    this.formError.set(null);

    this.api.registerAsset({
      id,
      businessName: this.fName(),
      technicalType: this.fLanguage() === 'java' ? 'spring-boot-service' : 'application',
      role: this.fRole() || 'Not described',
      owner: this.fOwner() || 'Unassigned',
      sourceLocator: this.fPath(),
      sensitivity: this.fSensitivity(),
      scopeStatus: 'in_scope',
      language: this.fLanguage(),
    }).subscribe({
      next: () => {
        // Grant the new asset to the standard demo roles so it is visible
        // without a manual database edit.
        this.api.grantScope(id, ['reviewer', 'reader']).subscribe({
          next: () => this.finishRegistration(id),
          error: () => this.finishRegistration(id),
        });
      },
      error: (err) => {
        this.saving.set(false);
        this.formError.set(
          err.error?.error ??
          (err.status === 403
            ? 'Registering an asset requires the platform owner role.'
            : 'Registration failed.'));
      },
    });
  }

  private finishRegistration(id: string): void {
    this.saving.set(false);
    this.showForm.set(false);
    this.message.set(
      `Registered "${id}". Run a refresh in Refresh & Trust to index it.`);
    this.fId.set(''); this.fName.set(''); this.fPath.set('');
    this.fOwner.set(''); this.fRole.set('');
    this.api.assets().subscribe({ next: (a) => this.assets.set(a) });
  }
}
