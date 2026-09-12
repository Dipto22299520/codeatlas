# Five-minute demo script

Start from revision A, indexed, with the backend on :8090 and the frontend on
:4200. Reset with `./switch-revision.sh A` and run a full refresh.

---

### 0:00 — Scope and freshness (30s)

Sign in as **owner / owner-demo**. Open **Refresh & Trust**.

Point out: the named platform owner, the active generation, the deployment
profile badge (`demo · external inference`), and **Indexed assets** — three Java
services with real extraction counts, plus `supplier-ui` showing **0 items and a
TypeScript badge**.

> "The platform says plainly what it cannot analyze rather than hiding it."

Scroll to **Coverage gaps**: two reflective-dispatch findings and the
unsupported language.

### 0:45 — Ask how the process works (60s)

Go to **Workspace**, sign out and back in as **reader / reader-demo**.
Click *"How does purchase approval work?"*

Point out: the four-stage progress trail, the business-language summary, and
findings carrying **reviewed** (curated) and **derived** (extracted) badges —
the distinction between what a person approved and what the parser found.

### 1:45 — Open the evidence (45s)

Click any source citation, e.g. `ApprovalService.java:36–72`.

The drawer shows **exact numbered source at a recorded revision digest**. This
is the answer to "how do you know that?".

### 2:30 — The threshold change question (60s)

Ask *"If the purchase approval threshold changes, what else is affected?"*

Point out:
- the configuration value **50000** with its snapshot age and a caveat that the
  running system may differ,
- the enforcement location and the ordered checks,
- the data effects on `approval_decision` and `purchase_submission`,
- the **inferred** recommendation, explicitly labelled as review guidance rather
  than a safety proof,
- **"What this answer does not establish"** — including that the reader's scope
  excludes payment-service.

### 3:30 — Knowledge drift: break it (45s)

In a terminal:

```bash
./switch-revision.sh B
```

As **owner**, open **Refresh & Trust** → **Run full refresh**.

The refresh **fails**. It names the meaning, the asset, and the old source
location. The previous generation stays active and answers keep working.

> "Revision B renamed `ApprovalService.approve`. Rather than silently serving
> business meaning that now points at nothing, the refresh refuses to publish."

Open **Business Knowledge**: the broken-anchor warning names the symbol.

### 4:15 — Repair through the proper workflow (45s)

Sign in as **reviewer / reviewer-demo**, Business Knowledge → **New statement**:

- Statement: the same threshold rule wording
- Reason: `Re-anchored after ApprovalService.approve was renamed`
- Asset: `approval-service`
- Anchor symbol: `com.example.approval.ApprovalService.decideApproval`
- Path: `src/main/java/com/example/approval/ApprovalService.java`

Save as draft → **Approve**. As **owner**, run the refresh again: it **succeeds**.

Ask *"What is the configured approval threshold?"* → now **25000**, from
revision B, with matching evidence. Version history shows v1 superseded and v2
reviewed — the old version is preserved, not overwritten.

### 5:00 — Honest unknown (15s)

Ask *"Is there a currency conversion rule?"*

It returns **partial**, states that nothing matched all terms, and says what
evidence would help — rather than inventing a rule.

---

## If something goes wrong

- Refresh fails unexpectedly → `./switch-revision.sh A` and refresh.
- No narrative in answers → the model endpoint is unreachable; evidence and
  findings are still correct and the answer says the explanation is unavailable.
- Reset the demo completely:
  ```bash
  docker compose down -v && docker compose up -d && ./restart-backend.sh
  ```
