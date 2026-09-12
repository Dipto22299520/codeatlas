# Requirement status

Status vocabulary from the specification: `implemented` (built and exercised),
`verified` (covered by an automated test or a recorded measurement), `partial`
(works within a stated narrower scope), `not implemented`.

**A mock, screenshot, or interface stub is never counted as completed
functionality.** Where something is partial, the limit is stated.

Evidence keys:
`ANALYZER` = JavaSpringAnalyzerTest · `AUTH` = AuthorizationBoundaryTest ·
`DRIFT` = RefreshAndDriftTest (5 tests) · `POLICY` = InjectionAndPolicyTest ·
`EVAL` = evaluation/results.json · `MANUAL` = exercised against the running system.

## CAP-1 — Estate Registration

| ID | Status | Evidence |
|---|---|---|
| BR-01 | verified | Registry persists id, business name, role, owner, source locator. `MANUAL` /api/assets |
| BR-02 | implemented | Scope changes flow through the registry; next refresh reflects them. `MANUAL` switch-revision.sh |
| BR-03 | partial | Two adapters exist: Java/Spring (structural) and configuration (YAML/properties). TypeScript is registered but **unsupported**, and reported as a coverage gap rather than silently skipped. |
| BR-04 | verified | Generated/vendor exclusions carry reasons. `ANALYZER` SourceScanner |
| BR-05 | verified | Estate page shows registered scope, exclusions, and per-asset extraction counts. `MANUAL` screenshot |

## CAP-2 — Structural Knowledge

| ID | Status | Evidence |
|---|---|---|
| BR-06 | verified | Structure is derived by a real parser; no hand-entered structural facts. `ANALYZER` |
| BR-07 | verified | Extracts classes, methods, endpoints, tables, integration points. `ANALYZER` |
| BR-08 | verified | Typed invokes/reads/writes/belongs-to/uses-config edges with evidence. `ANALYZER` |
| BR-09 | verified | Every derived item resolves to exact revision-specific source. `MANUAL` evidence drawer |
| BR-10 | verified | Cross-asset links are labelled `inferred` with a stated reason. `MANUAL` 2 inferred edges |
| BR-11 | verified | Identical source yields identical canonical output. `DRIFT` determinism test, `ANALYZER` |
| BR-12 | verified | Unknown node/edge types are rejected by DB check constraints and enums. Migration V1 |
| BR-13 | verified | Coverage report records unresolved dynamic calls, unsupported language, parse failures. `MANUAL` 3 findings |

## CAP-3 — Business Meaning

| ID | Status | Evidence |
|---|---|---|
| BR-14 | verified | Meaning stored separately in business language. Migration V2 |
| BR-15 | implemented | Types capability/rule/process/entity/stakeholder supported; fixture seeds rule and capability. |
| BR-16 | verified | Anchors are mandatory: a draft without one is rejected. `MANUAL` MeaningController |
| BR-17 | implemented | Every anchor carries a confidence basis and resolution status. |
| BR-18 | verified | A broken anchor fails the refresh and blocks publication. `DRIFT` |
| BR-19 | verified | Non-technical authoring/review UI with immutable version history. `MANUAL` Business Knowledge |
| BR-20 | verified | Derived vs reviewed vs inferred shown per claim. `MANUAL` provenance badges |
| BR-21 | implemented | `source_origin` distinguishes AI suggestions; drafts never render as reviewed. Automatic suggestion generation is **not implemented**. |

## CAP-4 — Process & Behaviour

| ID | Status | Evidence |
|---|---|---|
| BR-22 | verified | Ordered entry-to-completion stages. `EVAL` q1 |
| BR-23 | verified | Cross-application handovers within one process. `EVAL` q6 |
| BR-24 | implemented | Known entries are modelled as `entry` stages. |
| BR-25 | verified | Ordered checks with enforcement locations. `EVAL` q8, checks service |
| BR-26 | verified | Writes and state changes with locations. `EVAL` q5 |
| BR-27 | implemented | Configuration guards explained with snapshot evidence and age. `EVAL` q2 |
| BR-28 | not implemented | Locks and reservations are not modelled; the fixture contains none. |
| BR-29 | verified | Known failure paths and origins. `EVAL` q4 |
| BR-30 | partial | Calculations are visible only as extracted method bodies; no dedicated calculation model. |
| BR-31 | verified | Placement recommends a location with flow evidence and produces no patch. `EVAL` q8 |

