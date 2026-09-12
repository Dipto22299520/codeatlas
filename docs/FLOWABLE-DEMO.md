# The Flowable drift demo

Same feature as the fixture demo, but on **real open-source production code** —
7,700 files, 216 of them indexed — and framed around a control a compliance
officer actually cares about.

## Setup

```bash
./use-flowable-demo.sh       # switch the estate to Flowable
./restart-backend.sh
./switch-flowable.sh v1      # baseline
# refresh as owner, then verify the rule shows as reviewed
```

## The reviewed rule already in place

**"Process instance deletion is irreversible"** — owned by *Compliance and Risk*:

> Deleting a historic process instance permanently removes its audit trail,
> including all task history and variable values. Under our retention policy
> this operation must be restricted to compliance administrators and recorded
> in the access log.

Anchored to the real handler behind `DELETE /history/historic-process-instances/{id}`:

```
org.flowable.rest.service.api.history
  .HistoricProcessInstanceResource.deleteProcessInstance
```

## The demo

**1. Establish it's real code.**

> "This is Flowable — an open-source process engine, 7,700 Java files. We
> indexed 216 production files and pulled out 208 REST endpoints in about ten
> seconds. Nothing here is synthetic."

**2. Show the governed rule.**
Business Knowledge → *Process instance deletion is irreversible*.

> "A compliance team wrote this. It isn't a comment in the code or a wiki page
> — it's anchored to the exact method that performs the deletion."

**3. A developer refactors.**

```bash
./switch-flowable.sh v2
```

> "Someone renames `deleteProcessInstance` to `removeProcessInstance`. A
> perfectly reasonable refactor. Nothing about the compliance rule changes."

**4. Refresh as owner. It fails.**

> "Every documentation system I know does nothing here — the page still says
> the right words, pointing at a method that no longer exists. Ours refuses to
> publish, and names the rule, the application, and the exact symbol that
> disappeared."

**5. Old answers still work.**
Ask *"delete historic process instance"* — still answers from the last good
generation.

> "It didn't go down. It refuses to serve *new* knowledge it can't stand behind,
> while continuing to serve what it already verified."

**6. The line to land on.**

> "That's the difference between documentation that rots silently and
> documentation that tells you it rotted. On a 7,700-file codebase, nobody was
> ever going to notice this by hand."

## Reset

```bash
./switch-flowable.sh v1     # then refresh as owner
./restore-demo.sh           # back to the synthetic fixture
```
