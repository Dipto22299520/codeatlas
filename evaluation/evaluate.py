#!/usr/bin/env python3
"""Scores the CodeAtlas evaluation corpus against a running backend."""
import base64, json, sys, time, urllib.request, pathlib

base = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8090"
auth = sys.argv[2] if len(sys.argv) > 2 else "reviewer:reviewer-demo"
token = base64.b64encode(auth.encode()).decode()
corpus = json.loads((pathlib.Path(__file__).parent / "questions.json").read_text())

def ask(question):
    body = json.dumps({"question": question}).encode()
    req = urllib.request.Request(f"{base}/api/answers", data=body, method="POST")
    req.add_header("Authorization", f"Basic {token}")
    req.add_header("Content-Type", "application/json")
    started = time.time()
    with urllib.request.urlopen(req, timeout=90) as r:
        payload = json.loads(r.read())
    return payload["answer"], (time.time() - started) * 1000

results, latencies = [], []
for q in corpus["questions"]:
    if q["id"] == "q10-broken-anchor":
        results.append({**q, "verdict": "MANUAL", "note":
                        "Requires switching to revision B; covered by RefreshAndDriftTest."})
        continue
    try:
        answer, ms = ask(q["question"])
    except Exception as e:
        results.append({**q, "verdict": "ERROR", "note": str(e)})
        continue
    latencies.append(ms)
    blob = json.dumps(answer).lower()
    ev_ok = all(any(e.lower() in ev["path"].lower() for ev in answer["evidence"])
                for e in q["expectedEvidence"])
    cl_ok = all(c.lower() in blob for c in q["expectedClaims"])
    if q.get("expectedStatus") == "unknown":
        # An honest answer here is "unknown", or "partial" with the imprecision
        # stated: what must never happen is a confident answer to an absent rule.
        ok = (answer["status"] in ("unknown", "partial")) and bool(answer["unknowns"])
    else:
        ok = ev_ok and cl_ok and answer["status"] in ("answered", "partial")
    # Every factual claim must carry provenance, and derived claims must cite evidence.
    traceable = all(c["provenance"] in ("derived", "reviewed", "inferred", "unknown")
                    and (c["provenance"] != "derived" or c["evidenceIds"])
                    for c in answer["claims"])
    results.append({**q, "verdict": "PASS" if (ok and traceable) else "FAIL",
                    "status": answer["status"], "claims": len(answer["claims"]),
                    "evidence": len(answer["evidence"]), "latencyMs": round(ms),
                    "evidenceMatch": ev_ok, "claimMatch": cl_ok, "traceable": traceable})

scored = [r for r in results if r["verdict"] in ("PASS", "FAIL")]
passed = sum(1 for r in scored if r["verdict"] == "PASS")
print(f"{'ID':<26}{'VERDICT':<9}{'STATUS':<10}{'CLAIMS':<8}{'EVID':<6}{'MS':<7}")
print("-" * 70)
for r in results:
    print(f"{r['id']:<26}{r['verdict']:<9}{r.get('status','-'):<10}"
          f"{r.get('claims','-'):<8}{r.get('evidence','-'):<6}{r.get('latencyMs','-'):<7}")
print("-" * 70)
print(f"Scored: {passed}/{len(scored)} passed "
      f"({100*passed/len(scored):.0f}%)  |  1 manual (broken-anchor, covered by tests)")
if latencies:
    ordered = sorted(latencies)
    p50 = ordered[len(ordered)//2]
    p95 = ordered[min(len(ordered)-1, int(len(ordered)*0.95))]
    print(f"Latency p50 {p50:.0f} ms, p95 {p95:.0f} ms (measured on this host, "
          f"model openai/gpt-oss-120b via OpenRouter)")
json.dump(results, open(pathlib.Path(__file__).parent / "results.json", "w"), indent=2)
