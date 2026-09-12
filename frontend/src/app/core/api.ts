import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import {
  Answer, AskResponse, Asset, CoverageFinding, Identity, MeaningRow,
  RefreshResult, ServiceDefinition,
} from './models';

/**
 * Typed client for the CodeAtlas backend.
 *
 * Credentials are held in memory for the session only. No API key or secret is
 * ever bundled into the frontend (README section 11).
 */
@Injectable({ providedIn: 'root' })
export class Api {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api';

  private readonly credentials = signal<string | null>(
    sessionStorage.getItem('codeatlas.auth'),
  );
  readonly identity = signal<Identity | null>(null);
  readonly isAuthenticated = computed(() => this.identity() !== null);

  private authHeaders(): HttpHeaders {
    const token = this.credentials();
    return token
      ? new HttpHeaders({ Authorization: `Basic ${token}` })
      : new HttpHeaders();
  }

  /** Verifies credentials by loading the caller's own identity and scope. */
  signIn(username: string, password: string): Observable<Identity> {
    const token = btoa(`${username}:${password}`);
    this.credentials.set(token);
    sessionStorage.setItem('codeatlas.auth', token);
    return this.http
      .get<Identity>(`${this.baseUrl}/me`, { headers: this.authHeaders() })
      .pipe(tap((identity) => this.identity.set(identity)));
  }

  signOut(): void {
    this.credentials.set(null);
    this.identity.set(null);
    sessionStorage.removeItem('codeatlas.auth');
  }

  /** Restores a session on reload when credentials are still held. */
  restore(): Observable<Identity> | null {
    if (!this.credentials()) {
      return null;
    }
    return this.http
      .get<Identity>(`${this.baseUrl}/me`, { headers: this.authHeaders() })
      .pipe(tap((identity) => this.identity.set(identity)));
  }

  ask(question: string): Observable<AskResponse> {
    return this.http.post<AskResponse>(
      `${this.baseUrl}/answers`, { question }, { headers: this.authHeaders() });
  }

  invokeService(serviceId: string, input: Record<string, unknown>): Observable<Answer> {
    return this.http.post<Answer>(
      `${this.baseUrl}/services/${serviceId}/invoke`, input,
      { headers: this.authHeaders() });
  }

  services(): Observable<ServiceDefinition[]> {
    return this.http.get<ServiceDefinition[]>(
      `${this.baseUrl}/services`, { headers: this.authHeaders() });
  }

  /** Registers a new asset. Owner only; the backend validates the source path. */
  registerAsset(asset: Record<string, unknown>): Observable<Record<string, unknown>> {
    return this.http.post<Record<string, unknown>>(
      `${this.baseUrl}/assets`, asset, { headers: this.authHeaders() });
  }

  /** Grants an asset to a user so it appears in their authorized scope. */
  grantScope(assetId: string, usernames: string[]): Observable<Record<string, unknown>> {
    return this.http.post<Record<string, unknown>>(
      `${this.baseUrl}/assets/${assetId}/scope`, { usernames },
      { headers: this.authHeaders() });
  }

  assets(): Observable<Asset[]> {
    return this.http.get<Asset[]>(
      `${this.baseUrl}/assets`, { headers: this.authHeaders() });
  }

  status(): Observable<Answer> {
    return this.http.get<Answer>(
      `${this.baseUrl}/status`, { headers: this.authHeaders() });
  }

  refresh(scope = 'full', assetId?: string): Observable<RefreshResult> {
    return this.http.post<RefreshResult>(
      `${this.baseUrl}/refresh`, { scope, assetId },
      { headers: this.authHeaders() });
  }

  jobs(): Observable<Record<string, unknown>[]> {
    return this.http.get<Record<string, unknown>[]>(
      `${this.baseUrl}/jobs`, { headers: this.authHeaders() });
  }

  coverage(): Observable<CoverageFinding[]> {
    return this.http.get<CoverageFinding[]>(
      `${this.baseUrl}/coverage`, { headers: this.authHeaders() });
  }

  meanings(): Observable<MeaningRow[]> {
    return this.http.get<MeaningRow[]>(
      `${this.baseUrl}/meaning`, { headers: this.authHeaders() });
  }

  anchors(): Observable<Record<string, unknown>[]> {
    return this.http.get<Record<string, unknown>[]>(
      `${this.baseUrl}/meaning/anchors`, { headers: this.authHeaders() });
  }

  meaningHistory(meaningId: string): Observable<Record<string, unknown>[]> {
    return this.http.get<Record<string, unknown>[]>(
      `${this.baseUrl}/meaning/${meaningId}/history`, { headers: this.authHeaders() });
  }

  draftMeaning(body: unknown): Observable<Record<string, unknown>> {
    return this.http.post<Record<string, unknown>>(
      `${this.baseUrl}/meaning`, body, { headers: this.authHeaders() });
  }

  reviewMeaning(meaningId: string, versionId: string, decision: string, note: string) {
    return this.http.post<Record<string, unknown>>(
      `${this.baseUrl}/meaning/${meaningId}/review`,
      { versionId, decision, note }, { headers: this.authHeaders() });
  }

  source(locationId: string): Observable<Record<string, unknown>> {
    return this.http.get<Record<string, unknown>>(
      `${this.baseUrl}/source/${locationId}`, { headers: this.authHeaders() });
  }
}
