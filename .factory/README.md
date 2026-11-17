# .factory

The build loop for one project: a 30-minute router run (formerly the tick) starts a worker agent on ready GitHub issues and starts a Claude review run when a PR needs one. Progress lives only in GitHub (issues, PRs, labels, the Projects board). The template is versioned at github.com/kheniparth/software-factory; this copy is the live one for the NuvioTV fork (kheniparth/NuvioTV, base `dev`). Workers follow `AGENTS.md` here; checks run through `build.sh` (Gradle in WSL on a native-disk copy). Task: `nuvio-tv-tick`; history: `~/.nuvio/nuvio-tv-history.log`.

## Look at it

```bash
/mnt/d/nuvio-factory/NuvioTV/.factory/status        # tick health, review, worker, PRs, issues, warnings (no model call)
/mnt/d/nuvio-factory/NuvioTV/.factory/freeze        # pause Cursor; -Off resumes, -Status reports (-DeepSeek for that worker)
/mnt/d/nuvio-factory/NuvioTV/.factory/router -DryRun # what the next router run would do
```

## Console UI

```bash
python3 /mnt/d/nuvio-factory/NuvioTV/.factory/ui/tui.py          # live panels; f/d/c freeze cursor/deepseek/claude, q quits
python3 /mnt/d/nuvio-factory/NuvioTV/.factory/ui/tui.py --once   # print one snapshot
python3 /mnt/d/nuvio-factory/NuvioTV/.factory/ui/server.py       # web version on http://127.0.0.1:8787
```

## Files

| File | Role |
|---|---|
| `project.ps1` | The only per-project file: repo, base branch, upstream, checkout paths, task name, pinned issues, board ids, checks text |
| `AGENTS.md`, `build.sh` | Worker rules for this fork; the Gradle check run in WSL |
| `router.ps1` | The scheduled task body: filters triggers, dispatches workers, starts a review when due (`tick.ps1` forwards here) |
| `workers.json` | Which worker and model does which role |
| `ui/tui.py`, `ui/server.py` | Console UI and local web UI |
| `FLOW.md` | The flowchart and roles |
| `dispatch.ps1` | Starts a worker (Cursor or DeepSeek) on the next ready issue or a PR with requested changes |
| `review.md` | What the Claude review run does (project rules live here) |
| `handoff.md` | What a worker must put in its PR |
| `status.ps1`, `freeze.ps1` | Status, pause |
| `cleanup.ps1` | Every tick, no model: removes worktrees and local/remote branches of merged or closed PRs, whoever merged them |
| `sync-board.ps1` | Every tick, no model: a board card whose issue is closed moves to Done (not-planned ones are removed) |
| `sync-labels.ps1` | Every tick, no model: an issue with an open PR gets `review` instead of `implement`/`architect` |
| `setup.ps1`, `register-tick-task.ps1`, `remove.ps1` | Check and label a project, register the task, take it all out again |
| `router` (`tick`), `dispatch`, `status`, `freeze`, `review-now` | WSL wrappers for the .ps1 scripts |

## Set up in a new project

1. Copy this folder into the project root as `.factory/`.
2. Edit `project.ps1` (repo `owner/name`, checkout path, WSL path, task name, pinned issue numbers).
3. Edit `review.md` and `handoff.md` for the project's rules, checks and branch names. Dispatch uses the intent labels `architect` and `implement` and branches `cursor/<n>-...` and `deepseek/<n>-...`; change them in `dispatch.ps1` if needed.
4. Merge it through a PR, then in PowerShell:

```powershell
.\.factory\setup.ps1             # checks gh, claude, Cursor agent, DeepSeek key, creates the labels
.\.factory\router.ps1 -DryRun      # what the first tick would do
.\.factory\setup.ps1 -Register   # registers the 30-minute task (elevated shell)
.\.factory\status.ps1            # confirms the tick is reported
```

## Remove from a project

```powershell
.\.factory
emove.ps1                  # prints what it would do
.\.factory
emove.ps1 -Yes             # unregisters the task, deletes state and history
.\.factory
emove.ps1 -Yes -DeleteFolder   # also deletes .factory/ (then commit that through a PR)
git worktree list                      # remove leftover worktrees by hand
```

A worker that is running is not stopped, and GitHub labels are left alone.

## Labels are intents, not workers

Label an issue with what it needs: `architect` (design), `implement` (code), `review`; `later` holds it, `owner-check` is for the owner's hardware tests. The router turns intent into a role and `workers.json` picks the worker. Labels name the task, never the worker (no `cursor`/`deepseek` labels). DeepSeek freezes itself during its double-price peak hours (`schedule` in `workers.json`, 01:00-04:00 and 06:00-10:00 UTC on weekdays); that is separate from the manual FREEZE files.

## Agents

- **Cursor** is the default worker. It needs `cursor-agent` installed and logged in.
- **DeepSeek** is `claude -p` in WSL against DeepSeek's Anthropic-compatible API. Set the user env var `DEEPSEEK_API_KEY`; the router gives it `implement` issues whenever Cursor is frozen or unavailable (agent missing, logged out).
- **Another agent**: add a branch for it in `dispatch.ps1` next to the Cursor/DeepSeek start (one start command, one log file, one lock entry), plus a label and a `freeze` file name.
- **Review** uses `claude -p` (set `JIVA_CLAUDE` / `JIVA_CLAUDE_ARGS` if it is not on PATH or needs permission flags).
