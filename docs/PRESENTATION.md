# CodeAtlas — 15-minute presentation script

**Structure:** 3 min problem → 2 min approach → 7 min live demo → 2 min results → 1 min close.
The demo is the centrepiece. Everything else exists to set it up.

---

## PART 1 — The problem (3 min)

### Open with a question to the room

> "Think about the last time someone asked you: *if we change this rule, what
> else breaks?* How long did it take to answer — and how confident were you?"

Let that land. Everyone in the room has lived it.

### The real cost

> "In an enterprise, that answer lives in three places: the code, the heads of
> two or three senior engineers, and a wiki page that went stale eighteen months
> ago. When those people are busy — or gone — the business waits."

### Why the obvious solution fails

> "The obvious answer in 2026 is: point an AI at the codebase. We tried that
> thinking, and we rejected it — because an LLM reading code will confidently
> tell you a rule is enforced in a file that doesn't exist. It hallucinates a
> call graph. And in enterprise change management, **a confident wrong answer
> is worse than no answer** — because someone ships a change based on it."

### The thesis — say this slowly, it's your whole pitch

> "So we built CodeAtlas on one principle: **the AI is never allowed to state a
> fact.** Every structural fact comes from a real parser. Every claim is checked
> against evidence before you see it. The AI only gets to phrase what's already
> been proven."

---

## PART 2 — The approach (2 min)

### One diagram, four boxes

```
Source code → Real parser (JavaParser) → Knowledge graph with evidence
                                              ↓
Business meaning (human-written, anchored to symbols)
                                              ↓
Question → deterministic retrieval → VERIFY → AI writes the sentence
```

### The three rules

> "Three rules the system cannot break:
>
> **One — never fabricate.** Structure comes from a parser, not a model.
>
> **Two — never hide a gap.** If we can't analyse something, we say so out loud.
>
> **Three — never write to your code.** Source is mounted read-only. There's no
> code path that can modify what it reads."

### Name the differentiator now, prove it later

> "And one thing we haven't seen elsewhere: **knowledge drift detection.**
> Documentation doesn't become wrong loudly — it rots silently. We made rot
> impossible to ignore. I'll show you."

---

## PART 3 — Live demo (7 min) ← THE CORE

Open the URL. Sign in as **reader / reader-demo**.

### Beat 1 — Ask the question (90 sec)

Click: *"If the purchase approval threshold changes, what else is affected?"*

While it runs, narrate the progress trail:

> "Watch what it's doing: it classified the question, retrieved evidence,
> **verified** every claim, then asked the model to explain. Note the order —
> verification happens *before* the AI speaks."

When the answer lands:

> "It found the enforcement location, the configuration value, the ordered
> checks, the data it writes, and dependency paths."

### Beat 2 — The evidence (90 sec) ← YOUR STRONGEST MOMENT

Click any source citation.

> "This is the part I'd ask you to judge us on. Every claim links to **exact
> source, at a specific revision.** Not a file name — the actual lines, with a
> content digest. If you don't believe a claim, you check it in one click."

Point at the provenance badges:

> "Green is **derived** — a parser found it. Blue is **reviewed** — a human
> approved it. Amber is **inferred** — plausible, with the reason stated.
> We never blur those three."

### Beat 3 — Honest limits (45 sec)

Scroll to *"What this answer does not establish."*

> "Most demos hide this section. We lead with it. It says runtime dispatch isn't
> covered, and that this user's permissions exclude one application — so the
> answer is scoped, and it says so."

### Beat 4 — Knowledge drift (3 min) ← THE DIFFERENTIATOR

In a terminal:

```bash
./switch-revision.sh B
```

> "A developer has just renamed a method. In every documentation tool I know,
> nothing happens — the docs quietly become wrong."

Sign in as **owner / owner-demo** → **Refresh & Trust** → **Run full refresh**.

**It fails.** Read the message aloud:

> "It refuses to publish. It names the business rule, the asset, and the exact
> symbol that disappeared. And critically — the previous knowledge is still
> being served. **The system never serves an explanation pointing at code that
> no longer exists.**"

