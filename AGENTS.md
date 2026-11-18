# Agent rules for kheniparth/NuvioTV

Fork of NuvioMedia/NuvioTV (Android TV) adding Live TV ported from Nuvio Enhanced.

- **Start here:** read [PROGRESS.md](PROGRESS.md). It is the single progress doc for all Nuvio work (this fork,
  the NuvioDesktop fork, the factory, the Enhanced reference). "Continue the project" means: pick up its
  "Next steps".
- **End of every interactive work session:** update PROGRESS.md (past, current state, next steps, open issues,
  decisions, gotchas; dates YYYY-MM-DD) and commit and push it through a PR to `dev`. No secrets: name the file a
  secret lives in, never its value.
- **Factory workers** (Cursor, DeepSeek started by `.factory/dispatch`): follow `.factory/AGENTS.md` and do not
  edit PROGRESS.md; your handoff goes in the PR body (`.factory/handoff.md`).
- Work in WSL (`~/nuvio/nuvio-tv`), never Gradle on Windows or on `/mnt/d`.
- Never open a PR, issue or comment on `NuvioMedia/*`. PRs target `kheniparth/NuvioTV`, base `dev`.
