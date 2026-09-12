# CodeAtlas — Complete Project Guide

> Everything that was built, why it was built that way, which file is responsible
> for each behaviour, and how to verify every claim yourself.
>
> The specification lives in [`../README.md`](../README.md) and remains authoritative.
> This document explains the **implementation**.

---

## Table of contents

1. [What this system does](#1-what-this-system-does)
2. [The core idea in five minutes](#2-the-core-idea-in-five-minutes)
3. [Architecture](#3-architecture)
4. [Repository map — every file and its job](#4-repository-map--every-file-and-its-job)
5. [The data model](#5-the-data-model)
6. [How extraction actually works](#6-how-extraction-actually-works)
7. [The refresh pipeline and knowledge drift](#7-the-refresh-pipeline-and-knowledge-drift)
8. [Answer services and the agent](#8-answer-services-and-the-agent)
9. [Security model](#9-security-model)
10. [The frontend](#10-the-frontend)
11. [The synthetic fixture](#11-the-synthetic-fixture)
12. [Testing and evaluation](#12-testing-and-evaluation)
13. [Running it](#13-running-it)
14. [Build journal — bugs found and fixed](#14-build-journal--bugs-found-and-fixed)
15. [What is NOT built](#15-what-is-not-built)
16. [Design decisions and why](#16-design-decisions-and-why)

---

## 1. What this system does

CodeAtlas reads registered source code, extracts its structure deterministically,
lets people attach reviewed business meaning to specific symbols, and then answers
business-language questions **with a citation to exact source at a specific
revision** behind every factual claim.

The distinguishing feature is **knowledge drift detection**: when source changes,
business meaning anchored to a symbol that no longer exists does not silently rot.
The refresh *fails*, names the broken anchor, and keeps serving the last known-good
knowledge until a human repairs it.

### The three rules everything obeys

| Rule | How it is enforced |
|---|---|
| **Never fabricate** | Structural facts come only from a real parser. The language model can rephrase already-verified claims; it cannot introduce a fact, select a tool, or change policy. |
| **Never hide a gap** | Anything the analyzer cannot resolve is recorded as a coverage finding and surfaced in answers and the UI. |
| **Never write to described systems** | Source mounts are read-only. There is no code path that writes to an indexed repository. |

---

## 2. The core idea in five minutes

Ask: *"If the purchase approval threshold changes, what else is affected?"*

Here is what physically happens, and which file does it:

```
1. POST /api/answers                      api/AnswerController.java
      ↓
2. Classify intent → "change_impact"      agents/AnswerOrchestrator.java :plan()
   (deterministic keyword rules — the MODEL NEVER CHOOSES THE TOOL)
      ↓
3. Run the handler                        answers/AnswerServiceHandlers.java
   - resolve "threshold" → approval.threshold.amount
   - traverse who depends on it           answers/ImpactTraversal.java
   - read the approved config snapshot
   - list ordered checks + data effects
   - append a LABELLED recommendation
      ↓
4. Load exact source for every citation   answers/EvidenceService.java
   (scope-filtered in SQL — unauthorized assets never load)
      ↓
5. VERIFY every claim                     AnswerOrchestrator.java :verify()
   - does each cited evidence id exist in the retrieved set?
   - does every evidence record belong to an asset this caller may read?
   - claims that fail are REJECTED and listed as rejected
      ↓
6. Compose prose from verified claims only  AnswerOrchestrator.java :compose()
   - source text is fenced as UNTRUSTED DATA
   - if the model fails → answer still returned, marked "explanation unavailable"
      ↓
7. Render with provenance badges          frontend/src/app/pages/workspace.html
   + evidence drawer showing numbered source at a revision digest
```

**The key architectural insight:** steps 2–5 are entirely deterministic. The model
only runs at step 6, and by then every fact is already fixed. This is why a prompt
injection buried in the source code cannot change the answer — there is no step
where model output becomes a fact.

---

## 3. Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│  Angular 21 frontend  (:4200)                                     │
│  Workspace · Estate · Business Knowledge · Refresh & Trust        │
└───────────────────────────┬──────────────────────────────────────┘
                            │  /api proxied (proxy.conf.json)
┌───────────────────────────▼──────────────────────────────────────┐
│  Spring Boot 3.5.3 backend  (:8090)   Java 21                     │
│                                                                   │
│  api/          REST controllers + audit                           │
│  agents/       bounded orchestrator, model adapter                │
│  answers/      15 service handlers, evidence, traversal           │
│  refresh/      staged pipeline, anchor validation, publication    │
│  analysis/     JavaParser extractor, config reader, scanner       │
│  knowledge/    generation-scoped graph store, type enums          │
│  meaning/ …    curated business meaning (in api/MeaningController)│
│  security/     principal, asset scope, HTTP Basic + roles         │
│  config/       properties + profile boundary validator            │
└───────┬───────────────────────────────────────┬──────────────────┘
        │                                       │
┌───────▼─────────────┐              ┌──────────▼─────────────────┐
│ PostgreSQL 16       │              │ Read-only source mounts    │
│ (:55432, Docker)    │              │ demo-estate/revisions/{A,B}│
│ 23 tables, FTS      │              │ NEVER written to           │
└─────────────────────┘              └────────────────────────────┘
                                                │
                                     ┌──────────▼─────────────────┐
                                     │ OpenAI-compatible endpoint │
                                     │ (optional — prose only)    │
                                     └────────────────────────────┘
```

### Why these choices

| Choice | Reason |
|---|---|
| **Single Spring Boot app**, not microservices | The spec explicitly prefers one deployable. Background jobs run in-process. |
| **Java 21**, not 25 | Spring Boot 3.5 officially targets 21. Java 25 is installed on this machine but is not a supported LTS target for this stack. |
| **PostgreSQL 16 in Docker on :55432** | The host had a PostgreSQL needing unknown credentials. A container on a non-default port leaves the host untouched and is reproducible. |
| **Maven wrapper, no system Maven** | Maven was not installed. `backend/mvnw` pins 3.9.9 and bootstraps itself — a clean machine needs nothing extra. |
| **JavaParser with symbol resolution** | The spec forbids regex-only parsing and forbids an LLM as the structural extractor. |
| **Flyway migrations** | Reproducible schema from an empty database with no manual SQL. |

---

## 4. Repository map — every file and its job

### Root

| File | Responsibility |
|---|---|
| `README.md` | The original specification (510 lines), preserved, with implementation notes appended as §17. |
| `compose.yaml` | PostgreSQL 16 on port 55432 with a healthcheck and named volume. |
| `.env` / `.env.example` | Runtime configuration. `.env` is gitignored and holds the model key. |
| `run-backend.sh` | Loads `.env` and starts the backend via the Maven wrapper. |
| `restart-backend.sh` | Kills whatever holds :8090 (`fuser`), starts a detached instance, waits for readiness. Written because a naive `pkill` killed the calling shell. |
| `switch-revision.sh` | Repoints registered Java assets at fixture revision A or B — the knowledge-drift demo lever. |

### `backend/src/main/java/com/codeatlas/`

#### `analysis/` — deterministic extraction (1042 lines)

| File | Lines | Responsibility |
|---|---|---|
| **`JavaSpringAnalyzer.java`** | 675 | **The heart of the system.** Two-pass AST walk using JavaParser + symbol solver. Pass 1 collects classes, methods, Spring endpoints (composing `@RequestMapping` + `@GetMapping` into a route), and field types. Pass 2 resolves invocations, `@Value` config uses, `JdbcTemplate` reads/writes with literal SQL, and outbound HTTP. **Anything it cannot resolve becomes a `CoverageFinding`, never a guess.** Also splits camelCase identifiers so `notifyEscalation` matches the word "escalation" in search. |
| `SourceScanner.java` | 95 | Deterministic sorted file enumeration. Excludes `target/`, `node_modules/`, `.git/` etc. **with a recorded reason**. Rejects symlinks and any path whose real location escapes the asset root. |
| `ConfigurationAnalyzer.java` | 117 | Flattens YAML/properties into dotted keys with line numbers. Hard-rejects secret-like key fragments (`password`, `token`, `secret`, …) **even if allowlisted** — defence in depth. |
| `Identities.java` | 62 | Stable identity derivation. Node identity = `asset + type + qualifiedName` (so moving a method's lines does not create a new symbol). Location identity **includes the revision digest** because evidence is always revision-specific. |
| `ExtractedModel/Node/Edge/Location.java`, `CoverageFinding.java`, `ConfigValue.java` | 113 | Immutable records carrying extraction output and file counters. |

#### `knowledge/` — the graph store (288 lines)

| File | Lines | Responsibility |
|---|---|---|
| `KnowledgeStore.java` | 196 | Batch writes of nodes/edges/locations; atomic generation publication in one transaction; scope-filtered reads. **`scoped()` prefixes every node and edge id with its generation** — this fixed a serious bug where re-extraction silently dropped rows via `ON CONFLICT DO NOTHING`. |
| `NodeType.java` / `EdgeType.java` / `Provenance.java` | 92 | Closed enums for the 10 node types, 8 edge types, and 4 provenance labels. Unknown values throw. |

#### `refresh/` — the pipeline (426 lines)

| File | Responsibility |
|---|---|
| **`RefreshService.java`** | Stages: `scan-and-extract` → `resolve-anchors` → `validate` → `publish`. Computes a manifest hash over all file contents to pin the revision. Matches outbound routes against endpoints in *other* assets and records those as **`inferred`** with a stated reason. **`resolveAnchors()` is the broken-anchor rule**: any reviewed meaning whose anchor no longer resolves throws `RefreshFailure`, so the candidate generation is never published and the previous one keeps serving. |

#### `answers/` — services and evidence (1723 lines)

| File | Lines | Responsibility |
|---|---|---|
| **`AnswerServiceHandlers.java`** | 1129 | Implements all 15 services. Every handler returns claims with provenance and evidence ids. Notable: `configuration()` uses `DISTINCT ON` to return the snapshot for the *current* revision while preserving snapshot history; `search()` falls back to any-term matching and then reports **`partial`**, never a confident answer to a weak match; `changeImpact()` composes impact + config + checks + effects + a clearly labelled recommendation. |
| `ServiceRegistry.java` | 155 | **The single source of truth for all 15 services** — id, description, when to use, how it differs from adjacent services, typed parameters, required role. REST discovery and invocation both read this, so registering a service exposes it everywhere with no transport edits. |
| `AnswerModels.java` | 147 | The answer schema from spec §7.3. Builder de-duplicates evidence when composites merge from several handlers. **Unknown cost is `null`, never a fabricated `0`.** |
| `ImpactTraversal.java` | 128 | Bounded BFS over the graph, depth-capped at 6 and 50 paths. **Caller scope is applied inside the SQL**, so unauthorized assets are never even traversed. |
| `EvidenceService.java` | 121 | Resolves stored locations to exact source excerpts by reading the read-only mount. Scope-filtered in SQL. Returns `[source unavailable]` rather than throwing if a file moved. |
| `ServiceDefinition.java` | 43 | Record + JSON Schema generation for a service's inputs. |

#### `agents/` — bounded orchestration (459 lines)

| File | Lines | Responsibility |
|---|---|---|
| **`AnswerOrchestrator.java`** | 330 | Four stages. **`plan()` is deterministic keyword classification — the model never picks the tool.** Specific patterns are matched before generic ones (`"where should"` before `"check"`) because generic words appear in most questions. **`verify()`** rejects any derived claim whose citations are missing from the retrieved set, and fails closed if evidence for an unauthorized asset ever appears. **`compose()`** fences source text as untrusted data and instructs the model to ignore instructions inside it. If the model fails, the answer is still returned with findings intact and a note that the explanation is unavailable. |
| `ModelClient.java` | 129 | OpenAI-compatible adapter. Provider-specific headers live *only* here. Re-checks the enterprise egress policy at request time, not just at startup. **Deliberately ignores the `reasoning` field** that reasoning-capable models return — hidden chain-of-thought is never surfaced. Returns an "unavailable" result instead of throwing so callers degrade gracefully. |

#### `api/` — REST surface (545 lines)

| File | Endpoints |
|---|---|
| `PlatformController.java` | `GET/POST /api/assets`, `POST /api/refresh`, `GET /api/jobs[/{id}]`, `GET /api/status`, `GET /api/me`, `GET /api/source/{locationId}`, `GET /api/coverage`, `GET /api/audit` |
| `ServiceController.java` | `GET /api/services` (discovery), `POST /api/services/{id}/invoke` |
| `AnswerController.java` | `POST /api/answers` (natural language), `POST /api/answers/runs`. Records an `answer_run` row for every question. |
| `MeaningController.java` | `GET /api/meaning`, `/anchors`, `/{id}/history`, `POST /api/meaning` (draft), `POST /api/meaning/{id}/review`. **Rejects a draft with no anchor** and refuses to mutate a reviewed version. |
| `AuditService.java` | Writes `audit_event` rows. Deliberately does not store raw prompts or source. |

#### `security/` and `config/` (316 lines)

| File | Responsibility |
|---|---|
| `SecurityConfig.java` | HTTP Basic over the `platform_user` table with BCrypt. Registry and refresh are `OWNER`-only; review requires `REVIEWER` or `OWNER`. CORS limited to :4200. |
| `ScopeService.java` | Resolves the caller to a `Principal` carrying **the explicit list of asset ids they may read**. Owners get the whole estate; everyone else gets only granted assets. |
| `Principal.java` | The record threaded into every handler and every SQL query. |
| **`ProfileBoundaryValidator.java`** | **NFR-10 enforcement.** In the `enterprise` profile, a non-local inference endpoint throws at construction time — the application refuses to start rather than silently falling back to cloud. |
| `CodeAtlasProperties.java` | Typed binding for profile, source roots, model config, agent budgets. |

### `backend/src/main/resources/db/migration/` (471 lines)

| Migration | Contents |
|---|---|
| `V1__core_knowledge_model.sql` | Registry, source revisions/locations, generations, nodes, edges, coverage findings. **Check constraints reject unknown node and edge types at the database level.** |
| `V2__curated_and_governance.sql` | Business meaning + immutable versions + anchors, processes and stages, reference allowlist and snapshots, refresh jobs and stages, answer runs, audit events, platform settings. |
| `V3__access_and_search.sql` | Users, per-user asset scope, and **generated `tsvector` columns** with GIN indexes for full-text retrieval. |
| `V4__demo_seed.sql` | 4 users with real verified BCrypt hashes, 4 assets (including the deliberately unsupported TypeScript one), scoped grants, config allowlist. |
| `V5__demo_meaning_and_process.sql` | 3 reviewed meanings with anchors, and the 10-stage purchase approval process. Labelled explicitly as *demo*-reviewed, not a real organizational approval. |

### `frontend/src/app/`

| File | Responsibility |
|---|---|
| `core/api.ts` | Typed HTTP client. Credentials held in `sessionStorage` for the session only — **no key is ever bundled**. |
| `core/models.ts` | TypeScript mirrors of the backend answer schema. |
| `app.ts` / `.html` / `.css` | Shell: navigation, sign-in, and the always-visible **deployment profile badge**. |
| `pages/workspace.*` | The primary surface: question box, example chips, progress trail, findings with provenance badges, dependency paths (diagram **and** an accessible list alternative), the "what this answer does not establish" panel, and the evidence drawer with numbered source. |
| `pages/estate.*` | Per-asset owner, scope, language support, freshness, and coverage gaps. |
| `pages/knowledge.*` | Draft/reviewed filters, broken-anchor warnings, authoring form, approve/reject, and immutable version history. |
| `pages/trust.*` | Platform state, indexed assets with counts, coverage gaps, refresh history with failure reasons, and the owner-only refresh button. |
| `styles.css` | Design tokens. **Provenance is distinguished by label *and* colour**, so it survives colour-blind viewing. Visible keyboard focus everywhere. |

---

## 5. The data model

23 tables. The critical split:

```
GENERATED (disposable, rebuilt every refresh)   CURATED (survives rebuilds)
├── knowledge_node          ← generation-scoped ├── business_meaning
├── knowledge_edge          ← generation-scoped ├── business_meaning_version  (immutable)
├── coverage_finding                            ├── meaning_anchor
├── source_revision / source_location           ├── business_process / process_stage
└── reference_snapshot      (immutable history) └── reference_allowlist
```

A failed refresh destroys nothing curated. `RefreshAndDriftTest` asserts this.

### Provenance — four distinct labels, never blurred

| Label | Meaning | Needs a citation? |
|---|---|---|
| `derived` | A named deterministic extractor found it | **Yes** — rejected without one |
| `reviewed` | A human approved this interpretation | No — carries reviewer + date instead |
| `inferred` | Plausible, with stated evidence *and* stated limits | Yes, plus the reason |
| `unknown` | Insufficient evidence | N/A |

**Confidence is an evidence classification with an explanation, never a model-generated probability.**

### Generations

Every refresh builds a `candidate` generation. Only if every stage passes is it
promoted to `active` in one transaction, demoting the old one to `historical`.
Answers therefore never observe a half-built graph.

---

## 6. How extraction actually works

`JavaSpringAnalyzer` runs two passes so that calls can resolve to declarations
that appear later in the file set.

**Pass 1 — declarations.** For each class: record it, its Spring stereotype, its
`@RequestMapping` base path, and its field types (needed to resolve
`this.someService.call()` in pass 2). For each method: record it, emit a
`contains` edge, and if it carries a mapping annotation, compose the full route
and emit an `endpoint` node plus `exposes` and `invokes` edges.

**Pass 2 — relationships.** For each method call, in priority order:

| Detected | Action |
|---|---|
| Reflective dispatch (`Class.forName`, `.invoke`, `.newInstance`) | **Record `unresolved_dynamic_call`. Never guess a target.** |
| `JdbcTemplate` call with a literal SQL string | Extract the table, emit `reads` or `writes` |
| `JdbcTemplate` call with non-literal SQL | Record `unsupported_construct` — the table cannot be known statically |
| `RestTemplate` call | Emit an `integration` node and `calls_endpoint` edge |
| Anything else | Try full symbol resolution; fall back to declared field types; if the target is outside the asset, skip silently (not an error) |

### What the fixture actually yields

Verified live against the active generation:

| Nodes (79) | | Edges (112) | |
|---|---|---|---|
| method | 45 | invokes (derived) | 45 |
| class | 16 | contains (derived) | 45 |
| configuration_item | 6 | belongs_to (derived) | 16 |
| table | 4 | uses_config (derived) | 6 |
| endpoint | 4 | exposes (derived) | 4 |
| application | 3 | reads (derived) | 4 |
| integration | 2 | calls_endpoint (derived) | 2 |
| | | **calls_endpoint (inferred)** | **2** |
| | | writes (derived) | 2 |

The **2 inferred** edges are the cross-application HTTP handovers
(portal → approval, approval → payment). They are inferred because a matching
route string is evidence of a likely call, not proof of one — and the stored
`inference_reason` says exactly that.

### Cross-asset inference, verbatim from the database

> *"Outbound route `/api/payments/eligibility` in approval-service matches an
> endpoint declared by payment-service. No explicit contract establishes this
> call, so it is inferred from route equality alone."*

### Coverage findings — the honesty ledger

| Asset | Type | Detail |
|---|---|---|
| approval-service | `unresolved_dynamic_call` | Reflective dispatch `newInstance` at line 25 |
| approval-service | `unresolved_dynamic_call` | Reflective dispatch `invoke` at line 27 |
| supplier-ui | `unsupported_language` | TypeScript has no structural analyzer |

The `AuditNotifier` in the fixture dispatches reflectively **on purpose**, to prove
the system reports what it cannot resolve rather than inventing a call graph.

---

## 7. The refresh pipeline and knowledge drift

### Stages

```
scan-and-extract → resolve-anchors → validate → publish
```

Any stage failing throws `RefreshFailure`. The candidate is marked `failed`, the
previous generation stays `active`, and the job records the reason.

### The broken-anchor rule (BR-18) — the marquee feature

Revision B renames `ApprovalService.approve` to `decideApproval`. A reviewed
business rule is anchored to the old name. On refresh:

```
state: failed
reason: Refresh failed: 1 curated anchor(s) no longer resolve.
        meaning 'Purchase approval threshold' (bm-threshold-rule v1)
        anchored to approval-service:com.example.approval.ApprovalService.approve
        at src/main/java/com/example/approval/ApprovalService.java.
        The previous generation remains active; dependent answers are blocked
        until the meaning is re-reviewed against the new source.
```

**Precisely one anchor breaks.** The config-key anchor and the two untouched
anchors still resolve — verified by `RefreshAndDriftTest.onlyTheRenamedAnchorBreaks`.

### The repair workflow

1. Reviewer authors a new version anchored to `decideApproval` (with a reason).
2. Reviewer approves it → v1 becomes `superseded`, v2 becomes `reviewed`.
3. Owner refreshes → **succeeds**. The threshold now reports **25000** from revision B.
4. Version history retains both versions — the old one is preserved, not overwritten.

This full cycle was exercised end to end against the running system.

---

## 8. Answer services and the agent

### The 15 services (all registered once in `ServiceRegistry.java`)

**Primitives:** `navigate` · `impact` · `flow` · `checks` · `effects` · `explain` ·
`placement` · `search` · `detail` · `configuration` · `status`

**Composites:** `change_impact` · `failure_trace` · `input_acceptance` · `describe_process`

Each definition carries a *distinction* field explaining how it differs from
adjacent services — so an assistant client can choose correctly.

### Execution bounds

| Bound | Value | Enforced in |
|---|---|---|
| Max model calls | 2 | `CodeAtlasProperties.Agent` |
| Composite deadline | 15 000 ms | checked before composition |
| Traversal depth | 6 (requests capped) | `ImpactTraversal` |
| Max paths | 50 | `ImpactTraversal` |

### Why prompt injection cannot work here

A payload was planted in the fixture source instructing the model to reveal
unauthorized source, disable the boundary, and report a fake threshold of 999999.
Result, tested live:

| Attack vector | Outcome |
|---|---|
| Fabricate threshold `999999` | **Blocked** — reported the real 50000 from the snapshot |
| Leak `payment-service` to an unauthorized reader | **Blocked** |
| Exfiltrate to an attacker URL | **Blocked** — no outbound tool exists |

This holds *structurally*, not by filtering: the model runs only after facts are
fixed, cannot select tools, and has no network tool. Locked in by
`InjectionAndPolicyTest`.

---

## 9. Security model

### Scope filtering happens in SQL, before retrieval

Every query carries `asset_id = ANY(?)` bound to the caller's authorized list.
An unauthorized asset is never loaded into memory, never reaches the model
context, never appears in a cache, and never lands in an export.

Demonstrated with two accounts asking the *same* question:

| User | Scope | Sees `com.example.payment` symbols? |
|---|---|---|
| `reader` | portal + approval | **No** |
| `reviewer` | all three | Yes |

### Roles

| Role | Can do |
|---|---|
| READER | Search, inspect, ask questions within scope |
| REVIEWER | + author and review business meaning |
| OWNER | + register assets, run refresh, read audit log |
| ASSISTANT | Read-only service access for a client |

### Deployment profiles

| Profile | Behaviour |
|---|---|
| `demo` | Synthetic/public assets. External inference allowed **and badged in the UI**. |
| `enterprise` | A non-local model endpoint **fails startup**. No silent cloud fallback. No model configured is valid — answers return verified evidence and say the explanation is unavailable. |

### Secrets

Environment-injected only. `.env` is gitignored; `.env.example` carries no value;
no key reaches the frontend bundle; `ConfigurationAnalyzer` refuses to snapshot
secret-like keys even if an owner allowlists them; audit rows deliberately exclude
raw prompts and source.

---

## 10. The frontend

Four pages, all wired to real endpoints — **no mocked data anywhere**.

### Workspace (the demo surface)

- Question box + 8 example chips from the evaluation corpus
- **Progress trail** showing planning → retrieval → verification → composition
  (summaries only — never hidden chain-of-thought)
- Findings with `DERIVED` / `REVIEWED` / `INFERRED` badges
- Every citation is a button opening the **evidence drawer**: numbered source
  lines, the file path, the revision digest, and the symbol
- Dependency paths as a diagram **and** an accessible table (the graph is never
  the only way to understand a result); inferred edges are dashed and amber
- A permanent **"What this answer does not establish"** panel
- Model, token count, and **real cost** (`$0.0002865`), or "not reported"

### Estate / Business Knowledge / Refresh & Trust

- Estate: per-asset owner, scope, language support badge, freshness, coverage gaps
- Knowledge: draft/reviewed filters, broken-anchor alerts, authoring form
  (rejects a draft with no anchor), approve/reject, immutable history
- Trust: platform owner, profile and inference posture, per-asset extraction counts
  (supplier-ui honestly showing **0** with a TypeScript badge), coverage gaps,
  and refresh history with full failure reasons

### Required states, all implemented

empty estate · indexing · partial coverage · no evidence · model unavailable ·
access denied · stale source · broken anchor · successful answer.
**No dead buttons and no fabricated counters.**

---

## 11. The synthetic fixture

Three Spring services under `demo-estate/revisions/`, 32 Java files across both revisions.

```
purchase-portal  ──HTTP──▶  approval-service  ──HTTP──▶  payment-service
   :8080                       :8081                        :8082
```

Built to contain exactly what the spec requires to prove honesty:

| Required feature | Where |
|---|---|
| Explicit HTTP handovers | `PurchaseRequestService` → `EligibilityClient` |
| One configuration threshold | `approval.threshold.amount` = 50000 |
| Ordered checks | 4 sequential checks in `ApprovalService.approve` |
| A persistence effect | `ApprovalRepository.saveDecision` → `approval_decision` |
| An identifiable failure | `"Escalation notification failed"` |
| **One unsupported dynamic relationship** | `AuditNotifier` reflective dispatch |

### The two revisions

| | Revision A | Revision B |
|---|---|---|
| Threshold | 50000 | **25000** |
| Method name | `approve` | **`decideApproval`** ← breaks the anchor |
| Checks | 4 | 5 (adds currency check) |

Switch with `./switch-revision.sh A|B`. These are immutable fixtures — CodeAtlas
reads them and never modifies them.

---

## 12. Testing and evaluation

### 22 automated tests, all passing

| Suite | Tests | Proves |
|---|---|---|
| `JavaSpringAnalyzerTest` | 6 | Real extraction of classes, routes, cross-class calls, config uses, SQL tables; **determinism**; reflective dispatch is reported |
| `AuthorizationBoundaryTest` | 6 | Unauthenticated rejected; reader cannot refresh or register; **unauthorized asset does not leak through search**; evidence is scope-checked |
| `RefreshAndDriftTest` | 5 | Two refreshes of identical source produce identical canonical output; **broken anchor fails and preserves the prior generation**; only the renamed anchor breaks; curated content survives failure |
| `InjectionAndPolicyTest` | 5 | Embedded instructions cannot select tools; unauthorized assets never enter an answer; **enterprise profile rejects external inference**; local endpoint accepted; no-model is valid |

```bash
cd backend && JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw test
# Tests run: 22, Failures: 0, Errors: 0
```

### Evaluation corpus — 9/9 passing

`evaluation/questions.json` holds 10 questions with expected evidence, expected
claims, and **allowed uncertainty**. `evaluation/evaluate.py` scores a live backend.

| ID | Category | Verdict | Status |
|---|---|---|---|
| q1 | process description | PASS | answered |
| q2 | threshold lookup | PASS | answered |
| q3 | enforcement location | PASS | answered |
| q4 | failure cause candidate | PASS | partial |
| q5 | effect | PASS | answered |
| q6 | cross-app handover | PASS | answered |
| q7 | change impact | PASS | answered |
| q8 | placement recommendation | PASS | answered |
| q9 | **unsupported behaviour** | PASS | **partial** (correctly refuses to invent a rule) |
| q10 | broken anchor | MANUAL | covered by `RefreshAndDriftTest` |

**9/9 scored questions pass.** Scoring also requires every claim to carry
provenance and every `derived` claim to cite evidence.

### Measured performance

| Metric | Value | Caveat |
|---|---|---|
| Latency p50 | **~720–860 ms** | On a **16-file fixture**, not an enterprise estate |
| Latency p95 | **~1.7–2.6 s** | Well inside the ~15 s composite target |
| Cost per question | **~$0.00029** | `openai/gpt-oss-120b` via OpenRouter |
| Full rebuild | ~1 s | **Not extrapolated** toward the 100-minute target |

### Browser verification

The primary journey was driven in headless Chromium via Playwright: sign in →
ask the threshold question → 8 claims render → open the evidence drawer showing
real numbered source → navigate all four pages. **No console errors.**

---

## 13. Running it

### Prerequisites
JDK 21 · Node 20+ · Docker. **Maven is not required** — `backend/mvnw` bootstraps itself.

### Start

```bash
cp .env.example .env        # optional: add a model endpoint + key
docker compose up -d        # PostgreSQL on :55432
./run-backend.sh            # backend on :8090
cd frontend && npm install && npx ng serve    # UI on :4200
```

Sign in as `owner` / `owner-demo`, open **Refresh & Trust**, click **Run full refresh**.

### Accounts

| User | Password | Role | Scope |
|---|---|---|---|
| `owner` | `owner-demo` | OWNER | Everything |
| `reviewer` | `reviewer-demo` | REVIEWER | All three services |
| `reader` | `reader-demo` | READER | **Portal + approval only** (payment-service deliberately excluded) |
| `assistant` | `assistant-demo` | ASSISTANT | Read-only services |

### Verify

```bash
cd backend && ./mvnw test                                     # 21 tests
python3 evaluation/evaluate.py http://localhost:8090 reviewer:reviewer-demo   # 9/9
```

Full instructions: [`SETUP.md`](SETUP.md) · Demo walkthrough: [`DEMO.md`](DEMO.md)

---

## 14. Build journal — bugs found and fixed

Real defects caught during construction, each by a failing test or a live run.

| # | Bug | Root cause | Fix |
|---|---|---|---|
| 1 | Refresh failed on FK violation | Config snapshots written before their `source_location` rows existed | Collect snapshots, save locations, *then* write snapshots |
| 2 | `change_impact` returned HTTP 500 | `jdbc.queryForList(sql, String.class, array)` — Java bound the array as varargs, not one parameter | Cast to `(Object)`; **audited the whole codebase and found 3 more latent instances** |
| 3 | Business question didn't resolve to a config key | Matcher only checked if the *proposal* contained the key | Score keys by how many dotted word-parts appear; require ≥2 |
| 4 | **All 4 anchors broke on revision B (should be 1)** | Node ids were not generation-scoped, so `ON CONFLICT DO NOTHING` silently dropped re-extracted rows — the candidate generation had **1 node** | `KnowledgeStore.scoped()` prefixes every id with its generation |
| 5 | Threshold still reported 50000 after switching to revision B | Immutable snapshot history returned rows in arbitrary order | `DISTINCT ON (asset, key) … ORDER BY snapshot_at DESC` |
| 6 | Verifier rejected 2 valid claims | `changeImpact` merged effect *claims* but not their *evidence* | Carry evidence with claims; de-duplicate in the builder |
| 7 | Multi-word business questions found nothing | `websearch_to_tsquery` ANDs every term | Any-term fallback that reports **`partial`**, never confident |
| 8 | 3 evaluation questions misrouted | Generic keywords (`check`, `change`, `fails`) matched before specific phrases | Reordered intent rules: specific patterns first |
| 9 | "escalation" didn't match `notifyEscalation` | FTS does not split camelCase | Index split identifiers alongside the original |
| 10 | `/api/assets` returned 500 | Same varargs bug as #2, found by the browser test | Cast to `(Object)` |
| 11 | `restart-backend.sh` killed the calling shell | `pkill -f` matched its own process | Use `fuser -k 8090/tcp` + `setsid` |
| 12 | **Wrong threshold reported after switching revisions back** | `DISTINCT ON … ORDER BY snapshot_at DESC` returned the newest snapshot *ever taken*, which belonged to a revision no longer in use | Constrain snapshots to the revision the **active generation** actually indexed; regression-tested |
| 13 | Re-indexing an earlier revision left a newer `indexed_at` on the other row | `ON CONFLICT DO UPDATE SET indexed_at = now()` touched the stale row | Also refresh `observed_at` on conflict |

Bugs #12 and #13 were found by the end-to-end test script *after* the build was
"finished" — a reminder that switching state back and forth exercises paths a
one-way demo never touches.

**Lesson worth recording:** bug #4 was invisible without the browser and
end-to-end runs — the refresh *reported success*. Only checking the actual anchor
outcomes revealed that extraction had silently produced one node.

---

## 15. What is NOT built

Stated plainly, because the spec forbids presenting stubs as completion.

| Gap | Requirement | Status |
|---|---|---|
| **MCP assistant interface** | BR-58, BR-59, BR-63 | **Not built.** The largest gap; CAP-8 is a company Must. Services are exposed over authenticated REST from the same single registry, so an MCP transport would read the same definitions — but it does not exist. |
| **Semantic / vector retrieval** | BR-33 | **Partial.** Keyword FTS with camelCase splitting and an any-term fallback. Declared as degraded mode in every affected answer — *not* CAP-5 compliance. |
| Incremental refresh | BR-37 | Not built; every refresh is a full rebuild. |
| Stage restart | BR-67 | Stages are recorded; restart-from-stage is not implemented. |
| Expert query DSL | BR-57 | Not built (no arbitrary SQL is exposed either). |
| Locks / reservations | BR-28 | Not modelled; the fixture contains none. |
| AI draft suggestions | BR-21 | Schema supports `ai_suggestion` origin; generation is not implemented. |
| Scale + rebuild benchmarks | NFR-2, NFR-3, NFR-4 | **Deliberately not extrapolated** from a 16-file fixture. |
| User studies, availability drills | NFR-6, NFR-8, NFR-11 | Not run. |

Full line-by-line accounting: [`REQUIREMENTS-STATUS.md`](REQUIREMENTS-STATUS.md).

**This is a working vertical slice. It is not production-ready and does not claim
full company compliance.**

---

## 16. Design decisions and why

### The model is deliberately weak

It cannot select a tool, add a fact, call anything, or see an unauthorized asset.
It receives already-verified claims and returns prose. This is *why* injection
fails and why a model outage degrades to "findings without narrative" instead of
a wrong answer. Weakening the model was the security design.

### Failing loudly beats serving stale meaning

A broken anchor could have been a warning badge. It is a **hard refresh failure**
because business meaning pointing at a symbol that no longer exists is worse than
no answer — it is a confident wrong answer.

### Immutable history, current-revision reads

Reference snapshots accumulate forever (BR-43) and meaning versions are never
overwritten (BR-19). The *query* selects what is current; the *store* forgets
nothing. Bug #5 came from conflating these — the fix belonged in the query.

### Honest partial over confident wrong

When relaxed retrieval matches only some terms, the answer is `partial` with the
imprecision stated. Question q9 ("Is there a currency conversion rule?") finds
`getCurrency`/`setCurrency` accessors — real symbols, but not a business rule.
Returning `partial` with a caveat is correct; `answered` would have been a lie.

### Two identity schemes, on purpose

**Stable identity** (`asset + type + qualifiedName`) ignores line numbers, so
moving a method does not create a new symbol. **Location identity** includes the
revision digest, because evidence must pin a revision. Bug #4 came from using
stable identity as a primary key across generations.

### The fixture includes something unanalyzable

`AuditNotifier`'s reflective dispatch and the registered TypeScript asset exist
so the demo can *prove* the system reports gaps. A fixture that only contained
analyzable code would demonstrate nothing about honesty.

---

## Quick reference

| I want to… | Read |
|---|---|
| Understand extraction | `analysis/JavaSpringAnalyzer.java` |
| Understand the broken-anchor rule | `refresh/RefreshService.java` → `resolveAnchors()` |
| Understand verification | `agents/AnswerOrchestrator.java` → `verify()` |
| See how a service is defined | `answers/ServiceRegistry.java` |
| Check the schema | `db/migration/V1`, `V2`, `V3` |
| See scope enforcement | `security/ScopeService.java` + any `= ANY(?)` query |
| See the enterprise boundary | `config/ProfileBoundaryValidator.java` |
| Run the demo | [`DEMO.md`](DEMO.md) |
| Check requirement status | [`REQUIREMENTS-STATUS.md`](REQUIREMENTS-STATUS.md) |

**Scale:** ~9 000 lines across 109 source files · 23 tables · 15 services ·
22 tests · 13 commits.