Now repair it. **Business Knowledge** → **New statement** → anchor to
`com.example.approval.ApprovalService.decideApproval` → Save → **Approve**.
Refresh again as owner — **succeeds**.

> "A human re-reviewed the meaning against the new code. Now it republishes, and
> the threshold reads 25,000 from the new revision. The old version is preserved
> as history — nothing was overwritten."

### Beat 5 — Honest unknown (30 sec)

Ask: *"Is there a currency conversion rule?"*

> "There isn't one. It says so, and tells you what evidence would help. It does
> not invent a rule to look clever."

---

## PART 4 — Results (2 min)

### Credibility: it runs on real code

> "To check we hadn't just built something that works on our own fixture, we
> pointed it at **Flowable** — a real open-source process engine, 7,700 Java
> files. It indexed 216 production files and extracted **208 REST endpoints**
> in about ten seconds, with zero parse failures. We also ran it on three of our
> company's own Java projects."

### Verified numbers

| | |
|---|---|
| Automated tests | **22 passing** |
| End-to-end checks | **31 passing** |
| Evaluation corpus | **9/9 questions** |
| Answer latency | p50 **~0.8s**, p95 **~2.6s** |
| Cost per question | **$0.0002** |

> "Every factual claim in those answers is traceable to source. That's not a
> target — it's enforced by the verifier."

### Security, shown not claimed

> "We planted a prompt injection inside the source code — instructions telling
> the model to leak another application and report a fake threshold. It failed
> on every vector. Not because we filter prompts, but because **the model
> literally cannot introduce a fact or choose a tool.** That's architecture,
> not a patch."

### What we did NOT build ← say this; it builds trust

> "Two gaps we'll state plainly. We didn't build the MCP assistant interface —
> services are exposed over REST from a single registry, but that transport
> isn't written. And our retrieval is keyword-based, not vector search, so we
> label that capability partial rather than complete. Both are documented,
> requirement by requirement, in the repo."

---

## PART 5 — Close (1 min)

> "CodeAtlas turns a codebase into something a business person can ask questions
> of — and every answer comes with its receipts.
>
> The hard part wasn't making an AI talk about code. It was making one that
> **refuses to guess**: verification before generation, honest gaps, and
> documentation that fails loudly when it rots instead of quietly lying.
>
> That's what we'd want running against our own systems."

---

## Q&A — likely questions

**"Isn't this just RAG over a codebase?"**
> No. RAG retrieves text and lets the model summarise it — the model can still
> hallucinate. We extract structure with a parser, verify citations, and only
> then let the model phrase it. It cannot add a fact we didn't prove.

**"What if the AI is unavailable?"**
> You still get the full answer — claims, evidence, paths — and it's labelled
> "explanation unavailable". We degrade; we never fabricate. In enterprise
> profile with no model configured, that's the normal mode.

**"Does our source code leave our network?"**
> Not in enterprise profile. It refuses to start if you point it at an external
> endpoint — no silent cloud fallback. Today's demo runs on synthetic data.

**"How much work to support our stack?"**
> Today: Java and Spring MVC. Adding JPA or another language means writing one
> analyzer that emits the same records — everything above it is language-agnostic
> and unchanged.

**"Where does it break?"**
> Frameworks we haven't written adapters for. On Flowable we got every REST
> endpoint but no data relationships, because it uses `EntityManager` and we only
> read `JdbcTemplate`. The tool reported that as a coverage gap rather than
> pretending — which is the behaviour we care about most.

---

## Pre-flight checklist

- [ ] `./start-demo.sh --tunnel` — 10 min early
- [ ] `./show-url.sh` — URL changes every restart
- [ ] `./switch-revision.sh A` — baseline for beat 1
- [ ] Ask one question to warm the model
- [ ] **Disable laptop sleep** — tunnel dies with it
- [ ] Terminal open beside the browser for beat 4

**If the tunnel dies mid-demo:** switch to `http://localhost:4200`. Same app.

**If refresh fails unexpectedly:** `./switch-revision.sh A`, refresh as owner.
