import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api } from '../core/api';
import { MeaningRow } from '../core/models';

/** Business meaning authoring, review, anchors, and immutable history. */
@Component({
  selector: 'app-knowledge',
  imports: [FormsModule, DatePipe],
  templateUrl: './knowledge.html',
})
export class Knowledge {
  readonly api = inject(Api);
  readonly meanings = signal<MeaningRow[]>([]);
  readonly anchors = signal<Record<string, unknown>[]>([]);
  readonly filter = signal<'all' | 'reviewed' | 'draft'>('all');
  readonly loading = signal(true);
  readonly message = signal<string | null>(null);
  readonly error = signal<string | null>(null);
  readonly busy = signal(false);

  // Draft authoring form.
  readonly showForm = signal(false);
  /** Empty = create a new rule; otherwise revise this existing meaning. */
  readonly fMeaningId = signal('');
  readonly fName = signal('');
  readonly fStatement = signal('');
  readonly fReason = signal('');
  readonly fAsset = signal('approval-service');
  readonly fPath = signal('');
  readonly fSymbol = signal('');

  constructor() { this.reload(); }

  /** Pre-fills the form to revise an existing statement. */
  revise(row: MeaningRow): void {
    this.fMeaningId.set(row.id);
    this.fName.set(row.business_name);
    this.fStatement.set(row.statement);
    this.fReason.set('');
    this.fSymbol.set('');
    this.fPath.set('');
    this.showForm.set(true);
    this.message.set(null);
  }

  startNew(): void {
    this.fMeaningId.set('');
    this.fName.set('');
    this.fStatement.set('');
    this.fReason.set('');
    this.fSymbol.set('');
    this.showForm.set(!this.showForm());
  }

  reload(): void {
    this.loading.set(true);
    this.api.meanings().subscribe({
      next: (rows) => { this.meanings.set(rows); this.loading.set(false); },
      error: () => { this.error.set('Could not load business knowledge.'); this.loading.set(false); },
    });
    this.api.anchors().subscribe({ next: (a) => this.anchors.set(a), error: () => {} });
  }

  visible(): MeaningRow[] {
    const f = this.filter();
    return this.meanings().filter((m) => f === 'all' || m.status === f);
  }

  canReview(): boolean {
    const role = this.api.identity()?.role;
    return role === 'REVIEWER' || role === 'OWNER';
  }

  brokenAnchors(): Record<string, unknown>[] {
    return this.anchors().filter((a) => a['resolution_status'] === 'broken');
  }

  submitDraft(): void {
    if (!this.fStatement().trim() || !this.fReason().trim() || !this.fSymbol().trim()) {
      this.error.set('A statement, a reason, and at least one anchor are required.');
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.api.draftMeaning({
      // Supplying meaningId adds a version to that rule; omitting it creates
      // a new one. Repairing a broken anchor must revise the existing rule.
      meaningId: this.fMeaningId() || undefined,
      meaningType: 'rule',
      businessName: this.fName() || 'Untitled rule',
      statement: this.fStatement(),
      reason: this.fReason(),
      anchors: [{
        assetId: this.fAsset(),
        path: this.fPath(),
        symbolKey: this.fSymbol(),
        confidenceBasis: 'Author supplied during review.',
      }],
    }).subscribe({
      next: (r) => {
        this.busy.set(false);
        this.message.set(`Draft ${r['versionId']} created. It stays a draft until reviewed.`);
        this.showForm.set(false);
        this.fStatement.set(''); this.fReason.set(''); this.fSymbol.set('');
        this.reload();
      },
      error: (err) => {
        this.busy.set(false);
        this.error.set(err.error?.error ?? 'Could not save the draft.');
      },
    });
  }

  review(row: MeaningRow, decision: 'approve' | 'reject'): void {
    this.busy.set(true);
    this.api.reviewMeaning(row.id, row.version_id, decision,
      decision === 'approve' ? 'Approved through the review workflow.' : 'Rejected.')
      .subscribe({
        next: () => {
          this.busy.set(false);
          this.message.set(
            `Version ${row.version_id} ${decision === 'approve' ? 'approved' : 'rejected'}. ` +
            'Run a refresh to validate its anchors against current source.');
          this.reload();
        },
        error: (err) => {
          this.busy.set(false);
          this.error.set(err.error?.error ?? 'Review failed.');
        },
      });
  }
}
