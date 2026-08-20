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