## CAP-5 — Meaning-Based Retrieval

| ID | Status | Evidence |
|---|---|---|
| BR-32 | verified | Ordinary business-language questions answered. `EVAL` 9/9 |
| BR-33 | **partial** | Retrieval is PostgreSQL full-text (keyword) with camelCase splitting and an any-term fallback. **No vector index is configured, so this is explicitly degraded mode, not CAP-5 semantic compliance.** The limitation is stated in every affected answer. |
| BR-34 | verified | Meaning, source, or combined retrieval via the `scope` parameter. |
| BR-35 | verified | Exact evidence locations on retrieval results. `MANUAL` |
| BR-36 | implemented | Asset filter supported on search. |
| BR-37 | not implemented | Incremental artifact reuse is not built; every refresh is a full rebuild. Fixture scale makes this unnecessary, but it is **not** done. |
| BR-38 | verified | Authorized full source content at a location. `AUTH`, /api/source |

## CAP-6 — Reference Data

| ID | Status | Evidence |
|---|---|---|
| BR-39 | verified | Approved configuration copies stored as snapshots. |
| BR-40 | verified | Snapshots are taken during refresh; **no live operational connection exists**. |
| BR-41 | verified | Explicit reviewed allowlist; non-allowlisted keys are never snapshotted. |
| BR-42 | verified | Secret-like keys rejected even when allowlisted. ConfigurationAnalyzer |
| BR-43 | verified | Snapshots are immutable and read-only; history is preserved across refreshes. `MANUAL` two threshold snapshots |
| BR-44 | verified | Snapshot age shown with every value. `EVAL` q2 |
| BR-45 | verified | Authorized read-only access. `AUTH` |
| BR-46 | verified | Configuration answers use actual approved snapshots. `EVAL` q2 |

## CAP-7 — Answer Services

| ID | Status | Evidence |
|---|---|---|
| BR-47 | verified | Each service is individually callable. /api/services/{id}/invoke |
| BR-48 | verified | All 11 primitive categories registered and implemented. |
| BR-49 | verified | All 4 composites implemented. `EVAL` |
| BR-50 | verified | Composites carry evidence plus labelled guidance. `EVAL` q7 |
| BR-51 | verified | Single authoritative registry drives every access mode. ServiceRegistry |
| BR-52 | verified | Metadata includes whenToUse and distinction. /api/services |
| BR-53 | verified | All answer services are read-only; no write path exists. |
| BR-54 | verified | Verifier rejects claims whose citations are absent. `MANUAL` |
| BR-55 | verified | unknown/partial/blocked outcomes explain what is missing. `EVAL` q9 |
| BR-56 | implemented | Business names and ids resolve to scoped entities. |
| BR-57 | not implemented | The bounded expert query DSL is not built. No arbitrary SQL is exposed either. |

## CAP-8 — Assistant Interface

| ID | Status | Evidence |
|---|---|---|
| BR-58 | **not implemented** | No MCP server is built. Services are reachable over authenticated REST only. |
| BR-59 | not implemented | Local stdio and shared HTTP MCP transports are absent. |
| BR-60 | partial | Shared REST calls are authenticated with caller identity and audited; this is not an MCP transport. |
| BR-61 | verified | Answers are grounded in platform evidence and verified before composition. `POLICY` |
| BR-62 | implemented | `docs/SETUP.md` and `docs/DEMO.md` document scope, limits, useful questions. |
| BR-63 | partial | REST discovery (`GET /api/services`) lists tools with schemas; no MCP discovery. |
| BR-64 | verified | Registering a service exposes it in discovery and invocation with no transport edits. |

## CAP-9 — Refresh, Trust & Governance

