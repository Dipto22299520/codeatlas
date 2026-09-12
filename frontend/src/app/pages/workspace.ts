import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api } from '../core/api';
import { Answer, Evidence, Progress } from '../core/models';

/**
 * The primary demonstration surface: ask a question, read evidence-backed
 * findings, inspect dependency paths, and open exact source (README section 10).
 */
@Component({
  selector: 'app-workspace',
  imports: [FormsModule],
  templateUrl: './workspace.html',
  styleUrl: './workspace.css',
})
export class Workspace {
  private readonly api = inject(Api);

  readonly question = signal('');
  readonly answer = signal<Answer | null>(null);
  readonly progress = signal<Progress[]>([]);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly openEvidence = signal<Evidence | null>(null);
  readonly view = signal<'paths' | 'list'>('list');

  /** Example questions drawn from the evaluation corpus. */
  readonly examples = [
    'How does purchase approval work?',
    'If the purchase approval threshold changes, what else is affected?',
    'What is the configured approval threshold?',
    'Which validations run on a purchase request?',
    'What data does the approval process change?',
    'Where should I add a supplier contract check?',
    'What happens if the escalation notification fails?',
    'Is there a currency conversion rule?',
  ];

  ask(text?: string): void {
    const q = (text ?? this.question()).trim();
    if (!q) {
      return;
    }
    this.question.set(q);
    this.loading.set(true);
    this.error.set(null);
    this.answer.set(null);
    this.progress.set([]);
    this.openEvidence.set(null);

    this.api.ask(q).subscribe({
      next: (response) => {
        this.answer.set(response.answer);
        this.progress.set(response.progress);
        this.loading.set(false);
        this.view.set(response.answer.paths.length > 0 ? 'paths' : 'list');
      },
      error: (err) => {
        this.loading.set(false);
        this.error.set(
          err.status === 403
            ? 'Access denied for this question within your authorized scope.'
            : `The request failed (${err.status || 'network error'}). The backend may be unavailable.`,
        );
      },
    });
  }

  /** Resolves a claim's citations to the evidence records in this answer. */
  evidenceFor(ids: string[]): Evidence[] {
    const all = this.answer()?.evidence ?? [];
    return ids
      .map((id) => all.find((e) => e.id === id))
      .filter((e): e is Evidence => e !== undefined);
  }

  showEvidence(evidence: Evidence): void {
    this.openEvidence.set(evidence);
  }

  closeEvidence(): void {
    this.openEvidence.set(null);
  }

  /** Splits an excerpt into numbered lines for the drawer. */
  excerptLines(evidence: Evidence): { number: number; text: string }[] {
    return evidence.excerpt
      .replace(/\n$/, '')
      .split('\n')
      .map((text, index) => ({ number: evidence.startLine + index, text }));
  }

  statusLabel(status: string): string {
    switch (status) {
      case 'answered': return 'Answered';
      case 'partial': return 'Partial — some findings could not be supported';
      case 'unknown': return 'Unknown — insufficient evidence';
      case 'blocked': return 'Blocked — outside your authorized scope';
      default: return status;
    }
  }

  statusClass(status: string): string {
    switch (status) {
      case 'answered': return 'derived';
      case 'partial': return 'inferred';
      case 'blocked': return 'danger';
      default: return 'unknown';
    }
  }

  shortSymbol(symbol: string | undefined): string {
    if (!symbol) return '—';
    const parts = symbol.split('.');
    return parts.length > 2 ? parts.slice(-2).join('.') : symbol;
  }
}
