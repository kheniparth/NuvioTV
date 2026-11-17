# PR handoff

Every factory PR body carries this fixed handoff so a review or fix run starts from it instead of rediscovering the PR.
(Upstream's `.github/PULL_REQUEST_TEMPLATE.md` is for PRs to NuvioMedia; ignore it here.) Fill every section:

- **Status**: one line, e.g. "Ready for review", "Draft", "Blocked on #N".
- **Risk**: what could break and the blast radius if this is wrong.
- **What changed**: the user-visible behaviour, in one or two sentences.
- **Upstream files touched**: every existing upstream file you changed, or "none".
- **Verify**: the claims the next agent must re-check, not trust (checks you ran, emulator steps). The reviewer reruns the checks
  on the exact commit regardless of what Verify says passed; it is a claim, not proof.
- **Owner tests**: what only Parth can test on the real TV / Chromecast (queued; no real-device testing until he says so), or "none".
- **Next agent must**: the concrete next step (review, fix, hand-test).
- **Do not reopen**: decisions already settled for this PR, so the next agent does not redo them.
