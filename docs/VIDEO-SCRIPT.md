# 5-minute demo video — word-for-word script

**Record at:** `https://face-ambien-switch-rick.trycloudflare.com`
**Have open:** browser (full screen) + a small terminal for the 3:00 beat.

**Before you hit record**
- [ ] `./switch-revision.sh A` then refresh as owner (so there's something to break later)
- [ ] Ask one question to warm the model (first call is slow)
- [ ] Sign in as `reader` / `reader-demo`
- [ ] Close other tabs, silence notifications
- [ ] Browser zoom ~110% so text is readable in the recording

---

## 0:00–0:35 — The problem

> "Every engineering team knows this question: *if we change this rule, what else
> breaks?*
>
> Today the answer lives in three places — the code, the heads of two senior
> engineers, and a wiki page that went stale a year ago. When those people are
> busy, the business waits.
>
> The obvious fix is to point an AI at the codebase. We tried that thinking and
> rejected it — because a language model will confidently tell you a rule is
> enforced in a file that doesn't exist. And in change management, a confident
> wrong answer is worse than no answer, because someone ships on it.
>
> So we built CodeAtlas on one rule: **the AI is never allowed to state a fact.**"

*(On screen: the CodeAtlas workspace, already signed in.)*

---

## 0:35–1:05 — What it is
> "CodeAtlas reads your registered source code with a real parser — not a model —
> and builds a knowledge graph where every fact points back to exact source.
> Humans attach business meaning to specific symbols. When you ask a question,
> retrieval and verification are deterministic. The AI only writes the final
> sentence, and only about claims that already passed verification."

---

## 1:05–2:15 — The main answer *(slow down here)*

**Click the example chip:** *"If the purchase approval threshold changes, what else is affected?"*

While it runs:

> "Watch the trail: it classified the question, retrieved evidence, **verified**
> every claim, then composed the explanation. Verification happens *before* the
> AI speaks."

When the answer appears:

> "It found where the threshold is enforced, its configured value, the ordered
> checks around it, the data that gets written, and the dependency path.
>
> Look at the badges. **Green is derived** — a parser found it. **Blue is
> reviewed** — a human approved it. **Amber is inferred** — plausible, with the
> reasoning stated. We never blur those three."

---

## 2:15–2:45 — Evidence *(your strongest 30 seconds)*

**Click any source citation.**

> "This is what I'd ask you to judge us on. Every claim links to exact source at
> a specific revision — not a filename, the actual lines with a content digest.
> If you don't believe a claim, you verify it in one click."

**Close the drawer, scroll to "What this answer does not establish".**

> "And we lead with the limits. It says runtime dispatch isn't covered, and that
> this user's permissions exclude one application — so the answer is scoped, and
> it says so out loud."

---

## 2:45–4:00 — Knowledge drift *(the differentiator)*

**Terminal:**

```bash
./switch-revision.sh B
```

> "A developer just renamed a method. In every documentation tool I know,
> nothing happens — the docs quietly become wrong."

**Sign in as `owner` / `owner-demo` → Refresh & Trust → Run full refresh.**

**It fails.** Read it out:

> "It refuses to publish. It names the business rule, the application, and the
> exact symbol that disappeared. And the previous knowledge is still being
> served — the system never explains code that no longer exists."

**Business Knowledge → New statement.** Anchor to
`com.example.approval.ApprovalService.decideApproval`. Save → **Approve**.
**Refresh again → succeeds.**

> "A human re-reviewed the meaning against the new code. Now it republishes —
> and the threshold reads 25,000 from the new revision. The old version is kept
> as history. Nothing was overwritten."

---

## 4:00–4:30 — Honest unknown + real scale

**Ask:** *"Is there a currency conversion rule?"*

> "There isn't one. It says so, and tells you what evidence would help — instead
> of inventing a rule to look clever.
>
> And this isn't only a demo fixture. We pointed it at **Flowable**, a real
> open-source process engine with 7,700 Java files. It indexed 216 production
> files and extracted **208 REST endpoints in about ten seconds**, with zero
> parse failures."

---

## 4:30–5:00 — Close

> "Twenty-two automated tests. Nine out of nine evaluation questions. Sub-second
> answers at two-hundredths of a cent each.
>
> We also planted a prompt injection inside the source telling the model to leak
> another application and report a fake value. It failed on every vector — not
> because we filter prompts, but because the model cannot introduce a fact or
> choose a tool. That's architecture, not a patch.
>
> Two things we did **not** build: the MCP assistant transport, and vector
> search — our retrieval is keyword-based, so we label that capability partial.
> Both are documented requirement-by-requirement in the repo.
>
> CodeAtlas turns a codebase into something you can ask questions of — where
> every answer comes with its receipts, and the system tells you when it doesn't
> know."

---

## Timing safety

Running long? Cut in this order:
1. The Flowable paragraph (4:00–4:30)
2. The injection paragraph in the close
3. The "what it is" section (0:35–1:05) — shorten to one sentence

**Never cut:** the evidence click (2:15) or the drift failure (2:45). Those two
beats are the whole pitch.

## If something breaks mid-record

- Answer slow → keep narrating the progress trail, it's part of the story
- Refresh fails unexpectedly → `./switch-revision.sh A`, refresh, restart that beat
- Tunnel dies → switch to `http://localhost:4200`, identical app
