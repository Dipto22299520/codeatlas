# CodeAtlas — setup and operation

Reproducible startup for a clean machine. No manual database edits are required.

## Prerequisites

| Requirement | Verified with |
|---|---|
| JDK 21 | `/usr/lib/jvm/java-21-openjdk-amd64` (Spring Boot 3.5 targets Java 21) |
| Node.js 20+ and npm | Node 20.20.0, npm 10.8.2 |
| Docker with Compose | Docker 29.5.3 (runs PostgreSQL 16) |

Maven is **not** required: the repository ships a pinned Maven wrapper
(`backend/mvnw`, Maven 3.9.9) that downloads itself on first use.

## 1. Configure the environment

```bash
cp .env.example .env
```

Edit `.env`. To run without any model, leave `CODEATLAS_MODEL_BASE_URL` blank:
the platform still returns verified evidence and marks the narrative
explanation unavailable. It never fabricates an answer.

Never commit `.env`. No key is bundled into the frontend.

## 2. Start PostgreSQL

```bash
docker compose up -d          # postgres:16 on localhost:55432
```

The container uses port 55432 to avoid colliding with a host PostgreSQL.

## 3. Start the backend

```bash
./run-backend.sh              # http://localhost:8090
```

Flyway applies all five migrations and seeds the demo estate on first start.
`./restart-backend.sh` stops a running instance and starts a fresh one.

## 4. Start the frontend

```bash
cd frontend && npm install && npx ng serve
```

Open http://localhost:4200. `/api` is proxied to the backend by
`frontend/proxy.conf.json`.

## 5. Index the estate

Sign in as `owner` / `owner-demo`, open **Refresh & Trust**, and choose
**Run full refresh**. Equivalent API call:

```bash
curl -u owner:owner-demo -X POST http://localhost:8090/api/refresh \
     -H 'Content-Type: application/json' -d '{"scope":"full"}'
```

## Demo accounts

| User | Password | Role | Scope |
|---|---|---|---|
| `owner` | `owner-demo` | OWNER | Whole estate; may register assets and refresh |
| `reviewer` | `reviewer-demo` | REVIEWER | All three services; may author and review meaning |
| `reader` | `reader-demo` | READER | Portal and approval only — **payment-service is deliberately out of scope** |
| `assistant` | `assistant-demo` | ASSISTANT | Read-only service access for an assistant client |

The reader's narrower scope is what makes the authorization boundary
demonstrable: the same question returns different, correctly filtered results.

## Switching fixture revisions

```bash
./switch-revision.sh B    # threshold 25000; ApprovalService.approve renamed
./switch-revision.sh A    # threshold 50000
```

Revision B renames an anchored symbol, so the next refresh **fails** by design
and the previous generation keeps serving. Repair it in **Business Knowledge**
by authoring a new version anchored to `decideApproval`, approving it, then
refreshing again.

## Tests and evaluation

```bash
cd backend && JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw test
python3 evaluation/evaluate.py http://localhost:8090 reviewer:reviewer-demo
```

## Deployment profiles

- `demo` — synthetic/public assets only; external inference permitted and
  labelled in the UI.
- `enterprise` — source and source-derived context stay inside the boundary.
  A non-local inference endpoint **fails startup**; there is no silent cloud
  fallback.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `Port 8090 was already in use` | A backend is running. Use `./restart-backend.sh`. |
| `Connection refused` on :55432 | `docker compose up -d`, then wait for the healthcheck. |
| Refresh fails with "curated anchor(s) no longer resolve" | Intended behaviour after switching revisions. Repair the anchor in Business Knowledge, or switch back. |
| Answers have no narrative, only findings | No model configured, or the endpoint is unreachable. Evidence is still correct. |
| Startup fails with "Enterprise profile rejects external inference endpoint" | The enterprise profile is refusing cloud egress. Configure a local endpoint or unset the model. |
