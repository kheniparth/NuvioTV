#!/usr/bin/env python3
"""Factory console UI: panels, colours and keys in the terminal. Python standard library only (curses).
Run in WSL:  python3 .factory/ui/tui.py        keys: f cursor  d deepseek  c claude (freeze/unfreeze)  r refresh  q quit
One-shot print (no keys, works anywhere):  python3 .factory/ui/tui.py --once
Reads the same state as server.py (FACTORY_STATE / FACTORY_HISTORY / FACTORY_WIN_HOME apply).
"""
import os, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import server as S

KEYS = {"f": "cursor", "d": "deepseek", "c": "claude"}


def toggle(worker):
    w = S.workers().get(worker)
    if not w:
        return
    path = os.path.join(S.STATE, w.get("freezeFile", "FREEZE-" + worker))
    if os.path.exists(path):
        os.remove(path)
    else:
        os.makedirs(S.STATE, exist_ok=True)
        open(path, "a").close()


def clip(s, n):
    s = str(s or "")
    return s if len(s) <= n else s[: max(0, n - 1)] + "…"


def panel(title, rows, width):
    """A boxed panel: list of (text, style) with style in '', 'ok', 'bad', 'dim', 'head'."""
    out = [("┌─ " + title + " " + "─" * max(0, width - len(title) - 5) + "┐", "head")]
    for text, style in rows:
        out.append(("│ " + clip(text, width - 4).ljust(width - 4) + " │", style))
    out.append(("└" + "─" * (width - 2) + "┘", "head"))
    return out


def build(d, width):
    width = max(60, width)
    lines = [(f" FACTORY   {d['now']}", "head")]
    if not d["stateFound"]:
        lines.append((" State folder not found: set FACTORY_STATE", "bad"))
    rows = []
    for w, frozen in d["freeze"].items():
        run = d["running"]
        job = f"working: {run['what']} since {run['started']} [{run.get('model', '')}]" if run and run.get("worker") == w else "idle"
        key = next((k for k, v in KEYS.items() if v == w), " ")
        sc = d["schedule"].get(w)
        state = "FROZEN (manual)" if frozen else (f"frozen ({sc})" if sc else "active")
        rows.append((f"[{key}] {w:<9} {state:<22} {job}", "bad" if frozen or sc else ("ok" if job != "idle" else "")))
    rv = d["review"]
    rows.append((f"    claude review: {'running, ' + rv['what'] + ' since ' + rv['started'] if rv else 'idle'}", ""))
    lines += panel("Workers", rows, width)
    lr = d["lastRouter"]
    lines += panel("Router", [(f"last run {lr['time'] if lr else 'never'}", ""), (f"dispatch: {lr['dispatch'] if lr else ''}", "dim"),
                              (f"review:   {lr['review'] if lr else ''}", "dim")], width)
    rows = [("started          worker    work             min  tokens (DeepSeek only)", "dim")]
    for r in d["workerRuns"][:10]:
        rows.append((f"{r['started']:<16} {r['worker']:<9} {('fix PR' if r['kind'] == 'fix' else 'issue')} #{r['number']:<8} {r['minutes']:>4}  {r['tokens'] or '-'}", ""))
    lines += panel("Worker history (last 10)", rows, width)
    rows = [(f"{r['time'][5:16]}  {r['dispatch']}  |  {r['review']}", "dim") for r in d["routerRuns"][:8]]
    lines += panel("Recent router runs", rows or [("none", "dim")], width)
    return lines


ANSI = {"": "", "ok": "\033[32m", "bad": "\033[31m", "dim": "\033[2m", "head": "\033[1;36m"}


def run_curses(scr):
    import curses
    curses.curs_set(0)
    curses.start_color()
    curses.use_default_colors()
    for i, c in enumerate((curses.COLOR_GREEN, curses.COLOR_RED, curses.COLOR_CYAN), 1):
        curses.init_pair(i, c, -1)
    attrs = {"": 0, "ok": curses.color_pair(1), "bad": curses.color_pair(2), "head": curses.color_pair(3) | curses.A_BOLD, "dim": curses.A_DIM}
    scr.timeout(2000)
    while True:
        h, w = scr.getmaxyx()
        lines = build(S.snapshot(), w - 1)
        scr.erase()
        for y, (text, style) in enumerate(lines[: h - 1]):
            scr.addnstr(y, 0, text, w - 1, attrs.get(style, 0))
        scr.addnstr(h - 1, 0, " f/d/c freeze-unfreeze cursor/deepseek/claude · r refresh · q quit", w - 1, curses.A_REVERSE)
        scr.refresh()
        k = scr.getch()
        if k in (ord("q"), 27):
            return
        if 0 < k < 256 and chr(k) in KEYS:
            toggle(KEYS[chr(k)])


if __name__ == "__main__":
    if "--once" in sys.argv or not sys.stdout.isatty():
        color = sys.stdout.isatty()
        for text, style in build(S.snapshot(), int(os.environ.get("COLUMNS", 100))):
            print((ANSI[style] + text + "\033[0m") if color else text)
    else:
        import curses
        curses.wrapper(run_curses)
