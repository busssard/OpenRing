#!/usr/bin/env python3
"""Short release notes (owner's wish, 17 November: "Releases should have a more concise change log, it is a super long
list"). Each change note (changes/*.md, one per user visible pull request, often several bullets) becomes ONE line: its
first bullet, cut after the first sentence and at most MAX characters. The full notes stay in the files and are linked.

Usage: release_notes.py NOTE_FILE... (prints the lines, oldest first, as a markdown list; nothing for no files)
"""
import re
import sys

MAX = 140


def short(text):
    """The first bullet of a note, its first sentence, at most MAX characters, as plain text."""
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        line = re.sub(r"^[*\-]\s+", "", line)
        # the first sentence: up to the first ". " (a colon only introduces details, so it is not an end)
        m = re.search(r"^(.+?\.)\s", line + " ")
        first = m.group(1) if m else line
        if len(first) > MAX:
            cut = first[:MAX - 1]
            first = cut[:cut.rfind(" ")].rstrip(",;") + "…" if " " in cut else cut + "…"
        return first
    return ""


def main(files):
    out = []
    for f in files:
        try:
            s = short(open(f, encoding="utf-8").read())
        except OSError:
            continue
        if s and s not in out:
            out.append(s)
    for s in out:
        print("* " + s)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