| ID | Status | Evidence |
|---|---|---|
| BR-65 | verified | On-demand full rebuild from registered assets and curated content. `DRIFT` |
| BR-66 | verified | Transactional publication; generation-scoped ids prevent duplicates. `DRIFT` |
| BR-67 | partial | Stages are recorded with state and detail; restart-from-stage is not implemented. |
| BR-68 | implemented | Asset-scoped refresh supported; curated content is never deleted by a rebuild. |
| BR-69 | verified | Freshness, counts, and coverage visible. `MANUAL` Refresh & Trust |
| BR-70 | verified | Inconsistency causes a visible failure and preserves the prior generation. `DRIFT` |
| BR-71 | verified | Authorization enforced in SQL; every call audited. `AUTH` |
| BR-72 | verified | Scope, coverage, and gaps visible in the UI. `MANUAL` |
| BR-73 | verified | Named platform owner surfaced in status. `MANUAL` |
| BR-74 | implemented | Cadence published in settings and shown in status. |

## Non-functional

| ID | Status | Evidence |
|---|---|---|
| NFR-1 | verified (small scale) | Measured p50 **718 ms**, p95 **2552 ms** across 9 corpus questions on this host with `openai/gpt-oss-120b`. Comfortably inside the ~15 s composite target, **but measured on a 16-file fixture, not a declared enterprise workload**. |
| NFR-2 | not measured | Full rebuild of the fixture takes ~1 s. **Extrapolating that to the ~100-minute enterprise target would be dishonest and is not claimed.** |
| NFR-3 | not measured | No several-hundred-thousand-item benchmark was run. |
| NFR-4 | not measured | Doubling the estate needs no redesign by construction, but resource growth was not benchmarked. |
| NFR-5 | partial | 9/9 automated corpus questions pass with all factual claims traceable. This is a 9-question sample on a synthetic fixture, **not** the company's 90% measure on a realistic estate. |
| NFR-6 | not implemented | No availability objective or recovery drill. |
| NFR-7 | verified | Authenticated access, role enforcement, asset-scope boundaries. `AUTH` |
| NFR-8 | partial | UI supports ordinary-language questions with example prompts; no unfamiliar-user study was run. |
| NFR-9 | implemented | `docs/SETUP.md` documents asset and service addition steps; not validated with an unfamiliar team. |
| NFR-10 | verified | Enterprise profile refuses external inference at startup and per request. `POLICY` |
| NFR-11 | partial | Per-answer model cost is measured and displayed (e.g. $0.00029). No engineering-time baseline study. |

## Cross-cutting

| ID | Status | Evidence |
|---|---|---|
| CC-1 | verified | Source mounts are read-only; no write path to described systems exists. |
| CC-2 | verified | Every claim carries provenance; derived claims cite evidence. |
| CC-3 | verified | Failures and unknowns are always surfaced. `EVAL` q9 |
| CC-4 | verified | Facts and interpretation are visually and structurally distinct. |
| CC-5 | verified | No instrumentation or execution of indexed source. |
| CC-6 | implemented | New supported assets need registration only, no redesign. |
| CC-7 | verified | Central types and one service registry. |
| CC-8 | verified | Allowlist plus secret heuristics plus profile boundaries. `POLICY` |
| DR-1 to DR-5 | verified | Generated knowledge separate and disposable; curated content versioned; secrets only in environment. |
| DR-6 | partial | Structure rebuilds from source and meaning restores from curated records. Model drafts are not cached/versioned because suggestion generation is not implemented. |
| DR-7 | verified | Failed refresh preserves curated content. `DRIFT` |

## Summary of what is NOT done

1. **MCP assistant interface (BR-58, BR-59, BR-63)** — the largest gap. Services
   are defined once and exposed over REST; adding an MCP transport would read
   the same registry, but it is not built.
2. **Semantic/vector retrieval (BR-33)** — keyword retrieval only. Declared as
   degraded mode in every affected answer.
3. **Incremental refresh (BR-37)** and **stage restart (BR-67)**.
4. **Expert query DSL (BR-57)**, **locks/reservations (BR-28)**.
5. **Scale and rebuild benchmarks (NFR-2, NFR-3, NFR-4)** — deliberately not
   extrapolated from a 16-file fixture.
6. **User studies (NFR-8, NFR-11)** and **availability drills (NFR-6)**.

This build is a working vertical slice with honest coverage reporting. It is
**not** production-ready and does not claim full company compliance.
