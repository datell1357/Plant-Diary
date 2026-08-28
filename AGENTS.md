# Project Agent Rules

## QA Artifact Hygiene

- Treat `DerivedData`, Swift package build caches, `SourcePackages`, simulator
  caches, and build intermediates as disposable. They are not canonical QA
  evidence.
- Put QA-only build caches outside `.omo/evidence/ulw` whenever possible,
  preferably under a unique `/tmp` directory.
- After each QA run finishes, first save the compact evidence required by the
  active goal or ledger, stop every process using the cache, then immediately
  remove the disposable paths created by that run.
- Keep only canonical evidence: command summaries, JSON/JSONL receipts, text
  transcripts, required screenshots, and reviewer reports. Keep an
  `.xcresult` only when a success criterion explicitly names it as evidence.
- Never remove evidence for an active criterion before it is recorded and
  checkpointed. Never remove user-authored, unrelated, or pre-existing files.
- Verify cleanup by checking that the disposable path is absent and record a
  cleanup receipt with the QA evidence.

## Simulator Resource Discipline

- Reuse a compatible existing Simulator. Never create a device merely to give
  an agent, attempt, digest, or reviewer a unique name.
- A verification lane may own at most one Simulator. The only exception is a
  visual run that simultaneously requires native 402pt and 390pt device
  profiles; that run may own exactly two.
- Before `simctl create`, confirm no compatible idle project-owned device can
  be reused. Register `EXIT`, `INT`, `TERM`, and `HUP` cleanup traps before
  booting; the trap must shut down and delete the exact created UDID.
- Record every owned Simulator name and UDID in the run receipt. On every exit
  path, verify those UDIDs no longer appear in `simctl list devices`.
- Never delete a Simulator owned by another active process or an unrelated
  user device. Stale project-owned devices may be removed only after process
  ownership is checked.
