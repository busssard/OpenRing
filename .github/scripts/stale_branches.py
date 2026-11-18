#!/usr/bin/env python3
"""Deletes branches nobody needs any more (owner's wish, 3 November).

GitHub's "Automatically delete head branches" skips a branch that another open pull request is based on
(stacked pull requests) and never touched the branches from before it was switched on. This script cleans up
after it, on every closed pull request and once a day:

* deleted: a branch whose pull requests are all closed, at least one merged, and which still points at the
  commit of such a pull request (so nothing pushed after the merge is lost);
* deleted: a branch whose pull requests were all closed without merging more than 14 days ago, again only at
  that pull request's commit;
* deleted: a merged branch with later commits that are all in dev as well (ahead of dev by 0);
* kept: main, dev, the screenshot and badge branches, every branch an open pull request comes from or goes
  into, and every branch without any pull request (work in progress of a person or a scheduled run).

Usage: stale_branches.py [--dry-run] (reads GITHUB_REPOSITORY, uses gh), or
       stale_branches.py --plan FILE (FILE holds {"now", "branches", "prs"}; prints the plan, deletes nothing).
"""
import datetime
import json
import subprocess
import sys

KEEP = {"main", "dev", "screenshots", "screenshots-preview", "badges", "gh-pages"}
CLOSED_DAYS = 14


def when(s):
    return datetime.datetime.strptime(s, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=datetime.timezone.utc)


def plan(now, branches, prs, in_dev=()):
    """branches: {name: sha}; prs: [{head, head_sha, base, state, merged_at, closed_at, same_repo}]; in_dev: branches
    whose every commit is already in dev (GitHub's compare: ahead by 0). Returns (delete, kept) with a reason each."""
    now = when(now)
    open_refs = set()
    by_head = {}
    for p in prs:
        if p["state"] == "open":
            open_refs.add(p["base"])
            if p.get("same_repo", True):
                open_refs.add(p["head"])
        if p.get("same_repo", True):
            by_head.setdefault(p["head"], []).append(p)
    delete, kept = [], []
    for name in sorted(branches):
        sha = branches[name]
        mine = by_head.get(name, [])
        if name in KEEP:
            kept.append((name, "always kept"))
        elif name in open_refs:
            kept.append((name, "an open pull request uses it"))
        elif not mine:
            kept.append((name, "no pull request yet"))
        elif any(p["merged_at"] for p in mine):
            if any(p["merged_at"] and p["head_sha"] == sha for p in mine):
                delete.append((name, "merged"))
            else:
                if name in in_dev:
                    delete.append((name, "merged, and the later commits are in dev too"))
                else:
                    kept.append((name, "commits after the merge"))
        else:
            last = max(when(p["closed_at"]) for p in mine)
            if (now - last).days < CLOSED_DAYS:
                kept.append((name, "closed without merging less than %d days ago" % CLOSED_DAYS))
            elif any(p["head_sha"] == sha for p in mine):
                delete.append((name, "closed without merging"))
            else:
                kept.append((name, "commits after it was closed"))
    return delete, kept


def gh(*args):
    return subprocess.run(["gh", "api"] + list(args), check=True, capture_output=True, text=True).stdout


def pages(path):
    out, page = [], 1
    while True:
        got = json.loads(gh("%s%sper_page=100&page=%d" % (path, "&" if "?" in path else "?", page)))
        out += got
        if len(got) < 100:
            return out
        page += 1


def in_dev(repo, kept):
    """Of the branches kept for commits after their merge, those whose commits are all in dev anyway (for example a
    branch someone merged dev into after its pull request was merged). Branches without a common history with dev
    (from before the history was rewritten) are not compared and stay for the owner."""
    out = set()
    for n, why in kept:
        if why != "commits after the merge":
            continue
        try:
            c = json.loads(gh("repos/%s/compare/dev...%s" % (repo, n)))
            if c.get("ahead_by") == 0:
                out.add(n)
        except subprocess.CalledProcessError:
            pass
    return out


def read(repo):
    branches = {b["name"]: b["commit"]["sha"] for b in pages("repos/%s/branches" % repo)}
    prs = [{"head": p["head"]["ref"], "head_sha": p["head"]["sha"], "base": p["base"]["ref"], "state": p["state"],
            "merged_at": p["merged_at"], "closed_at": p["closed_at"],
            "same_repo": (p["head"].get("repo") or {}).get("full_name") == repo}
           for p in pages("repos/%s/pulls?state=all" % repo)]
    return branches, prs


def main(argv):
    if len(argv) == 3 and argv[1] == "--plan":
        data = json.load(open(argv[2]))
        delete, kept = plan(data["now"], data["branches"], data["prs"], set(data.get("in_dev", [])))
        for n, why in delete:
            print("delete %s (%s)" % (n, why))
        for n, why in kept:
            print("keep %s (%s)" % (n, why))
        return 0
    import os
    repo = os.environ["GITHUB_REPOSITORY"]
    dry = "--dry-run" in argv
    branches, prs = read(repo)
    now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    delete, kept = plan(now, branches, prs, in_dev(repo, plan(now, branches, prs)[1]))
    failed = 0
    for n, why in delete:
        if dry:
            print("would delete %s (%s)" % (n, why))
            continue
        try:
            gh("-X", "DELETE", "repos/%s/git/refs/heads/%s" % (repo, n))
            print("deleted %s (%s)" % (n, why))
        except subprocess.CalledProcessError as e:
            failed += 1
            print("could not delete %s: %s" % (n, (e.stderr or "").strip()))
    for n, why in kept:
        print("kept %s (%s)" % (n, why))
    if dry:
        print("%d to delete, %d kept (dry run, nothing deleted)" % (len(delete), len(kept)))
    else:
        print("%d deleted, %d kept, %d failed" % (len(delete) - failed, len(kept), failed))
    return 0  # a branch that could not be deleted is tried again next time; it never fails a merge


if __name__ == "__main__":
    sys.exit(main(sys.argv))
