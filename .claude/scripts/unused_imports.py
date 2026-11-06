#!/usr/bin/env python3
"""Lists, or removes, unused and duplicate imports in Java and Kotlin sources.

Usage:
    python .claude/scripts/unused_imports.py [--fix] [PATH ...]

PATH defaults to app/src and baselineprofile/src. Each finding prints as
file:line: import, with the reason. With --fix, those lines are deleted.

An import counts as used when its simple name appears anywhere else in the file
as a whole word, including in comments, so an import kept for a Javadoc
{@link} stays. Wildcard imports are never reported.
"""

import argparse
import re
import sys
from pathlib import Path

IMPORT = re.compile(
    r"^\s*import\s+(static\s+)?([\w.]+)(\.\*)?(?:\s+as\s+(\w+))?\s*;?\s*$")
DEFAULT_ROOTS = ["app/src", "baselineprofile/src"]


def findings(path):
    lines = path.read_text(encoding="utf-8").splitlines(keepends=True)
    import_lines = {}
    for number, line in enumerate(lines):
        match = IMPORT.match(line)
        if match and not match.group(3):
            import_lines[number] = match
    if not import_lines:
        return lines, []
    body = "".join(line for number, line in enumerate(lines) if number not in import_lines)
    seen = set()
    results = []
    for number, match in import_lines.items():
        target = (bool(match.group(1)), match.group(2), match.group(4))
        if target in seen:
            results.append((number, "duplicate"))
            continue
        seen.add(target)
        name = match.group(4) or match.group(2).rsplit(".", 1)[-1]
        if not re.search(r"(?<![\w$])" + re.escape(name) + r"(?![\w$])", body):
            results.append((number, "unused"))
    return lines, results


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--fix", action="store_true", help="delete the reported lines")
    parser.add_argument("paths", nargs="*", default=DEFAULT_ROOTS)
    args = parser.parse_args()

    files = []
    for root in map(Path, args.paths):
        if root.is_file():
            files.append(root)
        elif root.is_dir():
            files.extend(sorted(p for p in root.rglob("*") if p.suffix in (".java", ".kt")))
        else:
            print(f"Not found: {root}", file=sys.stderr)
            return 2

    total = 0
    for path in files:
        lines, results = findings(path)
        for number, reason in results:
            print(f"{path}:{number + 1}: {lines[number].strip()} ({reason})")
        total += len(results)
        if args.fix and results:
            drop = {number for number, _ in results}
            path.write_text("".join(l for n, l in enumerate(lines) if n not in drop), encoding="utf-8")
    action = "removed" if args.fix else "found"
    print(f"{total} unused or duplicate import(s) {action} in {len(files)} file(s).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
