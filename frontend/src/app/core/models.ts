/** Typed mirrors of the backend answer schema (README section 7.3). */

export type AnswerStatus = 'answered' | 'partial' | 'unknown' | 'blocked';
export type Provenance = 'derived' | 'reviewed' | 'inferred' | 'unknown';

export interface Claim {
  id: string;
  text: string;
  provenance: Provenance;
  evidenceIds: string[];
  limitations: string[];
}

export interface Evidence {
  id: string;
  assetId: string;
  revision: string;
  path: string;
  startLine: number;
  endLine: number;
  symbol?: string;
  excerpt: string;
}

export interface PathStep {
  fromSymbol: string;
  toSymbol: string;
  edgeType: string;
  provenance: Provenance;
  inferenceReason?: string;
  evidenceIds: string[];
}

export interface DependencyPath {
  steps: PathStep[];
  depth: number;
}

export interface Freshness {
  generation: string;
  observedAt?: string;
  stale: boolean;
}

export interface Coverage {
  supportedScope: string;
  gaps: string[];
}

export interface Usage {
  model?: string;
  tokens?: number;
  /** Null when unknown. Never a fabricated zero. */
  cost?: number | null;
}

export interface Answer {
  status: AnswerStatus;
  summary: string;
  claims: Claim[];
  evidence: Evidence[];
  paths: DependencyPath[];
  unknowns: string[];
  nextEvidenceNeeded: string[];
  freshness?: Freshness;
  coverage?: Coverage;
  usage?: Usage;
  requestId: string;
  data?: Record<string, unknown>;
}

export interface Progress {
  stage: string;
  detail: string;
}

export interface AskResponse {
  answer: Answer;
  progress: Progress[];
}

export interface Identity {
  username: string;
  role: 'READER' | 'REVIEWER' | 'OWNER' | 'ASSISTANT';
  authorizedAssets: string[];
  profile: string;
  externalInference: boolean;
}

export interface Asset {
  id: string;
  business_name: string;
  technical_type: string;
  role: string;
  owner: string;
  source_locator: string;
  sensitivity: string;
  scope_status: string;
  language: string;
  last_indexed?: string;
}

export interface ServiceDefinition {
  id: string;
  description: string;
  whenToUse: string;
  distinction: string;
  readOnly: boolean;
  requiredRole: string;
  inputSchema: Record<string, unknown>;
}

export interface RefreshResult {
  jobId: string;
  state: string;
  generationId: string;
  failureReason?: string;
  counts: Record<string, unknown>;
}

export interface MeaningRow {
  id: string;
  meaning_type: string;
  business_name: string;
  current_version: number;
  version_id: string;
  statement: string;
  status: string;
  owner: string;
  author: string;
  authored_at: string;
  reason: string;
  reviewer?: string;
  reviewed_at?: string;
  source_origin: string;
  anchor_count: number;
  broken_anchor_count: number;
}

export interface CoverageFinding {
  id: number;
  asset_id: string;
  finding_type: string;
  path?: string;
  symbol_key?: string;
  detail: string;
}
