# Runbook — starting the demo from a cold laptop

## Start everything

```bash
cd ~/Downloads/OneDrive_1_9-10-2026
./start-demo.sh --tunnel     # public URL for judges
./start-demo.sh              # local only
```

Takes ~60–90 seconds. It starts PostgreSQL, the backend, the frontend, indexes
the estate, and opens a Cloudflare tunnel. It prints the public URL at the end.

## Get the URL again

```bash
./show-url.sh
```

**The public URL changes every restart.** Re-check it before sharing with judges.

## Sign in

| User | Password | Use for |
|---|---|---|
| `owner` | `owner-demo` | Refresh, registry, full estate |
| `reviewer` | `reviewer-demo` | Authoring and reviewing meaning |
| `reader` | `reader-demo` | Demonstrating the authorization boundary |

## Stop everything

```bash
./stop-demo.sh
```

Database contents are preserved.

## Health check

```bash
./test-everything.sh    # 31 checks across all layers
```

## If something is wrong

| Symptom | Fix |
|---|---|
| Port already in use | `./stop-demo.sh` then `./start-demo.sh` |
| Tunnel URL dead | Laptop slept. `./start-demo.sh --tunnel` for a new URL |
| "Access denied" in the browser | The origin is not allowed. Check `CODEATLAS_ALLOWED_ORIGINS` in `.env` covers `https://*.trycloudflare.com` |
| Answers have no prose | No model configured, or enterprise profile. Evidence is still correct |
| Refresh fails on a broken anchor | Intended after switching revisions. Repair in Business Knowledge, or `./switch-revision.sh A` |
| Backend will not start | `tail -30 .logs/backend.log` |

## Switching what is indexed

```bash
./restore-demo.sh        # synthetic fixture (use this for public demos)
./use-real-projects.sh   # real Java projects, if re-registered
./switch-revision.sh B   # break an anchor, for the drift demo
./switch-revision.sh A   # back to the baseline
# after any switch: ./restart-backend.sh and refresh as owner
```

**Only expose the synthetic fixture publicly.** Company source must not be
served through a public evidence viewer.

## Demo day checklist

1. `./start-demo.sh --tunnel`
2. `./show-url.sh` — copy the URL
3. Open it, sign in as `reader`, ask the threshold question, confirm claims render
4. Disable sleep on the laptop — the tunnel dies with it
5. Keep [DEMO.md](DEMO.md) open for the five-minute script
